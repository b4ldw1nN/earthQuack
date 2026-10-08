package daemon

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// publicPaths are reachable without a token.
//
// /health is public for exactly one reason: the Go node's service refresher
// probes it with a bare TCP dial and an HTTP GET to decide whether a
// declared service is running. Probes carry no credentials, so gating
// health behind auth would report every service as down. It exposes only
// a liveness flag — no clipboard content, no file metadata — which is the
// same trade the node's own /api/health makes.
var publicPaths = map[string]bool{
	"/health": true,
}

// bearerAuth requires `Authorization: Bearer <token>` on every route but
// the public ones.
//
// This is the behaviour change from the Python daemon, which accepted any
// caller: with it, anyone who could reach 8875 could read and overwrite the
// clipboard and subscribe to a live SSE feed of it, and anyone who could
// reach 8876 could list and download staged files. The Android app already
// sends this header on every request (ClipboardApi, FileTransferService),
// so it needed no change to keep working.
//
// With no token configured the middleware fails closed, matching the node's
// own rule (internal/node/auth.go): returning 503 rather than serving
// unauthenticated. A daemon that silently accepted everyone because the
// operator forgot to set a token would reintroduce the original hole.
func bearerAuth(token string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if publicPaths[r.URL.Path] {
			next.ServeHTTP(w, r)
			return
		}
		if token == "" {
			writeJSONError(w, http.StatusServiceUnavailable,
				"authentication not configured")
			return
		}
		presented, ok := bearerToken(r.Header.Get("Authorization"))
		if !ok {
			w.Header().Set("WWW-Authenticate", `Bearer realm="earthQuack"`)
			writeJSONError(w, http.StatusUnauthorized, "missing or malformed Authorization header")
			return
		}
		// Constant time, so a caller cannot learn the token byte by byte
		// from response timing. subtle.ConstantTimeCompare returns 0 for
		// differing lengths, which is the correct outcome here.
		if subtle.ConstantTimeCompare([]byte(presented), []byte(token)) != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="earthQuack"`)
			writeJSONError(w, http.StatusUnauthorized, "invalid token")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// bearerToken extracts the credential from an Authorization header,
// accepting "Bearer <t>" case-insensitively per RFC 7235.
func bearerToken(header string) (string, bool) {
	const prefix = "bearer "
	if len(header) < len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		return "", false
	}
	token := strings.TrimSpace(header[len(prefix):])
	if token == "" {
		return "", false
	}
	return token, true
}
