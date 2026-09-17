package wallpaper

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// newTestModule wires a module over a fake provider with a fresh source
// and state dir. clock is fixed for deterministic last-sync stamps.
func newTestModule(t *testing.T) (*Module, *fakeProvider, string, string) {
	t.Helper()
	source := t.TempDir()
	state := t.TempDir()
	prov := newFakeProvider()
	fixed := time.Date(2026, 9, 16, 12, 0, 0, 0, time.UTC)
	m, err := NewModule(
		Config{Source: source, StateDir: state},
		prov,
		WithClock(func() time.Time { return fixed }),
		WithOutput(os.Stderr, false),
	)
	if err != nil {
		t.Fatal(err)
	}
	return m, prov, source, state
}

func TestSyncUploadsNewFilesAndSkipsExisting(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	p1 := writeFile(t, source, "Tokyo Night/a.png", []byte("one"))
	p2 := writeFile(t, source, "b.webp", []byte("two"))

	rep, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Discovered != 2 || rep.Pending != 2 || rep.Uploaded != 2 {
		t.Fatalf("unexpected first sync: %+v", rep)
	}
	if got := prov.uploadedCount(); got != 2 {
		t.Fatalf("want 2 uploads, got %d", got)
	}
	rep2, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep2.Discovered != 2 || rep2.Archived != 2 || rep2.Pending != 0 || rep2.Uploaded != 0 {
		t.Fatalf("expected full skip on second sync: %+v", rep2)
	}
	if got := prov.uploadedCount(); got != 2 {
		t.Fatalf("second sync must not re-upload: got %d", got)
	}
	if !m.state.IsArchived(hashOf(t, p1)) || !m.state.IsArchived(hashOf(t, p2)) {
		t.Fatal("both files should be marked archived")
	}
}

