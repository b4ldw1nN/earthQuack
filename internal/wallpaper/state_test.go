package wallpaper

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

var errUnexpectedError = errors.New("unexpected")

func TestStateLoadPreservesExistingUploads(t *testing.T) {
	dir := t.TempDir()
	existing := map[string]UploadRecord{
		"deadbeef": {File: "/s/w.jpg", Filename: "w.jpg", Topic: "Tokyo Night", ThreadID: 10, Size: 123},
	}
	saveUploads(t, dir, existing)

	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !s.IsArchived("deadbeef") {
		t.Fatal("existing digest must be recognised as archived")
	}
	up := s.Uploads()
	if up["deadbeef"].ThreadID != 10 || up["deadbeef"].Topic != "Tokyo Night" {
		t.Errorf("existing record not preserved verbatim: %+v", up["deadbeef"])
	}
}

// fixedTime is a deterministic timestamp for state writes.
var fixedTime = time.Date(2026, 9, 16, 12, 0, 0, 0, time.UTC)

func TestStateRoundTripUpload(t *testing.T) {
	dir := t.TempDir()
	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	f := ArchiveFile{SourcePath: "/a/b.png", Filename: "b.png", Category: "Tokyo Night", Digest: "abc", Size: 42, MtimeNS: 1234}
	if err := s.MarkUploaded("/a/b.png", f, fixedTime); err != nil {
		t.Fatal(err)
	}
	s2, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !s2.IsArchived("abc") {
		t.Fatal("upload must persist across reload")
	}
	rec := s2.Uploads()["abc"]
	if rec.Path != "/a/b.png" || rec.Filename != "b.png" || rec.Topic != "Tokyo Night" || rec.Size != 42 {
		t.Errorf("round-tripped record wrong: %+v", rec)
	}
	if rec.MtimeNS != 1234 || rec.UploadedAt != fixedTime.Unix() {
		t.Errorf("fingerprint metadata not persisted: %+v", rec)
	}
	if rec.File != "" {
		t.Errorf("new records must use the path-based shape, got file=%q", rec.File)
	}
}

func TestStateFailedClearedByUpload(t *testing.T) {
	dir := t.TempDir()
	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	f := ArchiveFile{SourcePath: "/a/x.png", Filename: "x.png", Category: Unsorted, Digest: "d1", Size: 5}
	if err := s.MarkFailed("/a/x.png", f, errUnexpectedError, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Failed()["/a/x.png"]; !ok {
		t.Fatal("failed entry must exist")
	}
	if err := s.MarkUploaded("/a/x.png", f, fixedTime); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Failed()["/a/x.png"]; ok {
		t.Fatal("successful upload must clear failed entry")
	}
	if !s.IsArchived("d1") {
		t.Fatal("upload must mark digest archived")
	}
}

func TestStateAtomicWritesNoTempLeftovers(t *testing.T) {
	dir := t.TempDir()
	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		f := ArchiveFile{SourcePath: "/f", Filename: "f.png", Category: Unsorted, Digest: string(rune('a' + i)), Size: 1}
		if err := s.MarkUploaded("/f", f, fixedTime); err != nil {
			t.Fatal(err)
		}
	}
	// Uploads file must exist and be valid JSON.
	data, err := os.ReadFile(filepath.Join(dir, uploadFileName))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]UploadRecord
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("uploads.json not valid JSON: %v", err)
	}
	// No temp files may be left behind.
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if filepath.Ext(e.Name()) == ".tmp" {
			t.Errorf("leftover temp file: %s", e.Name())
		}
	}
}

func TestStateRejectsInvalidJSON(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, uploadFileName), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewState(dir); err == nil {
		t.Fatal("expected error for invalid uploads.json")
	}
}

func saveUploads(t *testing.T, dir string, m map[string]UploadRecord) {
	t.Helper()
	if err := writeJSONAtomic(filepath.Join(dir, uploadFileName), m, true); err != nil {
		t.Fatal(err)
	}
}

