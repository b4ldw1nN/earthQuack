// TestBrowserSessionGaps pins the session-boundary minutiae the named
// tests above exercise only implicitly: an unknown/forged session id
// and a tampered cookie value are rejected exactly like having no
// session (302 to /login, never the dashboard), and an expired session
// fails end-to-end (not just at the store level) while a neighbouring
// live session keeps working.
package node

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestBrowserSessionGaps(t *testing.T) {
	srv := serveServer(t, "gap-token")
	c := noRedirect()

	getRoot := func(cookie *http.Cookie) (int, string) {
		t.Helper()
		req, _ := http.NewRequest(http.MethodGet, srv.URL+"/", nil)
		if cookie != nil {
			req.AddCookie(cookie)
		}
		resp, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(body)
	}

	// No cookie at all: login redirect.
	if code, _ := getRoot(nil); code != http.StatusFound {
		t.Fatalf("no session: want 302, got %d", code)
	}

	// Forged session id (never issued by the store): same redirect.
	forged := &http.Cookie{Name: sessionCookieName, Value: strings.Repeat("0", 64), Path: "/"}
	if code, body := getRoot(forged); code != http.StatusFound || strings.Contains(body, "archii") {
		t.Fatalf("unknown session: want 302 without dashboard, got %d", code)
	}

	// Tampered cookie: valid session id with one nibble flipped.
	good := postLogin(t, c, srv, "gap-token")
	if good == nil {
		t.Fatal("login failed")
	}
	tampered := &http.Cookie{Name: good.Name, Value: flipHexNibble(good.Value), Path: "/"}
	if code, _ := getRoot(tampered); code != http.StatusFound {
		t.Fatalf("tampered session: want 302, got %d", code)
	}
	// The untampered session still works.
	if code, body := getRoot(good); code != http.StatusOK || !strings.Contains(body, "archii") {
		t.Fatalf("valid session broken by neighbour tamper test: %d", code)
	}
}

// TestBrowserExpiredSessionEndToEnd proves an expired session is
// rejected at the HTTP boundary: after the TTL passes (controllable
// clock), GET / redirects to /login instead of serving the dashboard.
func TestBrowserExpiredSessionEndToEnd(t *testing.T) {
	now := time.Now()
	reg, _ := newTestRegistry(t)
	sessions := NewSessionStore(time.Hour, func() time.Time { return now }, true)
	id, err := sessions.Create()
	if err != nil {
		t.Fatal(err)
	}
	dash, err := NewDashboardHandler(reg)
	if err != nil {
		t.Fatal(err)
	}
	browserMux := http.NewServeMux()
	browserMux.HandleFunc("GET /{$}", dash)
	browserMux.HandleFunc("GET /login", loginGetHandler(sessions))
	h := BrowserSessionMiddleware(browserMux, sessions, nil)

	withSession := func() *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.AddCookie(&http.Cookie{Name: sessionCookieName, Value: id})
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	if w := withSession(); w.Code != http.StatusOK {
		t.Fatalf("fresh session: want 200, got %d", w.Code)
	}
	now = now.Add(2 * time.Hour) // past the TTL
	if w := withSession(); w.Code != http.StatusFound ||
		w.Header().Get("Location") != "/login" {
		t.Fatalf("expired session: want 302 to /login, got %d loc=%q",
			w.Code, w.Header().Get("Location"))
	}
}

// flipHexNibble flips the first hex digit so the value stays a
// well-formed session id that the store simply never issued.
func flipHexNibble(s string) string {
	if s == "" {
		return "0"
	}
	b := []byte(s)
	if b[0] == '0' {
		b[0] = '1'
	} else {
		b[0] = '0'
	}
	return string(b)
}
