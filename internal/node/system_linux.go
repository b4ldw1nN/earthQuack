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
	meminfoPath = "/proc/meminfo"
	uptimePath  = "/proc/uptime"
	loadavgPath = "/proc/loadavg"
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
	si := SystemInfo{CPUCount: runtime.NumCPU()}

	if b, err := read(meminfoPath); err == nil {
		si.Memory = parseProcMeminfo(string(b))
	}
	if b, err := read(uptimePath); err == nil {
		si.Uptime = parseProcUptime(string(b))
	}
	if b, err := read(loadavgPath); err == nil {
		si.Load = parseProcLoadavg(string(b))
	}
	return si
}
