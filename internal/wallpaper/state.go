package wallpaper

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// State is the module's persistent archive bookkeeping. It reuses the
// exact file format and layout produced by the original (and updated)
// wallpaper-backup implementation so existing archives are recognised
// without migration or re-upload:
//
//	uploads.json — map[sha256]UploadRecord (the "already archived" ledger)
//	failed.json  — map[sourcePath]FailedRecord (files to retry later)
//	files.json   — map[sourcePath]FileIndexRecord (additive digest cache)
//	sync.json    — small metadata (last sync timestamp)
//
// uploads.json and failed.json are shared with the Python script, which
// has two record shapes in the wild: the legacy one (`file`, `topic`,
// `thread_id`, `size`) and the current one (`path`, `filename`, `topic`,
// `size`, `mtime_ns`, `message_id`, `uploaded_at`). UploadRecord models
// both, so a record read from disk is written back with every field it
// carried and neither tool loses data written by the other.
//
// topics.json is the Telegram provider's own state and is managed by
// the TelegramProvider (category → thread id), not here.

// uploadFileName / failedFileName / syncFileName are the on-disk
// filenames inside the state directory. (filesFileName lives in
// index.go, next to the index type.)
const (
	uploadFileName = "uploads.json"
	failedFileName = "failed.json"
	syncFileName   = "sync.json"
)

// UploadRecord is the shape of one entry in uploads.json, keyed by the
// SHA-256 digest of the file content.
//
// It models both shapes found in existing archives:
//
//	legacy   — file, filename, topic, thread_id, size
//	current  — path, filename, topic, size, mtime_ns, message_id, uploaded_at
//
// The module writes the `path`-based shape (matching the updated
// wallpaper-backup script) and keeps `file` only for records that
// already carry it. ThreadID and MessageID are destination-specific
// metadata owned by the provider; they are preserved verbatim when
// present but are never required and never used for de-duplication
// (which is always content-addressed by digest).
type UploadRecord struct {
	File       string `json:"file,omitempty"`
	Path       string `json:"path,omitempty"`
	Filename   string `json:"filename"`
	Topic      string `json:"topic"`
	ThreadID   int64  `json:"thread_id,omitempty"`
	Size       int64  `json:"size"`
	MtimeNS    int64  `json:"mtime_ns,omitempty"`
	MessageID  int64  `json:"message_id,omitempty"`
	UploadedAt int64  `json:"uploaded_at,omitempty"`
}

// SourcePath returns the recorded source path, preferring the current
// `path` field and falling back to the legacy `file` field.
func (r UploadRecord) SourcePath() string {
	if r.Path != "" {
		return r.Path
	}
	return r.File
}

// FailedRecord is the shape of one entry in failed.json. It is keyed by
// the absolute source path so a later successful upload of the same
// file removes/resolves the failure. ThreadID is optional provider
// metadata preserved for compatibility with the Python script, which
// records the thread a retry should target.
type FailedRecord struct {
	File     string    `json:"file"`
	Category string    `json:"topic"`
	ThreadID int64     `json:"thread_id,omitempty"`
	Error    string    `json:"error"`
	Digest   string    `json:"sha256"`
	Size     int64     `json:"size"`
	Time     time.Time `json:"time,omitempty"`
}

// PathRecord is a source path paired with the digest it was archived
// under. It is used to bootstrap the file index (and therefore avoid
// re-hashing an adopted archive) without reading file contents.
type PathRecord struct {
	Digest string
	Size   int64
}

// syncMeta is the metadata stored in sync.json.
type syncMeta struct {
	LastSync time.Time `json:"last_sync"`
}

// State owns the module's archive bookkeeping for one state directory.
// All writes are atomic (temp file + rename + fsync), so a crash mid-
// sync never corrupts or tears archive state.
type State struct {
	dir     string
	uploads map[string]UploadRecord
	failed  map[string]FailedRecord
	last    time.Time
}

// NewState loads (or initialises) archive state from dir. It never
// throws away existing data: uploads, failures, and last-sync metadata
// are all read back in. A missing file is simply empty state.
func NewState(dir string) (*State, error) {
	s := &State{
		dir:     dir,
		uploads: map[string]UploadRecord{},
		failed:  map[string]FailedRecord{},
	}

	if err := s.load(); err != nil {
		return nil, err
	}
	return s, nil
}

// Dir returns the state directory path.
func (s *State) Dir() string { return s.dir }

// IsArchived reports whether the given SHA-256 digest is already
// recorded as uploaded (the basis for duplicate prevention).
func (s *State) IsArchived(digest string) bool {
	_, ok := s.uploads[digest]
	return ok
}

// Uploads returns a copy of the uploaded records keyed by digest.
func (s *State) Uploads() map[string]UploadRecord {
	out := make(map[string]UploadRecord, len(s.uploads))
	for k, v := range s.uploads {
		out[k] = v
	}
	return out
}

// Failed returns a copy of the failed records keyed by source path.
func (s *State) Failed() map[string]FailedRecord {
	out := make(map[string]FailedRecord, len(s.failed))
	for k, v := range s.failed {
		out[k] = v
	}
	return out
}

// LastSync returns the stored last-sync timestamp, if any.
func (s *State) LastSync() (time.Time, bool) {
	if s.last.IsZero() {
		return time.Time{}, false
	}
	return s.last, true
}

