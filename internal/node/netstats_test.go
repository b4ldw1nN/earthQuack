package node

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/b4ldw1nN/earthquack/web"
)

// fakeNetworkStatsProvider is a deterministic NetworkStatsProvider
// for tests.
type fakeNetworkStatsProvider struct{ info NetworkStatsInfo }

func (f fakeNetworkStatsProvider) Collect() NetworkStatsInfo { return f.info }

// testNetworkStats returns a deterministic three-interface snapshot
// (eth0 with traffic, loopback, wg0 with error counters).
func testNetworkStats() NetworkStatsInfo {
	return NetworkStatsInfo{Interfaces: []InterfaceStats{
		{Name: "eth0", MTU: 1500, HardwareAddress: "aa:bb:cc:dd:ee:ff",
			Addresses: []string{"192.168.1.10/24"},
			RXBytes:   5 * bytesPerGB, TXBytes: 2 * bytesPerGB,
			RXPackets: 3_200_000, TXPackets: 4_100_000},
		{Name: "lo", Loopback: true,
			RXBytes: 1024, TXBytes: 2048, RXPackets: 9876, TXPackets: 9876},
		{Name: "wg0", RXErrors: 2, RXDropped: 1},
	}}
}

// testProcNetDev mirrors a real /proc/net/dev table: two header
// lines, four complete interfaces, one short (truncated) line, one
// line without a colon. eth1 has counters but is down in the
// collector tests; wlan0 is up but has no usable counter row.
const testProcNetDev = `Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 1234567    9876    0    0    0     0          0         0  1234567    9876    0    0    0     0       0          0
  eth0: 5368709120 3200000    2    7    0     0          0         0 2147483648 4100000    0    0    0     0       0          0
  eth1: 999999    1000    0    0    0     0          0         0   888888    900    0    0    0     0       0          0
tailscale0: 1048576     1024    0    0    0     0          0         0  2097152     2048    0    0    0     0       0          0
wlan0: 1000 10 0 0
badline-without-colon
`

// --- pure parsing ---

func TestParseProcNetDev(t *testing.T) {
	got := parseProcNetDev(testProcNetDev)
	if len(got) != 4 {
		t.Fatalf("want 4 interfaces, got %d: %+v", len(got), got)
	}
	eth0 := got["eth0"]
	if eth0.rxBytes != 5368709120 || eth0.txBytes != 2147483648 {
		t.Errorf("eth0 byte counters wrong: %+v", eth0)
	}
	if eth0.rxErrs != 2 || eth0.rxDrop != 7 || eth0.txErrs != 0 || eth0.txDrop != 0 {
		t.Errorf("eth0 error counters wrong: %+v", eth0)
	}
	if lo := got["lo"]; lo.rxPackets != 9876 || lo.txPackets != 9876 {
		t.Errorf("lo packet counters wrong: %+v", lo)
	}
	if ts := got["tailscale0"]; ts.txPackets != 2048 {
		t.Errorf("tailscale0 counters wrong: %+v", ts)
	}
	if _, ok := got["wlan0"]; ok {
		t.Errorf("short counter line must be skipped")
	}
	if _, ok := got["badline-without-colon"]; ok {
		t.Errorf("line without colon must be skipped")
	}
	if got := parseProcNetDev(""); len(got) != 0 {
		t.Errorf("empty input: %+v", got)
	}
}

// --- human formatting ---

func TestBytesHuman(t *testing.T) {
	cases := map[uint64]string{
		0:                             "0 B",
		512:                           "512 B",
		1024:                          "1.0 KB",
		1536:                          "1.5 KB",
		5 * 1024 * 1024:               "5.0 MB",
		3 * 1024 * 1024 * 1024:        "3.0 GB",
		2 * 1024 * 1024 * 1024 * 1024: "2.0 TB",
	}
	for in, want := range cases {
		if got := bytesHuman(in); got != want {
			t.Errorf("bytesHuman(%d) = %q, want %q", in, got, want)
		}
	}
}

func TestInterfaceStatsHuman(t *testing.T) {
	i := InterfaceStats{
		RXBytes:   5 * bytesPerGB,
		TXBytes:   2 * bytesPerGB,
		Addresses: []string{"192.168.1.10/24", "fd7a::1/64"},
	}
	if got := i.RXHuman(); got != "5.0 GB" {
		t.Errorf("RXHuman = %q", got)
	}
	if got := i.TXHuman(); got != "2.0 GB" {
		t.Errorf("TXHuman = %q", got)
	}
	if got := i.AddrHuman(); got != "192.168.1.10/24, fd7a::1/64" {
		t.Errorf("AddrHuman = %q", got)
	}
	if got := (InterfaceStats{}).AddrHuman(); got != "" {
		t.Errorf("empty AddrHuman = %q, want empty", got)
	}
	if got := (InterfaceStats{}).ErrorsHuman(); got != "" {
		t.Errorf("clean interface ErrorsHuman = %q, want empty", got)
	}
	i.RXErrors = 2
	i.TXDropped = 7
	if got := i.ErrorsHuman(); got != "2 rx err · 7 tx drop" {
		t.Errorf("ErrorsHuman = %q", got)
	}
}

