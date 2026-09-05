package node

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/b4ldw1nN/earthquack/web"
)

const sampleStatus = `{
  "BackendState": "Running",
  "TailscaleIPs": ["100.92.160.31"],
  "Self": {
    "ID": "nSELF", "HostName": "archii", "OS": "linux", "Online": true,
    "TailscaleIPs": ["100.92.160.31"]
  },
  "Peer": {
    "nodekey:1": {
      "ID": "nPEER1", "HostName": "homeserver", "OS": "linux",
      "Online": true, "TailscaleIPs": ["100.10.10.10"]
    },
    "nodekey:2": {
      "ID": "nPEER2", "HostName": "vps", "OS": "linux",
      "Online": false, "TailscaleIPs": ["100.20.20.20"]
    }
  }
}`

func newTestRegistry(t *testing.T) (*Registry, *fakeProvider) {
	t.Helper()
	fp := &fakeProvider{nodes: []Node{
		{
			Identity: "tailscale:nPEER1", Hostname: "homeserver", OS: "linux",
			Online: true, Capabilities: []string{}, Services: []Service{},
			Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.10.10.10"}},
		},
		{
			Identity: "tailscale:nPEER2", Hostname: "vps", OS: "linux",
			Online: false, Capabilities: []string{}, Services: []Service{},
			Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.20.20.20"}},
		},
	}}
	reg, err := NewRegistry("machine:test", []NetworkProvider{fp}, nil, nil)
	if err != nil {
		t.Fatalf("NewRegistry: %v", err)
	}
	// Explicit local capability/service registration (the node's own
	// declarations; never inferred).
	reg.RegisterCapability("clipboard")
	reg.RegisterService(Service{Name: "clipboard", Status: ServiceRunning, Endpoint: "127.0.0.1:8875"})
	reg.RegisterService(Service{Name: "file-transfer", Status: ServiceStopped})
	return reg, fp
}

type fakeProvider struct {
	mu    sync.Mutex
	nodes []Node
}

func (f *fakeProvider) Name() string { return "fake" }

func (f *fakeProvider) DiscoverPeers() ([]Node, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.nodes, nil
}

func TestTailscaleProviderParsesSample(t *testing.T) {
	p := &TailscaleProvider{binary: "/bin/true"} // not used; we test parse via DiscoverPeers? skip exec
	_ = p
	// Instead validate the tsStatus shape against the sample.
	var st tsStatus
	if err := json.Unmarshal([]byte(sampleStatus), &st); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if st.Self.HostName != "archii" || len(st.Peer) != 2 {
		t.Fatalf("unexpected parse: %+v", st)
	}
}

func TestRegistryPeersAndIdentityStability(t *testing.T) {
	reg, fp := newTestRegistry(t)
	nodes := reg.Nodes()
	if len(nodes) != 3 {
		t.Fatalf("want 3 nodes (local+2 peers), got %d", len(nodes))
	}
	// Identity must be stable across hostname and IP changes.
	fp.nodes[0].Hostname = "renamed-server"
	fp.nodes[0].Network.Addresses = []string{"100.99.99.99"}
	nodes = reg.Nodes()
	for _, n := range nodes {
		if n.Identity == "tailscale:nPEER1" {
			if n.Hostname != "renamed-server" || n.Network.Addresses[0] != "100.99.99.99" {
				t.Fatalf("identity not stable across metadata change: %+v", n)
			}
		}
	}
	// IP is not identity: identity string must not contain an IP.
	if containsIP(string(fp.nodes[0].Identity)) {
		t.Fatalf("identity must not embed an IP: %s", fp.nodes[0].Identity)
	}
}

func TestOnlineDecaysAfterTTL(t *testing.T) {
	fp := &fakeProvider{nodes: []Node{{
		Identity: "tailscale:nPEER1", Hostname: "homeserver", Online: true,
		Capabilities: []string{}, Services: []Service{},
	}}}
	base := time.Now()
	clock := func() time.Time { return base }
	reg, _ := NewRegistry("machine:test", []NetworkProvider{fp}, clock, nil)

	if n := findNode(reg.Nodes(), "tailscale:nPEER1"); !n.Online {
		t.Fatal("peer should be online immediately after refresh")
	}
	// Advance the clock past PeerTTL without any refresh happening.
	// (Nodes() refreshes, so simulate provider going silent by
	// removing the provider's report and relying on lastSeen.)
	reg.mu.Lock()
	reg.providers = nil // provider no longer responds
	reg.mu.Unlock()
	base = base.Add(2 * PeerTTL)
	if n := findNode(reg.Nodes(), "tailscale:nPEER1"); n.Online {
		t.Fatal("peer must not stay online forever without refreshes")
	}
}

