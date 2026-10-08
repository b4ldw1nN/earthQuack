package node

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/b4ldw1nN/earthquack/internal/internet"
)

// API exposes read-only node endpoints. It is deliberately minimal
// and carries no write/management endpoints.
type API struct {
	registry *Registry
	version  string
}

// historyResponse is the JSON shape of GET /api/history. It carries
// bounded, chronological metric samples plus recent transition events.
type historyResponse struct {
	Samples []MetricSample `json:"samples"`
	Events  []HistoryEvent `json:"events,omitempty"`
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
	overview, err := NewOverviewHandler(reg)
	if err != nil {
		return nil, err
	}
	nodesH, err := NewNodesHandler(reg)
	if err != nil {
		return nil, err
	}
	eventsH, err := NewEventsHandler(reg)
	if err != nil {
		return nil, err
	}
	peersH, err := NewPeersHandler(reg)
	if err != nil {
		return nil, err
	}
	internetH, err := NewInternetHandler(reg)
	if err != nil {
		return nil, err
	}
	api := &API{registry: reg, version: version}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", overview)
	mux.HandleFunc("GET /nodes", nodesH)
	mux.HandleFunc("GET /events", eventsH)
	mux.HandleFunc("GET /peers", peersH)
	mux.HandleFunc("GET /internet", internetH)
	mux.HandleFunc("GET /static/style.css", stylesheetHandler())
	mux.HandleFunc("GET /static/background.svg", backgroundHandler())
	mux.HandleFunc("GET /api/health", api.handleHealth)
	mux.HandleFunc("GET /api/node", api.handleLocalNode)
	mux.HandleFunc("GET /api/nodes", api.handleNodes)
	mux.HandleFunc("GET /api/history", api.handleHistory)
	mux.HandleFunc("GET /api/events", api.handleEvents)
	mux.HandleFunc("GET /api/internet", api.handleInternet)
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
func NewServer(reg *Registry, version string, auth ServerAuthConfig, services ...ManagedService) (http.Handler, error) {
	overview, err := NewOverviewHandler(reg)
	if err != nil {
		return nil, err
	}
	nodesH, err := NewNodesHandler(reg)
	if err != nil {
		return nil, err
	}
	eventsH, err := NewEventsHandler(reg)
	if err != nil {
		return nil, err
	}
	peersH, err := NewPeersHandler(reg)
	if err != nil {
		return nil, err
	}
	internetH, err := NewInternetHandler(reg)
	if err != nil {
		return nil, err
	}
	if auth.SessionTTL <= 0 {
		auth.SessionTTL = DefaultSessionTTL
	}
	sessions := NewSessionStore(auth.SessionTTL, nil, auth.Token != "")
	controls, err := newServiceControls(services, sessions)
	if err != nil {
		return nil, err
	}
	nodesH = controls.page(nodesH)
	api := &API{registry: reg, version: version}

	// API subtree: Bearer-token authenticated, /api/health still public.
	apiMux := http.NewServeMux()
	apiMux.HandleFunc("GET /api/health", api.handleHealth)
	apiMux.HandleFunc("GET /api/node", api.handleLocalNode)
	apiMux.HandleFunc("GET /api/nodes", api.handleNodes)
	apiMux.HandleFunc("GET /api/history", api.handleHistory)
	apiMux.HandleFunc("GET /api/events", api.handleEvents)
	apiMux.HandleFunc("GET /api/internet", api.handleInternet)
	apiHandler := AuthMiddleware(apiMux, auth.Token)

	// Browser subtree: session-cookie authenticated, with a pass-through
	// for valid Bearer credentials so API clients (curl, scripts) can
	// read the dashboard directly. The browser itself only ever uses the
	// session cookie; an invalid Bearer falls through to the normal
	// session/login behavior.
	bearerOK := bearerChecker(auth.Token)
	browserMux := http.NewServeMux()
	browserMux.HandleFunc("GET /{$}", overview)
	browserMux.HandleFunc("GET /nodes", nodesH)
	browserMux.HandleFunc("GET /events", eventsH)
	browserMux.HandleFunc("GET /peers", peersH)
	browserMux.HandleFunc("GET /internet", internetH)
	browserMux.HandleFunc("GET /login", loginGetHandler(sessions))
	browserMux.HandleFunc("POST /login", loginPostHandler(sessions, auth.Token, auth.SecureCookie, auth.SessionTTL))
	browserMux.HandleFunc("POST /logout", logoutHandler(sessions))
	browserMux.HandleFunc("POST /services/control", controls.action)
	browserHandler := BrowserSessionMiddleware(browserMux, sessions, bearerOK)

	root := http.NewServeMux()
	root.Handle("/api/", apiHandler)
	root.Handle("/static/style.css", stylesheetHandler())
	root.Handle("/static/background.svg", backgroundHandler())
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

// handleHistory serves the local node's bounded telemetry history.
// It reads only the already-recorded rings — it never samples, never
// reads /proc, and never probes. Bounded by construction:
// LocalHistory caps the returned samples and the ring itself discards
// the oldest once MaxHistorySamples is reached.
func (a *API) handleHistory(w http.ResponseWriter, _ *http.Request) {
	samples, events := a.registry.LocalHistory(0)
	if samples == nil {
		samples = []MetricSample{}
	}
	if events == nil {
		events = []HistoryEvent{}
	}
	a.writeJSON(w, http.StatusOK, historyResponse{Samples: samples, Events: events})
}

// eventsResponse is the JSON shape of GET /api/events.
type eventsResponse struct {
	Events []Event `json:"events"`
}

// handleInternet serves the Internet Microscope snapshot: the module's
// sources, their last observation, and the headline counts. It is
// read-only and bounded by the number of configured sources; it never
// fetches, never triggers a check and never exposes how a source is
// observed beyond what the module reports.
func (a *API) handleInternet(w http.ResponseWriter, _ *http.Request) {
	snap := a.registry.InternetSnapshot()
	if snap.Sources == nil {
		snap.Sources = []internet.SourceStatus{}
	}
	a.writeJSON(w, http.StatusOK, snap)
}

// handleEvents serves the local node's recent events as the public typed
// Event model. It is read-only and bounded by MaxHistoryEvents. Default
// limit is 50; maximum is 200.
func (a *API) handleEvents(w http.ResponseWriter, r *http.Request) {
	limit := 50
	if s := r.URL.Query().Get("limit"); s != "" {
		if v, err := strconv.Atoi(s); err == nil && v > 0 {
			limit = v
			if limit > 200 {
				limit = 200
			}
		}
	}
	events := a.registry.History().RecentEvents(limit)
	if events == nil {
		events = []Event{}
	}
	a.writeJSON(w, http.StatusOK, eventsResponse{Events: events})
}
