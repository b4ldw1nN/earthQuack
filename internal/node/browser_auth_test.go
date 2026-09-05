package node

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// serveServer spins up a NewServer handler with the given token.
func serveServer(t *testing.T, token string) *httptest.Server {
	t.Helper()
	reg, _ := newTestRegistry(t)
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: token})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return srv
}

// noRedirect returns an http.Client that does not follow redirects, so
// tests can assert on 302 responses and capture Set-Cookie headers.
func noRedirect() *http.Client {
	return &http.Client{
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

func TestBrowserLoginFlow(t *testing.T) {
	srv := serveServer(t, "topsecret")
	c := noRedirect()

	// GET / with no session must redirect to /login (302), not 401.
	resp, err := c.Get(srv.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusFound {
		t.Fatalf("GET / with no session: want 302, got %d", resp.StatusCode)
	}
	if loc := resp.Header.Get("Location"); loc != "/login" {
		t.Fatalf("redirect location = %q, want /login", loc)
	}

	// GET /login renders a page containing the form field.
	lr, err := c.Get(srv.URL + "/login")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(lr.Body)
	lr.Body.Close()
	if lr.StatusCode != http.StatusOK {
		t.Fatalf("GET /login: want 200, got %d", lr.StatusCode)
	}
	for _, want := range []string{"Authentication required", `name="token"`, "/login"} {
		if !strings.Contains(string(body), want) {
			t.Errorf("login page missing %q", want)
		}
	}
	if strings.Contains(string(body), "topsecret") {
		t.Fatal("token leaked into login page")
	}

	// POST /login with the wrong token is rejected.
	if err := postLogin(t, c, srv, "wrong"); err != nil {
		t.Fatal(err)
	}
	// POST /login with the correct token issues a session cookie.
	cookie := postLogin(t, c, srv, "topsecret")
	if cookie == nil {
		t.Fatal("no session cookie issued")
	}
	if cookie.HttpOnly != true || cookie.SameSite != http.SameSiteStrictMode ||
		cookie.Path != "/" || cookie.Secure {
		t.Errorf("session cookie hardening wrong: %+v", cookie)
	}

	// With the session cookie, GET / serves the dashboard (200).
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/", nil)
	req.AddCookie(cookie)
	dr, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	dbody, _ := io.ReadAll(dr.Body)
	dr.Body.Close()
	if dr.StatusCode != http.StatusOK {
		t.Fatalf("GET / with session: want 200, got %d", dr.StatusCode)
	}
	if !strings.Contains(string(dbody), "archii") {
		t.Errorf("dashboard didn't render with session")
	}
	if strings.Contains(string(dbody), "topsecret") {
		t.Fatal("token leaked into dashboard")
	}

	// POST /logout invalidates the session and redirects to /login.
	ll, _ := http.NewRequest(http.MethodPost, srv.URL+"/logout", nil)
	ll.AddCookie(cookie)
	out, err := c.Do(ll)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, out.Body)
	out.Body.Close()
	if loc := out.Header.Get("Location"); loc != "/login" {
		t.Fatalf("logout location = %q, want /login", loc)
	}
	// The now-invalidated cookie no longer grants access.
	req2, _ := http.NewRequest(http.MethodGet, srv.URL+"/", nil)
	req2.AddCookie(cookie)
	after, _ := c.Do(req2)
	io.Copy(io.Discard, after.Body)
	after.Body.Close()
	if after.StatusCode != http.StatusFound {
		t.Fatalf("GET / after logout: want 302, got %d", after.StatusCode)
	}
}

// postLogin POSTs credentials using the given (non-redirect) client and
// returns the issued session cookie. A "wrong" token returns 403 and no
// cookie.
func postLogin(t *testing.T, c *http.Client, srv *httptest.Server, token string) *http.Cookie {
	t.Helper()
	form := url.Values{"token": {token}}
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/login",
		strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if token == "wrong" {
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("wrong token: want 403, got %d", resp.StatusCode)
		}
		return nil
	}
	if resp.StatusCode != http.StatusFound {
		t.Fatalf("login status %d, want 302", resp.StatusCode)
	}
	for _, cc := range resp.Cookies() {
		if cc.Name == sessionCookieName {
			return cc
		}
	}
	return nil
}
