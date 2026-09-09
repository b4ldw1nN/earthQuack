package node

import (
	"testing"
	"time"
)

func TestSamplerRecordsMemorySample(t *testing.T) {
	reg, sys, net := newHistoryReg(t)
	sys.info = SystemInfo{Memory: Memory{Total: 32 << 30, Available: 24 << 30, Used: 8 << 30}}
	net.info = NetworkStatsInfo{} // no network
	s := NewTelemetrySampler(reg, 0)
	s.cpu = makeCPU(nil)
	s.Sample()
	samp := reg.History().Samples()
	if len(samp) != 1 {
		t.Fatalf("samples = %d, want 1", len(samp))
	}
	if samp[0].MemTotal != 32<<30 || samp[0].MemUsed != 8<<30 || samp[0].MemAvailable != 24<<30 {
		t.Fatalf("memory sample wrong: %+v", samp[0])
	}
	if samp[0].RXRate != nil || samp[0].TXRate != nil {
		t.Fatalf("rates must be nil without prior sample: %+v", samp[0])
	}
}

func TestSamplerNetworkDeltaAndReset(t *testing.T) {
	reg, _, net := newHistoryReg(t)
	s := NewTelemetrySampler(reg, 0)
	s.cpu = makeCPU(nil)
	now := reg.now()
	net.info = NetworkStatsInfo{Interfaces: []InterfaceStats{
		{Name: "eth0", RXBytes: 1000, TXBytes: 2000},
	}}
	s.Sample()
	s1 := reg.History().Samples()[0]
	if s1.RXRate != nil || s1.TXRate != nil {
		t.Fatalf("first sample must have nil rates: %+v", s1)
	}
	// Second sample 10s later, counters up: rates present.
	s.prevT = now.Add(-10 * time.Second)
	s.prevRX, s.prevTX = 1000, 2000
	net.info = NetworkStatsInfo{Interfaces: []InterfaceStats{
		{Name: "eth0", RXBytes: 1500, TXBytes: 2250},
	}}
	s.Sample()
	g := reg.History().Samples()
	last := g[len(g)-1]
	if last.RXRate == nil || last.TXRate == nil {
		t.Fatalf("second sample must have rates: %+v", last)
	}
	if *last.RXRate < 49 || *last.RXRate > 51 || *last.TXRate < 24 || *last.TXRate > 26 {
		t.Fatalf("rates wrong: rx=%v tx=%v", *last.RXRate, *last.TXRate)
	}
	// Counter reset (counters drop): rates nil, never negative.
	s.prevT = now
	s.prevRX, s.prevTX = 5000, 6000
	net.info = NetworkStatsInfo{Interfaces: []InterfaceStats{
		{Name: "eth0", RXBytes: 100, TXBytes: 100},
	}}
	s.Sample()
	l2 := reg.History().Samples()
	l2 = l2[len(l2)-1:]
	if l2[0].RXRate != nil || l2[0].TXRate != nil {
		t.Fatalf("reset must yield nil rates: %+v", l2[0])
	}
}

func TestSamplerCPUInSample(t *testing.T) {
	reg, sys, net := newHistoryReg(t)
	sys.info = SystemInfo{}
	net.info = NetworkStatsInfo{}
	s := NewTelemetrySampler(reg, 0)
	s.cpu = makeCPU([]float64{0, 33.3})
	s.Sample()
	if got := reg.History().Samples(); len(got) != 1 {
		t.Fatalf("cpu-only sample 1: %d", len(got))
	}
	s.Sample()
	got := reg.History().Samples()
	if len(got) != 2 {
		t.Fatalf("cpu-only sample 2: %d", len(got))
	}
	if got[1].CPUPercent == nil || *got[1].CPUPercent != 33.3 {
		t.Fatalf("cpu second sample wrong: %+v", got[1])
	}
}

