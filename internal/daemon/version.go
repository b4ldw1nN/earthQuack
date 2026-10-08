package daemon

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// clipboardStateFile is the name of the version ledger inside the state dir.
const clipboardStateFile = "version.json"

// versionLedger remembers the clipboard version across restarts.
//
// Why this exists: the version is a monotonic event counter, and the Android
// client uses it to discard duplicate SSE events — SyncState.tryClaimRemoteEvent
// skips anything whose version is <= the highest it has already seen. A counter
// that resets to zero on every start therefore wedges the client permanently:
// after a node restart it sees versions 1, 2, 3 while still remembering 14, and
// silently drops every one of them. Nothing recovers except restarting the app.
//
// Persisting the counter keeps the counter monotonic across restarts, which is
// the property the client assumes. A fresh install still starts at 0 so the
// first update is version 1, exactly as before.
type versionLedger struct {
	path string

	mu      sync.Mutex
	version int
	dirty   bool
}

// loadVersionLedger reads the persisted version, returning a ledger seeded
// from it. A missing file is not an error: it means a fresh start at 0.
//
// A corrupt or unreadable file is also not fatal. It is reported once and the
// counter restarts from 0, which is precisely the old behaviour — the node
// still starts and clipboard sync still works, it just risks the wedge until
// the client is restarted.
func loadVersionLedger(dir string) (*versionLedger, error) {
	if dir == "" {
		return &versionLedger{}, nil
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("daemon: clipboard state dir: %w", err)
	}
	l := &versionLedger{path: filepath.Join(dir, clipboardStateFile)}
	raw, err := os.ReadFile(l.path)
	if err != nil {
		if os.IsNotExist(err) {
			return l, nil
		}
		return l, fmt.Errorf("daemon: read %s: %w", l.path, err)
	}
	var doc struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		logf("clipboard: ignoring unreadable %s (%v); starting from version 0", l.path, err)
		return l, nil
	}
	if doc.Version > 0 {
		l.version = doc.Version
	}
	return l, nil
}

// next returns the next version to hand out. Callers must already hold the
// clipboard state lock, or be the sole writer.
func (l *versionLedger) next() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.version++
	l.dirty = true
	return l.version
}

// flush writes the counter if it moved. It is best effort: a failure must not
// fail a clipboard update, because the update itself already succeeded and is
// already published. The cost of not persisting is only that a future restart
// resumes from the last value this process happened to reach.
func (l *versionLedger) flush() {
	l.mu.Lock()
	if l.path == "" || !l.dirty {
		l.mu.Unlock()
		return
	}
	l.dirty = false
	version := l.version
	l.mu.Unlock()

	body, err := json.Marshal(struct {
		Version int `json:"version"`
	}{Version: version})
	if err != nil {
		return
	}
	// Atomic: a torn write here would reset the counter to garbage and wedge
	// the client, which is worse than not writing at all.
	tmp, err := os.CreateTemp(filepath.Dir(l.path), ".version-*.tmp")
	if err != nil {
		return
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if _, err := tmp.Write(body); err != nil {
		tmp.Close()
		return
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return
	}
	if err := tmp.Close(); err != nil {
		return
	}
	if err := os.Chmod(tmpName, 0o600); err != nil {
		return
	}
	if err := os.Rename(tmpName, l.path); err != nil {
		logf("clipboard: could not persist version: %v", err)
	}
}

// defaultClipboardStateDir is the XDG location for the version ledger.
func defaultClipboardStateDir() string {
	if dir := os.Getenv("EARTHQUACK_CLIPBOARD_STATE"); dir != "" {
		return dir
	}
	if dir := os.Getenv("XDG_STATE_HOME"); dir != "" {
		return filepath.Join(dir, "earthquack", "clipboard")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".local", "share", "earthquack", "clipboard")
}

// clipboardStateDir resolves where the version ledger lives, honouring an
// explicit Config value first and the XDG default otherwise.
func (c Config) clipboardStateDir() string {
	if c.ClipboardStateDir != "" {
		return c.ClipboardStateDir
	}
	// Persistence is opt-out for tests. A test that builds a Config without
	// a state dir gets an in-memory counter instead of silently reading and
	// writing the operator's real ledger, which would make assertions depend
	// on whatever a previous run left behind.
	if !versionLedgerEnabled() {
		return ""
	}
	return defaultClipboardStateDir()
}

// versionLedgerEnabled reports whether persistence is on. It exists so tests
// can run with an in-memory counter by leaving ClipboardStateDir empty AND
// setting this false, rather than by accidentally writing to the real
// ~/.local/share tree.
var versionLedgerEnabled = func() bool { return true }

// current returns the persisted version without advancing it.
func (l *versionLedger) current() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.version
}
