//go:build !linux

package node

// procSysInfo is a stub for platforms where /proc is unavailable
// (Windows/Android node support is deferred). Collect intentionally
// returns an empty snapshot; the Node layer degrades cleanly.
type procSysInfo struct{}

// newSysInfoProvider returns a provider that reports no system info.
func newSysInfoProvider() SysInfoProvider { return procSysInfo{} }

func (p procSysInfo) Collect() SystemInfo { return SystemInfo{} }
