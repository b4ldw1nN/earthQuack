package node

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/b4ldw1nN/earthquack/internal/internet"
)

func TestLoadConfigInternetDeclaration(t *testing.T) {
	// No internet key: genuinely absent, not present-but-empty.
	plain, err := LoadConfig(writeTemp(t, `{"capabilities": [], "services": []}`))
	if err != nil {
		t.Fatal(err)
	}
	if plain.Internet != nil {
		t.Fatalf("absent internet key must decode to nil, got %+v", plain.Internet)
	}

	cfg, err := LoadConfig(writeTemp(t, `{
		"capabilities": [],
		"services": [],
		"internet": {"enabled": true, "state_dir": "/tmp/iq", "interval": "10m"}
	}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Internet == nil || !cfg.Internet.Enabled ||
		cfg.Internet.StateDir != "/tmp/iq" || cfg.Internet.Interval != "10m" {
		t.Fatalf("internet declaration wrong: %+v", cfg.Internet)
	}
}

// fakeInternet is an InternetProvider backed by a fixed snapshot.
type fakeInternet struct{ snap internet.Snapshot }

func (f fakeInternet) Snapshot() internet.Snapshot { return f.snap }

func testInternetSnapshot() internet.Snapshot {
	return internet.Snapshot{
		Enabled:         true,
		Running:         false,
		StateDir:        "/tmp/internet-test",
		DefaultInterval: internet.Duration(15 * time.Minute),
		Summary:         internet.Summary{Sources: 3, Enabled: 2, Errors: 1},
		Sources: []internet.SourceStatus{
			{
				Source:      internet.Source{ID: "releases", Name: "GitHub Releases", Type: internet.TypeHTTP, URL: "https://example.com/a", Enabled: true},
				Observation: internet.Observation{Status: internet.StatusChanged, Fingerprint: "aaaa", PreviousFingerprint: "bbbb", Checks: 4, Changes: 1},
			},
			{
				Source:      internet.Source{ID: "arch-news", Name: "Arch News RSS", Type: internet.TypeRSS, URL: "https://example.com/b", Enabled: true},
				Observation: internet.Observation{Status: internet.StatusUnchanged, Fingerprint: "cccc", Checks: 4},
			},
			{
				Source:      internet.Source{ID: "dead-api", Name: "Example API", Type: internet.TypeHTTP, URL: "http://127.0.0.1:1/", Enabled: true},
				Observation: internet.Observation{Status: internet.StatusError, Checks: 4, Errors: 2, LastError: "connection refused"},
			},
		},
	}
}

func TestInternetDisabledByDefault(t *testing.T) {
	reg, _ := newTestRegistry(t)
	snap := reg.InternetSnapshot()
	if snap.Enabled {
		t.Fatal("a node with no module must report enabled=false")
	}
	if len(snap.Sources) != 0 {
		t.Fatalf("a node with no module must report no sources: %+v", snap)
	}
}

func TestInternetAPI(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetInternetProvider(fakeInternet{snap: testInternetSnapshot()})
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(handler)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/internet")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	var snap internet.Snapshot
	if err := json.NewDecoder(resp.Body).Decode(&snap); err != nil {
		t.Fatal(err)
	}
	if !snap.Enabled || len(snap.Sources) != 3 {
		t.Fatalf("snapshot = %+v", snap)
	}
	if snap.Summary.Errors != 1 || snap.Summary.Enabled != 2 {
		t.Fatalf("summary = %+v", snap.Summary)
	}
	if snap.Sources[0].ID != "releases" || snap.Sources[0].Status != internet.StatusChanged {
		t.Fatalf("first source = %+v", snap.Sources[0])
	}
	if snap.Sources[2].LastError != "connection refused" {
		t.Fatalf("error not exposed: %+v", snap.Sources[2])
	}
}

func TestInternetAPIRequiresBearer(t *testing.T) {
	reg, _ := newTestRegistry(t)
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	// Unauthenticated API requests are rejected; the browser page is not
	// publicly reachable either (it redirects to login).
	if resp, err := http.Get(srv.URL + "/api/internet"); err != nil {
		t.Fatal(err)
	} else {
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("/api/internet unauthenticated: want 401, got %d", resp.StatusCode)
		}
	}
}

func TestInternetDashboardPage(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetInternetProvider(fakeInternet{snap: testInternetSnapshot()})
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: "supersecret"})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	c := noRedirect()
	cookie := postLogin(t, c, srv, "supersecret")
	if cookie == nil {
		t.Fatal("no session cookie issued")
	}
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/internet", nil)
	req.AddCookie(cookie)
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	html := string(body)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, body:\n%s", resp.StatusCode, html)
	}

	for _, want := range []string{
		"INTERNET", "GitHub Releases", "Arch News RSS", "Example API",
		"CHANGED", "ERROR", ">OK<",
		"health-pill degraded", "health-pill offline", "health-pill healthy",
		"connection refused",
	} {
		if !strings.Contains(html, want) {
			t.Errorf("internet page missing %q", want)
		}
	}
	for _, want := range []string{">3<", ">2<", ">1<"} {
		if !strings.Contains(html, want) {
			t.Errorf("internet page missing count %q", want)
		}
	}
	if strings.Contains(html, "supersecret") {
		t.Error("token leaked into the internet page")
	}
}

func TestInternetDashboardPageDisabled(t *testing.T) {
	reg, _ := newTestRegistry(t)
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: "supersecret"})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	c := noRedirect()
	cookie := postLogin(t, c, srv, "supersecret")
	if cookie == nil {
		t.Fatal("no session cookie issued")
	}
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/internet", nil)
	req.AddCookie(cookie)
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "not enabled") {
		t.Errorf("disabled module must render an explanation:\n%s", body)
	}
}
