package internet

import (
	"context"
	"net/http"
	"testing"
)

func TestCheckErrorAndRecovery(t *testing.T) {
	srv := newTestServer(t, "stable\n")
	var events []Event
	mod := testModule(t, &events)
	mustAdd(t, mod, "page", TypeHTTP, srv.srv.URL)

	if got := checkNow(t, mod, "page").Status; got != StatusNew {
		t.Fatalf("first check = %s, want new", got)
	}
	if got := checkNow(t, mod, "page").Status; got != StatusUnchanged {
		t.Fatalf("second check = %s, want unchanged", got)
	}

	// 4. Failed request → ERROR, exactly one event per outage.
	srv.setStatus(http.StatusInternalServerError)
	first := checkNow(t, mod, "page")
	if first.Status != StatusError || first.Error == nil {
		t.Fatalf("failed check = %+v, want an error result", first)
	}
	second := checkNow(t, mod, "page")
	if second.Status != StatusError {
		t.Fatalf("still failing check = %s, want error", second.Status)
	}
	rec, _ := mod.st().Get("page")
	if rec.Observation.LastError == "" {
		t.Fatal("state must record the last error")
	}
	if rec.Observation.Fingerprint == "" {
		t.Fatal("an outage must keep the last known fingerprint")
	}
	if rec.Observation.Errors != 2 {
		t.Fatalf("error counter = %d, want 2", rec.Observation.Errors)
	}

	// 5. Identical content recovers: UNCHANGED + a recovered event.
	srv.setStatus(http.StatusOK)
	third := checkNow(t, mod, "page")
	if third.Status != StatusUnchanged {
		t.Fatalf("recovered check = %s, want unchanged", third.Status)
	}
	rec, _ = mod.st().Get("page")
	if rec.Observation.LastError != "" {
		t.Fatal("a success must clear the last error")
	}

	want := []EventType{EventSourceNew, EventSourceError, EventSourceRecovered}
	kinds := eventKinds(events)
	if len(kinds) != len(want) {
		t.Fatalf("events = %v, want %v", kinds, want)
	}
	for i := range want {
		if kinds[i] != want[i] {
			t.Fatalf("events = %v, want %v", kinds, want)
		}
	}

	// 6. Recovery with changed content: recovered, then changed.
	srv.setStatus(http.StatusInternalServerError)
	if got := checkNow(t, mod, "page").Status; got != StatusError {
		t.Fatalf("re-broken check = %s, want error", got)
	}
	srv.setStatus(http.StatusOK)
	srv.setBody("new content\n")
	if got := checkNow(t, mod, "page").Status; got != StatusChanged {
		t.Fatalf("recovered-with-change check = %s, want changed", got)
	}
	want = append(want, EventSourceError, EventSourceRecovered, EventSourceChanged)
	kinds = eventKinds(events)
	if len(kinds) != len(want) {
		t.Fatalf("events = %v, want %v", kinds, want)
	}
	for i := range want {
		if kinds[i] != want[i] {
			t.Fatalf("events = %v, want %v", kinds, want)
		}
	}
}

func TestCheckUnknownSource(t *testing.T) {
	mod := testModule(t, nil)
	if _, err := mod.Check(context.Background(), "nope"); err == nil {
		t.Fatal("checking an unknown source must fail")
	}
}

func TestCheckDisabledSourceStillChecksOnDemand(t *testing.T) {
	srv := newTestServer(t, "x\n")
	mod := testModule(t, nil)
	src, err := mod.Add(Source{ID: "page", Type: TypeHTTP, URL: srv.srv.URL, Enabled: false})
	if err != nil {
		t.Fatal(err)
	}
	if src.Enabled {
		t.Fatal("source must be stored disabled")
	}
	if n := len(mod.CheckAll(context.Background())); n != 0 {
		t.Fatalf("CheckAll checked %d disabled sources", n)
	}
	if got := checkNow(t, mod, "page").Status; got != StatusNew {
		t.Fatalf("explicit check of a disabled source = %s, want new", got)
	}
}

func TestCheckCancelledContextRecordsNothing(t *testing.T) {
	srv := newTestServer(t, "x\n")
	mod := testModule(t, nil)
	mustAdd(t, mod, "page", TypeHTTP, srv.srv.URL)

	if got := checkNow(t, mod, "page").Status; got != StatusNew {
		t.Fatalf("baseline = %s, want new", got)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	res, err := mod.Check(ctx, "page")
	if err != nil {
		t.Fatal(err)
	}
	if res.Error == nil {
		t.Fatal("cancelled check must report the interruption")
	}
	rec, _ := mod.st().Get("page")
	if rec.Observation.Checks != 1 {
		t.Fatalf("cancelled check was recorded (checks=%d)", rec.Observation.Checks)
	}
}

func TestBadFeedKeepsGoodFingerprint(t *testing.T) {
	// An rss source pointed at a plain page fails normalization. The
	// error is recorded with no fingerprint, and when a real feed
	// appears later it is a NEW observation — never a silent adoption
	// of an error as the previous state.
	srv := newTestServer(t, "not a feed at all\n")
	var events []Event
	mod := testModule(t, &events)
	mustAdd(t, mod, "feed", TypeRSS, srv.srv.URL)

	res := checkNow(t, mod, "feed")
	if res.Status != StatusError {
		t.Fatalf("bad-feed check = %s, want error", res.Status)
	}
	rec, _ := mod.st().Get("feed")
	if rec.Observation.Fingerprint != "" {
		t.Fatal("a normalization failure must not invent a fingerprint")
	}

	srv.setBody(`<?xml version="1.0"?><rss version="2.0"><channel><title>f</title>` +
		`<item><guid>1</guid><title>One</title></item></channel></rss>`)
	res = checkNow(t, mod, "feed")
	if res.Status != StatusNew {
		t.Fatalf("first good check = %s, want new", res.Status)
	}
	if res.CurrentFingerprint == "" {
		t.Fatal("NEW observation must carry a fingerprint")
	}

	kinds := eventKinds(events)
	want := []EventType{EventSourceError, EventSourceRecovered, EventSourceNew}
	if len(kinds) != len(want) {
		t.Fatalf("events = %v, want %v", kinds, want)
	}
	for i := range want {
		if kinds[i] != want[i] {
			t.Fatalf("events = %v, want %v", kinds, want)
		}
	}
}

func TestConditionalRequest304IsUnchanged(t *testing.T) {
	srv := newTestServer(t, "etag body\n")
	srv.setETag(`"v1"`)
	mod := testModule(t, nil)
	mustAdd(t, mod, "page", TypeHTTP, srv.srv.URL)

	if got := checkNow(t, mod, "page").Status; got != StatusNew {
		t.Fatalf("first check = %s, want new", got)
	}
	rec, _ := mod.st().Get("page")
	if rec.Observation.ETag != `"v1"` {
		t.Fatalf("validators not stored: %+v", rec.Observation)
	}
	if got := checkNow(t, mod, "page").Status; got != StatusUnchanged {
		t.Fatalf("304 check = %s, want unchanged", got)
	}
}
