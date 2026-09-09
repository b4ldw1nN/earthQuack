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

func TestDashboardUIRedesign(t *testing.T) {
	fifty := 15.0
	view := dashboardView{
		Local: Node{
			Identity: "machine:local", Hostname: "archii", OS: "linux",
			Online: true, Registered: true,
			Capabilities: []string{"clipboard", "file-transfer"},
			Services: []Service{
				{Name: "clipboard", Status: ServiceRunning, Endpoint: "8875", Version: "1.0"},
				{Name: "file-transfer", Status: ServiceRunning, Endpoint: "8876", Version: "1.0"},
			},
			Health:       &HealthInfo{Status: HealthHealthy},
			System:       &SystemInfo{CPUCount: 8, Arch: "x86_64", OS: "Arch Linux", Memory: Memory{Total: 16 * bytesPerGB, Used: 4 * bytesPerGB}, Uptime: 86400, Load: LoadAvg{One: 0.42, Five: 0.55, Fifteen: 0.60}},
			Storage:      &StorageInfo{Filesystems: []Filesystem{{Mount: "/", Total: 100 * bytesPerGB, Used: 40 * bytesPerGB, Available: 60 * bytesPerGB}}},
			NetworkStats: &NetworkStatsInfo{Interfaces: []InterfaceStats{{Name: "eth0", Addresses: []string{"100.64.0.1"}, RXBytes: 1024 * 1024, TXBytes: 2048 * 1024}}},
		},
		Nodes: []Node{
			{
				Identity: "machine:local", Hostname: "archii", OS: "linux",
				Online: true, Registered: true,
				Capabilities: []string{"clipboard", "file-transfer"},
				Services: []Service{
					{Name: "clipboard", Status: ServiceRunning, Endpoint: "8875", Version: "1.0"},
					{Name: "file-transfer", Status: ServiceRunning, Endpoint: "8876", Version: "1.0"},
				},
				Health:       &HealthInfo{Status: HealthHealthy},
				System:       &SystemInfo{CPUCount: 8, Arch: "x86_64", OS: "Arch Linux", Memory: Memory{Total: 16 * bytesPerGB, Used: 4 * bytesPerGB}, Uptime: 86400, Load: LoadAvg{One: 0.42, Five: 0.55, Fifteen: 0.60}},
				Storage:      &StorageInfo{Filesystems: []Filesystem{{Mount: "/", Total: 100 * bytesPerGB, Used: 40 * bytesPerGB, Available: 60 * bytesPerGB}}},
				NetworkStats: &NetworkStatsInfo{Interfaces: []InterfaceStats{{Name: "eth0", Addresses: []string{"100.64.0.1"}, RXBytes: 1024 * 1024, TXBytes: 2048 * 1024}}},
			},
			{
				Identity: "machine:home", Hostname: "homeserver", OS: "linux",
				Online: true, Registered: true,
				Services: []Service{
					{Name: "clipboard", Status: ServiceStopped, Endpoint: "8875"},
					{Name: "file-transfer", Status: ServiceRunning, Endpoint: "8876"},
				},
				Health: &HealthInfo{Status: HealthDegraded, Summary: "1 service stopped"},
			},
			{
				Identity: "machine:vps", Hostname: "vps", OS: "linux",
				Online: false, Registered: true,
				Health: &HealthInfo{Status: HealthOffline, Summary: "earthQuack node unavailable"},
			},
			{
				Identity: "tailscale:peer1", Hostname: "phone", OS: "android",
				Online: true, Registered: false,
				Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.64.0.99"}},
			},
		},
		Now: time.Now(),
		HistRows: []historyRow{
			{Label: "CPU %", Bars: "▂▃▅█", Value: "15%"},
			{Label: "Memory %", Bars: "▄▄▄▄", Value: "25%"},
		},
		RecentEvents: []Event{
			{Seq: 2, Time: time.Now(), Node: "machine:home", Type: EvServiceStopped, Name: "clipboard", Message: "service clipboard stopped"},
			{Seq: 1, Time: time.Now().Add(-time.Minute), Node: "machine:local", Type: EvNodeOnline, Message: "node online"},
		},
		EventStr: "15:04 healthy → degraded · service clipboard running → stopped",
	}

	tmpl, err := web.DashboardTemplate()
	if err != nil {
		t.Fatalf("DashboardTemplate failed: %v", err)
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, view); err != nil {
		t.Fatalf("Template execution failed: %v", err)
	}

	html := buf.String()

	// 1. Overview renders
	if !strings.Contains(html, "Overview") {
		t.Error("missing Overview section")
	}

	// 2. Sidebar renders
	if !strings.Contains(html, "sidebar") || !strings.Contains(html, "Tailnet Node Monitor") {
		t.Error("missing Sidebar with subtitle")
	}

	// 3. Summary counts
	if !strings.Contains(html, `id="online-count">2<`) {
		t.Errorf("summary online count wrong, html: %s", html)
	}
	if !strings.Contains(html, `id="offline-count">1<`) {
		t.Errorf("summary offline count wrong, html: %s", html)
	}
	if !strings.Contains(html, `id="running-services">3<`) {
		t.Errorf("summary running services count wrong, html: %s", html)
	}
	if !strings.Contains(html, `id="degraded-nodes">1<`) {
		t.Errorf("summary degraded nodes count wrong, html: %s", html)
	}

	// 4. Registered node cards render
	for _, host := range []string{"archii", "homeserver", "vps"} {
		if !strings.Contains(html, host) {
			t.Errorf("missing registered host %s", host)
		}
	}

	// 5. "THIS NODE" appears exactly once
	if got := strings.Count(html, `class="badge">this node`); got != 1 {
		t.Errorf("expected exactly 1 'this node' badge, got %d", got)
	}

	// 6 & 7. Services prominently rendered
	if !strings.Contains(html, "services-card-list") || !strings.Contains(html, "service-item") {
		t.Error("services not rendered as first-class card list")
	}

	// 8. Service status states (running, stopped)
	if !strings.Contains(html, "status-running") || !strings.Contains(html, "status-stopped") {
		t.Error("missing service status classes")
	}

	// 9. Health states render
	if !strings.Contains(html, "HEALTHY") || !strings.Contains(html, "DEGRADED") {
		t.Error("missing health badges")
	}

	// 10 & 11. Discovered peers rendered without capabilities/services/system
	idxPeers := strings.Index(html, "DISCOVERED PEERS")
	if idxPeers == -1 {
		t.Fatal("missing DISCOVERED PEERS section")
	}
	peerSec := html[idxPeers:]
	if !strings.Contains(peerSec, "phone") || !strings.Contains(peerSec, "unverified") {
		t.Error("peer section missing discovered peer details")
	}
	if strings.Contains(peerSec, "<h3>Capabilities</h3>") || strings.Contains(peerSec, "<h3>Services</h3>") || strings.Contains(peerSec, "<h3>System</h3>") {
		t.Error("discovered peer fabricated capabilities/services/system")
	}

	// 12. Recent events render newest-first
	idxEvStopped := strings.Index(html, "service.stopped")
	idxEvOnline := strings.Index(html, "node.online")
	if idxEvStopped == -1 || idxEvOnline == -1 || !(idxEvStopped < idxEvOnline) {
		t.Errorf("recent events not rendered newest first: stopped=%d, online=%d", idxEvStopped, idxEvOnline)
	}

	// 14. System telemetry
	if !strings.Contains(html, "8 logical") || !strings.Contains(html, "Arch Linux") {
		t.Error("system telemetry missing")
	}

	// 15. Storage telemetry
	if !strings.Contains(html, "40.0 / 100.0 GB") {
		t.Error("storage telemetry missing")
	}

	// 16. Network telemetry
	if !strings.Contains(html, ">eth0<") {
		t.Error("network telemetry missing")
	}

	// 17. History renders
	if got := strings.Count(html, "<h3>History</h3>"); got != 1 {
		t.Errorf("expected 1 history section, got %d", got)
	}
	if !strings.Contains(html, "CPU %") || !strings.Contains(html, "Memory %") {
		t.Error("history sparklines missing")
	}

	_ = fifty
}

func TestDashboardEmptyEvents(t *testing.T) {
	view := dashboardView{
		Local: Node{Identity: "machine:local", Hostname: "archii", Online: true, Registered: true},
		Nodes: []Node{{Identity: "machine:local", Hostname: "archii", Online: true, Registered: true}},
		Now:   time.Now(),
	}
	tmpl, err := web.DashboardTemplate()
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, view); err != nil {
		t.Fatal(err)
	}
	html := buf.String()
	if !strings.Contains(html, "No recent events recorded.") {
		t.Error("missing empty events state")
	}
}

func TestStylesheetServes(t *testing.T) {
	h := stylesheetHandler()
	srv := httptest.NewServer(h)
	defer srv.Close()

	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("stylesheet GET status = %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.Contains(ct, "text/css") {
		t.Fatalf("stylesheet Content-Type = %q, want text/css", ct)
	}
	body, _ := io.ReadAll(resp.Body)
	css := string(body)
	if !strings.Contains(css, "--bg") || !strings.Contains(css, ".sidebar") || !strings.Contains(css, ".service-item") {
		t.Fatal("stylesheet missing core selectors")
	}
}
