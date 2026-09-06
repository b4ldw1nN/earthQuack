//go:build linux

package node

import (
	"os"
	"runtime"
)

// procSysInfo collects system info from Linux /proc interfaces plus
// runtime.NumCPU for the logical CPU count. Degrades gracefully: if a
// source is unreadable, that field is simply left zero.
type procSysInfo struct {
	read func(string) ([]byte, error) // injectable for tests; nil => os.ReadFile
}

const (
	meminfoPath   = "/proc/meminfo"
	uptimePath    = "/proc/uptime"
	loadavgPath   = "/proc/loadavg"
	osReleasePath = "/etc/os-release"
)

// newSysInfoProvider returns the default (Linux proc-backed) collector.
func newSysInfoProvider() SysInfoProvider {
	return &procSysInfo{read: nil}
}

func (p *procSysInfo) Collect() SystemInfo {
	read := p.read
	if read == nil {
		read = os.ReadFile
	}
	// Arch is a pure compile/runtime constant — no /proc, no syscall.
	si := SystemInfo{CPUCount: runtime.NumCPU(), Arch: runtime.GOARCH}

	if b, err := read(meminfoPath); err == nil {
		si.Memory = parseProcMeminfo(string(b))
	}
	if b, err := read(uptimePath); err == nil {
		si.Uptime = parseProcUptime(string(b))
	}
	if b, err := read(loadavgPath); err == nil {
		si.Load = parseProcLoadavg(string(b))
	}
	// Distro/product name; fall back to the coarse runtime GOOS if the
	// release file is unreadable or lacks a usable value.
	if b, err := read(osReleasePath); err == nil {
		si.OS = parseOsRelease(string(b))
	}
	if si.OS == "" {
		si.OS = runtime.GOOS
	}
	return si
}
