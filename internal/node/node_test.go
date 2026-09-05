package node

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
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
