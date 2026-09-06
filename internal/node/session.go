package node

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sync"
	"time"
)

// sessionCookieName is the browser session cookie. It holds only a
// random session identifier — never the shared auth token.
const sessionCookieName = "eq_session"

// errSessionsNotConfigured is returned by Create when no login secret
// exists; sessions can never be established in that state.
var errSessionsNotConfigured = errors.New("sessions not configured")

// DefaultSessionTTL is how long a browser session lives before the
// user must log in again. Defined centrally here so handlers never
// scatter magic durations. Several hours is reasonable for a private
// infrastructure dashboard.
const DefaultSessionTTL = 12 * time.Hour

// SessionStore is an in-memory map of session ID -> expiry. It stores
// only random identifiers and their lifetime, never user data or the
// auth token. Sessions vanish on process restart (acceptable: the user
// logs in again). No database.
//
// configured records whether a login secret exists at all: with no
// token configured, sessions can never be created and the browser
// boundary fails closed instead of showing a login page. Safe for
// concurrent use.
type SessionStore struct {
	mu         sync.Mutex
	sessions   map[string]time.Time
	ttl        time.Duration
	configured bool
	now        func() time.Time
}

// NewSessionStore returns an empty store with the given session
// lifetime. now may be nil to use time.Now (tests inject a clock).
// configured reports whether a login secret is configured; when false,
// Create always fails and the browser boundary fails closed.
func NewSessionStore(ttl time.Duration, now func() time.Time, configured bool) *SessionStore {
	if now == nil {
		now = time.Now
	}
	return &SessionStore{
		sessions:   make(map[string]time.Time),
		ttl:        ttl,
		configured: configured,
		now:        now,
	}
}

// Configured reports whether login is possible at all (a token is
// configured). It mirrors the fail-closed policy of the Bearer
// middleware: no secret means no login, for anyone, ever.
func (s *SessionStore) Configured() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.configured
}

// Create generates a cryptographically random session identifier,
// records it with an expiry, and returns it. It fails when no token is
// configured (no secret to log in against) or when randomness is
// unavailable. Expired sessions are pruned during each create so the
// store never grows unbounded.
func (s *SessionStore) Create() (string, error) {
	if !s.Configured() {
		return "", errSessionsNotConfigured
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	id := hex.EncodeToString(buf)

	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneLocked()
	s.sessions[id] = s.now().Add(s.ttl)
	return id, nil
}

// Valid reports whether id is a live, unexpired session. Expired
// sessions are removed lazily here.
func (s *SessionStore) Valid(id string) bool {
	if id == "" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	exp, ok := s.sessions[id]
	if !ok {
		return false
	}
	if s.now().After(exp) {
		delete(s.sessions, id)
		return false
	}
	return true
}

// Delete invalidates a session by id. It is idempotent.
func (s *SessionStore) Delete(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.sessions, id)
}

// Count returns the number of live sessions (for tests/logging).
func (s *SessionStore) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.sessions)
}

// pruneLocked removes expired sessions. Caller holds s.mu.
func (s *SessionStore) pruneLocked() {
	now := s.now()
	for id, exp := range s.sessions {
		if now.After(exp) {
			delete(s.sessions, id)
		}
	}
}
