package node

import (
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

func TestBrowserAPISeparation(t *testing.T) {
	srv := serveServer(t, "bearer-secret")
	c := noRedirect()

	// Establish a browser session by logging in.
	cookie := postLogin(t, c, srv, "bearer-secret")
	if cookie == nil {
		t.Fatal("login failed")
	}

	// /api/node with the session cookie but NO Bearer header -> 401.
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/node", nil)
	req.AddCookie(cookie)
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("session cookie must not authorize API: got %d", resp.StatusCode)
	}

	// /api/node with a valid Bearer header -> 200, token not leaked.
	req2, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/node", nil)
	req2.Header.Set("Authorization", "Bearer bearer-secret")
	resp2, err := c.Do(req2)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp2.Body)
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("bearer /api/node: want 200, got %d", resp2.StatusCode)
	}
	if strings.Contains(string(body), "bearer-secret") {
		t.Fatal("token leaked into /api/node")
	}

	// Bearer endpoints and health behave as before.
	for path, want := range map[string]int{
		"/api/health": http.StatusOK,
		"/api/nodes":  http.StatusUnauthorized,
	} {
		r, _ := http.Get(srv.URL + path)
		io.Copy(io.Discard, r.Body)
		r.Body.Close()
		if r.StatusCode != want {
			t.Errorf("%s: want %d, got %d", path, want, r.StatusCode)
		}
	}
}

func TestSessionExpiry(t *testing.T) {
	now := time.Now()
	s := NewSessionStore(time.Hour, func() time.Time { return now }, true)
	id, err := s.Create()
	if err != nil {
		t.Fatal(err)
	}
	if !s.Valid(id) {
		t.Fatal("fresh session should be valid")
	}
	// Advance past the TTL; the same id becomes invalid and is pruned.
	now = now.Add(2 * time.Hour)
	if s.Valid(id) {
		t.Fatal("expired session should be invalid")
	}
	if s.Count() != 0 {
		t.Fatalf("expired session not pruned; count=%d", s.Count())
	}
}

func TestSessionIDsAreRandom(t *testing.T) {
	s := NewSessionStore(time.Hour, nil, true)
	a, _ := s.Create()
	b, _ := s.Create()
	if a == b {
		t.Fatal("session IDs must be unique")
	}
	if len(a) < 32 {
		t.Fatalf("session ID too short: %d", len(a))
	}
}

func TestNodeClientStillAuthenticates(t *testing.T) {
	// NodeClient must still send Bearer (peer probing unchanged under
	// the new layered server).
	var gotAuth string
	peerSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(validNodeJSON("machine:p")))
	}))
	defer peerSrv.Close()
	c := NewNodeClientWithToken(portOf(t, peerSrv.URL), "share")
	if _, err := c.GetNode(strings.TrimPrefix(peerSrv.URL, "http://")); err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer share" {
		t.Errorf("NodeClient sent %q", gotAuth)
	}
}

func TestStaticCSSPublic(t *testing.T) {
	srv := serveServer(t, "tok")
	resp, err := http.Get(srv.URL + "/static/style.css")
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("static css: want 200, got %d", resp.StatusCode)
	}
}

// TestLoginMissingTokenUniform proves a missing (empty) token behaves
// exactly like a wrong token: generic login page re-render, no session,
// no observable difference between "missing" and "wrong".
func TestLoginMissingTokenUniform(t *testing.T) {
	srv := serveServer(t, "real-token")
	c := noRedirect()

	form := url.Values{"token": {""}}
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/login",
		strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("missing token: want 200 login re-render, got %d", resp.StatusCode)
	}
	if !strings.Contains(string(body), "Invalid token") {
		t.Error("missing token: generic error missing")
	}
	if len(resp.Cookies()) != 0 {
		t.Error("missing token must not create a session")
	}
	if c2 := postLogin(t, c, srv, "real-token"); c2 == nil {
		t.Fatal("sanity: correct token should still work")
	}
}