func TestAPIEndpoints(t *testing.T) {
	reg, _ := newTestRegistry(t)
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatalf("NewAPI: %v", err)
	}
	srv := httptest.NewServer(handler)
	defer srv.Close()

	for _, path := range []string{"/api/health", "/api/node", "/api/nodes"} {
		resp, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatalf("GET %s: %v", path, err)
		}
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET %s: status %d", path, resp.StatusCode)
		}
		if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
			t.Fatalf("GET %s: content-type %q", path, ct)
		}
		resp.Body.Close()
	}

	// /api/nodes returns the local node and sorted peers.
	resp, err := http.Get(srv.URL + "/api/nodes")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var body struct {
		Nodes []Node `json:"nodes"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if len(body.Nodes) != 3 {
		t.Fatalf("want 3 nodes, got %d", len(body.Nodes))
	}
	if body.Nodes[0].Hostname != "archii" || body.Nodes[0].Identity != "machine:test" {
		t.Fatalf("unexpected first node: %+v", body.Nodes[0])
	}
}

func TestDashboardRendersNodeModel(t *testing.T) {
	reg, _ := newTestRegistry(t)
	// Give the local node concrete service statuses so the template
	// renders both a running and a stopped service.
	reg.SetLocalServiceStatus("clipboard", ServiceRunning, "127.0.0.1:8875")
	reg.SetLocalServiceStatus("file-transfer", ServiceStopped, "127.0.0.1:8876")
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatalf("NewAPI: %v", err)
	}
	srv := httptest.NewServer(handler)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /: status %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/html; charset=utf-8" {
		t.Fatalf("content-type: %q", ct)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	html := string(body)

	// The template must render Node-model fields only.
	for _, want := range []string{
		"earthQuack", "archii", "linux", "homeserver", "vps",
		"clipboard", "file-transfer", "running", "stopped",
	} {
		if !strings.Contains(html, want) {
			t.Errorf("dashboard HTML missing %q", want)
		}
	}
	// Must not leak transport-specific detail.
	for _, forbidden := range []string{"tailscale status", "nodekey", "BackendState", "Peer"} {
		if strings.Contains(html, forbidden) {
			t.Errorf("dashboard HTML leaked transport detail %q", forbidden)
		}
	}

	// Stylesheet is served.
	css, err := http.Get(srv.URL + "/static/style.css")
	if err != nil {
		t.Fatal(err)
	}
	defer css.Body.Close()
	if css.StatusCode != http.StatusOK {
		t.Fatalf("GET /static/style.css: status %d", css.StatusCode)
	}
	if ct := css.Header.Get("Content-Type"); ct != "text/css; charset=utf-8" {
		t.Fatalf("css content-type: %q", ct)
	}
}

func TestDashboardRendersNodeCategories(t *testing.T) {
	tmpl, err := web.DashboardTemplate()
	if err != nil {
		t.Fatalf("DashboardTemplate: %v", err)
	}
	// Exercise every node-state the dashboard must distinguish, built
	// purely from the Node model (no transport details).
	view := dashboardView{
		Local: Node{
			Identity: "machine:a", Hostname: "archii", OS: "linux", Online: true, Registered: true,
			Capabilities: []string{"clipboard", "file-transfer"},
			Services: []Service{
				{Name: "clipboard", Status: ServiceRunning, Endpoint: ":8875"},
				{Name: "file-transfer", Status: ServiceStopped, Endpoint: ":8876"},
			},
			Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.92.160.31"}},
		},
		Nodes: []Node{
			{ // local registered node
				Identity: "machine:a", Hostname: "archii", OS: "linux", Online: true, Registered: true,
				Capabilities: []string{"clipboard", "file-transfer"},
				Services: []Service{
					{Name: "clipboard", Status: ServiceRunning, Endpoint: ":8875"},
					{Name: "file-transfer", Status: ServiceStopped, Endpoint: ":8876"},
				},
				System: &SystemInfo{
					CPUCount: 16,
					Memory:   Memory{Total: 32 << 30, Available: 24 << 30, Used: 8 << 30},
					Uptime:   (3*24 + 12) * 3600,
					Load:     LoadAvg{One: 1.42, Five: 1.18, Fifteen: 0.91},
				},
				Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.92.160.31"}},
			},
			{ // registered remote, multiple capabilities, zero services
				Identity: "machine:b", Hostname: "homeserver", OS: "linux", Online: true, Registered: true,
				Capabilities: []string{"storage", "docker"},
				Network:      NetworkInfo{Transport: "tailscale", Addresses: []string{"100.1.1.1"}},
			},
			{ // registered remote, zero capabilities and zero services
				Identity: "machine:c", Hostname: "vps", OS: "linux", Online: false, Registered: true,
				Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.2.2.2"}},
			},
			{ // online discovered peer (no earthQuack)
				Identity: "tailscale:n1", Hostname: "DESKTOP-9S55DRM", OS: "windows", Online: true, Registered: false,
				Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.3.3.3"}},
			},
			{ // offline discovered peer
				Identity: "tailscale:n2", Hostname: "V2253", OS: "android", Online: false, Registered: false,
				Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.4.4.4"}},
			},
		},
		Now: time.Now(),
	}
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, view); err != nil {
		t.Fatalf("template execute: %v", err)
	}
	html := buf.String()

	// "this node" badge appears exactly once and only on the local node.
	if got := strings.Count(html, `class="badge">this node`); got != 1 {
		t.Fatalf("expected 1 'this node' badge, got %d", got)
	}

	// Two top-level sections: registered nodes then discovered peers.
	if !strings.Contains(html, ">NODES<") || !strings.Contains(html, "DISCOVERED PEERS") {
		t.Fatalf("expected NODES and DISCOVERED PEERS sections")
	}
	idx := strings.Index(html, "DISCOVERED PEERS")
	registeredSec := html[:idx]
	peerSec := html[idx:]

	// Registered section renders every registered node...
	for _, want := range []string{"archii", "homeserver", "vps", "100.92.160.31", "100.1.1.1", "100.2.2.2"} {
		if !strings.Contains(registeredSec, want) {
			t.Errorf("registered section missing %q", want)
		}
	}
	// ...and ONLY the nodes that declared them show capability/service
	// sections: archii + homeserver declare capabilities (2 headings),
	// only archii declares services (1 heading). vps has neither.
	if got := strings.Count(registeredSec, `<h3>Capabilities</h3>`); got != 2 {
		t.Errorf("expected 2 capability headings (archii, homeserver), got %d", got)
	}
	if got := strings.Count(registeredSec, `<h3>Services</h3>`); got != 1 {
		t.Errorf("expected 1 service heading (archii only), got %d", got)
	}
	// Multiple capabilities render as chips.
	for _, cap := range []string{">clipboard<", ">file-transfer<", ">storage<", ">docker<"} {
		if !strings.Contains(html, cap) {
			t.Errorf("missing capability chip %q", cap)
		}
	}
	// Running and stopped service states both render.
	if !strings.Contains(html, ">running<") || !strings.Contains(html, ">stopped<") {
		t.Errorf("running/stopped service states not rendered")
	}
	if !strings.Contains(html, ":8875") || !strings.Contains(html, ":8876") {
		t.Errorf("service endpoints not rendered")
	}
	// System section renders for the registered local node (its own
	// authoritative snapshot) and only there.
	if !strings.Contains(registeredSec, "<h3>System</h3>") {
		t.Errorf("system section not rendered for registered node")
	}
	for _, want := range []string{">16 logical", "8.0 / 32.0 GB", "3d 12h", "1.42"} {
		if !strings.Contains(html, want) {
			t.Errorf("system value %q not rendered", want)
		}
	}

	// Discovered-peer section shows both states but NEVER fabricates
	// capabilities/services for unregistered peers.
	for _, want := range []string{"DESKTOP-9S55DRM", "V2253", "earthQuack not responding", "earthQuack unavailable"} {
		if !strings.Contains(peerSec, want) {
			t.Errorf("peer section missing %q", want)
		}
	}
	if strings.Contains(peerSec, `<h3>Capabilities</h3>`) || strings.Contains(peerSec, `<h3>Services</h3>`) {
		t.Errorf("discovered peers fabricated capabilities/services")
	}
	if strings.Contains(peerSec, `<h3>System</h3>`) {
		t.Errorf("discovered peers fabricated system information")
	}
	// No transport detail leaks into the template output.
	for _, forbidden := range []string{"nodekey", "BackendState", "tailscale status", "peer map"} {
		if strings.Contains(html, forbidden) {
			t.Errorf("dashboard leaked transport detail %q", forbidden)
		}
	}
}

func findNode(nodes []Node, id Identity) Node {
	for _, n := range nodes {
		if n.Identity == id {
			return n
		}
	}
	return Node{}
}

func containsIP(s string) bool {
	for _, part := range []string{"100.", "192.168.", "10."} {
		if len(s) >= len(part) && s[:len(part)] == part {
			return true
		}
	}
	return false
}