// --- collection (injected reader + interface tables; no real NICs) ---

// prefixAddr parses a CIDR string and returns it as a net.Addr,
// failing the test if it is malformed. netip.ParsePrefix is used (not
// net.ParseCIDR, whose arity changed in Go 1.25) so the test compiles
// across toolchains.
func prefixAddr(t *testing.T, s string) net.Addr {
	t.Helper()
	if _, err := netip.ParsePrefix(s); err != nil {
		t.Fatalf("bad test CIDR %q: %v", s, err)
	}
	return testAddr(s)
}

// testAddr is a minimal net.Addr carrying a pre-formatted string, as
// returned by net.Interface.Addrs in practice.
type testAddr string

func (a testAddr) Network() string { return "" }
func (a testAddr) String() string  { return string(a) }

func TestProcNetworkStatsCollect(t *testing.T) {
	p := &procNetworkStats{
		read: func(string) ([]byte, error) { return []byte(testProcNetDev), nil },
		list: func() ([]net.Interface, error) {
			return []net.Interface{
				{Index: 1, MTU: 65536, Name: "lo", Flags: net.FlagUp | net.FlagLoopback},
				{Index: 2, MTU: 1500, Name: "eth0", Flags: net.FlagUp | net.FlagBroadcast,
					HardwareAddr: net.HardwareAddr{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff}},
				{Index: 3, MTU: 1500, Name: "eth1"},                     // down: skipped
				{Index: 4, MTU: 1280, Name: "wlan0", Flags: net.FlagUp}, // up, no counters: skipped
				{Index: 5, MTU: 1280, Name: "tailscale0", Flags: net.FlagUp},
			}, nil
		},
		addrs: func(i net.Interface) ([]net.Addr, error) {
			if i.Name == "eth0" {
				return []net.Addr{prefixAddr(t, "192.168.1.10/24"), prefixAddr(t, "fe80::1/64")}, nil
			}
			return nil, nil
		},
	}
	got := p.Collect()
	if !got.HasInfo() {
		t.Fatal("collection produced no info")
	}
	wantNames := []string{"eth0", "lo", "tailscale0"} // sorted; down + counterless skipped
	if len(got.Interfaces) != len(wantNames) {
		t.Fatalf("want %d interfaces, got %d: %+v", len(wantNames), len(got.Interfaces), got.Interfaces)
	}
	for i, want := range wantNames {
		if got.Interfaces[i].Name != want {
			t.Errorf("interfaces[%d].Name = %q, want %q (must be sorted)", i, got.Interfaces[i].Name, want)
		}
	}
	eth0 := got.Interfaces[0]
	if eth0.RXBytes != 5368709120 || eth0.TXBytes != 2147483648 {
		t.Errorf("eth0 counters not merged: %+v", eth0)
	}
	if eth0.MTU != 1500 || eth0.HardwareAddress != "aa:bb:cc:dd:ee:ff" {
		t.Errorf("eth0 stdlib state not merged: %+v", eth0)
	}
	if len(eth0.Addresses) != 2 || eth0.Addresses[0] != "192.168.1.10/24" {
		t.Errorf("eth0 addresses not merged: %+v", eth0.Addresses)
	}
	lo := got.Interfaces[1]
	if !lo.Loopback {
		t.Errorf("loopback flag lost: %+v", lo)
	}
	// Deterministic: a second collect is identical.
	if got2 := p.Collect(); fmt.Sprint(got2) != fmt.Sprint(got) {
		t.Errorf("collect is not deterministic:\n%v\n%v", got, got2)
	}
}

func TestProcNetworkStatsUnreadableSources(t *testing.T) {
	// No counter table: nothing may be fabricated.
	p := &procNetworkStats{
		read: func(string) ([]byte, error) { return nil, errors.New("ENOENT") },
		list: func() ([]net.Interface, error) {
			return []net.Interface{{Index: 1, MTU: 1500, Name: "eth0", Flags: net.FlagUp}}, nil
		},
	}
	if got := p.Collect(); got.HasInfo() {
		t.Errorf("unreadable /proc/net/dev must yield empty snapshot: %+v", got)
	}
	// No interface table: same rule.
	p2 := &procNetworkStats{
		read: func(string) ([]byte, error) { return []byte(testProcNetDev), nil },
		list: func() ([]net.Interface, error) { return nil, errors.New("EPERM") },
	}
	if got := p2.Collect(); got.HasInfo() {
		t.Errorf("unreadable interface table must yield empty snapshot: %+v", got)
	}
}

