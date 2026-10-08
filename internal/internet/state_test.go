package internet

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func testConfig(t *testing.T) Config {
	t.Helper()
	return Config{StateDir: t.TempDir()}.WithDefaults()
}

func TestSourceValidation(t *testing.T) {
	good := Source{ID: "a.b-c_d", Type: TypeHTTP, URL: "https://example.com/x", Enabled: true}
	if _, err := good.Normalize(); err != nil {
		t.Fatalf("valid source rejected: %v", err)
	}
	// Empty type defaults to http.
	unset, err := Source{ID: "x", URL: "https://example.com"}.Normalize()
	if err != nil {
		t.Fatal(err)
	}
	if unset.Type != TypeHTTP {
		t.Fatalf("type = %s, want http", unset.Type)
	}

	for name, src := range map[string]Source{
		"empty id":      {Type: TypeHTTP, URL: "https://example.com"},
		"bad id":        {ID: "has space", Type: TypeHTTP, URL: "https://example.com"},
		"bad type":      {ID: "x", Type: "ftp", URL: "https://example.com"},
		"bad url":       {ID: "x", Type: TypeHTTP, URL: "://missing"},
		"no host":       {ID: "x", Type: TypeHTTP, URL: "https://"},
		"gopher scheme": {ID: "x", Type: TypeHTTP, URL: "gopher://example.com"},
		"fast interval": {ID: "x", Type: TypeHTTP, URL: "https://example.com", Interval: Duration(time.Second)},
	} {
		if _, err := src.Normalize(); err == nil {
			t.Errorf("%s: must be rejected", name)
		}
	}
}

func TestSourceEffectiveInterval(t *testing.T) {
	def := Duration(MinInterval * 2)
	if got := (Source{}).EffectiveInterval(def); got != def {
		t.Fatalf("zero interval = %s, want module default", got)
	}
	if got := (Source{Interval: Duration(MinInterval * 10)}).EffectiveInterval(def); got != Duration(MinInterval*10) {
		t.Fatalf("declared interval = %s, want it honoured", got)
	}
	if got := (Source{Interval: Duration(time.Nanosecond)}).EffectiveInterval(def); got != Duration(MinInterval) {
		t.Fatalf("sub-minimum interval = %s, want the floor", got)
	}
}

func TestParseDuration(t *testing.T) {
	if got, err := ParseDuration("15m"); err != nil || got != Duration(15*time.Minute) {
		t.Fatalf("duration string: %v %v", got, err)
	}
	if got, err := ParseDuration("900"); err != nil || got != Duration(15*time.Minute) {
		t.Fatalf("bare seconds: %v %v", got, err)
	}
	if _, err := ParseDuration(""); err == nil {
		t.Fatal("empty interval must fail")
	}
	if _, err := ParseDuration("soon"); err == nil {
		t.Fatal("nonsense interval must fail")
	}
}

func TestStateAddRemoveEnablePersistsAcrossRestart(t *testing.T) {
	dir := t.TempDir()
	first, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	src, err := first.Add(Source{ID: "page", Name: "Page", Type: TypeHTTP, URL: "https://example.com", Enabled: true,
		Metadata: map[string]string{"cat": "news"}})
	if err != nil {
		t.Fatal(err)
	}
	if src.DisplayName() != "Page" {
		t.Fatalf("display name = %q", src.DisplayName())
	}
	if _, err := first.Add(Source{ID: "page", Type: TypeHTTP, URL: "https://example.com"}); err == nil {
		t.Fatal("duplicate id must fail")
	}
	if err := first.SetEnabled("page", false); err != nil {
		t.Fatal(err)
	}
	if err := first.SetEnabled("missing", true); err == nil {
		t.Fatal("enabling an unknown source must fail")
	}
	if err := first.Remove("missing"); err == nil {
		t.Fatal("removing an unknown source must fail")
	}

	// Restart: a fresh load must see the persisted configuration.
	second, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	rec, ok := second.Get("page")
	if !ok {
		t.Fatal("source lost across restart")
	}
	if rec.Enabled || rec.Metadata["cat"] != "news" || rec.Status != StatusPending {
		t.Fatalf("persisted record wrong: %+v", rec)
	}

	if err := second.Remove("page"); err != nil {
		t.Fatal(err)
	}
	third, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := third.Get("page"); ok {
		t.Fatal("removed source survived a restart")
	}
}

func TestStateRefusesMalformedFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, sourcesFileName), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewState(dir); err == nil {
		t.Fatal("malformed state must fail loudly")
	}
}

func TestObservationsSurviveARestart(t *testing.T) {
	dir := t.TempDir()
	mod, err := NewModule(Config{StateDir: dir}, WithClock(func() time.Time { return time.Unix(1_700_000_000, 0).UTC() }))
	if err != nil {
		t.Fatal(err)
	}
	srv := newTestServer(t, "revision one\n")
	mustAdd(t, mod, "page", TypeHTTP, srv.srv.URL)
	first := checkNow(t, mod, "page")
	if first.Status != StatusNew {
		t.Fatalf("first check = %s, want new", first.Status)
	}

	// Restart the module over the same directory: the observation must
	// survive, so an identical response is UNCHANGED, not NEW.
	reopened, err := NewModule(Config{StateDir: dir}, WithClock(func() time.Time { return time.Unix(1_700_001_000, 0).UTC() }))
	if err != nil {
		t.Fatal(err)
	}
	second, err := reopened.Check(context.Background(), "page")
	if err != nil {
		t.Fatal(err)
	}
	if second.Status != StatusUnchanged {
		t.Fatalf("check after restart = %s, want unchanged", second.Status)
	}
	if second.CurrentFingerprint != first.CurrentFingerprint {
		t.Fatal("fingerprint lost across restart")
	}
}

func TestSummaryCounts(t *testing.T) {
	records := []Record{
		{Source: Source{ID: "a", Enabled: true}, Observation: Observation{Status: StatusUnchanged}},
		{Source: Source{ID: "b", Enabled: true}, Observation: Observation{Status: StatusError}},
		{Source: Source{ID: "c"}, Observation: Observation{Status: StatusPending}},
	}
	s := Summarize(records)
	if s.Sources != 3 || s.Enabled != 2 || s.Errors != 1 || s.Pending != 1 {
		t.Fatalf("summary = %+v", s)
	}
}
