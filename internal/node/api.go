package node

import (
	"encoding/json"
	"net/http"
)

// API exposes read-only node endpoints. It is deliberately minimal
// and carries no write/management endpoints. In the current
// deployment the server binds only to the Tailscale interface; when
// auth is added ecosystem-wide it should wrap these handlers.
type API struct {
	registry *Registry
	version  string
}

// NewAPI returns an http.Handler serving the node API and dashboard:
//
//	GET /              — HTML dashboard (same data as /api/nodes)
//	GET /static/style.css — dashboard stylesheet
//	GET /api/health    — liveness of this instance
//	GET /api/node      — this earthQuack instance's node description
//	GET /api/nodes     — all known nodes (local + discovered peers)
//
// All handlers are read-only. In the current deployment the server
// binds only to the Tailscale interface; when auth is added
// ecosystem-wide it should wrap these handlers.
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
