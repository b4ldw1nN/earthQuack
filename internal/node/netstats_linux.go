//go:build linux

package node

import (
	"net"
	"os"
	"sort"
)

// procNetworkStats collects network-interface statistics on Linux
// without shelling out: interface enumeration (names, MTU, flags,
// hardware addresses, IP addresses) comes from the standard library's
// net.Interfaces/Addrs, and traffic counters come from
// /proc/net/dev, the kernel's own per-device table.
//
// Never fabricates data: an unreadable interface table or counter
// table yields an empty snapshot; interfaces that are down are
// skipped (they carry no traffic); an up interface with no counter
// row is skipped rather than reported with invented zeros.
//
// The readers are injectable so tests exercise collection
// deterministically, without the real machine's interfaces.
type procNetworkStats struct {
	read  func(string) ([]byte, error)            // nil => os.ReadFile
	list  func() ([]net.Interface, error)         // nil => net.Interfaces
	addrs func(net.Interface) ([]net.Addr, error) // nil => iface.Addrs
}

const procNetDevPath = "/proc/net/dev"

// newNetworkStatsProvider returns the default (Linux net+proc-backed)
// collector.
func newNetworkStatsProvider() NetworkStatsProvider {
	return &procNetworkStats{read: nil, list: nil, addrs: nil}
}

// Collect enumerates up interfaces, merges their stdlib-reported
// state with /proc/net/dev counters, and reports them in ascending
// name order, which keeps dashboards stable.
func (p *procNetworkStats) Collect() NetworkStatsInfo {
	read := p.read
	if read == nil {
		read = os.ReadFile
	}
	list := p.list
	if list == nil {
		list = net.Interfaces
	}
	addrs := p.addrs
	if addrs == nil {
		addrs = func(i net.Interface) ([]net.Addr, error) { return i.Addrs() }
	}

	b, err := read(procNetDevPath)
	if err != nil {
		return NetworkStatsInfo{} // no counter table => no fabricated data
	}
	counters := parseProcNetDev(string(b))

	ifaces, err := list()
	if err != nil {
		return NetworkStatsInfo{} // no interface table => nothing to report
	}

	var out []InterfaceStats
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 {
			continue // down interfaces carry no traffic
		}
		c, ok := counters[ifc.Name]
		if !ok {
			continue // up but no counter row: refuse to invent zeros
		}
		st := InterfaceStats{
			Name:      ifc.Name,
			MTU:       ifc.MTU,
			Loopback:  ifc.Flags&net.FlagLoopback != 0,
			RXBytes:   c.rxBytes,
			TXBytes:   c.txBytes,
			RXPackets: c.rxPackets,
			TXPackets: c.txPackets,
			RXErrors:  c.rxErrs,
			TXErrors:  c.txErrs,
			RXDropped: c.rxDrop,
			TXDropped: c.txDrop,
		}
		if len(ifc.HardwareAddr) > 0 {
			st.HardwareAddress = ifc.HardwareAddr.String()
		}
		if as, err := addrs(ifc); err == nil {
			for _, a := range as {
				if s := a.String(); s != "" {
					st.Addresses = append(st.Addresses, s)
				}
			}
		}
		out = append(out, st)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return NetworkStatsInfo{Interfaces: out}
}
