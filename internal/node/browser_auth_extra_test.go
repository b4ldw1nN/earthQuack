package node

import (
	"io"
	"net/http"
	"net/http/httptest"
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
	s := NewSessionStore(time.Hour, func() time.Time { return now })
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
	s := NewSessionStore(time.Hour, nil)
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