func TestSamplerSkipsEmptySample(t *testing.T) {
	reg, _, _ := newHistoryReg(t)
	s := NewTelemetrySampler(reg, 0)
	s.cpu = cpuNone{}
	reg.SetSysInfoProvider(nil)
	reg.SetNetworkStatsProvider(nil)
	s.Sample()
	if got := reg.History().Samples(); len(got) != 0 {
		t.Fatalf("empty sample recorded: %+v", got)
	}
}

// cpuNone always reports no CPU reading (e.g. unreadable /proc/stat).
type cpuNone struct{}

func (cpuNone) Utilization() (float64, bool, time.Time) { return 0, false, time.Time{} }

func TestSamplerHealthAndServiceEvents(t *testing.T) {
	reg, _, _ := newHistoryReg(t)
	s := NewTelemetrySampler(reg, 0)
	s.cpu = makeCPU([]float64{0})
	reg.SetSysInfoProvider(nil)
	reg.SetNetworkStatsProvider(nil)

	reg.RegisterService(Service{Name: "clipboard", Status: ServiceRunning})
	reg.RegisterService(Service{Name: "file-transfer", Status: ServiceRunning})
	reg.RegisterCapability("clipboard")
	reg.RegisterCapability("file-transfer")
	s.Sample() // baseline
	if got := reg.History().Events(); len(got) != 0 {
		t.Fatalf("baseline must not emit events: %+v", got)
	}
	reg.SetLocalServiceStatus("file-transfer", ServiceStopped, "127.0.0.1:8876")
	s.Sample()
	events := reg.History().Events()
	found := false
	for _, e := range events {
		if e.Kind == "service" && e.Name == "file-transfer" && e.From == "running" && e.To == "stopped" {
			found = true
		}
	}
	if !found {
		t.Fatalf("missing service transition event: %+v", events)
	}

	// Repeated sample while stopped -> no duplicate event
	s.Sample()
	if len(reg.History().Events()) != len(events) {
		t.Fatalf("repeated stopped state emitted duplicate event")
	}

	// Service recovered -> service.recovered / running
	reg.SetLocalServiceStatus("file-transfer", ServiceRunning, "127.0.0.1:8876")
	s.Sample()
	rec := reg.History().RecentEvents(10)
	foundRecovered := false
	for _, e := range rec {
		if e.Type == EvServiceRecovered && e.Name == "file-transfer" {
			foundRecovered = true
			break
		}
	}
	if !foundRecovered {
		t.Fatalf("expected service.recovered event, got: %+v", rec)
	}

	// Repeated sample while running -> no duplicate event
	count := len(reg.History().Events())
	s.Sample()
	if len(reg.History().Events()) != count {
		t.Fatalf("repeated running state emitted duplicate event")
	}
}

func TestSamplerHealthTransitions(t *testing.T) {
	reg, _, _ := newHistoryReg(t)
	s := NewTelemetrySampler(reg, 0)
	s.cpu = makeCPU([]float64{0})
	reg.SetSysInfoProvider(nil)
	reg.SetNetworkStatsProvider(nil)

	reg.RegisterService(Service{Name: "clipboard", Status: ServiceRunning})
	reg.RegisterCapability("clipboard")
	s.Sample() // baseline: healthy
	if got := reg.History().Events(); len(got) != 0 {
		t.Fatalf("baseline must not emit events: %+v", got)
	}

	// Degrade health by stopping registered capability service
	reg.SetLocalServiceStatus("clipboard", ServiceStopped, "127.0.0.1:8875")
	s.Sample()
	events := reg.History().Events()
	var hlthEv *HistoryEvent
	for i := range events {
		if events[i].Kind == "health" {
			hlthEv = &events[i]
			break
		}
	}
	if hlthEv == nil || hlthEv.From != "healthy" || hlthEv.To != "degraded" {
		t.Fatalf("expected health healthy -> degraded event, got: %+v", events)
	}

	// Repeated sample with degraded health -> no duplicate health event
	prevLen := len(reg.History().Events())
	s.Sample()
	if len(reg.History().Events()) != prevLen {
		t.Fatalf("repeated degraded health emitted duplicate event")
	}

	// Recover health
	reg.SetLocalServiceStatus("clipboard", ServiceRunning, "127.0.0.1:8875")
	s.Sample()
	rec := reg.History().RecentEvents(10)
	foundRecovered := false
	for _, e := range rec {
		if e.Type == EvHealthRecovered {
			foundRecovered = true
			break
		}
	}
	if !foundRecovered {
		t.Fatalf("expected EvHealthRecovered, got: %+v", rec)
	}
}

