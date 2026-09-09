package node

import (
	"testing"
)

func healthyNode() Node {
	return Node{
		Identity: "machine:a", Hostname: "archii", OS: "linux",
		Online: true, Registered: true,
		Capabilities: []string{"clipboard", "file-transfer"},
		Services: []Service{
			{Name: "clipboard", Status: ServiceRunning},
			{Name: "file-transfer", Status: ServiceRunning},
		},
		Network: NetworkInfo{Transport: "tailscale", Addresses: []string{"100.92.160.31"}},
	}
}

func TestHealthHealthyRegisteredNode(t *testing.T) {
	h := EvaluateHealth(healthyNode())
	if h == nil || h.Status != HealthHealthy {
		t.Fatalf("want healthy, got %+v", h)
	}
	if h.Summary != "" || len(h.Issues) != 0 {
		t.Fatalf("healthy must carry no summary/issues: %+v", h)
	}
}

func TestHealthHealthyWithZeroServices(t *testing.T) {
	n := healthyNode()
	n.Services = nil
	n.Capabilities = nil
	if h := EvaluateHealth(n); h == nil || h.Status != HealthHealthy {
		t.Fatalf("zero-service registered node must be healthy: %+v", h)
	}
}

func TestHealthDegradedOneStopped(t *testing.T) {
	n := healthyNode()
	n.Services[1].Status = ServiceStopped
	h := EvaluateHealth(n)
	if h == nil || h.Status != HealthDegraded {
		t.Fatalf("want degraded, got %+v", h)
	}
	if h.Summary != "1 service stopped" {
		t.Fatalf("summary: %q", h.Summary)
	}
	if len(h.Issues) != 1 || h.Issues[0].Name != "file-transfer" {
		t.Fatalf("issues: %+v", h.Issues)
	}
}

func TestHealthDegradedMultipleStopped(t *testing.T) {
	n := healthyNode()
	n.Services[0].Status = ServiceStopped
	n.Services[1].Status = ServiceStopped
	h := EvaluateHealth(n)
	if h == nil || h.Status != HealthDegraded {
		t.Fatalf("want degraded, got %+v", h)
	}
	if h.Summary != "2 services stopped" {
		t.Fatalf("summary: %q", h.Summary)
	}
	if len(h.Issues) != 2 {
		t.Fatalf("issues: %+v", h.Issues)
	}
}

func TestHealthUnknownServiceStatusDegrades(t *testing.T) {
	n := healthyNode()
	n.Services[1].Status = ServiceUnknown
	h := EvaluateHealth(n)
	if h == nil || h.Status != HealthDegraded {
		t.Fatalf("unknown service status must degrade, not pass as running: %+v", h)
	}
	if h.Summary != "1 service status unknown" {
		t.Fatalf("summary: %q", h.Summary)
	}
}

func TestHealthOfflineRegisteredNode(t *testing.T) {
	n := healthyNode()
	n.Online = false
	h := EvaluateHealth(n)
	if h == nil || h.Status != HealthOffline {
		t.Fatalf("want offline, got %+v", h)
	}
	if h.Summary != "earthQuack node unavailable" {
		t.Fatalf("summary: %q", h.Summary)
	}
}

func TestHealthStaleServicesDoNotOverrideOffline(t *testing.T) {
	n := healthyNode()
	n.Online = false
	n.Services[0].Status = ServiceStopped
	n.Services[1].Status = ServiceStopped
	h := EvaluateHealth(n)
	if h == nil || h.Status != HealthOffline {
		t.Fatalf("offline must win over stale stopped services: %+v", h)
	}
}

func TestHealthUnknownWhenNoIdentity(t *testing.T) {
	n := Node{Registered: true, Online: true}
	h := EvaluateHealth(n)
	if h == nil || h.Status != HealthUnknown {
		t.Fatalf("want unknown, got %+v", h)
	}
}

func TestHealthDiscoveredPeersHaveNone(t *testing.T) {
	for _, n := range []Node{
		{Identity: "tailscale:n1", Hostname: "V2253", OS: "android", Online: true},
		{Identity: "tailscale:n2", Hostname: "V2253", OS: "android", Online: false},
	} {
		if h := EvaluateHealth(n); h != nil {
			t.Fatalf("discovered peer must have no health: %+v", h)
		}
	}
}

func TestHealthTelemetryNeverDegrades(t *testing.T) {
	base := healthyNode()
	cases := []struct {
		name   string
		mutate func(*Node)
	}{
		{"high cpu load", func(n *Node) {
			n.System = &SystemInfo{CPUCount: 96, Load: LoadAvg{One: 8.5, Five: 8.0, Fifteen: 7.5}}
		}},
		{"high memory", func(n *Node) {
			n.System = &SystemInfo{Memory: Memory{Total: 32 << 30, Available: 1 << 30, Used: 31 << 30}}
		}},
		{"high disk", func(n *Node) {
			n.Storage = &StorageInfo{Filesystems: []Filesystem{
				{Mount: "/", Total: 100 << 30, Used: 95 << 30, Available: 5 << 30, UsagePercent: 95},
			}}
		}},
		{"high traffic", func(n *Node) {
			n.NetworkStats = &NetworkStatsInfo{Interfaces: []InterfaceStats{
				{Name: "eth0", RXBytes: 1 << 40, TXBytes: 1 << 40},
			}}
		}},
		{"net errors/drops", func(n *Node) {
			n.NetworkStats = &NetworkStatsInfo{Interfaces: []InterfaceStats{
				{Name: "eth0", RXErrors: 500, RXDropped: 300, TXErrors: 200, TXDropped: 100},
			}}
		}},
		{"all at once", func(n *Node) {
			n.System = &SystemInfo{
				CPUCount: 96, Load: LoadAvg{One: 64},
				Memory: Memory{Total: 32 << 30, Available: 1 << 27, Used: 32<<30 - 1<<27},
			}
			n.Storage = &StorageInfo{Filesystems: []Filesystem{
				{Mount: "/", Total: 100 << 30, Used: 99 << 30, Available: 1 << 30, UsagePercent: 99},
			}}
			n.NetworkStats = &NetworkStatsInfo{Interfaces: []InterfaceStats{
				{Name: "eth0", RXBytes: 1 << 42, RXErrors: 9999, RXDropped: 9999},
			}}
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			n := base
			tc.mutate(&n)
			if h := EvaluateHealth(n); h == nil || h.Status != HealthHealthy {
				t.Fatalf("telemetry must not degrade health: %+v", h)
			}
		})
	}
}

func TestHealthConfigRejectsHealthFields(t *testing.T) {
	for _, body := range []string{
		`{"health": {"status": "healthy"}}`,
		`{"status": "healthy"}`,
		`{"online": true}`,
		`{"healthy": true}`,
		`{"degraded": true}`,
		`{"offline": false}`,
	} {
		if _, err := LoadConfig(writeTemp(t, body)); err == nil {
			t.Fatalf("config accepted health/runtime field: %s", body)
		}
	}
}

func TestHealthServiceTransitionChangesHealth(t *testing.T) {
	n := healthyNode()
	if h := EvaluateHealth(n); h.Status != HealthHealthy {
		t.Fatalf("initial: %+v", h)
	}
	n.Services[1].Status = ServiceStopped
	if h := EvaluateHealth(n); h.Status != HealthDegraded {
		t.Fatalf("stopped: %+v", h)
	}
	n.Services[1].Status = ServiceRunning
	if h := EvaluateHealth(n); h.Status != HealthHealthy {
		t.Fatalf("recovered: %+v", h)
	}
}
