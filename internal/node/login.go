package node

import (
	"crypto/subtle"
	"net/http"
	"time"

	"github.com/b4ldw1nN/earthquack/web"
)

// loginPage renders the minimal browser login page. It never contains
// the configured token.
func loginPage(w http.ResponseWriter) {
	tmpl, err := web.LoginTemplate()
	if err != nil {
		http.Error(w, "template error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = tmpl.Execute(w, nil)
}

// loginGetHandler renders the login page, or redirects to the dashboard
// if the browser already has a valid session.
func loginGetHandler(sessions *SessionStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie(sessionCookieName); err == nil && sessions.Valid(c.Value) {
			http.Redirect(w, r, "/", http.StatusFound)
			return
		}
		loginPage(w)
	}
}

// loginPostHandler validates the submitted token against the configured
// server token (constant-time comparison), then issues a random session
// cookie. It never logs the submitted token and never reveals whether
// the token was "close". If no token is configured, login fails closed.
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
		if subtle.ConstantTimeCompare([]byte(provided), []byte(configured)) != 1 {
			http.Error(w, "invalid token", http.StatusForbidden)
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
// log out.
func logoutHandler(sessions *SessionStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie(sessionCookieName); err == nil && c.Value != "" {
			sessions.Delete(c.Value)
		}
		http.SetCookie(w, expiredSessionCookie())
		http.Redirect(w, r, "/login", http.StatusFound)
	}
}
