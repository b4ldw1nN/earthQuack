//go:build !linux

package node

// procNetworkStats is a stub for platforms where /proc/net/dev and
// the net_interfaces collector are unavailable (Windows/Android node
// support is deferred). Collect intentionally returns an empty
// snapshot; the Node layer degrades cleanly, exactly as for the
// system-info and storage stubs.
type procNetworkStats struct{}

// newNetworkStatsProvider returns a provider that reports no network
// stats.
func newNetworkStatsProvider() NetworkStatsProvider { return procNetworkStats{} }

func (p procNetworkStats) Collect() NetworkStatsInfo { return NetworkStatsInfo{} }
