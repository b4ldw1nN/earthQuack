package node

import (
	"net/http"
	"time"

	"github.com/b4ldw1nN/earthquack/web"
)

// dashboardView is the data passed to the dashboard template.
// It is built purely from the Node model — the template never sees
// transport-specific structures (Tailscale JSON, peer maps, etc.).
type dashboardView struct {
	Local Node
	Nodes []Node
	Now   time.Time
}

// NewDashboardHandler returns a handler rendering the dashboard HTML
// from the registry. It is read-only: it exposes exactly what
// /api/nodes exposes, in human-readable form.
func NewDashboardHandler(reg *Registry) (http.HandlerFunc, error) {
	tmpl, err := web.DashboardTemplate()
	if err != nil {
		return nil, err
	}
	return func(w http.ResponseWriter, _ *http.Request) {
		nodes := reg.Nodes()
		local := reg.Local()
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		err := tmpl.Execute(w, dashboardView{
			Local: local,
			Nodes: nodes,
			Now:   time.Now(),
		})
		if err != nil {
			http.Error(w, "template error", http.StatusInternalServerError)
		}
	}, nil
}

// stylesheetHandler serves the embedded dashboard stylesheet.
func stylesheetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		css, err := web.StyleSheet()
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "text/css; charset=utf-8")
		_, _ = w.Write(css)
	}
}