func TestSamplerPeerLifecycle(t *testing.T) {
	reg, _, _ := newHistoryReg(t)
	s := NewTelemetrySampler(reg, 0)
	s.cpu = makeCPU([]float64{0})
	reg.SetSysInfoProvider(nil)
	reg.SetNetworkStatsProvider(nil)

	// Add registered peer, unregistered peer, and local node
	reg.mu.Lock()
	reg.peers["machine:peer1"] = peerEntry{
		node: Node{
			Identity:   "machine:peer1",
			Hostname:   "homeserver",
			Online:     true,
			Registered: true,
		},
		lastSeen: time.Now(),
	}
	reg.peers["tailscale:disco"] = peerEntry{
		node: Node{
			Identity:   "tailscale:disco",
			Hostname:   "unregistered-phone",
			Online:     true,
			Registered: false,
		},
		lastSeen: time.Now(),
	}
	reg.mu.Unlock()

	// 1. First observation = baseline, no events
	s.Sample()
	if len(reg.History().Events()) != 0 {
		t.Fatalf("baseline emitted events: %+v", reg.History().Events())
	}

	// 2. Unregistered peer goes offline -> no event (Registered=false peers skipped)
	reg.mu.Lock()
	reg.peers["tailscale:disco"] = peerEntry{
		node: Node{
			Identity:   "tailscale:disco",
			Hostname:   "unregistered-phone",
			Online:     false,
			Registered: false,
		},
		lastSeen: time.Now(),
	}
	reg.mu.Unlock()
	s.Sample()
	if len(reg.History().Events()) != 0 {
		t.Fatalf("unregistered peer offline emitted event: %+v", reg.History().Events())
	}

	// 3. Registered peer goes offline -> node.offline event emitted with authoritative Identity
	reg.mu.Lock()
	reg.peers["machine:peer1"] = peerEntry{
		node: Node{
			Identity:   "machine:peer1",
			Hostname:   "homeserver",
			Online:     false,
			Registered: true,
		},
		lastSeen: time.Now(),
	}
	reg.mu.Unlock()
	s.Sample()
	rec := reg.History().RecentEvents(10)
	if len(rec) != 1 {
		t.Fatalf("expected 1 event, got %d: %+v", len(rec), rec)
	}
	if rec[0].Type != EvNodeOffline || rec[0].Node != "machine:peer1" {
		t.Fatalf("unexpected event: %+v", rec[0])
	}

	// 4. Repeated offline sample -> no duplicate
	s.Sample()
	if len(reg.History().Events()) != 1 {
		t.Fatalf("repeated offline state emitted duplicate event")
	}

	// 5. Registered peer recovers (goes online) -> node.online event emitted
	reg.mu.Lock()
	reg.peers["machine:peer1"] = peerEntry{
		node: Node{
			Identity:   "machine:peer1",
			Hostname:   "homeserver",
			Online:     true,
			Registered: true,
		},
		lastSeen: time.Now(),
	}
	reg.mu.Unlock()
	s.Sample()
	rec = reg.History().RecentEvents(10)
	if len(rec) != 2 {
		t.Fatalf("expected 2 events, got %d: %+v", len(rec), rec)
	}
	if rec[0].Type != EvNodeOnline || rec[0].Node != "machine:peer1" {
		t.Fatalf("unexpected event: %+v", rec[0])
	}

	// 6. Repeated online sample -> no duplicate
	s.Sample()
	if len(reg.History().Events()) != 2 {
		t.Fatalf("repeated online state emitted duplicate event")
	}
}
