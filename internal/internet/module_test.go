package internet

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// testServer serves its current body to every request. The body and the
// status code can be changed between checks, which is how the tests
// simulate an external source changing (or failing).
type testServer struct {
	srv    *httptest.Server
	mu     sync.Mutex
	body   string
	status int
	etag   string
}

func newTestServer(t *testing.T, body string) *testServer {
	t.Helper()
	ts := &testServer{body: body, status: http.StatusOK}
	ts.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ts.mu.Lock()
		defer ts.mu.Unlock()
		if ts.etag != "" {
			if r.Header.Get("If-None-Match") == ts.etag {
				w.Header().Set("ETag", ts.etag)
				w.WriteHeader(http.StatusNotModified)
				return
			}
			w.Header().Set("ETag", ts.etag)
		}
		w.WriteHeader(ts.status)
		_, _ = w.Write([]byte(ts.body))
	}))
	t.Cleanup(ts.srv.Close)
	return ts
}

func (s *testServer) setBody(body string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.body = body
	s.status = http.StatusOK
}

func (s *testServer) setStatus(status int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.status = status
}

func (s *testServer) setETag(etag string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.etag = etag
}

func testModule(t *testing.T, events *[]Event) *Module {
	t.Helper()
	dir := t.TempDir()
	mod, err := NewModule(Config{StateDir: dir}, WithClock(func() time.Time { return time.Unix(1_700_000_000, 0).UTC() }))
	if err != nil {
		t.Fatal(err)
	}
	if events != nil {
		mod.sink = func(ev Event) { *events = append(*events, ev) }
	}
	return mod
}

func mustAdd(t *testing.T, mod *Module, id string, typ SourceType, url string) Source {
	t.Helper()
	src, err := mod.Add(Source{ID: id, Type: typ, URL: url, Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	return src
}

func checkNow(t *testing.T, mod *Module, id string) Result {
	t.Helper()
	res, err := mod.Check(context.Background(), id)
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func eventKinds(events []Event) []EventType {
	kinds := make([]EventType, 0, len(events))
	for _, ev := range events {
		kinds = append(kinds, ev.Type)
	}
	return kinds
}

func TestCheckLifecycleNewUnchangedChanged(t *testing.T) {
	srv := newTestServer(t, "revision one\n")
	var events []Event
	mod := testModule(t, &events)
	mustAdd(t, mod, "page", TypeHTTP, srv.srv.URL)

	// 1. First observation → NEW, with an event.
	first := checkNow(t, mod, "page")
	if first.Status != StatusNew {
		t.Fatalf("first check = %s, want new", first.Status)
	}
	if first.CurrentFingerprint == "" {
		t.Fatal("NEW observation must carry a fingerprint")
	}

	// 2. Identical response → UNCHANGED, no event.
	second := checkNow(t, mod, "page")
	if second.Status != StatusUnchanged {
		t.Fatalf("second check = %s, want unchanged", second.Status)
	}
	if second.CurrentFingerprint != first.CurrentFingerprint {
		t.Fatal("unchanged fingerprint must be preserved")
	}

	// 3. Changed response → CHANGED, with an event carrying from/to.
	srv.setBody("revision two\n")
	third := checkNow(t, mod, "page")
	if third.Status != StatusChanged {
		t.Fatalf("third check = %s, want changed", third.Status)
	}
	if third.PreviousFingerprint != first.CurrentFingerprint {
		t.Fatal("changed must report the previous fingerprint")
	}
	if third.CurrentFingerprint == third.PreviousFingerprint {
		t.Fatal("changed must report a new fingerprint")
	}

	kinds := eventKinds(events)
	want := []EventType{EventSourceNew, EventSourceChanged}
	if len(kinds) != len(want) {
		t.Fatalf("events = %v, want exactly %v", kinds, want)
	}
	for i := range want {
		if kinds[i] != want[i] {
			t.Fatalf("events = %v, want %v", kinds, want)
		}
	}

	changed := events[1]
	if changed.SourceID != "page" || changed.URL != srv.srv.URL || changed.SourceType != TypeHTTP {
		t.Fatalf("change event is missing source identity: %+v", changed)
	}
	if changed.PreviousFingerprint != first.CurrentFingerprint ||
		changed.CurrentFingerprint != third.CurrentFingerprint ||
		changed.ObservedAt.IsZero() {
		t.Fatalf("change event is missing transition detail: %+v", changed)
	}
	if changed.Status != StatusChanged {
		t.Fatalf("change event status = %s, want changed", changed.Status)
	}
}
