//go:build linux

package node

import (
	"os"
	"sort"
	"syscall"
)

// procStorageInfo collects mounted-filesystem statistics from Linux
// interfaces without shelling out: mounts are enumerated from
// /proc/self/mounts (kernel-provided, fstab format) and capacities
// come from the statfs(2) syscall. Degrades gracefully: an unreadable
// mount table, a mount whose statfs fails, or a zero-capacity mount
// simply yields fewer (or no) entries — never fabricated data.
//
// Both the mount-table reader and the statfs func are injectable so
// tests exercise collection deterministically, without the real
// machine's disks.
type procStorageInfo struct {
	read  func(string) ([]byte, error)     // nil => os.ReadFile
	statf func(string) (statfsData, error) // nil => real statfs
}

// statfsData is the platform-neutral view of a statfs result needed
// for capacity math. All values are bytes.
type statfsData struct {
	Total     uint64 // f_blocks * f_bsize
	Free      uint64 // f_bfree  * f_bsize (includes root-reserved blocks)
	Available uint64 // f_bavail * f_bsize (free to unprivileged users)
}

// newStorageProvider returns the default (Linux mounts+statfs-backed)
// collector.
func newStorageProvider() StorageProvider {
	return &procStorageInfo{read: nil, statf: nil}
}

// realStatfs adapts syscall.Statfs to statfsData.
func realStatfs(path string) (statfsData, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return statfsData{}, err
	}
	bsize := uint64(st.Bsize)
	return statfsData{
		Total:     st.Blocks * bsize,
		Free:      st.Bfree * bsize,
		Available: st.Bavail * bsize,
	}, nil
}

const mountsPath = "/proc/self/mounts"

// Collect enumerates mounted filesystems from the mount table and
// reports capacity for the persistent ones (pseudo-filesystems are
// filtered by type — see isPseudoFilesystem). Mounts are reported in
// ascending mount-point order, which keeps dashboards stable. A mount
// point that appears multiple times (overmounting) is reported at its
// topmost entry — the last one in table order, which is the one the
// kernel actually exposes.
func (p *procStorageInfo) Collect() StorageInfo {
	read := p.read
	if read == nil {
		read = os.ReadFile
	}
	statf := p.statf
	if statf == nil {
		statf = realStatfs
	}

	b, err := read(mountsPath)
	if err != nil {
		return StorageInfo{} // no mount table => no fabricated data
	}

	// Stable processing order: by mount point, breaking ties by table
	// position so overmounting keeps the topmost (last) entry.
	entries := parseMounts(string(b))
	type indexed struct {
		entry mountEntry
		pos   int
	}
	sorted := make([]indexed, len(entries))
	for i, e := range entries {
		sorted[i] = indexed{entry: e, pos: i}
	}
	sort.Slice(sorted, func(i, j int) bool {
		if sorted[i].entry.MountPoint != sorted[j].entry.MountPoint {
			return sorted[i].entry.MountPoint < sorted[j].entry.MountPoint
		}
		return sorted[i].pos < sorted[j].pos
	})

	var out []Filesystem
	for i := range sorted {
		m := sorted[i].entry
		if isPseudoFilesystem(m.Type) {
			continue
		}
		st, err := statf(m.MountPoint)
		if err != nil {
			continue // unreadable filesystem: skip, never fabricate
		}
		if st.Total == 0 {
			continue // zero-capacity: nothing meaningful for a dashboard
		}
		// Overmounted path: keep the topmost entry (largest table
		// position wins within an equal mount point).
		if len(out) > 0 && out[len(out)-1].Mount == m.MountPoint {
			out = out[:len(out)-1]
		}
		out = append(out, Filesystem{
			Mount:        m.MountPoint,
			Filesystem:   m.Type,
			Total:        st.Total,
			Used:         st.Total - st.Free,
			Available:    st.Available,
			UsagePercent: fileUsagePercent(st.Total, st.Available),
		})
	}
	return StorageInfo{Filesystems: out}
}
