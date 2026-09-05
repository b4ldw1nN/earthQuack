package node

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// fakeNodeServer serves a fixed /api/node response and counts hits.
func fakeNodeServer(t *testing.T, body string, contentType string, status int) (*httptest.Server, *int64) {
	t.Helper()
	var hits int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		atomic.AddInt64(&hits, 1)
		if contentType != "" {
			w.Header().Set("Content-Type", contentType)
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv, &hits
}

func validNodeJSON(identity string) string {
	b, _ := json.Marshal(Node{
		Identity:     Identity(identity),
		Hostname:     "homeserver",
		OS:           "linux",
		Online:       true,
		Capabilities: []string{"clipboard"},
		Services:     []Service{{Name: "clipboard", Status: ServiceRunning}},
	})
	return string(b)
}

// newProbingRegistry wires a registry whose discovered peer points at
// the given address (host part only; port comes from the client).
func newProbingRegistry(t *testing.T, addr string, port int) (*Registry, *fakeProvider) {
	t.Helper()
	fp := &fakeProvider{nodes: []Node{{
		Identity: "tailscale:nPEER1", Hostname: "candidate", OS: "linux",
		Online: true, Capabilities: []string{}, Services: []Service{},
		Network: NetworkInfo{Transport: "tailscale", Addresses: []string{addr}},
	}}}
	reg, err := NewRegistry("machine:test", []NetworkProvider{fp}, nil, NewNodeClient(port))
	if err != nil {
		t.Fatalf("NewRegistry: %v", err)
	}
	return reg, fp
}

func portOf(t *testing.T, url string) int {
	t.Helper()
	idx := strings.LastIndex(url, ":")
	if idx < 0 {
		t.Fatalf("no port in %q", url)
	}
	var port int
	if _, err := fmt.Sscanf(url[idx+1:], "%d", &port); err != nil {
		t.Fatalf("bad port in %q", url)
	}
	return port
}

func TestPeerProbeSuccessRegistersAuthoritativeNode(t *testing.T) {
	srv, hits := fakeNodeServer(t, validNodeJSON("machine:peer1"), "application/json", http.StatusOK)
	port := portOf(t, srv.URL)

	reg, _ := newProbingRegistry(t, "127.0.0.1", port)
	nodes := reg.Nodes()

	// Registered under its SELF-REPORTED identity, not the transport
	// scoped one, with authoritative data adopted.
	n := findNode(nodes, "machine:peer1")
	if n.Identity != "machine:peer1" {
		t.Fatalf("peer not registered authoritatively: %+v", nodes)
	}
	if !n.Registered || !n.Online {
		t.Errorf("want registered+online, got %+v", n)
	}
	if len(n.Capabilities) != 1 || n.Capabilities[0] != "clipboard" {
		t.Errorf("authoritative capabilities not adopted: %+v", n)
	}
	// Route comes from discovery, not the response body.
	if n.Network.Transport != "tailscale" || n.Network.Addresses[0] != "127.0.0.1" {
		t.Errorf("network info lost: %+v", n.Network)
	}
	// Transport-scoped discovered entry must be superseded.
	if x := findNode(nodes, "tailscale:nPEER1"); x.Identity != "" {
		t.Errorf("stale discovered entry still present: %+v", x)
	}
	if atomic.LoadInt64(hits) != 1 {
		t.Errorf("expected exactly 1 probe, got %d", atomic.LoadInt64(hits))
	}
	// ProbeBackoff: a second listing must not re-probe.
	_ = reg.Nodes()
	if atomic.LoadInt64(hits) != 1 {
		t.Errorf("probe backoff violated: %d probes", atomic.LoadInt64(hits))
	}
}

func TestPeerProbeFailureKeepsDiscoveredPeer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {}))
	url := srv.URL
	srv.Close() // nothing listens: guaranteed connection refusal

	reg, _ := newProbingRegistry(t, "127.0.0.1", portOf(t, url))
	nodes := reg.Nodes()

	n := findNode(nodes, "tailscale:nPEER1")
	if n.Identity == "" {
		t.Fatal("discovered peer was dropped after failed probe")
	}
	if n.Registered {
		t.Errorf("failed probe must not mark peer registered: %+v", n)
	}
	if len(n.Capabilities) != 0 || len(n.Services) != 0 {
		t.Errorf("discovered peer must not gain earthQuack data: %+v", n)
	}
	if n.Hostname != "candidate" || n.OS != "linux" {
		t.Errorf("discovery info discarded: %+v", n)
	}
}

