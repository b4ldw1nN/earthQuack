package node

import (
	"encoding/json"
	"net/http"
	"time"
)

// API exposes read-only node endpoints. It is deliberately minimal
// and carries no write/management endpoints.
type API struct {
	registry *Registry
	version  string
}

// ServerAuthConfig carries the authentication settings for a node
// server: the shared Bearer token for API clients/nodes, and the
// browser session settings. Token is separate from Config.AuthConfig
// (the on-disk JSON declaration); it is resolved at startup with env
// precedence and passed here. SecureCookie should be enabled only once
// TLS is available; the dashboard is currently served over http on the
// Tailscale address.
type ServerAuthConfig struct {
	Token        string
	SessionTTL   time.Duration
	SecureCookie bool
}

// NewAPI returns an unauthenticated http.Handler serving the node
// endpoints and dashboard:
//
//	GET /              — HTML dashboard (same data as /api/nodes)
//	GET /static/style.css — dashboard stylesheet
//	GET /api/health    — liveness of this instance
//	GET /api/node      — this earthQuack instance's node description
//	GET /api/nodes     — all known nodes (local + discovered peers)
//
// It does no authentication; production uses NewServer, which layers
// the Bearer and browser-session boundaries. NewAPI is used directly by
// tests and by embedding the same handlers behind a proxy.
func NewAPI(reg *Registry, version string) (http.Handler, error) {
	dash, err := NewDashboardHandler(reg)
	if err != nil {
		return nil, err
	}
	api := &API{registry: reg, version: version}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", dash)
	mux.HandleFunc("GET /static/style.css", stylesheetHandler())
	mux.HandleFunc("GET /api/health", api.handleHealth)
	mux.HandleFunc("GET /api/node", api.handleLocalNode)
	mux.HandleFunc("GET /api/nodes", api.handleNodes)
	return mux, nil
}

// NewServer returns the production http.Handler with the authentication
// boundaries layered at the routing level:
//
//	/api/*             — bearer-token (AuthMiddleware); /api/health public
//	/static/style.css  — public
//	/ (browser)        — session cookie (BrowserSessionMiddleware)
//
// Future API endpoints added under /api/ inherit Bearer auth; future
// browser pages added under / inherit session auth. API clients and
// remote nodes keep using Bearer and never need a browser session.
func NewServer(reg *Registry, version string, auth ServerAuthConfig) (http.Handler, error) {
	dash, err := NewDashboardHandler(reg)
	if err != nil {
		return nil, err
	}
	if auth.SessionTTL <= 0 {
		auth.SessionTTL = DefaultSessionTTL
	}
	sessions := NewSessionStore(auth.SessionTTL, nil, auth.Token != "")
	api := &API{registry: reg, version: version}

	// API subtree: Bearer-token authenticated, /api/health still public.
	apiMux := http.NewServeMux()
	apiMux.HandleFunc("GET /api/health", api.handleHealth)
	apiMux.HandleFunc("GET /api/node", api.handleLocalNode)
	apiMux.HandleFunc("GET /api/nodes", api.handleNodes)
	apiHandler := AuthMiddleware(apiMux, auth.Token)

	// Browser subtree: session-cookie authenticated, with a pass-through
	// for valid Bearer credentials so API clients (curl, scripts) can
	// read the dashboard directly. The browser itself only ever uses the
	// session cookie; an invalid Bearer falls through to the normal
	// session/login behavior.
	bearerOK := bearerChecker(auth.Token)
	browserMux := http.NewServeMux()
	browserMux.HandleFunc("GET /{$}", dash)
	browserMux.HandleFunc("GET /login", loginGetHandler(sessions))
	browserMux.HandleFunc("POST /login", loginPostHandler(sessions, auth.Token, auth.SecureCookie, auth.SessionTTL))
	browserMux.HandleFunc("POST /logout", logoutHandler(sessions))
	browserHandler := BrowserSessionMiddleware(browserMux, sessions, bearerOK)

	root := http.NewServeMux()
	root.Handle("/api/", apiHandler)
	root.Handle("/static/style.css", stylesheetHandler())
	root.Handle("/", browserHandler)
	return root, nil
}

func (a *API) writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func (a *API) handleHealth(w http.ResponseWriter, _ *http.Request) {
	a.writeJSON(w, http.StatusOK, map[string]string{
		"status":  "ok",
		"service": "earthQuack-node",
		"version": a.version,
	})
}

func (a *API) handleLocalNode(w http.ResponseWriter, _ *http.Request) {
	a.writeJSON(w, http.StatusOK, a.registry.Local())
}

func (a *API) handleNodes(w http.ResponseWriter, _ *http.Request) {
	a.writeJSON(w, http.StatusOK, map[string]any{
		"nodes": a.registry.Nodes(),
	})
}
