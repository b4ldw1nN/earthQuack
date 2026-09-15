package node

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestDashboardHistorySection(t *testing.T) {
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.SetStorageInfoProvider(fakeStorageProvider{})
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{})
	reg.RegisterCapability("clipboard")
	reg.RegisterService(Service{Name: "clipboard", Status: ServiceRunning})
	fifty := 12.5
	reg.History().AddSample(MetricSample{Time: time.Now(), MemUsed: 8 << 30, MemTotal: 32 << 30, NetRX: 1000, NetTX: 2000, CPUPercent: &fifty})
	reg.History().AddEvent(HistoryEvent{Time: time.Now(), Kind: "health", From: "healthy", To: "degraded"})

	h, err := NewNodesHandler(reg)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/nodes")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	html := string(body)
	if !strings.Contains(html, "<h3>History</h3>") {
		t.Fatal("history section missing")
	}
	if !strings.Contains(html, "CPU %") || !strings.Contains(html, "Memory %") {
		t.Fatal("history rows missing")
	}
	if !strings.Contains(html, "healthy → degraded") {
		t.Fatal("event strip missing")
	}
}

func TestDashboardNoHistoryForPeers(t *testing.T) {
	reg, err := NewRegistry("machine:h", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.SetStorageInfoProvider(fakeStorageProvider{})
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{})
	reg.History().AddSample(MetricSample{Time: time.Now(), MemUsed: 1, MemTotal: 2})
	h, err := NewNodesHandler(reg)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/nodes")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	html := string(body)
	if got := strings.Count(html, "<h3>History</h3>"); got != 1 {
		t.Fatalf("History sections = %d, want exactly 1 (local only)", got)
	}
}

func TestDashboardRecentEventsRendering(t *testing.T) {
	reg, err := NewRegistry("machine:local", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.SetStorageInfoProvider(fakeStorageProvider{})
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{})

	reg.History().AddEvent(HistoryEvent{
		Time:    time.Date(2026, 9, 9, 15, 42, 0, 0, time.UTC),
		Node:    "machine:local",
		Kind:    "service",
		Name:    "clipboard",
		From:    "running",
		To:      "stopped",
		Message: "clipboard stopped",
	})
	reg.History().AddEvent(HistoryEvent{
		Time:    time.Date(2026, 9, 9, 15, 43, 0, 0, time.UTC),
		Node:    "machine:local",
		Kind:    "health",
		From:    "healthy",
		To:      "degraded",
		Message: "health degraded · 1 service stopped",
	})
	reg.History().AddEvent(HistoryEvent{
		Time:    time.Date(2026, 9, 9, 15, 44, 0, 0, time.UTC),
		Node:    "machine:homeserver",
		Kind:    "node",
		From:    "true",
		To:      "false",
		Message: "node offline",
	})

	h, err := NewOverviewHandler(reg)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	html := string(body)

	// Section presence
	if !strings.Contains(html, "<h3>Recent Events</h3>") {
		t.Fatal("recent events section missing")
	}

	// Content rendered
	for _, want := range []string{
		"clipboard stopped",
		"service.stopped",
		"health degraded · 1 service stopped",
		"health.degraded",
		"node offline",
		"node.offline",
		"machine:homeserver",
	} {
		if !strings.Contains(html, want) {
			t.Errorf("dashboard HTML missing %q", want)
		}
	}

	// Order: newest first (node.offline -> health.degraded -> service.stopped)
	idxNode := strings.Index(html, "node.offline")
	idxHealth := strings.Index(html, "health.degraded")
	idxService := strings.Index(html, "service.stopped")
	if !(idxNode < idxHealth && idxHealth < idxService) {
		t.Errorf("events not in newest-first order in dashboard: node=%d health=%d service=%d", idxNode, idxHealth, idxService)
	}

	// Must not leak sensitive details
	for _, forbidden := range []string{"nodekey:", "tailscale status", "PrivateKey"} {
		if strings.Contains(html, forbidden) {
			t.Errorf("dashboard leaked forbidden string: %q", forbidden)
		}
	}
}
