package node

import (
	"net/http"
	"time"
)

// browserSessionPaths are reachable without an established session.
// Everything else under the browser subtree requires a valid session
// cookie. login is the auth entry point; logout must always be
// reachable so the user can clear their (possibly stale) cookie.
var browserSessionPaths = map[string]bool{
	"/login":  true,
	"/logout": true,
}

// BrowserSessionMiddleware gates browser routes behind a valid session
// cookie. It redirects unauthenticated browser requests to the login
// page. It never inspects the shared auth token — the browser only ever
// holds a random session identifier. The auth token itself is never in
// a cookie, URL, page, or Referer.
//
// This is intentionally only the browser boundary. API routes use the
// separate Bearer-token middleware (AuthMiddleware) and are never gated
// by a session cookie.
func BrowserSessionMiddleware(next http.Handler, sessions *SessionStore, secureCookie bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if browserSessionPaths[r.URL.Path] {
			next.ServeHTTP(w, r)
			return
		}
		c, err := r.Cookie(sessionCookieName)
		if err != nil || !sessions.Valid(c.Value) {
			http.Redirect(w, r, "/login", http.StatusFound)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// sessionCookie builds a hardened session cookie. HttpOnly prevents
// script access; SameSite=Strict mitigates CSRF on state-changing
// browser requests; Path=/ scopes it to the whole app.
//
// Secure is intentionally configurable: the dashboard is currently
// served over plain http on the Tailscale address (100.x.x.x:8890), so
// forcing Secure=true would make it unusable. It should be enabled once
// TLS is introduced. The auth token itself is never in the cookie.
func sessionCookie(id string, secure bool, ttl time.Duration) *http.Cookie {
	return &http.Cookie{
		Name:     sessionCookieName,
		Value:    id,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		Secure:   secure,
		MaxAge:   int(ttl / time.Second),
	}
}

// expiredSessionCookie clears the browser's session cookie on logout.
func expiredSessionCookie() *http.Cookie {
	return &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		MaxAge:   -1,
	}
}
