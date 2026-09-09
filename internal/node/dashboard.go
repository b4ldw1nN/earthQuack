package node

import (
	"bytes"
	"fmt"
	"net/http"
	"time"

	"github.com/b4ldw1nN/earthquack/web"
)

// dashboardView is the data passed to the dashboard template.
// It is built purely from the Node model — the template never sees
// transport-specific structures (Tailscale JSON, peer maps, etc.).
type dashboardView struct {
	Local        Node
	Nodes        []Node
	Now          time.Time
	History      []MetricSample
	RecentEvents []Event
	HistRows     []historyRow
	EventStr     string
}

func (v dashboardView) OnlineNodesCount() int {
	c := 0
	for _, n := range v.Nodes {
		if n.Registered && n.Online {
			c++
		}
	}
	return c
}

func (v dashboardView) OfflineNodesCount() int {
	c := 0
	for _, n := range v.Nodes {
		if n.Registered && !n.Online {
			c++
		}
	}
	return c
}

func (v dashboardView) RegisteredNodesCount() int {
	c := 0
	for _, n := range v.Nodes {
		if n.Registered {
			c++
		}
	}
	return c
}

func (v dashboardView) DegradedNodesCount() int {
	c := 0
	for _, n := range v.Nodes {
		if n.Registered && n.Health != nil && n.Health.Status == HealthDegraded {
			c++
		}
	}
	return c
}

func (v dashboardView) RunningServicesCount() int {
	c := 0
	for _, n := range v.Nodes {
		if n.Registered && n.Online {
			for _, s := range n.Services {
				if s.Status == ServiceRunning {
					c++
				}
			}
		}
	}
	return c
}

func (v dashboardView) TotalServicesCount() int {
	c := 0
	for _, n := range v.Nodes {
		if n.Registered {
			c += len(n.Services)
		}
	}
	return c
}

func (v dashboardView) GlobalHealthStatus() string {
	if v.DegradedNodesCount() > 0 {
		return "DEGRADED"
	}
	if !v.Local.Online {
		return "OFFLINE"
	}
	return "HEALTHY"
}

func (v dashboardView) Greeting() string {
	hour := v.Now.Hour()
	if hour < 12 {
		return "Good morning!"
	} else if hour < 18 {
		return "Good afternoon!"
	}
	return "Good evening!"
}

func (v dashboardView) NodeMemoryPercent(n Node) string {
	if n.System != nil && n.System.Memory.Total > 0 {
		return fmt.Sprintf("%.0f%%", float64(n.System.Memory.Used)/float64(n.System.Memory.Total)*100)
	}
	return "—"
}

func (v dashboardView) NodeRunningServicesCount(n Node) int {
	c := 0
	for _, s := range n.Services {
		if s.Status == ServiceRunning {
			c++
		}
	}
	return c
}

func (v dashboardView) NodeStoragePercent(n Node) string {
	if n.Storage != nil && len(n.Storage.Filesystems) > 0 {
		return n.Storage.Filesystems[0].UsagePercentHuman()
	}
	return "—"
}

// NewDashboardHandler returns a handler rendering the dashboard HTML
// from the registry. It is read-only: it exposes exactly what
// /api/nodes exposes, in human-readable form, plus a small history
// section for the local node.
func NewDashboardHandler(reg *Registry) (http.HandlerFunc, error) {
	tmpl, err := web.DashboardTemplate()
	if err != nil {
		return nil, err
	}
	return func(w http.ResponseWriter, _ *http.Request) {
		nodes := reg.Nodes()
		local := reg.Local()
		samples, events := reg.LocalHistory(0)
		var buf bytes.Buffer
		err := tmpl.Execute(&buf, dashboardView{
			Local:        local,
			Nodes:        nodes,
			Now:          time.Now(),
			History:      samples,
			RecentEvents: reg.History().RecentEvents(20),
			HistRows:     buildHistoryRows(samples),
			EventStr:     formatHistoryEvents(events),
		})
		if err != nil {
			http.Error(w, "template error", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = buf.WriteTo(w)
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
