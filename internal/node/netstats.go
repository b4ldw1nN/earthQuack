package node

import (
	"fmt"
	"strings"
)

// NetworkStatsInfo is a read-only snapshot of a node's network
// interfaces and their traffic counters, reported authoritatively by
// the node itself. Like SystemInfo and StorageInfo it is measured
// runtime state — never configurable and never fabricated for
// discovered peers.
//
// Network telemetry is deliberately separate from NetworkInfo:
//
//	NetworkInfo      = how earthQuack reaches/discovers the node
//	                   (transport + addresses, see node.go)
//	NetworkStatsInfo = what interfaces exist on the machine and
//	                   what traffic they are handling
//
// Tailscale discovery must never be mixed into interface telemetry,
// and interface telemetry never influences identity or capabilities.
type NetworkStatsInfo struct {
	Interfaces []InterfaceStats `json:"interfaces,omitempty"`
}

// InterfaceStats is one network interface's current state and
// lifetime traffic counters. Counters are the kernel's since-boot
// totals (the same source as `ip -s link` / ifconfig), not rates.
// Fields are omitted when the platform does not report them.
type InterfaceStats struct {
	Name            string   `json:"name"`
	MTU             int      `json:"mtu,omitempty"`
	Loopback        bool     `json:"loopback,omitempty"`
	HardwareAddress string   `json:"hardware_address,omitempty"`
	Addresses       []string `json:"addresses,omitempty"`
	RXBytes         uint64   `json:"rx_bytes"`
	TXBytes         uint64   `json:"tx_bytes"`
	RXPackets       uint64   `json:"rx_packets,omitempty"`
	TXPackets       uint64   `json:"tx_packets,omitempty"`
	RXErrors        uint64   `json:"rx_errors,omitempty"`
	TXErrors        uint64   `json:"tx_errors,omitempty"`
	RXDropped       uint64   `json:"rx_dropped,omitempty"`
	TXDropped       uint64   `json:"tx_dropped,omitempty"`
}

// HasInfo reports whether the snapshot carries any usable data.
func (n NetworkStatsInfo) HasInfo() bool {
	return len(n.Interfaces) > 0
}

// --- Human-formatted accessors (callable directly from html/template,
// mirroring SystemInfo/StorageInfo; the template has no FuncMap). ---

// RXHuman renders received bytes in adaptive binary units, e.g.
// "12.3 MB", "1.4 GB". Always non-empty ("0 B" when nothing counted).
func (i InterfaceStats) RXHuman() string { return bytesHuman(i.RXBytes) }

// TXHuman renders transmitted bytes like RXHuman.
func (i InterfaceStats) TXHuman() string { return bytesHuman(i.TXBytes) }

// AddrHuman renders the interface's current IP addresses as one
// comma-joined string, or "" when none are reported.
func (i InterfaceStats) AddrHuman() string {
	if len(i.Addresses) == 0 {
		return ""
	}
	return strings.Join(i.Addresses, ", ")
}

// ErrorsHuman summarizes nonzero error/drop counters as e.g.
// "2 rx err · 7 tx drop", or "" when the interface is clean. Kept out
// of the default view so quiet interfaces render one tight line.
func (i InterfaceStats) ErrorsHuman() string {
	var parts []string
	if i.RXErrors > 0 {
		parts = append(parts, fmt.Sprintf("%d rx err", i.RXErrors))
	}
	if i.RXDropped > 0 {
		parts = append(parts, fmt.Sprintf("%d rx drop", i.RXDropped))
	}
	if i.TXErrors > 0 {
		parts = append(parts, fmt.Sprintf("%d tx err", i.TXErrors))
	}
	if i.TXDropped > 0 {
		parts = append(parts, fmt.Sprintf("%d tx drop", i.TXDropped))
	}
	return strings.Join(parts, " · ")
}

// bytesHuman formats a byte count in adaptive binary units
// (B/KB/MB/GB/TB, where 1 GB = 1024³ — matching the codebase's
// existing bytesPerGB convention). Zero renders as "0 B", never "".
func bytesHuman(v uint64) string {
	const unit = 1024
	switch {
	case v < unit:
		return fmt.Sprintf("%d B", v)
	case v < unit*unit:
		return fmt.Sprintf("%.1f KB", float64(v)/unit)
	case v < unit*unit*unit:
		return fmt.Sprintf("%.1f MB", float64(v)/(unit*unit))
	case v < unit*unit*unit*unit:
		return fmt.Sprintf("%.1f GB", float64(v)/(unit*unit*unit))
	default:
		return fmt.Sprintf("%.1f TB", float64(v)/(unit*unit*unit*unit))
	}
}

// NetworkStatsProvider collects a cheap, read-only snapshot of the
// local network interfaces. It is platform-isolated (see
// netstats_linux.go / netstats_other.go) and, like the system and
// storage providers, never part of the Node abstraction's identity,
// config, or capabilities. Implementations must not fail: unreadable
// sources simply yield an empty snapshot.
type NetworkStatsProvider interface {
	Collect() NetworkStatsInfo
}

// --- Pure /proc/net/dev parsing (platform-neutral, deterministic).
// These let tests exercise parsing with fixed strings; the
// Linux-backed collector (netstats_linux.go) feeds them real contents.

// devCounters is the traffic-counter subset of one /proc/net/dev line
// (the same fields `ip -s link` shows).
type devCounters struct {
	rxBytes, rxPackets, rxErrs, rxDrop uint64
	txBytes, txPackets, txErrs, txDrop uint64
}

// parseProcNetDev parses /proc/net/dev content into per-interface
// counters keyed by interface name. The header lines (the
// "Inter-|   Receive" table face and the "face |bytes    packets"
// column names) contain no colon-separated field list and are
// skipped naturally. Malformed lines (no colon, or fewer than 12
// numeric fields after it) are skipped rather than failing the whole
// parse: partial counter data is still better than none.
func parseProcNetDev(data string) map[string]devCounters {
	out := map[string]devCounters{}
	for _, line := range strings.Split(data, "\n") {
		name, rest, ok := strings.Cut(line, ":")
		if !ok {
			continue // header or malformed line
		}
		f := strings.Fields(rest)
		if len(f) < 12 {
			continue // need through tx_dropped
		}
		var vals [12]uint64
		bad := false
		for i := 0; i < 12; i++ {
			if _, err := fmt.Sscanf(f[i], "%d", &vals[i]); err != nil {
				bad = true
				break
			}
		}
		if bad {
			continue
		}
		out[strings.TrimSpace(name)] = devCounters{
			rxBytes:   vals[0],
			rxPackets: vals[1],
			rxErrs:    vals[2],
			rxDrop:    vals[3],
			txBytes:   vals[8],
			txPackets: vals[9],
			txErrs:    vals[10],
			txDrop:    vals[11],
		}
	}
	return out
}
