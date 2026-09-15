package node

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/b4ldw1nN/earthquack/web"
)

// renderView executes the embedded nodes template against a view and
// returns the resulting HTML. Tests below exercise the UI contract only.
func renderView(t *testing.T, view dashboardView) string {
	tmpl, err := web.NodesTemplate()
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, view); err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

// TestServicesImmediatelyVisiblePerNode asserts services are first-class:
// rendered on every registered node card in an always-visible inline list,
// NOT only inside the collapsible "Inspect Details" toggle.
func TestServicesImmediatelyVisiblePerNode(t *testing.T) {
	view := dashboardView{
		Local: Node{Identity: "machine:local", Hostname: "archii", Online: true, Registered: true},
		Nodes: []Node{
			{
				Identity: "machine:local", Hostname: "archii", Online: true, Registered: true,
				Services: []Service{
					{Name: "clipboard", Status: ServiceRunning, Endpoint: ":8875"},
					{Name: "file-transfer", Status: ServiceRunning, Endpoint: ":8876"},
				},
			},
			{
				Identity: "machine:home", Hostname: "homeserver", Online: true, Registered: true,
				Services: []Service{
					{Name: "storage", Status: ServiceRunning},
					{Name: "docker", Status: ServiceStopped},
				},
			},
		},
		Now: time.Now(),
	}
	html := renderView(t, view)

	// One always-visible inline services block per registered node card.
	if got := strings.Count(html, "card-services-inline"); got != 2 {
		t.Errorf("inline services block per registered card: got %d, want 2", got)
	}
	// Every service is rendered by name in the visible area.
	for _, want := range []string{">clipboard<", ">file-transfer<", ">storage<", ">docker<"} {
		if !strings.Contains(html, want) {
			t.Errorf("service %q not rendered by name", want)
		}
	}
	// Both running and stopped states carry distinct classes + text.
	if !strings.Contains(html, "service-mini-item run") || !strings.Contains(html, "service-mini-item stop") {
		t.Error("running/stopped service state classes missing on inline list")
	}
	if !strings.Contains(html, ">running<") || !strings.Contains(html, ">stopped<") {
		t.Error("running/stopped state text missing")
	}

	// The inline services list must appear before the hidden details toggle,
	// proving services are not gated behind a click.
	toggleIdx := strings.Index(html, "node-expanded-details hidden")
	inlineIdx := strings.Index(html, "card-services-inline")
	if toggleIdx == -1 || inlineIdx == -1 || !(inlineIdx < toggleIdx) {
		t.Errorf("inline services must render before the hidden details block (inline=%d toggle=%d)", inlineIdx, toggleIdx)
	}
}

// TestServicesUnknownStateRenders asserts the amber/unknown state renders
// for a service whose status is genuinely unknown.
func TestServicesUnknownStateRenders(t *testing.T) {
	view := dashboardView{
		Local: Node{Identity: "machine:local", Hostname: "archii", Online: true, Registered: true},
		Nodes: []Node{
			{
				Identity: "machine:local", Hostname: "archii", Online: true, Registered: true,
				Services: []Service{{Name: "probe", Status: ServiceUnknown}},
			},
		},
		Now: time.Now(),
	}
	html := renderView(t, view)
	if !strings.Contains(html, "service-mini-item unk") || !strings.Contains(html, ">unknown<") {
		t.Error("unknown service state not rendered")
	}
}

// TestServicesRunningRatioBar asserts the services metric bar reflects the
// actual running share, not a hardcoded 100%.
func TestServicesRunningRatioBar(t *testing.T) {
	view := dashboardView{
		Local: Node{Identity: "machine:local", Hostname: "archii", Online: true, Registered: true},
		Nodes: []Node{
			{
				Identity: "machine:local", Hostname: "archii", Online: true, Registered: true,
				Services: []Service{
					{Name: "clipboard", Status: ServiceRunning},
					{Name: "file-transfer", Status: ServiceRunning},
				},
			},
			{
				Identity: "machine:home", Hostname: "homeserver", Online: true, Registered: true,
				Services: []Service{
					{Name: "storage", Status: ServiceRunning},
					{Name: "docker", Status: ServiceStopped},
				},
			},
		},
		Now: time.Now(),
	}
	html := renderView(t, view)
	if !strings.Contains(html, "style=\"width: 100%\"") {
		t.Error("fully-running node should show 100% services bar")
	}
	if !strings.Contains(html, "style=\"width: 50%\"") {
		t.Error("half-running node should show 50% services bar")
	}
}

