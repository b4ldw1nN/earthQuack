package node

import (
	"fmt"
	"strconv"
	"strings"
)

// StorageInfo is a read-only snapshot of a node's mounted filesystems,
// reported authoritatively by the node itself. It is measured runtime
// state — like SystemInfo, never configurable and never fabricated for
// discovered peers. Fields are empty/omitted when nothing useful could
// be measured on the platform or in the snapshot.
//
// Storage is node telemetry, NOT a capability: having disks does not
// mean a node declares a "storage" capability. Capabilities remain
// explicit declarations (see config.go).
type StorageInfo struct {
	Filesystems []Filesystem `json:"filesystems,omitempty"`
}

// Filesystem is one mounted filesystem's capacity snapshot. Sizes are
// bytes as reported by the platform's stat interface (statfs on
// Linux); UsagePercent is 0-100 computed from Used/(Used+Available),
// i.e. relative to the space actually usable, mirroring df.
type Filesystem struct {
	Mount        string  `json:"mount"`                // mount point, e.g. "/", "/mnt/Storage"
	Filesystem   string  `json:"filesystem,omitempty"` // fs type, e.g. "ext4", "vfat"
	Total        uint64  `json:"total"`                // bytes
	Used         uint64  `json:"used"`                 // bytes
	Available    uint64  `json:"available"`            // bytes free to non-root
	UsagePercent float64 `json:"usage_percent"`
}

// HasInfo reports whether the snapshot carries any usable data.
func (s StorageInfo) HasInfo() bool {
	return len(s.Filesystems) > 0
}

// The following human-formatted accessors keep presentation formatting
// out of the template (which has no FuncMap), mirroring SystemInfo.
// They are callable directly from html/template.

// UsageHuman renders "used / total" in one decimal GB, e.g.
// "742.3 / 1000.0 GB". Returns "" when there is no total to show.
func (f Filesystem) UsageHuman() string {
	if f.Total == 0 {
		return ""
	}
	return fmt.Sprintf("%.1f / %.1f GB",
		float64(f.Used)/float64(bytesPerGB),
		float64(f.Total)/float64(bytesPerGB))
}

// UsagePercentHuman renders the usage percentage as "74%", or "" when
// there is no meaningful percentage. Derived from Total/Available so
// display stays consistent even for snapshots without the field set.
func (f Filesystem) UsagePercentHuman() string {
	if f.Total == 0 {
		return ""
	}
	return fmt.Sprintf("%.0f%%", fileUsagePercent(f.Total, f.Available))
}

// UsageBar renders a 20-character ASCII usage bar, e.g.
// "███████████████░░░░░". The filled portion is UsagePercent of the
// bar; it clamps to [0,20]. Returns "" for filesystems without a
// measurable total.
func (f Filesystem) UsageBar() string {
	if f.Total == 0 {
		return ""
	}
	const width = 20
	filled := int(fileUsagePercent(f.Total, f.Available) / 100 * width)
	if filled < 0 {
		filled = 0
	}
	if filled > width {
		filled = width
	}
	return strings.Repeat("█", filled) + strings.Repeat("░", width-filled)
}

// StorageProvider collects a cheap, read-only snapshot of the local
// mounted filesystems. It is platform-isolated (see storage_linux.go /
// storage_other.go) and never part of the Node abstraction's identity
// or config. Like SysInfoProvider, implementations must not fail:
// unreadable sources simply yield an empty snapshot.
type StorageProvider interface {
	Collect() StorageInfo
}

// --- Pure /proc/self/mounts parsing (platform-neutral, deterministic)
// --- These let tests exercise parsing with fixed strings; the
// Linux-backed collector (storage_linux.go) feeds them real contents.

// mountEntry is one parsed line of /proc/self/mounts.
type mountEntry struct {
	Device     string
	MountPoint string
	Type       string
}

// parseMounts parses fstab-style /proc/self/mounts content. Octal
// escapes (\040 space, \011 tab, \012 newline, \134 backslash) are
// decoded as the kernel encodes them. Malformed lines (fewer than
// three fields) are skipped rather than failing the whole parse.
func parseMounts(data string) []mountEntry {
	var out []mountEntry
	for _, line := range strings.Split(data, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		f := strings.Fields(line)
		if len(f) < 3 {
			continue
		}
		out = append(out, mountEntry{
			Device:     unescapeMountField(f[0]),
			MountPoint: unescapeMountField(f[1]),
			Type:       f[2],
		})
	}
	return out
}

// unescapeMountField decodes the kernel's octal encoding of whitespace
// and backslashes in mount fields ("\040" → space etc.). Unknown
// escapes are left verbatim. Pure function — deterministic.
func unescapeMountField(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); {
		if s[i] == '\\' && i+3 < len(s) {
			if v, err := strconv.ParseUint(s[i+1:i+4], 8, 8); err == nil {
				b.WriteByte(byte(v))
				i += 4
				continue
			}
		}
		b.WriteByte(s[i])
		i++
	}
	return b.String()
}

// fileUsagePercent is the pure math behind Filesystem.UsagePercent:
// used share of total (used = total - available), mirroring df.
// Zero/underflow inputs yield 0 — never NaN, never negative. Kept as
// a pure function so parser/collection tests can exercise it directly
// with fixed numbers; the collector stores its result in the
// UsagePercent field at collection time.
func fileUsagePercent(total, available uint64) float64 {
	if total == 0 || available >= total {
		return 0
	}
	return float64(total-available) / float64(total) * 100
}

// pseudoFilesystemTypes lists kernel/auxiliary filesystem types that
// carry no persistent storage and would only clutter an infrastructure
// dashboard. Filtering is by TYPE, never by mount path — the provider
// must not hardcode mount points. Real filesystems on unusual paths
// (/data, /media, /backup, ...) are always reported.
var pseudoFilesystemTypes = map[string]bool{
	"proc":        true,
	"procfs":      true,
	"sysfs":       true,
	"devtmpfs":    true,
	"tmpfs":       true,
	"devpts":      true,
	"cgroup":      true,
	"cgroup2":     true,
	"securityfs":  true,
	"pstore":      true,
	"efivarfs":    true,
	"bpf":         true,
	"tracefs":     true,
	"debugfs":     true,
	"configfs":    true,
	"fusectl":     true,
	"hugetlbfs":   true,
	"mqueue":      true,
	"binfmt_misc": true,
	"autofs":      true,
	"ramfs":       true,
	"overlay":     true, // container layers, not host storage
	"squashfs":    true, // read-only image (live ISOs, snap packages)
	"nsfs":        true,
	"rpc_pipefs":  true,
	"fuseFD":      true,
}

// isPseudoFilesystem reports whether the given filesystem type is a
// kernel/pseudo filesystem rather than persistent storage. Unknown
// types are NOT filtered: an exotic but real filesystem on a future
// homeserver must still show up.
func isPseudoFilesystem(fsType string) bool {
	return pseudoFilesystemTypes[fsType]
}
