package wallpaper

import (
	"path/filepath"
	"strings"
	"time"
)

// filesFileName is the additive local file index. It is an optimisation
// only: deleting it never loses archive state (digests are simply
// recomputed). The updated wallpaper-backup implementation reads and
// writes the very same file, so both tools can share one state
// directory.
const filesFileName = "files.json"

// FileIndexRecord is one entry in files.json, keyed by the resolved
// absolute source path. The shape matches the original wallpaper-backup
// implementation exactly:
//
//	{"<resolved path>": {"size": N, "mtime_ns": N, "sha256": "..."}}
type FileIndexRecord struct {
	Size    int64  `json:"size"`
	MtimeNS int64  `json:"mtime_ns"`
	SHA256  string `json:"sha256"`
}

// fileIndex caches path → (size, mtime_ns, sha256) so that unchanged
// files are never read again. It is what keeps `status` cheap on a large
// archive: a file whose fingerprint still matches is not hashed.
//
// The index is deliberately advisory. A missing/stale/partial index only
// costs CPU; it can never change which files are considered archived,
// because that decision is always made from uploads.json.
type fileIndex struct {
	path    string
	records map[string]FileIndexRecord
}

// loadFileIndex reads files.json from dir. A missing file is empty
// state; invalid JSON is an error (never silently discarded).
func loadFileIndex(dir string) (*fileIndex, error) {
	ix := &fileIndex{
		path:    filepath.Join(dir, filesFileName),
		records: map[string]FileIndexRecord{},
	}
	records, err := readJSONObject[FileIndexRecord](ix.path)
	if err != nil {
		return nil, err
	}
	for k, v := range records {
		ix.records[k] = v
	}
	return ix, nil
}

// lookup returns the cached digest for a resolved path, but only when the
// file's current size and mtime still match the index (i.e. the file is
// unchanged since the digest was computed).
func (ix *fileIndex) lookup(resolved string, size, mtimeNS int64) (string, bool) {
	rec, ok := ix.records[resolved]
	if !ok || rec.SHA256 == "" {
		return "", false
	}
	if rec.Size != size || rec.MtimeNS != mtimeNS {
		return "", false
	}
	return rec.SHA256, true
}

// set records the fingerprint + digest for a resolved path.
func (ix *fileIndex) set(resolved string, size, mtimeNS int64, digest string) {
	ix.records[resolved] = FileIndexRecord{Size: size, MtimeNS: mtimeNS, SHA256: digest}
}

// prune drops entries for files that no longer exist, returning how many
// were removed. Only entries inside root are considered: a state
// directory can be shared by more than one source tree (or by the
// Python script), and entries belonging to another root are not this
// scan's to discard.
func (ix *fileIndex) prune(root string, current map[string]struct{}) int {
	root = filepath.Clean(root)
	removed := 0
	for p := range ix.records {
		if !underRoot(p, root) {
			continue
		}
		if _, ok := current[p]; !ok {
			delete(ix.records, p)
			removed++
		}
	}
	return removed
}

// underRoot reports whether p lies inside (or is) root.
func underRoot(p, root string) bool {
	if p == root {
		return true
	}
	return strings.HasPrefix(p, root+string(filepath.Separator))
}

// size returns the number of indexed files.
func (ix *fileIndex) size() int { return len(ix.records) }

// save atomically persists the index (temp file + fsync + rename).
func (ix *fileIndex) save() error {
	return writeJSONAtomic(ix.path, ix.records, false)
}

// mtimeNS returns the nanosecond mtime used as the index fingerprint —
// the same unit the original implementation stores.
func mtimeNS(t time.Time) int64 { return t.UnixNano() }
