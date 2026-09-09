package node

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHealthJSONSerialization(t *testing.T) {
	n := healthyNode()
	n.Services[1].Status = ServiceStopped
	h := EvaluateHealth(n)
	rawH, err := json.Marshal(h)
	if err != nil {
		t.Fatal(err)
	}
	var decoded HealthInfo
	if err := json.Unmarshal(rawH, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Status != HealthDegraded || decoded.Summary != "1 service stopped" {
		t.Fatalf("round-trip: %+v", decoded)
	}
	n.Health = h
	rawN, _ := json.Marshal(n)
	var withHealth Node
	if err := json.Unmarshal(rawN, &withHealth); err != nil {
		t.Fatal(err)
	}
	if withHealth.Health == nil || withHealth.Health.Status != HealthDegraded {
		t.Fatalf("node round-trip: %+v", withHealth.Health)
	}
	d := Node{Identity: "tailscale:n1", Hostname: "V2253", Online: true}
	d.Health = EvaluateHealth(d)
	rawD, _ := json.Marshal(d)
	if strings.Contains(string(rawD), `"health"`) {
		t.Fatalf("discovered peer must omit health: %s", rawD)
	}
}

func fetchLocalNode(t *testing.T, srv string, path string) Node {
	t.Helper()
	resp, err := http.Get(srv + path)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if path == "/api/nodes" {
		var body struct {
			Nodes []Node `json:"nodes"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		for _, x := range body.Nodes {
			if x.Identity == "machine:test" {
				return x
			}
		}
		t.Fatal("local node missing")
	}
	var n Node
	if err := json.NewDecoder(resp.Body).Decode(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func fetchBody(t *testing.T, url string) string {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

func TestHealthAPIAndDashboard(t *testing.T) {
	reg, err := NewRegistry("machine:test", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.SetStorageInfoProvider(fakeStorageProvider{})
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{})
	reg.RegisterCapability("clipboard")
	reg.RegisterCapability("file-transfer")
	reg.RegisterService(Service{Name: "clipboard", Status: ServiceRunning, Endpoint: "127.0.0.1:8875"})
	reg.RegisterService(Service{Name: "file-transfer", Status: ServiceRunning, Endpoint: "127.0.0.1:8876"})
	handler, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(handler)
	defer srv.Close()

	if n := fetchLocalNode(t, srv.URL, "/api/node"); n.Health == nil || n.Health.Status != HealthHealthy {
		t.Fatalf("/api/node health: %+v", n.Health)
	}
	if n := fetchLocalNode(t, srv.URL, "/api/nodes"); n.Health == nil || n.Health.Status != HealthHealthy {
		t.Fatalf("/api/nodes health: %+v", n.Health)
	}
	resp, err := http.Get(srv.URL + "/api/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var live map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&live); err != nil {
		t.Fatal(err)
	}
	if live["status"] != "ok" {
		t.Fatalf("/api/health changed: %+v", live)
	}
	if html := fetchBody(t, srv.URL+"/"); !strings.Contains(html, "HEALTHY") {
		t.Fatalf("dashboard missing HEALTHY state")
	}
	reg.SetLocalServiceStatus("file-transfer", ServiceStopped, "127.0.0.1:8876")
	if n := fetchLocalNode(t, srv.URL, "/api/node"); n.Health == nil || n.Health.Status != HealthDegraded {
		t.Fatalf("after stop /api/node: %+v", n.Health)
	}
	html2 := fetchBody(t, srv.URL+"/")
	if !strings.Contains(html2, "DEGRADED") || !strings.Contains(html2, "1 service stopped") {
		t.Fatalf("dashboard missing degraded explanation")
	}
	reg.SetLocalServiceStatus("file-transfer", ServiceRunning, "127.0.0.1:8876")
	if n := fetchLocalNode(t, srv.URL, "/api/node"); n.Health == nil || n.Health.Status != HealthHealthy {
		t.Fatalf("after recovery /api/node: %+v", n.Health)
	}
}

func TestHealthRegisteredOfflinePeerCleared(t *testing.T) {
	srv, _ := fakeNodeServer(t, validNodeJSON("machine:peer1"), "application/json", http.StatusOK)
	reg, fp := newProbingRegistry(t, "127.0.0.1", portOf(t, srv.URL))
	nodes := reg.Nodes()
	n := findNode(nodes, "machine:peer1")
	if !n.Registered || !n.Online {
		t.Fatalf("peer not registered: %+v", nodes)
	}
	if n.Health == nil || n.Health.Status != HealthHealthy {
		t.Fatalf("registered online peer must be healthy: %+v", n.Health)
	}
	fp.mu.Lock()
	fp.nodes[0].Online = false
	fp.mu.Unlock()
	_ = reg.Nodes()
	reg.mu.Lock()
	reg.providers = nil
	reg.mu.Unlock()
	reg.mu.Lock()
	for id, e := range reg.peers {
		e.lastSeen = e.lastSeen.Add(-2 * PeerTTL)
		reg.peers[id] = e
	}
	reg.mu.Unlock()
	nodes = reg.Nodes()
	n = findNode(nodes, "machine:peer1")
	if n.Online {
		t.Fatalf("peer must be offline after TTL: %+v", n)
	}
	if n.Health == nil || n.Health.Status != HealthOffline {
		t.Fatalf("offline registered peer must be offline: %+v", n.Health)
	}
	if len(n.Services) != 0 {
		t.Fatalf("stale services leaked into offline view: %+v", n.Services)
	}
	if n.System != nil || n.Storage != nil || n.NetworkStats != nil {
		t.Fatalf("stale telemetry leaked into offline view: %+v", n)
	}
	d := findNode(nodes, "tailscale:nPEER1")
	if d.Identity != "" && d.Health != nil {
		t.Fatalf("discovered peer must have no health: %+v", d.Health)
	}
}
