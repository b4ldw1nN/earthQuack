package node

import (
	"fmt"
	"strconv"
	"strings"
)

// SystemInfo is a read-only snapshot of a node's current system
// resources, reported authoritatively by the node itself. Fields are
// zero/omitted when unavailable on the platform or in the snapshot.
// It is runtime information only — never configurable.
type SystemInfo struct {
	CPUCount int     `json:"cpu_count,omitempty"`
	Memory   Memory  `json:"memory,omitempty"`
	Uptime   float64 `json:"uptime,omitempty"` // seconds
	Load     LoadAvg `json:"load,omitempty"`
}

// Memory is a snapshot of system memory in bytes.
type Memory struct {
	Total     uint64 `json:"total"`
	Available uint64 `json:"available"`
	Used      uint64 `json:"used"`
}

// LoadAvg is the 1/5/15-minute load average.
type LoadAvg struct {
	One     float64 `json:"one"`
	Five    float64 `json:"five"`
	Fifteen float64 `json:"fifteen"`
}

// HasInfo reports whether the snapshot carries any usable data.
func (s SystemInfo) HasInfo() bool {
	return s.CPUCount > 0 || s.Uptime > 0 || s.Memory.Total > 0
}

// The following human-formatted accessors keep presentation formatting
// out of the template (which has no FuncMap). They are callable
// directly from html/template as {{.System.MemoryHuman}} etc.

func (s SystemInfo) CPUHuman() string {
	return fmt.Sprintf("%d", s.CPUCount)
}

func (s SystemInfo) MemoryHuman() string {
	if s.Memory.Total == 0 {
		return ""
	}
	return fmt.Sprintf("%.1f / %.1f GB",
		float64(s.Memory.Used)/float64(bytesPerGB),
		float64(s.Memory.Total)/float64(bytesPerGB))
}

func (s SystemInfo) UptimeHuman() string {
	sec := int64(s.Uptime)
	if sec <= 0 {
		return ""
	}
	days := sec / 86400
	hours := (sec % 86400) / 3600
	if days > 0 {
		return fmt.Sprintf("%dd %dh", days, hours)
	}
	minutes := sec / 60
	if minutes < 60 {
		return fmt.Sprintf("%dm", minutes)
	}
	return fmt.Sprintf("%dh %dm", hours, (sec%3600)/60)
}

func (s SystemInfo) LoadString() string {
	if s.Load.One == 0 && s.Load.Five == 0 && s.Load.Fifteen == 0 {
		return ""
	}
	return fmt.Sprintf("%.2f  %.2f  %.2f", s.Load.One, s.Load.Five, s.Load.Fifteen)
}

const bytesPerGB = 1024 * 1024 * 1024

// SysInfoProvider collects a cheap, read-only snapshot of the local
// system. It is platform-isolated (see system_linux.go / system_other.go)
// and never part of the Node abstraction's identity or config.
type SysInfoProvider interface {
	Collect() SystemInfo
}

// --- Pure /proc parsers (platform-neutral, deterministic) ---
// These let tests exercise parsing with fixed strings; the proc-backed
// collector (system_linux.go) feeds them real file contents.

// parseProcMeminfo parses /proc/meminfo. Values are in kB on Linux and
// are converted to bytes. Malformed/unavailable keys degrade to zero.
func parseProcMeminfo(data string) Memory {
	var total, avail uint64
	for _, line := range strings.Split(data, "\n") {
		f := strings.Fields(line)
		if len(f) < 2 {
			continue
		}
		key := strings.TrimSuffix(f[0], ":")
		v, err := strconv.ParseUint(f[1], 10, 64)
		if err != nil {
			continue
		}
		switch key {
		case "MemTotal":
			total = v
		case "MemAvailable":
			avail = v
		}
	}
	used := total
	if avail < total {
		used = total - avail
	}
	return Memory{
		Total:     total * 1024,
		Available: avail * 1024,
		Used:      used * 1024,
	}
}

// parseProcUptime parses /proc/uptime; returns the first field
// (seconds as float), or 0 on malformed input.
func parseProcUptime(data string) float64 {
	f := strings.Fields(data)
	if len(f) == 0 {
		return 0
	}
	v, err := strconv.ParseFloat(f[0], 64)
	if err != nil {
		return 0
	}
	return v
}

// parseProcLoadavg parses the 1/5/15-minute load averages from
// /proc/loadavg, ignoring the runnable/total fields that follow.
func parseProcLoadavg(data string) LoadAvg {
	f := strings.Fields(data)
	var out LoadAvg
	if len(f) >= 1 {
		out.One, _ = strconv.ParseFloat(f[0], 64)
	}
	if len(f) >= 2 {
		out.Five, _ = strconv.ParseFloat(f[1], 64)
	}
	if len(f) >= 3 {
		out.Fifteen, _ = strconv.ParseFloat(f[2], 64)
	}
	return out
}
