package node

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestParseProcMeminfo(t *testing.T) {
	data := "MemTotal:       32674144 kB\nMemFree:        12850700 kB\nMemAvailable:   19473960 kB\nBuffers:            ...\n"
	m := parseProcMeminfo(data)
	if m.Total != 32674144*1024 {
		t.Errorf("Total = %d, want %d", m.Total, 32674144*1024)
	}
	if m.Available != 19473960*1024 {
		t.Errorf("Available = %d", m.Available)
	}
	if m.Used != (32674144-19473960)*1024 {
		t.Errorf("Used = %d, want %d", m.Used, (32674144-19473960)*1024)
	}
}

func TestParseProcMeminfoMalformed(t *testing.T) {
	// Missing keys, non-numeric values, empty all degrade to zero.
	for _, in := range []string{"", "garbage\n", "MemTotal: notanumber\n", "MemAvailable: 100 kB\n"} {
		m := parseProcMeminfo(in)
		if m.Total == 0 && m.Available == 0 && m.Used == 0 {
			continue // expected safe degradation
		}
		// MemAvailable-only input must not fabricate total/used.
		if strings.Contains(in, "MemAvailable: 100") && m.Used != 0 {
			t.Errorf("fabricated Used from available only: %+v", m)
		}
	}
}

func TestParseProcUptime(t *testing.T) {
	if got := parseProcUptime("12345.67 89012.34\n"); got != 12345.67 {
		t.Errorf("uptime = %v", got)
	}
	if got := parseProcUptime(""); got != 0 {
		t.Errorf("empty uptime = %v", got)
	}
	if got := parseProcUptime("notanumber"); got != 0 {
		t.Errorf("malformed uptime = %v", got)
	}
}

func TestParseProcLoadavg(t *testing.T) {
	l := parseProcLoadavg("1.42 1.18 0.91 3/120 4567\n")
	if l.One != 1.42 || l.Five != 1.18 || l.Fifteen != 0.91 {
		t.Errorf("load = %+v", l)
	}
	if got := parseProcLoadavg(""); got != (LoadAvg{}) {
		t.Errorf("empty load = %+v", got)
	}
}

func TestProcSysInfoDegradesOnUnreadable(t *testing.T) {
	// If every /proc read fails, the /proc-derived fields degrade to
	// zero without crashing. CPU count (runtime.NumCPU) still reports.
	p := &procSysInfo{read: func(string) ([]byte, error) {
		return nil, errors.New("ENOENT")
	}}
	si := p.Collect()
	if si.Memory != (Memory{}) || si.Uptime != 0 || si.Load != (LoadAvg{}) {
		t.Errorf("expected /proc fields to degrade to zero, got %+v", si)
	}
	if si.CPUCount == 0 {
		t.Error("cpu_count should still be reported from runtime")
	}
}

func TestProcSysInfoReads(t *testing.T) {
	fixtures := map[string]string{
		meminfoPath: "MemTotal:       1000000 kB\nMemAvailable:   400000 kB\n",
		uptimePath:  "1000.5 2000.5\n",
		loadavgPath: "0.50 0.40 0.30 1/1 1\n",
	}
	p := &procSysInfo{read: func(path string) ([]byte, error) {
		v, ok := fixtures[path]
		if !ok {
			return nil, errors.New("ENOENT")
		}
		return []byte(v), nil
	}}
	si := p.Collect()
	if si.CPUCount == 0 {
		t.Error("cpu_count should be non-zero")
	}
	if si.Memory.Total != 1000000*1024 || si.Uptime != 1000.5 {
		t.Errorf("system info not parsed from fixture reads: %+v", si)
	}
	if !strings.Contains(si.MemoryHuman(), "/") {
		t.Errorf("MemoryHuman missing: %q", si.MemoryHuman())
	}
	if !strings.Contains(si.UptimeHuman(), "m") {
		t.Errorf("UptimeHuman missing: %q", si.UptimeHuman())
	}
}

// fakeSysProvider is a deterministic SysInfoProvider for tests.
type fakeSysProvider struct{ info SystemInfo }

func (f fakeSysProvider) Collect() SystemInfo { return f.info }

func testSystem() SystemInfo {
	return SystemInfo{
		CPUCount: 16,
		Memory:   Memory{Total: 32 << 30, Available: 24 << 30, Used: 8 << 30},
		Uptime:   (3*24 + 12) * 3600,
		Load:     LoadAvg{One: 1.42, Five: 1.18, Fifteen: 0.91},
	}
}

func TestLocalNodeSystemInfo(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetSysInfoProvider(fakeSysProvider{info: testSystem()})
	n := reg.Local()
	if n.System == nil {
		t.Fatal("local node missing system snapshot")
	}
	if n.System.CPUCount != 16 || n.System.Memory.Total != 32<<30 {
		t.Fatalf("system not attached to local node: %+v", n.System)
	}
}

func TestAPINodeHasSystem(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetSysInfoProvider(fakeSysProvider{info: testSystem()})
	h, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/node")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var n Node
	if err := json.NewDecoder(resp.Body).Decode(&n); err != nil {
		t.Fatal(err)
	}
	if n.System == nil || n.System.CPUCount != 16 || n.System.Uptime != (3*24+12)*3600 {
		t.Fatalf("system missing from /api/node: %+v", n.System)
	}
}

func TestDiscoveredPeerHasNoSystem(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetSysInfoProvider(fakeSysProvider{info: testSystem()})
	for _, n := range reg.Nodes() {
		if !n.Registered && n.System != nil {
			t.Errorf("discovered/unregistered peer fabricated system info: %+v", n)
		}
	}
}

func TestPeerProbePreservesSystem(t *testing.T) {
	srv, _ := fakeNodeServer(t, validNodeJSONWithSystem("machine:peer1", testSystem()),
		"application/json", http.StatusOK)
	reg, _ := newProbingRegistry(t, "127.0.0.1", portOf(t, srv.URL))
	n := findNode(reg.Nodes(), "machine:peer1")
	if n.Identity != "machine:peer1" {
		t.Fatalf("peer not registered: %+v", reg.Nodes())
	}
	if n.System == nil || n.System.CPUCount != 16 {
		t.Fatalf("peer system info not preserved through probe: %+v", n.System)
	}
	// System must come from the peer's own response, not inference.
	if n.System.Memory.Total != 32<<30 {
		t.Errorf("peer system data wrong: %+v", n.System)
	}
}

// validNodeJSONWithSystem builds a valid /api/node response carrying a
// system snapshot, as the opposite side of a peer probe would.
func validNodeJSONWithSystem(identity string, sys SystemInfo) string {
	b, _ := json.Marshal(Node{
		Identity:     Identity(identity),
		Hostname:     "homeserver",
		OS:           "linux",
		Online:       true,
		Registered:   true,
		Capabilities: []string{"clipboard"},
		System:       &sys,
	})
	return string(b)
}
