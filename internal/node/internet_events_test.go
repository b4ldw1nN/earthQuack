package node

import (
	"strings"
	"testing"
	"time"

	"github.com/b4ldw1nN/earthquack/internal/internet"
	"github.com/b4ldw1nN/earthquack/web"
)

// testInternetObserved is the fixed event time used by the bridge tests.
func testInternetObserved() time.Time {
	return time.Unix(1_700_000_000, 0).UTC()
}

func TestInternetEventBridge(t *testing.T) {
	observed := testInternetObserved()
	ev := internet.Event{
		Type:                internet.EventSourceChanged,
		SourceID:            "releases",
		SourceName:          "GitHub Releases",
		SourceType:          internet.TypeHTTP,
		URL:                 "https://example.com/a",
		Status:              internet.StatusChanged,
		PreviousFingerprint: "bbbb",
		CurrentFingerprint:  "aaaa",
		ObservedAt:          observed,
	}
	he := InternetEvent(ev)
	if he.Kind != string(internet.EventSourceChanged) {
		t.Fatalf("kind = %q", he.Kind)
	}
	if he.From != "bbbb" || he.To != "aaaa" {
		t.Fatalf("from/to = %q/%q", he.From, he.To)
	}
	for _, want := range []string{"source_id", "source_type", "url", "previous_fingerprint", "current_fingerprint", "observed_at"} {
		if he.Data[want] == "" {
			t.Errorf("event data missing %q: %+v", want, he.Data)
		}
	}
	if he.Data["source_id"] != "releases" || he.Data["url"] != ev.URL {
		t.Fatalf("event data wrong: %+v", he.Data)
	}

	// Through the ring: the typed event keeps its type and data.
	h := NewHistory(10, 10)
	h.AddEvent(he)
	public := h.RecentEvents(1)
	if len(public) != 1 {
		t.Fatalf("recent = %d", len(public))
	}
	got := public[0]
	if got.Type != EvInternetSourceChanged {
		t.Fatalf("type = %q, want internet.source.changed", got.Type)
	}
	if got.Data["current_fingerprint"] != "aaaa" {
		t.Fatalf("data lost through the ring: %+v", got.Data)
	}
	if got.Message == "" {
		t.Fatal("event without a message")
	}
}

func TestInternetEventMessages(t *testing.T) {
	for _, typ := range []internet.EventType{
		internet.EventSourceNew, internet.EventSourceChanged,
		internet.EventSourceError, internet.EventSourceRecovered,
	} {
		msg := InternetEventMessage(internet.Event{Type: typ, SourceID: "x", SourceName: "X", Error: "boom"})
		if !strings.Contains(msg, "X") {
			t.Errorf("message for %s missing source name: %q", typ, msg)
		}
	}
}

func TestInternetStateHelpers(t *testing.T) {
	view := dashboardView{}
	cases := []struct {
		status internet.Status
		label  string
		class  string
	}{
		{internet.StatusUnchanged, "OK", "healthy"},
		{internet.StatusNew, "NEW", "degraded"},
		{internet.StatusChanged, "CHANGED", "degraded"},
		{internet.StatusError, "ERROR", "offline"},
		{internet.StatusPending, "PENDING", "unknown"},
		{"", "PENDING", "unknown"},
	}
	for _, c := range cases {
		s := internet.SourceStatus{Observation: internet.Observation{Status: c.status}}
		if got := view.InternetStateLabel(s); got != c.label {
			t.Errorf("status %q: label = %q, want %q", c.status, got, c.label)
		}
		if got := view.InternetStateClass(s); got != c.class {
			t.Errorf("status %q: class = %q, want %q", c.status, got, c.class)
		}
	}
}

func TestInternetTemplateRenders(t *testing.T) {
	tmpl, err := web.InternetTemplate()
	if err != nil {
		t.Fatal(err)
	}
	reg, _ := newTestRegistry(t)
	reg.SetInternetProvider(fakeInternet{snap: testInternetSnapshot()})
	view := buildView(reg, "internet")
	var sb strings.Builder
	if err := tmpl.Execute(&sb, view); err != nil {
		t.Fatal(err)
	}
	html := sb.String()
	if !strings.Contains(html, "INTERNET") || !strings.Contains(html, "GitHub Releases") {
		t.Errorf("template missing content:\n%.2000s", html)
	}
}
