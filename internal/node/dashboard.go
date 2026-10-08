package node

import (
	"bytes"
	"fmt"
	"html/template"
	"net/http"
	"time"

	"github.com/b4ldw1nN/earthquack/internal/internet"
	"github.com/b4ldw1nN/earthquack/web"
)

// dashboardView is the data passed to every page template.
// It is built purely from the Node model — the template never sees
// transport-specific structures (Tailscale JSON, peer maps, etc.).
type dashboardView struct {
	ActivePage   string // "overview" | "nodes" | "events" | "peers" | "internet"
	Local        Node
	Nodes        []Node
	Now          time.Time
	History      []MetricSample
	RecentEvents []Event
	HistRows     []historyRow
	EventStr     string
	Controls     controlsView
	// Internet is the Internet Microscope snapshot. It is always
	// present: a node without the module serves Enabled=false, so the
	// page renders an explanation instead of an empty list.
	Internet internet.Snapshot
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

// NodeServicesPercent renders the share of declared services on a node
// that are currently running, as a CSS width percentage (e.g. "50%").
// It is derived purely from each service's real status — never hardcoded
// and never fabricated when the node reports no services.
func (v dashboardView) NodeServicesPercent(n Node) string {
	if n.Services == nil || len(n.Services) == 0 {
		return "0%"
	}
	run := 0
	for _, s := range n.Services {
		if s.Status == ServiceRunning {
			run++
		}
	}
	return fmt.Sprintf("%.0f%%", float64(run)/float64(len(n.Services))*100)
}

// EventStateClass maps a typed transition event to a dashboard semantic
// state so the events timeline can render a consistent status indicator
// alongside the text label (which remains the source of truth).
func (v dashboardView) EventStateClass(e Event) string {
	switch e.Type {
	case EvServiceRecovered, EvHealthRecovered, EvNodeOnline:
		return "on"
	case EvHealthDegraded:
		return "warn"
	case EvServiceStopped, EvNodeOffline:
		return "off"
	default:
		return "muted"
	}
}

// InternetStateLabel renders the short status label for an Internet
// Microscope source. "unchanged" is shown as OK: it is the healthy
// steady state, and OK reads better on a dashboard than a double
// negative.
func (v dashboardView) InternetStateLabel(s internet.SourceStatus) string {
	switch s.Status {
	case internet.StatusUnchanged:
		return "OK"
	case internet.StatusNew:
		return "NEW"
	case internet.StatusChanged:
		return "CHANGED"
	case internet.StatusError:
		return "ERROR"
	default:
		return "PENDING"
	}
}

// InternetStateClass maps a source state onto the dashboard's existing
// semantic state classes (the same ones node health and services use),
// so no new visual language is introduced.
func (v dashboardView) InternetStateClass(s internet.SourceStatus) string {
	switch s.Status {
	case internet.StatusUnchanged:
		return "healthy"
	case internet.StatusNew, internet.StatusChanged:
		return "degraded"
	case internet.StatusError:
		return "offline"
	default:
		return "unknown"
	}
}

// buildView constructs a dashboardView for the given page name, drawing
// all data from the registry. It is read-only: it exposes exactly what
// /api/nodes exposes, in human-readable form, plus a small history
// section for the local node.
func buildView(reg *Registry, page string) dashboardView {
	nodes := reg.Nodes()
	local := reg.Local()
	samples, events := reg.LocalHistory(0)
	return dashboardView{
		ActivePage:   page,
		Local:        local,
		Nodes:        nodes,
		Now:          time.Now(),
		History:      samples,
		RecentEvents: reg.History().RecentEvents(20),
		HistRows:     buildHistoryRows(samples),
		EventStr:     formatHistoryEvents(events),
		Internet:     reg.InternetSnapshot(),
	}
}

// pageHandler returns an http.HandlerFunc that renders tmpl with a
// dashboardView for the given active page name. Template execution
// errors surface as 500s; the write is buffered so headers aren't
// sent until the template succeeds.
func pageHandler(tmpl *template.Template, reg *Registry, page string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var buf bytes.Buffer
		view := buildView(reg, page)
		view.Controls, _ = r.Context().Value(controlsKey{}).(controlsView)
		if err := tmpl.Execute(&buf, view); err != nil {
			http.Error(w, "template error", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = buf.WriteTo(w)
	}
}

// NewOverviewHandler returns the handler for GET /.
func NewOverviewHandler(reg *Registry) (http.HandlerFunc, error) {
	tmpl, err := web.OverviewTemplate()
	if err != nil {
		return nil, err
	}
	return pageHandler(tmpl, reg, "overview"), nil
}

// NewNodesHandler returns the handler for GET /nodes.
func NewNodesHandler(reg *Registry) (http.HandlerFunc, error) {
	tmpl, err := web.NodesTemplate()
	if err != nil {
		return nil, err
	}
	return pageHandler(tmpl, reg, "nodes"), nil
}

// NewEventsHandler returns the handler for GET /events.
func NewEventsHandler(reg *Registry) (http.HandlerFunc, error) {
	tmpl, err := web.EventsTemplate()
	if err != nil {
		return nil, err
	}
	return pageHandler(tmpl, reg, "events"), nil
}

// NewPeersHandler returns the handler for GET /peers.
func NewPeersHandler(reg *Registry) (http.HandlerFunc, error) {
	tmpl, err := web.PeersTemplate()
	if err != nil {
		return nil, err
	}
	return pageHandler(tmpl, reg, "peers"), nil
}

// NewInternetHandler returns the handler for GET /internet, the Internet
// Microscope page. Like every dashboard page it only reads: the module
// is polled by its own poller, never by a page request.
func NewInternetHandler(reg *Registry) (http.HandlerFunc, error) {
	tmpl, err := web.InternetTemplate()
	if err != nil {
		return nil, err
	}
	return pageHandler(tmpl, reg, "internet"), nil
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

// backgroundHandler serves the embedded atmospheric night-scene artwork
// as a static asset. Like the stylesheet it is inert presentation data and
// must not require authentication.
func backgroundHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		art, err := web.Background()
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		_, _ = w.Write(art)
	}
}