func TestChangedFileIsDetected(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	p := writeFile(t, source, "cat.png", []byte("version one"))
	if _, err := m.Sync(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	if got := prov.uploadedCount(); got != 1 {
		t.Fatalf("want 1 upload, got %d", got)
	}

	if err := os.WriteFile(p, []byte("version two changed"), 0o644); err != nil {
		t.Fatal(err)
	}
	rep, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Pending != 1 || rep.Uploaded != 1 || rep.Archived != 0 {
		t.Fatalf("changed file must be detected as pending: %+v", rep)
	}
}

func TestExistingUploadStateCausesSkipOnFreshState(t *testing.T) {
	source := t.TempDir()
	state := t.TempDir()
	p := writeFile(t, source, "Tokyo Night/preloaded.jpg", []byte("already-uploaded"))
	digest, err := SHA256(p)
	if err != nil {
		t.Fatal(err)
	}
	uploads := map[string]UploadRecord{
		digest: {File: p, Filename: "preloaded.jpg", Topic: "Tokyo Night", Size: 16},
	}
	if err := writeJSONAtomic(filepath.Join(state, uploadFileName), uploads, true); err != nil {
		t.Fatal(err)
	}

	prov := newFakeProvider()
	m, err := NewModule(Config{Source: source, StateDir: state}, prov, WithOutput(os.Stderr, false))
	if err != nil {
		t.Fatal(err)
	}
	rep, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Archived != 1 || rep.Pending != 0 || rep.Uploaded != 0 {
		t.Fatalf("existing archive must prevent re-upload: %+v", rep)
	}
	if prov.uploadedCount() != 0 {
		t.Fatalf("must not upload a file already in uploads.json")
	}
}

func TestFailedUploadEntersFailedStateAndNotUploadLedger(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	p := writeFile(t, source, "fail.png", []byte("doomed"))
	prov.failOn[p] = errors.New("simulated bad gateway")

	rep, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Failed != 1 || rep.Uploaded != 0 {
		t.Fatalf("expected 1 failed, 0 uploaded: %+v", rep)
	}
	if m.state.IsArchived(hashOf(t, p)) {
		t.Fatal("failed upload must not be marked archived")
	}
	if _, ok := m.state.Failed()[p]; !ok {
		t.Fatal("failed upload must be recorded in failed state")
	}
}

// TestRetryFailedClearsEntriesAlreadyArchived mirrors the updated
// wallpaper-backup retry behaviour: an entry whose (re-hashed) content is
// already in the ledger is resolved without an upload.
func TestRetryFailedClearsEntriesAlreadyArchived(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	p := writeFile(t, source, "ghost.png", []byte("landed anyway"))
	digest := hashOf(t, p)
	af := ArchiveFile{SourcePath: p, Filename: "ghost.png", Category: Unsorted, Digest: digest, Size: 13}
	// Simulate: the upload actually succeeded, but a failure was recorded
	// (e.g. the response was lost) — state has both records.
	if err := m.state.MarkUploaded(p, af, fixedTime); err != nil {
		t.Fatal(err)
	}
	if err := m.state.MarkFailed(p, af, errors.New("response lost"), fixedTime); err != nil {
		t.Fatal(err)
	}

	rep, err := m.RetryFailed(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if rep.Uploaded != 0 || rep.Skipped != 1 || rep.Failed != 0 {
		t.Fatalf("already-archived entry must be skipped, not uploaded: %+v", rep)
	}
	if prov.uploadedCount() != 0 {
		t.Fatal("an already-archived entry must not reach the provider")
	}
	if _, ok := m.state.Failed()[p]; ok {
		t.Fatal("resolved entry must be cleared from failed state")
	}
}

// TestRetryFailedRehashesChangedContent proves the retry re-reads the
// file: a failure recorded with a stale digest/size must upload the
// current bytes under the current digest.
func TestRetryFailedRehashesChangedContent(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	p := writeFile(t, source, "changed.png", []byte("old"))
	stale := ArchiveFile{SourcePath: p, Filename: "changed.png", Category: Unsorted, Digest: "stale-digest", Size: 3}
	if err := m.state.MarkFailed(p, stale, errors.New("boom"), fixedTime); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte("brand new content"), 0o644); err != nil {
		t.Fatal(err)
	}

	rep, err := m.RetryFailed(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if rep.Uploaded != 1 || rep.Failed != 0 {
		t.Fatalf("retry must upload the current content: %+v", rep)
	}
	want := hashOf(t, p)
	if want == "stale-digest" {
		t.Fatal("test setup is wrong")
	}
	if !m.state.IsArchived(want) {
		t.Fatal("ledger must be keyed by the re-hashed digest")
	}
	if m.state.IsArchived("stale-digest") {
		t.Fatal("the stale digest must never enter the ledger")
	}
	up := m.state.Uploads()[want]
	if up.Size != 17 || up.Path != p {
		t.Fatalf("upload record must carry current metadata: %+v", up)
	}
	if prov.uploadedCount() != 1 {
		t.Fatalf("want exactly one upload, got %d", prov.uploadedCount())
	}
}

// TestRetryFailedKeepsUnresolvableEntries documents that a retry which
// cannot resolve an entry (here: the file is gone) preserves the entry
// and reports it as still failing, rather than silently dropping state.
func TestRetryFailedKeepsUnresolvableEntries(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	p := writeFile(t, source, "vanishing.png", []byte("here"))
	af := ArchiveFile{SourcePath: p, Filename: "vanishing.png", Category: Unsorted, Digest: hashOf(t, p), Size: 4}
	if err := m.state.MarkFailed(p, af, errors.New("boom"), fixedTime); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(p); err != nil {
		t.Fatal(err)
	}

	rep, err := m.RetryFailed(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if rep.Failed != 1 || rep.Uploaded != 0 || rep.Skipped != 0 {
		t.Fatalf("unresolvable entry must count as still failing: %+v", rep)
	}
	if _, ok := m.state.Failed()[p]; !ok {
		t.Fatal("an unresolvable entry must be preserved, never dropped")
	}
	if prov.uploadedCount() != 0 {
		t.Fatal("nothing should reach the provider")
	}
}

func TestSuccessfulRetryClearsFailedState(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	p := writeFile(t, source, "retry.png", []byte("will eventually land"))
	prov.failOn[p] = errors.New("transient outage")

	if _, err := m.Sync(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	if _, ok := m.state.Failed()[p]; !ok {
		t.Fatal("expected a failed entry after sync")
	}

	prov.mu.Lock()
	delete(prov.failOn, p)
	prov.mu.Unlock()
	rep, err := m.RetryFailed(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if rep.Uploaded != 1 || rep.Failed != 0 {
		t.Fatalf("retry should succeed once: %+v", rep)
	}
	if _, ok := m.state.Failed()[p]; ok {
		t.Fatal("successful retry must clear the failed entry")
	}
	if !m.state.IsArchived(hashOf(t, p)) {
		t.Fatal("successful retry must update the success ledger")
	}
}

func TestDryRunDoesNotPerformProviderOperationsOrStateChanges(t *testing.T) {
	m, prov, source, state := newTestModule(t)
	writeFile(t, source, "Tokyo Night/a.png", []byte("one"))
	writeFile(t, source, "b.webp", []byte("two"))

	rep, err := m.Sync(context.Background(), true)
	if err != nil {
		t.Fatal(err)
	}
	if !rep.DryRun {
		t.Fatal("report must be marked dry-run")
	}
	if prov.uploadedCount() != 0 {
		t.Fatalf("dry-run must not upload: got %d", prov.uploadedCount())
	}
	for _, f := range []string{uploadFileName, failedFileName, syncFileName, filesFileName} {
		if _, err := os.Stat(filepath.Join(state, f)); !os.IsNotExist(err) {
			t.Errorf("dry-run must not create %s", f)
		}
	}
}

// TestSyncPersistsFileIndexAndAvoidsRehashing proves the index
// optimisation: the second sync of an untouched, fully archived tree
// reads no file contents at all.
func TestSyncPersistsFileIndexAndAvoidsRehashing(t *testing.T) {
	m, _, source, state := newTestModule(t)
	writeFile(t, source, "Tokyo Night/a.png", []byte("one"))
	writeFile(t, source, "b.webp", []byte("two"))

	rep, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Hashed != 2 {
		t.Fatalf("first sync must hash both files, got Hashed=%d", rep.Hashed)
	}
	if _, err := os.Stat(filepath.Join(state, filesFileName)); err != nil {
		t.Fatalf("a real sync must persist the file index: %v", err)
	}

	rep2, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep2.Hashed != 0 {
		t.Fatalf("unchanged files must not be re-hashed, got Hashed=%d", rep2.Hashed)
	}
	if rep2.Archived != 2 || rep2.Pending != 0 {
		t.Fatalf("unexpected second sync: %+v", rep2)
	}
}

// TestStatusIsReadOnlyAndUsesIndex keeps the invariant that status never
// writes state while still benefiting from a populated index.
func TestStatusIsReadOnlyAndUsesIndex(t *testing.T) {
	m, _, source, state := newTestModule(t)
	writeFile(t, source, "a.png", []byte("one"))
	writeFile(t, source, "b.webp", []byte("two"))
	if _, err := m.Sync(context.Background(), false); err != nil {
		t.Fatal(err)
	}

	indexPath := filepath.Join(state, filesFileName)
	before, err := os.Stat(indexPath)
	if err != nil {
		t.Fatal(err)
	}
	// Snapshot the other state files too: status must not touch them.
	snapshot := map[string][]byte{}
	for _, f := range []string{uploadFileName, failedFileName, syncFileName} {
		b, err := os.ReadFile(filepath.Join(state, f))
		if err != nil {
			t.Fatal(err)
		}
		snapshot[f] = b
	}

	st, err := m.Status()
	if err != nil {
		t.Fatal(err)
	}
	if st.Archived != 2 || st.Pending != 0 {
		t.Fatalf("unexpected status: %+v", st)
	}
	if st.Cached != 2 || st.Hashed != 0 {
		t.Fatalf("status must reuse the index, got Cached=%d Hashed=%d", st.Cached, st.Hashed)
	}
	after, err := os.Stat(indexPath)
	if err != nil {
		t.Fatal(err)
	}
	if !before.ModTime().Equal(after.ModTime()) {
		t.Error("status must not rewrite the file index")
	}
	for f, want := range snapshot {
		got, err := os.ReadFile(filepath.Join(state, f))
		if err != nil {
			t.Errorf("status removed %s: %v", f, err)
			continue
		}
		if !bytes.Equal(got, want) {
			t.Errorf("status modified %s", f)
		}
	}
}

// TestScanAdoptsLegacyArchiveDigestWithoutHashing covers the migration
// path: an archive written by an older wallpaper-backup uses `file`
// rather than `path`, and its digest must be adopted (no read) when the
// size still matches.
func TestScanAdoptsLegacyArchiveDigestWithoutHashing(t *testing.T) {
	source := t.TempDir()
	state := t.TempDir()
	p := writeFile(t, source, "Tokyo Night/preloaded.jpg", []byte("already-uploaded"))
	digest, err := SHA256(p)
	if err != nil {
		t.Fatal(err)
	}
	// Legacy shape: no `path`, only `file`.
	legacy := map[string]UploadRecord{
		digest: {File: p, Filename: "preloaded.jpg", Topic: "Tokyo Night", ThreadID: 9, Size: 16},
	}
	if err := writeJSONAtomic(filepath.Join(state, uploadFileName), legacy, true); err != nil {
		t.Fatal(err)
	}

	prov := newFakeProvider()
	m, err := NewModule(Config{Source: source, StateDir: state}, prov, WithOutput(os.Stderr, false))
	if err != nil {
		t.Fatal(err)
	}
	rep, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Archived != 1 || rep.Pending != 0 {
		t.Fatalf("legacy archive record must be adopted: %+v", rep)
	}
	if rep.Hashed != 0 {
		t.Fatalf("adopted archive must not re-hash, got Hashed=%d", rep.Hashed)
	}
	if prov.uploadedCount() != 0 {
		t.Fatal("adopted archive must not re-upload")
	}
	// The legacy record must survive the rewrite with its thread_id.
	up := m.state.Uploads()[digest]
	if up.ThreadID != 9 || up.File != p {
		t.Fatalf("legacy record damaged: %+v", up)
	}
}

func TestOversizedFileFailsValidationAndNotUploaded(t *testing.T) {
	m, prov, source, _ := newTestModule(t)
	prov.maxSize = 10
	big := writeFile(t, source, "big.png", []byte("0123456789ABCDEF"))
	small := writeFile(t, source, "small.webp", []byte("ok"))

	rep, err := m.Sync(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Invalid != 1 || rep.Uploaded != 1 {
		t.Fatalf("want 1 invalid + 1 uploaded, got %+v", rep)
	}
	for _, path := range prov.uploadedPaths() {
		if path == big {
			t.Fatal("oversized file must not be handed to the provider")
		}
	}
	// A permanently-invalid file (too large) is reported, not re-queued
	// for retries and never added to the success ledger.
	if m.state.IsArchived(hashOf(t, big)) {
		t.Fatal("oversized file must not be marked archived")
	}
	if !m.state.IsArchived(hashOf(t, small)) {
		t.Fatal("within-limit file should archive normally")
	}
}

func TestStatusReportsCounts(t *testing.T) {
	m, _, source, _ := newTestModule(t)
	writeFile(t, source, "Tokyo Night/a.png", []byte("one"))
	writeFile(t, source, "b.webp", []byte("two"))

	st, err := m.Status()
	if err != nil {
		t.Fatal(err)
	}
	if st.Discovered != 2 || st.Pending != 2 || st.Archived != 0 || st.Topics != 2 {
		t.Fatalf("unexpected status: %+v", st)
	}
	if st.HasLastSync {
		t.Fatal("no sync should have happened yet")
	}

	// After archiving everything, the topic count still reflects the
	// distinct destination categories (not just pending ones).
	if _, err := m.Sync(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	st2, err := m.Status()
	if err != nil {
		t.Fatal(err)
	}
	if st2.Archived != 2 || st2.Pending != 0 || st2.Topics != 2 {
		t.Fatalf("post-sync status wrong: %+v", st2)
	}
	if !st2.HasLastSync {
		t.Fatal("sync should have recorded a last-sync timestamp")
	}
}

func TestModuleEmitsLifecycleEvents(t *testing.T) {
	m, _, source, _ := newTestModule(t)
	writeFile(t, source, "a.png", []byte("one"))
	var events []SyncEvent
	m.events = func(e SyncEvent, msg string) { events = append(events, e) }
	if _, err := m.Sync(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	found := map[SyncEvent]bool{}
	for _, e := range events {
		found[e] = true
	}
	if !found[SyncStarted] || !found[SyncCompleted] {
		t.Fatalf("expected started+completed events, got %v", events)
	}
}

func hashOf(t *testing.T, path string) string {
	t.Helper()
	h, err := SHA256(path)
	if err != nil {
		t.Fatal(err)
	}
	return h
}