// TestStatePreservesBothPythonRecordShapes documents the compatibility
// contract with wallpaper-backup: an archive contains legacy records
// (file/topic/thread_id/size) and current records
// (path/filename/topic/size/mtime_ns/message_id/uploaded_at), and the
// module must round-trip every field of both when it rewrites the file.
func TestStatePreservesBothPythonRecordShapes(t *testing.T) {
	dir := t.TempDir()
	const (
		legacyDigest = "1111111111111111111111111111111111111111111111111111111111111111"
		modernDigest = "2222222222222222222222222222222222222222222222222222222222222222"
	)
	legacy := `{"file":"/home/zoro/Pictures/Wallpapers/wallhaven-2863em.jpg","filename":"wallhaven-2863em.jpg","topic":"Unsorted","thread_id":8,"size":321177}`
	current := `{"path":"/home/zoro/Pictures/Wallpapers/Wallhaven/wallhaven_1q2le9.jpg","filename":"wallhaven_1q2le9.jpg","topic":"Wallhaven","size":690476,"mtime_ns":1789552342111352324,"message_id":538,"uploaded_at":1789553881}`
	raw := "{\n  \"" + legacyDigest + "\": " + legacy + ",\n  \"" + modernDigest + "\": " + current + "\n}\n"
	if err := os.WriteFile(filepath.Join(dir, uploadFileName), []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}

	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !s.IsArchived(legacyDigest) || !s.IsArchived(modernDigest) {
		t.Fatal("both record shapes must be recognised as archived")
	}

	// Rewriting state (a new upload) must not damage the existing records.
	f := ArchiveFile{SourcePath: "/x/new.png", Filename: "new.png", Category: "Unsorted", Digest: "3333", Size: 3}
	if err := s.MarkUploaded("/x/new.png", f, fixedTime); err != nil {
		t.Fatal(err)
	}

	data, err := os.ReadFile(filepath.Join(dir, uploadFileName))
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]map[string]any
	if err := json.Unmarshal(data, &got); err != nil {
		t.Fatalf("uploads.json not valid JSON: %v", err)
	}
	// Legacy record keeps every original field, including thread_id.
	for _, key := range []string{"file", "filename", "topic", "thread_id", "size"} {
		if _, ok := got[legacyDigest][key]; !ok {
			t.Errorf("legacy record lost %q after rewrite: %v", key, got[legacyDigest])
		}
	}
	if got[legacyDigest]["thread_id"] != float64(8) {
		t.Errorf("legacy thread_id not preserved: %v", got[legacyDigest]["thread_id"])
	}
	if got[legacyDigest]["file"] != "/home/zoro/Pictures/Wallpapers/wallhaven-2863em.jpg" {
		t.Errorf("legacy file path not preserved: %v", got[legacyDigest]["file"])
	}
	// Current record keeps path/message_id/uploaded_at.
	for _, key := range []string{"path", "filename", "topic", "size", "mtime_ns", "message_id", "uploaded_at"} {
		if _, ok := got[modernDigest][key]; !ok {
			t.Errorf("current record lost %q after rewrite: %v", key, got[modernDigest])
		}
	}
	if got[modernDigest]["message_id"] != float64(538) {
		t.Errorf("message_id not preserved: %v", got[modernDigest]["message_id"])
	}
}

// TestStateFailedPreservesThreadID mirrors the updated failure shape:
// the Python script records the target thread id alongside the error.
func TestStateFailedPreservesThreadID(t *testing.T) {
	dir := t.TempDir()
	raw := `{"/w/a.jpg":{"file":"/w/a.jpg","topic":"Anime","thread_id":42,"error":"boom","sha256":"beef","size":7}}`
	if err := os.WriteFile(filepath.Join(dir, failedFileName), []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	rec, ok := s.Failed()["/w/a.jpg"]
	if !ok {
		t.Fatal("failure record must load")
	}
	if rec.ThreadID != 42 {
		t.Fatalf("thread_id must be preserved, got %d", rec.ThreadID)
	}
}

func TestClearFailedRemovesEntryWithoutUploading(t *testing.T) {
	dir := t.TempDir()
	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	f := ArchiveFile{SourcePath: "/w/a.jpg", Filename: "a.jpg", Category: "Anime", Digest: "beef", Size: 7}
	if err := s.MarkFailed(f.SourcePath, f, errUnexpectedError, fixedTime); err != nil {
		t.Fatal(err)
	}
	if err := s.ClearFailed(f.SourcePath); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Failed()[f.SourcePath]; ok {
		t.Fatal("ClearFailed must remove the entry")
	}
	if s.IsArchived("beef") {
		t.Fatal("ClearFailed must not add anything to the success ledger")
	}
	// Clearing a non-existent entry is a no-op, not an error.
	if err := s.ClearFailed("/w/nothing.jpg"); err != nil {
		t.Fatalf("clear of unknown path: %v", err)
	}
}

func TestUploadsBySourcePathPrefersPathThenFile(t *testing.T) {
	dir := t.TempDir()
	uploads := map[string]UploadRecord{
		"d1": {File: "/legacy/a.jpg", Filename: "a.jpg", Topic: "Unsorted", Size: 10},
		"d2": {Path: "/current/b.jpg", Filename: "b.jpg", Topic: "Anime", Size: 20},
		"d3": {Path: "/both/c.jpg", File: "/both/c.jpg", Filename: "c.jpg", Topic: "Anime", Size: 30},
		"d4": {Filename: "orphan.jpg", Topic: "Unsorted", Size: 1}, // no path at all
	}
	saveUploads(t, dir, uploads)
	s, err := NewState(dir)
	if err != nil {
		t.Fatal(err)
	}
	byPath := s.UploadsBySourcePath()
	if got := byPath["/legacy/a.jpg"]; got.Digest != "d1" || got.Size != 10 {
		t.Errorf("legacy file field must be used as the path: %+v", got)
	}
	if got := byPath["/current/b.jpg"]; got.Digest != "d2" {
		t.Errorf("path field must be used: %+v", got)
	}
	if got := byPath["/both/c.jpg"]; got.Digest != "d3" {
		t.Errorf("conflicting shapes must agree here: %+v", got)
	}
	if len(byPath) != 3 {
		t.Errorf("records without any path must be skipped, got %d entries: %v", len(byPath), byPath)
	}
}
