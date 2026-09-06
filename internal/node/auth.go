package node

import (
	"crypto/subtle"
	"net/http"
	"strings"
	"sync/atomic"
)

// publicPaths are served without authentication. /api/health is a
// liveness check only (status/version — no hostname, identity, or
// topology); /static/style.css is inert presentation data.
var publicPaths = map[string]bool{
	"/api/health":       true,
	"/static/style.css": true,
}

// authMiddleware enforces shared-token Bearer authentication on all
// non-public paths. It knows nothing about Tailscale, the Registry, or
// the Node model — only HTTP authentication.
//
// Fail-closed: if no token is configured (empty), protected endpoints
// return 503 Configuration Error rather than silently serving
// unauthenticated. The node still starts and /api/health works, so a
// missing token is always an obvious misconfiguration, never an
// accident of "open by default".
type authMiddleware struct {
	// token is the configured shared token. Empty means "not
	// configured" and fails closed. Accessed atomically so the token
	// could be rotated at runtime later without a data race.
	token atomic.Value // string

	// unauthenticated counts requests rejected for missing/invalid
	// credentials. Never logs credentials; counters only, to avoid a
	// log-flood vector from repeated bad requests.
	unauthenticated atomic.Int64
}

func newAuthMiddleware(token string) *authMiddleware {
	m := &authMiddleware{}
	m.token.Store(token)
	return m
}

func (m *authMiddleware) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		configured, _ := m.token.Load().(string)
		if !publicPaths[r.URL.Path] {
			if configured == "" {
				// Fail closed: auth unconfigured is a server-side
				// configuration failure, not "allow".
				http.Error(w, "authentication not configured", http.StatusServiceUnavailable)
				return
			}
			if !m.authorized(r, configured) {
				m.unauthenticated.Add(1)
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// authorized checks the Authorization header against the configured
// token using constant-time comparison. It never logs credentials and
// returns the same 401 for missing/invalid/wrong tokens (the
// middleware above does not distinguish them in its response).
func (m *authMiddleware) authorized(r *http.Request, configured string) bool {
	const prefix = "Bearer "
	h := r.Header.Get("Authorization")
	if len(h) <= len(prefix) || !strings.EqualFold(h[:len(prefix)], prefix) {
		return false
	}
	provided := h[len(prefix):]
	return subtle.ConstantTimeCompare([]byte(provided), []byte(configured)) == 1
}

// AuthMiddleware wraps next with bearer-token authentication using the
// given shared token. It is independent of Tailscale, the Registry, and
// the Node model. See authMiddleware for the public/fail-closed policy.
func AuthMiddleware(next http.Handler, token string) http.Handler {
	return newAuthMiddleware(token).Middleware(next)
}

// bearerChecker reports whether a request presents exactly the given
// shared Bearer token (constant-time comparison). It is the single
// credential check shared by the API middleware and the browser
// pass-through, so both boundaries accept identical credentials.
func bearerChecker(token string) func(*http.Request) bool {
	if token == "" {
		return func(*http.Request) bool { return false }
	}
	return func(r *http.Request) bool {
		const prefix = "Bearer "
		h := r.Header.Get("Authorization")
		if len(h) <= len(prefix) || !strings.EqualFold(h[:len(prefix)], prefix) {
			return false
		}
		return subtle.ConstantTimeCompare([]byte(h[len(prefix):]), []byte(token)) == 1
	}
}