func TestPeerProbeInvalidResponseRejected(t *testing.T) {
	cases := []struct {
		name        string
		body        string
		contentType string
		status      int
	}{
		{"malformed json", "{not json", "application/json", http.StatusOK},
		{"missing identity", `{"hostname":"h","os":"linux"}`, "application/json", http.StatusOK},
		{"missing hostname", `{"identity":"machine:x","os":"linux"}`, "application/json", http.StatusOK},
		{"wrong status", validNodeJSON("machine:x"), "application/json", http.StatusInternalServerError},
		{"wrong content type", validNodeJSON("machine:x"), "text/html", http.StatusOK},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv, _ := fakeNodeServer(t, tc.body, tc.contentType, tc.status)
			reg, _ := newProbingRegistry(t, "127.0.0.1", portOf(t, srv.URL))
			nodes := reg.Nodes()
			n := findNode(nodes, "tailscale:nPEER1")
			if n.Identity == "" || n.Registered {
				t.Fatalf("invalid response accepted: %+v", nodes)
			}
		})
	}
}

func TestPeerIdentityStableAcrossAddressChange(t *testing.T) {
	srv, _ := fakeNodeServer(t, validNodeJSON("machine:peer1"), "application/json", http.StatusOK)
	port := portOf(t, srv.URL)

	reg, fp := newProbingRegistry(t, "127.0.0.1", port)
	if n := findNode(reg.Nodes(), "machine:peer1"); n.Identity != "machine:peer1" {
		t.Fatal("peer not registered")
	}

	// The peer's Tailscale address changes; identity must not.
	fp.mu.Lock()
	fp.nodes[0].Network.Addresses = []string{"100.99.99.99"}
	fp.mu.Unlock()
	nodes := reg.Nodes()
	n := findNode(nodes, "machine:peer1")
	if n.Identity != "machine:peer1" {
		t.Fatalf("identity changed with address: %+v", nodes)
	}
	if n.Network.Addresses[0] != "100.99.99.99" {
		t.Errorf("route not updated: %+v", n.Network)
	}
}

func TestSelfNodeNeverProbed(t *testing.T) {
	srv, hits := fakeNodeServer(t, validNodeJSON("machine:test"), "application/json", http.StatusOK)
	defer srv.Close()
	fp := &fakeProvider{nodes: []Node{{
		Identity: "machine:test", Hostname: "self", OS: "linux",
		Online: true, Capabilities: []string{}, Services: []Service{},
		Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"127.0.0.1"}},
	}}}
	reg, err := NewRegistry("machine:test", []NetworkProvider{fp}, nil, NewNodeClient(portOf(t, srv.URL)))
	if err != nil {
		t.Fatal(err)
	}
	nodes := reg.Nodes()
	if len(nodes) != 1 {
		t.Fatalf("self must not be tracked as peer: %+v", nodes)
	}
	if nodes[0].Hostname == "self" {
		t.Fatal("self was replaced by peer response")
	}
	if atomic.LoadInt64(hits) != 0 {
		t.Errorf("self was probed %d times", atomic.LoadInt64(hits))
	}
}

func TestNodeClientValidates(t *testing.T) {
	t.Run("valid", func(t *testing.T) {
		srv, _ := fakeNodeServer(t, validNodeJSON("machine:p"), "application/json", http.StatusOK)
		n, err := NewNodeClient(portOf(t, srv.URL)).GetNode(strings.TrimPrefix(srv.URL, "http://"))
		if err != nil || n.Identity != "machine:p" {
			t.Fatalf("GetNode: %v %+v", err, n)
		}
	})
	t.Run("garbage", func(t *testing.T) {
		srv, _ := fakeNodeServer(t, "garbage", "application/json", http.StatusOK)
		if _, err := NewNodeClient(portOf(t, srv.URL)).GetNode(strings.TrimPrefix(srv.URL, "http://")); err == nil {
			t.Fatal("garbage accepted")
		}
	})
}
