package node

import (
	"crypto/subtle"
	"net/http"
	"time"

	"github.com/b4ldw1nN/earthquack/web"
)

// loginView is the data for the login template. It carries only the
// error flag — never the submitted token, the configured token, or any
// other secret.
type loginView struct {
	Error bool
}

// loginPage renders the minimal browser login page. failed shows the
// generic "Invalid token" message. The page never contains the
// configured token and never repopulates the submitted token.
func loginPage(w http.ResponseWriter, failed bool) {
	tmpl, err := web.LoginTemplate()
	if err != nil {
		http.Error(w, "template error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = tmpl.Execute(w, loginView{Error: failed})
}

// loginGetHandler renders the login page, or redirects to the dashboard
// if the browser already has a valid session. When no token is
// configured the middleware has already failed closed; this handler is
// unreachable in that state.
func loginGetHandler(sessions *SessionStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie(sessionCookieName); err == nil && sessions.Valid(c.Value) {
			http.Redirect(w, r, "/", http.StatusFound)
			return
		}
		loginPage(w, false)
	}
}

// loginPostHandler validates the submitted token against the configured
// server token (constant-time comparison), then issues a random session
// cookie. Missing, malformed, and wrong tokens all produce the identical
// generic login page with "Invalid token" — no distinction is ever
// observable. The submitted token is never echoed back, logged, or put
// in a redirect. If no token is configured, login fails closed (503).
func loginPostHandler(sessions *SessionStore, configured string, secure bool, ttl time.Duration) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if configured == "" {
			http.Error(w, "authentication not configured", http.StatusServiceUnavailable)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		provided := r.PostForm.Get("token")
		if provided == "" || subtle.ConstantTimeCompare([]byte(provided), []byte(configured)) != 1 {
			// Uniform failure: re-render the login page with a
			// generic error. Never distinguish missing vs wrong.
			loginPage(w, true)
			return
		}
		id, err := sessions.Create()
		if err != nil {
			http.Error(w, "could not start session", http.StatusInternalServerError)
			return
		}
		http.SetCookie(w, sessionCookie(id, secure, ttl))
		http.Redirect(w, r, "/", http.StatusFound)
	}
}

// logoutHandler invalidates the session (if any) and clears the cookie.
// It does not require the shared token again — a valid session, or even
// just a browser that wants to clear an expired cookie, is enough to
// log out. Logout only ever destroys state; it creates nothing.
func logoutHandler(sessions *SessionStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie(sessionCookieName); err == nil && c.Value != "" {
			sessions.Delete(c.Value)
		}
		http.SetCookie(w, expiredSessionCookie())
		http.Redirect(w, r, "/login", http.StatusFound)
	}
}
