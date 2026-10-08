// Package daemon implements the earthQuack sync services in Go: the
// clipboard broker, the file-transfer service, and the desktop bridge.
//
// It is a Go rewrite of the Python daemon in daemon/, which it replaces
// without changing the wire protocol. The Android app (app/) already
// speaks this protocol, so every route, status code, header and JSON
// field name here is a compatibility contract, not a free choice.
//
// The one deliberate behaviour change is authentication: the Python
// services accepted any caller, while every route here except /health
// requires the shared bearer token.
package daemon

import "sync"

// Snapshot is the observable clipboard state. The JSON field names are the
// wire contract shared with the Android app (EarthQuackService.handleSseEvent
// reads "clipboard", "origin" and "version").
type Snapshot struct {
	Clipboard string `json:"clipboard"`
	Origin    string `json:"origin"`
	Version   int    `json:"version"`
}

// ClipboardState is the shared clipboard value plus a monotonic version.
//
// It reproduces daemon/core/state.py exactly, including the rule that an
// update changing neither the text nor the origin is a no-op: no version
// bump, and therefore no SSE event. That idempotence is what stops a
// desktop and a phone from echoing a value back and forth forever.
//
// The Python original guarded this with a threading.Lock; the Go original
// had no equivalent (internal/wallpaper.State), which is the kind of gap
// this rewrite closes rather than carries over.
type ClipboardState struct {
	mu        sync.Mutex
	clipboard string
	origin    string
	version   int
	// ledger persists the version across restarts. It is nil in tests and in
	// the parity harness, where a fresh counter per process is what we want.
	ledger *versionLedger
}

// NewClipboardState returns an empty state at version 0, matching the
// Python constructor.
func NewClipboardState() *ClipboardState {
	return &ClipboardState{}
}

// NewClipboardStateWithLedger returns a state whose version continues from
// dir's ledger, so a restart does not rewind the counter.
//
// The Android client discards any event whose version is not greater than the
// highest it has already seen, so a counter that resets on every start wedges
// it permanently. See versionLedger for the full explanation.
func NewClipboardStateWithLedger(dir string) (*ClipboardState, error) {
	ledger, err := loadVersionLedger(dir)
	if err != nil {
		return nil, err
	}
	return &ClipboardState{version: ledger.current(), ledger: ledger}, nil
}

// Get returns a copy of the current state.
func (s *ClipboardState) Get() Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return Snapshot{Clipboard: s.clipboard, Origin: s.origin, Version: s.version}
}

// Update stores a new clipboard value and reports whether it actually
// changed. On no change it returns the existing state untouched and
// changed=false, so the caller can skip publishing an event.
func (s *ClipboardState) Update(clipboard, origin string) (bool, Snapshot) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if clipboard == s.clipboard && origin == s.origin {
		return false, Snapshot{Clipboard: s.clipboard, Origin: s.origin, Version: s.version}
	}
	s.clipboard = clipboard
	s.origin = origin
	s.version = s.nextVersion()
	s.flushLedger()
	return true, Snapshot{Clipboard: s.clipboard, Origin: s.origin, Version: s.version}
}

// nextVersion advances the counter, delegating to the ledger when one is
// attached. Callers must hold s.mu.
func (s *ClipboardState) nextVersion() int {
	if s.ledger == nil {
		s.version++
		return s.version
	}
	return s.ledger.next()
}

// flushLedger persists the version if a ledger is attached.
func (s *ClipboardState) flushLedger() {
	if s.ledger == nil {
		return
	}
	s.ledger.flush()
}