func TestNetworkStatsHasInfo(t *testing.T) {
	if (NetworkStatsInfo{}).HasInfo() {
		t.Error("empty snapshot claims info")
	}
	if !(NetworkStatsInfo{Interfaces: []InterfaceStats{{Name: "eth0"}}}).HasInfo() {
		t.Error("interface snapshot denies info")
	}
}

// --- Node / registry / API integration ---

func TestLocalNodeNetworkStats(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{info: testNetworkStats()})
	n := reg.Local()
	if n.NetworkStats == nil || !n.NetworkStats.HasInfo() {
		t.Fatal("local node missing network-stats snapshot")
	}
	if n.NetworkStats.Interfaces[0].Name != "eth0" {
		t.Fatalf("network stats not attached to local node: %+v", n.NetworkStats)
	}
}

func TestAPINodeHasNetworkStats(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{info: testNetworkStats()})
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
	if n.NetworkStats == nil || len(n.NetworkStats.Interfaces) != 3 {
		t.Fatalf("network stats missing from /api/node: %+v", n.NetworkStats)
	}
	eth0 := n.NetworkStats.Interfaces[0]
	if eth0.Name != "eth0" || eth0.RXBytes != 5*bytesPerGB || eth0.TXBytes != 2*bytesPerGB {
		t.Errorf("network values wrong through API: %+v", eth0)
	}
}

func TestDiscoveredPeerHasNoNetworkStats(t *testing.T) {
	reg, _ := newTestRegistry(t)
	reg.SetNetworkStatsProvider(fakeNetworkStatsProvider{info: testNetworkStats()})
	for _, n := range reg.Nodes() {
		if !n.Registered && n.NetworkStats != nil {
			t.Errorf("discovered/unregistered peer fabricated network stats: %+v", n)
		}
	}
	// And /api/nodes must likewise carry network stats only for the
	// local node.
	h, err := NewAPI(reg, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/nodes")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var payload struct {
		Nodes []Node
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatal(err)
	}
	for _, n := range payload.Nodes {
		if !n.Registered && n.NetworkStats != nil {
			t.Errorf("peer network stats leaked through /api/nodes: %+v", n)
		}
	}
}

// --- dashboard ---

func TestDashboardRendersNetworkStats(t *testing.T) {
	tmpl, err := web.DashboardTemplate()
	if err != nil {
		t.Fatalf("DashboardTemplate: %v", err)
	}
	view := dashboardView{
		Local: Node{
			Identity: "machine:a", Hostname: "archii", OS: "linux",
			Online: true, Registered: true,
			NetworkStats: &NetworkStatsInfo{Interfaces: []InterfaceStats{
				{Name: "eth0", Addresses: []string{"192.168.1.10/24"},
					RXBytes: 5 * bytesPerGB, TXBytes: 2 * bytesPerGB},
				{Name: "wg0", RXErrors: 2, RXDropped: 1},
			}},
		},
		Nodes: []Node{
			{Identity: "machine:a", Hostname: "archii", OS: "linux", Online: true, Registered: true,
				NetworkStats: &NetworkStatsInfo{Interfaces: []InterfaceStats{
					{Name: "eth0", Addresses: []string{"192.168.1.10/24"},
						RXBytes: 5 * bytesPerGB, TXBytes: 2 * bytesPerGB},
					{Name: "wg0", RXErrors: 2, RXDropped: 1},
				}}},
		},
		Now: time.Now(),
	}
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, view); err != nil {
		t.Fatalf("template execute: %v", err)
	}
	html := buf.String()
	if !strings.Contains(html, "<h3>Network</h3>") {
		t.Errorf("network section not rendered")
	}
	for _, want := range []string{">eth0<", "192.168.1.10/24", "rx 5.0 GB · tx 2.0 GB", "2 rx err · 1 rx drop"} {
		if !strings.Contains(html, want) {
			t.Errorf("network value %q not rendered", want)
		}
	}
	// The template must never leak raw JSON field names or counters.
	for _, forbidden := range []string{"rx_bytes", "tx_bytes", "hardware_address"} {
		if strings.Contains(html, forbidden) {
			t.Errorf("dashboard leaked raw telemetry field %q", forbidden)
		}
	}
}
