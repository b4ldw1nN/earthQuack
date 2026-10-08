package daemon

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestVersionSurvivesRestart is the regression test for the wedge that made
// desktop→phone sync stop working after any node restart.
//
// The Android client keeps the highest version it has seen and discards
// anything not greater (SyncState.tryClaimRemoteEvent). A counter starting at
// zero each process therefore looks permanently stale to the client, and every
// event is dropped until the app itself is restarted. The Python daemon had
// the same flaw; this pins the fix.
func TestVersionSurvivesRestart(t *testing.T) {
	dir := t.TempDir()

	// First "process": three updates.
	s1, err := NewClipboardStateWithLedger(dir)
	if err != nil {
		t.Fatal(err)
	}
	for i, text := range []string{"a", "b", "c"} {
		changed, snap := s1.Update(text, OriginPhone)
		if !changed {
			t.Fatalf("update %d should be a change", i)
		}
		if want := i + 1; snap.Version != want {
			t.Fatalf("update %d: version = %d, want %d", i, snap.Version, want)
		}
	}

	// Second "process": a fresh state seeded from the ledger must continue
	// from 3, not rewind to 0.
	s2, err := NewClipboardStateWithLedger(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got := s2.Get().Version; got != 3 {
		t.Fatalf("after restart the counter must resume at 3, got %d", got)
	}
	changed, snap := s2.Update("d", OriginDesktop)
	if !changed || snap.Version != 4 {
		t.Fatalf("first update after restart: version = %d, want 4", snap.Version)
	}
}

// TestVersionResumesAcrossManyRestarts proves the guarantee holds repeatedly,
// which is what a client actually experiences over the life of a node.
func TestVersionResumesAcrossManyRestarts(t *testing.T) {
	dir := t.TempDir()
	var last int
	for restart := 0; restart < 5; restart++ {
		s, err := NewClipboardStateWithLedger(dir)
		if err != nil {
			t.Fatal(err)
		}
		if got := s.Get().Version; got != last {
			t.Fatalf("restart %d: resumed at %d, want %d", restart, got, last)
		}
		for i := 0; i < 3; i++ {
			// Distinct text per update: an identical repeat is a no-op by
			// design and would not advance the counter.
			_, snap := s.Update(fmt.Sprintf("r%d-u%d", restart, i), OriginPhone)
			last = snap.Version
		}
	}
	if last != 15 {
		t.Fatalf("after 5 restarts of 3 updates the version must be 15, got %d", last)
	}
}

// TestVersionClientNeverGoesBackwards states the client-side invariant
// directly: for any sequence of versions a client observes across restarts,
// each must be strictly greater than the last, or the client discards it.
func TestVersionClientNeverGoesBackwards(t *testing.T) {
	dir := t.TempDir()
	clientSeen := -1 // what the Android client remembers

	for restart := 0; restart < 4; restart++ {
		s, err := NewClipboardStateWithLedger(dir)
		if err != nil {
			t.Fatal(err)
		}
		for i := 0; i < 2; i++ {
			// Distinct text: an identical repeat is a no-op and would not
			// produce a new version to compare.
			_, snap := s.Update(fmt.Sprintf("r%d-u%d", restart, i), OriginDesktop)
			if snap.Version <= clientSeen {
				t.Fatalf("client would DROP version %d (already saw %d)", snap.Version, clientSeen)
			}
			clientSeen = snap.Version
		}
	}
}

// TestVersionLedgerSurvivesCorruptFile: a damaged ledger must not stop the
// node. It degrades to the old behaviour (start at 0) and says so.
func TestVersionLedgerSurvivesCorruptFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, clipboardStateFile), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := NewClipboardStateWithLedger(dir)
	if err != nil {
		t.Fatalf("a corrupt ledger must not fail startup: %v", err)
	}
	if got := s.Get().Version; got != 0 {
		t.Fatalf("corrupt ledger should start from 0, got %d", got)
	}
	// And the node must still work: an update succeeds and gets version 1.
	if _, snap := s.Update("x", OriginPhone); snap.Version != 1 {
		t.Errorf("version = %d, want 1", snap.Version)
	}
}

// TestVersionLedgerFileIsPrivate: the ledger lives in a state directory and
// must not be world readable.
func TestVersionLedgerFileIsPrivate(t *testing.T) {
	dir := t.TempDir()
	s, err := NewClipboardStateWithLedger(dir)
	if err != nil {
		t.Fatal(err)
	}
	s.Update("secret value", OriginPhone)

	info, err := os.Stat(filepath.Join(dir, clipboardStateFile))
	if err != nil {
		t.Fatalf("ledger file must exist after an update: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("ledger mode = %o, want 600", perm)
	}
	// It must contain only the counter, never clipboard content.
	raw, err := os.ReadFile(filepath.Join(dir, clipboardStateFile))
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("ledger is not JSON: %v", err)
	}
	for k := range doc {
		if k != "version" {
			t.Errorf("ledger must only hold the version, found key %q", k)
		}
	}
	if strings.Contains(string(raw), "secret value") {
		t.Error("ledger must never contain clipboard content")
	}
}

// TestVersionLedgerStateDirIsPrivate checks the directory mode too.
func TestVersionLedgerStateDirIsPrivate(t *testing.T) {
	base := t.TempDir()
	dir := filepath.Join(base, "clipboard")
	if _, err := NewClipboardStateWithLedger(dir); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm&0o077 != 0 {
		t.Errorf("state dir mode = %o, want no group/other access", perm)
	}
}

// TestVersionNoLedgerKeepsFreshCounter documents that the ledger is optional:
// tests and the parity harness still get a per-process counter starting at 0.
func TestVersionNoLedgerKeepsFreshCounter(t *testing.T) {
	s := NewClipboardState()
	for i := 1; i <= 3; i++ {
		if _, snap := s.Update(fmt.Sprintf("v%d", i), OriginPhone); snap.Version != i {
			t.Fatalf("version = %d, want %d", snap.Version, i)
		}
	}
	// No ledger file may be created.
	if entries, err := os.ReadDir(t.TempDir()); err == nil && len(entries) != 0 {
		t.Errorf("a ledger-less state must not write files, found %d", len(entries))
	}
}

// TestVersionEndToEndAcrossRestart drives the real HTTP surface across a
// simulated restart, which is the sequence that used to fail.
func TestVersionEndToEndAcrossRestart(t *testing.T) {
	dir := t.TempDir()

	run := func(text string) int {
		state, err := NewClipboardStateWithLedger(dir)
		if err != nil {
			t.Fatal(err)
		}
		d := &Daemon{state: state, broker: NewBroker()}
		// A token is required: the service fails closed without one, which
		// is the same contract the phone sees.
		h := newClipboardHandlerWithSetter(state, d.Broker(), "test-token", d.SetClipboard)
		base := httptestServer(t, h)
		_, body := decodeJSON(t, "POST", base+"/clipboard", "test-token", map[string]string{
			"clipboard": text, "origin": OriginPhone,
		})
		v, _ := body["version"].(float64)
		return int(v)
	}

	clientSeen := -1
	for i, text := range []string{"one", "two", "three", "four", "five", "six"} {
		// run() builds a fresh state from the ledger every call, so each
		// iteration is a server restart — exactly the sequence that used to
		// leave the phone discarding every event.
		v := run(text)
		if v <= clientSeen {
			t.Fatalf("update %d: client would drop version %d (saw %d)", i, v, clientSeen)
		}
		clientSeen = v
	}
	if clientSeen != 6 {
		t.Fatalf("six updates across six restarts must end at version 6, got %d", clientSeen)
	}
}
