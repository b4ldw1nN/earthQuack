package node

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"
)

func TestManagedControlsBrowserFlow(t *testing.T) {
	reg, _ := newTestRegistry(t)
	starts, stops := 0, 0
	svc := ManagedService{ID: "python-daemon", Name: "Clipboard + file transfer", Description: "Shared process", Start: func() error { starts++; return nil }, Stop: func() { stops++ }, Snapshot: func() ManagedState { return ManagedState{} }}
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: "test-secret"}, svc)
	if err != nil {
		t.Fatal(err)
	}
	request := func(method, path, body string, cookie *http.Cookie, bearer bool) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		if cookie != nil {
			r.AddCookie(cookie)
		}
		if bearer {
			r.Header.Set("Authorization", "Bearer test-secret")
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	login := func() *http.Cookie {
		w := request("POST", "/login", "token=test-secret", nil, false)
		for _, c := range w.Result().Cookies() {
			if c.Name == sessionCookieName {
				return c
			}
		}
		t.Fatalf("no session: %d %s", w.Code, w.Body.String())
		return nil
	}
	cookie := login()
	w := request("GET", "/nodes", "", cookie, false)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "Clipboard &#43; file transfer") {
		t.Fatalf("render: %d %s", w.Code, w.Body.String())
	}
	match := regexp.MustCompile(`name="csrf" value="([a-f0-9]+)"`).FindStringSubmatch(w.Body.String())
	if len(match) != 2 {
		t.Fatal("missing CSRF form")
	}
	token := match[1]
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("control page must not be cached")
	}
	form := func(csrf, service, action string) string {
		return url.Values{"csrf": {csrf}, "service": {service}, "action": {action}}.Encode()
	}
	for _, tc := range []struct {
		name, method, path, body string
		cookie                   *http.Cookie
		bearer                   bool
		want                     int
	}{
		{"no session", "POST", "/services/control", form(token, "python-daemon", "start"), nil, false, 302},
		{"bearer read-only", "POST", "/services/control", form(token, "python-daemon", "start"), nil, true, 403},
		{"missing CSRF", "POST", "/services/control", form("", "python-daemon", "start"), cookie, false, 403},
		{"other session CSRF", "POST", "/services/control", form(token, "python-daemon", "start"), login(), false, 403},
		{"unknown service", "POST", "/services/control", form(token, "remote", "start"), cookie, false, 404},
		{"invalid action", "POST", "/services/control", form(token, "python-daemon", "restart"), cookie, false, 400},
		{"GET forbidden", "GET", "/services/control", "", cookie, false, 405},
		{"API read-only", "POST", "/api/services/control", form(token, "python-daemon", "start"), nil, true, 404},
		{"oversized form", "POST", "/services/control", strings.Repeat("x", 5000), cookie, false, 400},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := request(tc.method, tc.path, tc.body, tc.cookie, tc.bearer)
			if w.Code != tc.want {
				t.Fatalf("got %d want %d", w.Code, tc.want)
			}
		})
	}
	if starts != 0 || stops != 0 {
		t.Fatal("rejected requests executed actions")
	}
	for _, action := range []string{"start", "stop"} {
		w := request("POST", "/services/control", form(token, "python-daemon", action), cookie, false)
		if w.Code != 303 || !strings.Contains(w.Header().Get("Location"), "control=ok") {
			t.Fatal(w)
		}
	}
	if starts != 1 || stops != 1 {
		t.Fatalf("starts=%d stops=%d", starts, stops)
	}
	w = request("GET", "/nodes", "", nil, true)
	if strings.Contains(w.Body.String(), `action="/services/control"`) {
		t.Fatal("bearer-only page has controls")
	}
	request("POST", "/logout", "", cookie, false)
	if w := request("POST", "/services/control", form(token, "python-daemon", "start"), cookie, false); w.Code != 302 {
		t.Fatal("logged-out session accepted")
	}
}

func TestManagedControlStartFailure(t *testing.T) {
	sessions := NewSessionStore(DefaultSessionTTL, nil, true)
	session, err := sessions.Create()
	if err != nil {
		t.Fatal(err)
	}
	c, err := newServiceControls([]ManagedService{{ID: "wallpaper", Start: func() error { return errors.New("secret") }}}, sessions)
	if err != nil {
		t.Fatal(err)
	}
	body := url.Values{"csrf": {c.token(session)}, "service": {"wallpaper"}, "action": {"start"}}.Encode()
	r := httptest.NewRequest("POST", "/services/control", strings.NewReader(body))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r.AddCookie(&http.Cookie{Name: sessionCookieName, Value: session})
	w := httptest.NewRecorder()
	c.action(w, r)
	if w.Code != 303 || !strings.Contains(w.Header().Get("Location"), "failed") || strings.Contains(w.Body.String(), "secret") {
		t.Fatal(w)
	}
}

// TestManagedControlWallpaperRunningToStopped drives a real running job
// through the control handler and asserts the running→stopped transition
// the panel reports, not just callback invocation counts.
func TestManagedControlWallpaperRunningToStopped(t *testing.T) {
	sessions := NewSessionStore(DefaultSessionTTL, nil, true)
	session, err := sessions.Create()
	if err != nil {
		t.Fatal(err)
	}
	job := NewManagedJob(context.Background(), func(ctx context.Context) (string, error) {
		<-ctx.Done() // simulates a long sync that only Stop can end
		return "", ctx.Err()
	})
	t.Cleanup(func() { job.Stop(); job.Wait() })
	c, err := newServiceControls([]ManagedService{
		{ID: "wallpaper", Name: "Wallpaper sync", Start: job.Start, Stop: job.Stop, Snapshot: job.Snapshot},
	}, sessions)
	if err != nil {
		t.Fatal(err)
	}
	post := func(action string) *httptest.ResponseRecorder {
		form := url.Values{"csrf": {c.token(session)}, "service": {"wallpaper"}, "action": {action}}
		r := httptest.NewRequest("POST", "/services/control", strings.NewReader(form.Encode()))
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		r.AddCookie(&http.Cookie{Name: sessionCookieName, Value: session})
		w := httptest.NewRecorder()
		c.action(w, r)
		return w
	}

	if w := post("start"); w.Code != http.StatusSeeOther || !strings.Contains(w.Header().Get("Location"), "control=ok") {
		t.Fatalf("start: got %d %s", w.Code, w.Header().Get("Location"))
	}
	if !job.Snapshot().Running {
		t.Fatal("job must be running after start")
	}
	if w := post("stop"); w.Code != http.StatusSeeOther || !strings.Contains(w.Header().Get("Location"), "control=ok") {
		t.Fatalf("stop: got %d %s", w.Code, w.Header().Get("Location"))
	}
	waitManaged(t, func() bool { return !job.Snapshot().Running })
	if s := job.Snapshot(); s.Running || !strings.Contains(s.Message, "stopped") {
		t.Fatalf("running→stopped transition failed: %+v", s)
	}
}