// UploadsBySourcePath indexes the upload ledger by recorded source path
// (the current `path` field, falling back to the legacy `file` field).
// It lets a scan adopt an existing digest for a file whose bytes are not
// yet in the local index, so an adopted archive is not re-hashed. When
// several digests claim the same path the first (by digest) wins, which
// keeps the result deterministic.
func (s *State) UploadsBySourcePath() map[string]PathRecord {
	out := make(map[string]PathRecord, len(s.uploads))
	digests := make([]string, 0, len(s.uploads))
	for digest := range s.uploads {
		digests = append(digests, digest)
	}
	sort.Strings(digests)
	for _, digest := range digests {
		rec := s.uploads[digest]
		src := rec.SourcePath()
		if src == "" {
			continue
		}
		if _, ok := out[src]; ok {
			continue
		}
		out[src] = PathRecord{Digest: digest, Size: rec.Size}
	}
	return out
}

// MarkUploaded records a successful upload and atomically persists the
// ledger. It also removes any prior failed record for sourcePath.
func (s *State) MarkUploaded(srcPath string, f ArchiveFile, now time.Time) error {
	s.uploads[f.Digest] = UploadRecord{
		Path:       srcPath,
		Filename:   f.Filename,
		Topic:      f.Category,
		Size:       f.Size,
		MtimeNS:    f.MtimeNS,
		UploadedAt: now.Unix(),
	}
	delete(s.failed, srcPath)
	return s.save()
}

// ClearFailed removes a failed record without recording an upload. It is
// used when a retry discovers the file is in fact already archived (for
// example an upload that succeeded after the failure was written).
func (s *State) ClearFailed(srcPath string) error {
	if _, ok := s.failed[srcPath]; !ok {
		return nil
	}
	delete(s.failed, srcPath)
	return s.save()
}

// MarkFailed records a failed upload attempt and atomically persists it.
// A failed upload is never added to the success ledger.
func (s *State) MarkFailed(srcPath string, f ArchiveFile, err error, now time.Time) error {
	s.failed[srcPath] = FailedRecord{
		File:     srcPath,
		Category: f.Category,
		Error:    err.Error(),
		Digest:   f.Digest,
		Size:     f.Size,
		Time:     now,
	}
	return s.save()
}

// RecordLastSync atomically persists the completion timestamp of a sync.
func (s *State) RecordLastSync(now time.Time) error {
	s.last = now
	return writeJSONAtomic(filepath.Join(s.dir, syncFileName), syncMeta{LastSync: now}, true)
}

// load reads all three state files into memory.
func (s *State) load() error {
	uploads, err := readJSONObject[UploadRecord](filepath.Join(s.dir, uploadFileName))
	if err != nil {
		return err
	}
	for k, v := range uploads {
		s.uploads[k] = v
	}

	failed, err := readJSONObject[FailedRecord](filepath.Join(s.dir, failedFileName))
	if err != nil {
		return err
	}
	for k, v := range failed {
		s.failed[k] = v
	}

	var meta syncMeta
	if err := readJSON(filepath.Join(s.dir, syncFileName), &meta); err != nil {
		return err
	}
	s.last = meta.LastSync
	return nil
}

// save atomically persists uploads + failures together.
func (s *State) save() error {
	if err := writeJSONAtomic(filepath.Join(s.dir, uploadFileName), s.uploads, true); err != nil {
		return err
	}
	return writeJSONAtomic(filepath.Join(s.dir, failedFileName), s.failed, true)
}

// readJSONObject decodes a JSON object map of T, returning an empty map
// when the file does not exist.
func readJSONObject[T any](path string) (map[string]T, error) {
	out := map[string]T{}
	var raw map[string]json.RawMessage
	if err := readJSON(path, &raw); err != nil {
		return nil, err
	}
	if raw == nil {
		return out, nil
	}
	for k, r := range raw {
		var v T
		if err := json.Unmarshal(r, &v); err != nil {
			return nil, fmt.Errorf("wallpaper: state %s: %w", path, err)
		}
		out[k] = v
	}
	return out, nil
}

// readJSON decodes a file into v. A missing file leaves v untouched.
func readJSON(path string, v any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return fmt.Errorf("wallpaper: read state %s: %w", path, err)
	}
	if err := json.Unmarshal(data, v); err != nil {
		return fmt.Errorf("wallpaper: invalid JSON %s: %w", path, err)
	}
	return nil
}

// writeJSONAtomic writes v to path via a temp file, fsync, then rename,
// so readers never observe a torn write. Optionally chmods to 0600 when
// the file may carry sensitive history.
func writeJSONAtomic(path string, v any, private bool) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("wallpaper: mkdir %s: %w", dir, err)
	}
	f, err := os.CreateTemp(dir, filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("wallpaper: %w", err)
	}
	tmp := f.Name()
	cleanup := func() {
		f.Close()
		os.Remove(tmp)
	}

	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	// Match the Python writer (json.dumps(..., ensure_ascii=False)): do
	// not HTML-escape <, > and & inside paths or error strings.
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		cleanup()
		return fmt.Errorf("wallpaper: encode %s: %w", path, err)
	}
	if private {
		_ = f.Chmod(0o600)
	}
	if err := f.Sync(); err != nil {
		cleanup()
		return fmt.Errorf("wallpaper: %w", err)
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("wallpaper: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("wallpaper: %w", err)
	}
	return nil
}