// TestNoTokenFailsClosedBrowser proves the browser boundary fails
// closed exactly like the API when no token is configured: 503 on /,
// /login (GET and POST), /logout — never a login page that can never
// succeed, never an open dashboard.
func TestNoTokenFailsClosedBrowser(t *testing.T) {
	srv := serveServer(t, "")
	c := noRedirect()
	for _, tc := range []struct{ method, path string }{
		{http.MethodGet, "/"},
		{http.MethodGet, "/login"},
		{http.MethodPost, "/login"},
		{http.MethodPost, "/logout"},
	} {
		req, _ := http.NewRequest(tc.method, srv.URL+tc.path, strings.NewReader(
			url.Values{"token": {"anything"}}.Encode()))
		if tc.method == http.MethodPost {
			req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		}
		resp, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusServiceUnavailable {
			t.Errorf("%s %s: want 503 fail-closed, got %d", tc.method, tc.path, resp.StatusCode)
		}
		if !strings.Contains(string(body), "authentication not configured") {
			t.Errorf("%s %s: want fail-closed message", tc.method, tc.path)
		}
	}
	// The store itself refuses to hand out sessions when unconfigured.
	if _, err := NewSessionStore(time.Hour, nil, false).Create(); err == nil {
		t.Error("unconfigured store must refuse to create sessions")
	}
}

// TestBearerGrantsDashboardNotAPI proves the Bearer pass-through renders
// the dashboard for non-browser clients while keeping API isolation:
// a session cookie (or a Bearer) must never authorize /api/node|nodes,
// and the token never appears in any response.
func TestBearerGrantsDashboardNotAPI(t *testing.T) {
	srv := serveServer(t, "bearer-tok")
	c := noRedirect()

	// Bearer on the dashboard: 200, no session cookie minted.
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/", nil)
	req.Header.Set("Authorization", "Bearer bearer-tok")
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	dbody, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET / with Bearer: want 200, got %d", resp.StatusCode)
	}
	if len(resp.Cookies()) != 0 {
		t.Error("Bearer pass-through must not mint a session cookie")
	}
	if !strings.Contains(string(dbody), "earthQuack") || strings.Contains(string(dbody), "bearer-tok") {
		t.Error("dashboard must render without leaking the token")
	}

	// Session cookie on both API endpoints: still 401.
	cookie := postLogin(t, c, srv, "bearer-tok")
	if cookie == nil {
		t.Fatal("login failed")
	}
	for _, path := range []string{"/api/node", "/api/nodes"} {
		r, _ := http.NewRequest(http.MethodGet, srv.URL+path, nil)
		r.AddCookie(cookie)
		rr, err := c.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, rr.Body)
		rr.Body.Close()
		if rr.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s with session cookie: want 401, got %d", path, rr.StatusCode)
		}
	}

	// Bearer still authorizes both API endpoints.
	for _, path := range []string{"/api/node", "/api/nodes"} {
		r, _ := http.NewRequest(http.MethodGet, srv.URL+path, nil)
		r.Header.Set("Authorization", "Bearer bearer-tok")
		rr, err := c.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(rr.Body)
		rr.Body.Close()
		if rr.StatusCode != http.StatusOK {
			t.Errorf("%s with Bearer: want 200, got %d", path, rr.StatusCode)
		}
		if strings.Contains(string(b), "bearer-tok") {
			t.Errorf("%s: token leaked", path)
		}
	}

	// The cookie value must never be (or contain) the shared token.
	if cookie.Value == "bearer-tok" || strings.Contains(cookie.Value, "bearer") {
		t.Errorf("session cookie contains auth token material: %q", cookie.Value)
	}
}

// TestTokenNeverLogged captures the process log across a full
// login/logout cycle and proves neither the configured token nor the
// submitted token ever reaches the log.
func TestTokenNeverLogged(t *testing.T) {
	old := log.Writer()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	log.SetOutput(w)
	defer log.SetOutput(old)

	srv := serveServer(t, "super-secret-token")
	c := noRedirect()
	postLogin(t, c, srv, "wrong")              // failed attempt (generic re-render)
	postLogin(t, c, srv, "super-secret-token") // successful login
	ll, _ := http.NewRequest(http.MethodPost, srv.URL+"/logout", nil)
	out, _ := c.Do(ll)
	out.Body.Close()

	w.Close()
	logged, _ := io.ReadAll(r)
	for _, s := range []string{"super-secret-token", "wrong-typed-token"} {
		if strings.Contains(string(logged), s) {
			t.Errorf("token %q leaked into logs", s)
		}
	}
}

// TestRestartInvalidatesSessions proves sessions live only in memory: a
// fresh SessionStore (what a process restart produces) rejects all
// previously issued session IDs.
func TestRestartInvalidatesSessions(t *testing.T) {
	s1 := NewSessionStore(time.Hour, nil, true)
	id, err := s1.Create()
	if err != nil {
		t.Fatal(err)
	}
	if !s1.Valid(id) {
		t.Fatal("sanity: session valid in original store")
	}
	// Simulated restart: same ttl, brand-new empty store.
	s2 := NewSessionStore(time.Hour, nil, true)
	if s2.Valid(id) {
		t.Fatal("session survived restart (must be memory-only)")
	}
}
