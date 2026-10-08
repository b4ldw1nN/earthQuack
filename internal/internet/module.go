package internet

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"time"
)

// Module is the Internet Microscope: it owns the sources, the fetcher,
// the persistent state and the event sink. It performs no scheduling
// itself — checking is explicit (Check/CheckAll), and the Poller
// (poller.go) is the only thing that calls it periodically. That split
// is what makes the module testable without a clock or a network.
//
// The state pointer is atomic so Reload (a fresh load from disk,
// performed by the poller while stopped) can never race with a
// concurrent dashboard or API read.
type Module struct {
	cfg     Config
	state   atomic.Pointer[State]
	fetcher *Fetcher
	clock   func() time.Time
	sink    eventSink
}

// Option configures a Module.
type Option func(*Module)

// WithClock overrides the clock (tests, and deterministic timestamps).
func WithClock(f func() time.Time) Option {
	return func(m *Module) {
		if f != nil {
			m.clock = f
		}
	}
}

// WithEventSink reports observation transitions through fn. The sink is
// how the node bridges microscope events into its own event ring.
func WithEventSink(fn func(Event)) Option {
	return func(m *Module) { m.sink = fn }
}

// WithFetcher replaces the HTTP fetcher (tests inject a client, a short
// timeout or a small size limit).
func WithFetcher(f *Fetcher) Option {
	return func(m *Module) {
		if f != nil {
			m.fetcher = f
		}
	}
}

// NewModule builds the module over cfg and loads its persistent state.
// A malformed sources.json is an error: the module refuses to start
// rather than silently inventing an empty source set.
func NewModule(cfg Config, opts ...Option) (*Module, error) {
	cfg = cfg.WithDefaults()
	m := &Module{
		cfg:     cfg,
		fetcher: NewFetcher(nil, cfg.Timeout, cfg.MaxBytes, cfg.UserAgent),
		clock:   time.Now,
	}
	for _, opt := range opts {
		opt(m)
	}
	if err := m.Reload(); err != nil {
		return nil, err
	}
	return m, nil
}

// st returns the current state. Never nil after NewModule.
func (m *Module) st() *State { return m.state.Load() }

// Reload re-reads sources.json from disk, picking up sources added,
// removed, enabled or disabled by another process (the CLI).
//
// It must not be called while checks are in flight: the poller only
// calls it when transitioning from stopped to running, and a stopped
// poller has no in-flight work by construction.
func (m *Module) Reload() error {
	state, err := NewState(m.cfg.StateDir)
	if err != nil {
		return err
	}
	m.state.Store(state)
	return nil
}

// Config returns the resolved module configuration.
func (m *Module) Config() Config { return m.cfg }

// StateDir returns the module's state directory.
func (m *Module) StateDir() string { return m.st().Dir() }

// Sources returns the configured sources, sorted by id.
func (m *Module) Sources() []Source { return m.st().Sources() }

// Records returns the configured sources with their last observation.
func (m *Module) Records() []Record { return m.st().Records() }

// Add defines a new source and persists it.
func (m *Module) Add(src Source) (Source, error) { return m.st().Add(src) }

// Remove deletes a source and its history.
func (m *Module) Remove(id string) error { return m.st().Remove(id) }

// SetEnabled enables or disables a single source.
func (m *Module) SetEnabled(id string, enabled bool) error { return m.st().SetEnabled(id, enabled) }

// Result is the outcome of one source check.
type Result struct {
	// SourceID identifies the source.
	SourceID string
	// Name is the source display name.
	Name string
	// Status is the resulting state (new, changed, unchanged, error).
	Status Status
	// PreviousFingerprint and CurrentFingerprint describe the
	// transition.
	PreviousFingerprint string
	CurrentFingerprint  string
	// ObservedAt is when the check ran.
	ObservedAt time.Time
	// Error is the failure message for error results; nil otherwise.
	Error error
}

// Check observes one source immediately, whatever its enabled state: an
// operator asking for `internet check <source>` means it. Unknown ids
// are an error; everything else is reported in the Result.
func (m *Module) Check(ctx context.Context, id string) (Result, error) {
	rec, ok := m.st().Get(id)
	if !ok {
		return Result{}, fmt.Errorf("internet: unknown source %q", id)
	}
	return m.check(ctx, rec), nil
}

// CheckAll observes every enabled source, sequentially, in id order. One
// source at a time is deliberate: the microscope must never look like a
// crawler, and a slow source must not fan out into parallel load.
func (m *Module) CheckAll(ctx context.Context) []Result {
	records := m.st().Records()
	results := make([]Result, 0, len(records))
	for _, rec := range records {
		if !rec.Source.Enabled {
			continue
		}
		if err := ctx.Err(); err != nil {
			break
		}
		results = append(results, m.check(ctx, rec))
	}
	return results
}

// interrupted reports whether a failed check was cut short by the
// caller's context (node shutdown, stop request) rather than by the
// source itself.
func interrupted(ctx context.Context, err error) bool {
	return ctx.Err() != nil || errors.Is(err, context.Canceled)
}