// TestHealthUnknownStateRenders asserts a registered, online node with
// genuinely insufficient info renders an UNKNOWN health pill.
func TestHealthUnknownStateRenders(t *testing.T) {
	n := Node{
		Identity: "machine:u", Hostname: "bare", Online: true, Registered: true,
		Health:   &HealthInfo{Status: HealthUnknown, Summary: "insufficient information"},
	}
	view := dashboardView{Local: n, Nodes: []Node{n}, Now: time.Now()}
	html := renderView(t, view)
	if !strings.Contains(html, "health-pill unknown") || !strings.Contains(html, ">UNKNOWN<") {
		t.Error("unknown health state not rendered")
	}
}
// TestLocalNodeDetailPanel asserts the local node's expandable detail section
// on the /nodes page renders the full telemetry: services, system, storage,
// network, and history sub-sections.
func TestLocalNodeDetailPanel(t *testing.T) {
	local := Node{
		Identity: "machine:local", Hostname: "archii", OS: "linux", Online: true, Registered: true,
		Services: []Service{{Name: "clipboard", Status: ServiceRunning, Endpoint: ":8875"}},
		System: &SystemInfo{CPUCount: 8, Arch: "x86_64", OS: "Arch Linux",
			Memory: Memory{Total: 16 * bytesPerGB, Used: 4 * bytesPerGB},
			Uptime: 86400, Load: LoadAvg{One: 0.42, Five: 0.55, Fifteen: 0.60}},
		Storage:      &StorageInfo{Filesystems: []Filesystem{{Mount: "/", Total: 100 * bytesPerGB, Used: 40 * bytesPerGB, Available: 60 * bytesPerGB}}},
		NetworkStats: &NetworkStatsInfo{Interfaces: []InterfaceStats{{Name: "eth0", RXBytes: 1024 * 1024, TXBytes: 2048 * 1024}}},
	}
	view := dashboardView{
		Local: local,
		Nodes: []Node{local},
		Now:   time.Now(),
		HistRows: []historyRow{
			{Label: "CPU %", Bars: "▂▃▅█", Value: "15%"},
			{Label: "Memory %", Bars: "▄▄▄▄", Value: "25%"},
		},
		RecentEvents: []Event{
			{Seq: 2, Time: time.Now(), Node: "machine:home", Type: EvServiceStopped, Name: "clipboard", Message: "service clipboard stopped"},
		},
	}
	html := renderView(t, view)

	// Expandable details section is in the page.
	if !strings.Contains(html, "node-expanded-details") {
		t.Error("local node expandable detail section not rendered")
	}
	// Deep detail sections render inside the collapsed area.
	for _, want := range []string{"Services", "System", "Storage", "Network", "History"} {
		if !strings.Contains(html, want) {
			t.Errorf("node detail missing section %q", want)
		}
	}
	// Telemetry renders in the page.
	if !strings.Contains(html, "8 logical") || !strings.Contains(html, "Arch Linux") ||
		!strings.Contains(html, "40.0 / 100.0 GB") || !strings.Contains(html, ">eth0<") {
		t.Error("local detail telemetry missing")
	}
}

// TestBackgroundAssetServed asserts the atmosphere artwork is a static,
// embedded, unauthenticated asset (not a base64 blob in the HTML/CSS).
func TestBackgroundAssetServed(t *testing.T) {
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(handler)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/static/background.svg")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("background asset status %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.Contains(ct, "svg") {
		t.Fatalf("background Content-Type = %q, want svg", ct)
	}
}

// TestDashboardTokenNeverLeaks asserts the configured token never appears
// in the rendered HTML for an authenticated session.
func TestDashboardTokenNeverLeaks(t *testing.T) {
	reg, _ := newTestRegistry(t)
	h, err := NewServer(reg, "test", ServerAuthConfig{Token: "supersecret"})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	c := noRedirect()
	cookie := postLogin(t, c, srv, "supersecret")
	if cookie == nil {
		t.Fatal("no session cookie issued")
	}
	req, _ := http.NewRequest(http.MethodGet, srv.URL + "/", nil)
	req.AddCookie(cookie)
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if strings.Contains(string(body), "supersecret") {
		t.Fatal("token leaked into dashboard HTML")
	}
}

// TestResponsiveCSSStructural asserts responsive rules exist and the layout
// cannot introduce obvious horizontal overflow (flex gutter + media rules).
func TestResponsiveCSSStructural(t *testing.T) {
	css, err := web.StyleSheet()
	if err != nil {
		t.Fatal(err)
	}
	s := string(css)
	if got := strings.Count(s, "@media"); got < 2 {
		t.Errorf("expected responsive @media queries, got %d", got)
	}
	for _, sel := range []string{".app-layout", ".sidebar", ".service-item", ".local-subsection"} {
		if !strings.Contains(s, sel) {
			t.Errorf("stylesheet missing %q", sel)
		}
	}
	// Flex layout with a shrinkable content column guards against overflow.
	if !strings.Contains(s, "min-width: 0") {
		t.Error("layout missing min-width:0 on flex child (overflow guard)")
	}
}