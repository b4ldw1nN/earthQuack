package internet

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

// errStopped is returned by a lifecycle action on a parent context that
// has already been cancelled (process shutdown).
var errStopped = errors.New("internet: poller stopped")

// Poller owns the periodic observation loop. Its lifecycle follows the
// same contract as the node's ManagedJob: Start is idempotent, Stop
// requests cancellation without blocking the web server, Snapshot never
// blocks, and Wait is only used at shutdown.
//
// One goroutine runs the loop; there is deliberately no per-source
// goroutine, so sources coming and going can never leak a scheduler.
type Poller struct {
	module *Module
	parent context.Context
	tick   time.Duration
	now    func() time.Time

	mu       sync.Mutex
	cancel   context.CancelFunc
	done     chan struct{}
	lastPoll time.Time
	polls    int
}

// PollerOption configures a Poller.
type PollerOption func(*Poller)

// WithPollerTick overrides the tick period. The default is derived from
// the configured sources (see tickPeriod); tests use a short tick.
func WithPollerTick(d time.Duration) PollerOption {
	return func(p *Poller) {
		if d > 0 {
			p.tick = d
		}
	}
}

// NewPoller returns a poller for mod that runs until the parent context
// is cancelled. It does not start polling; call Start.
func NewPoller(parent context.Context, mod *Module, opts ...PollerOption) *Poller {
	if parent == nil {
		parent = context.Background()
	}
	p := &Poller{module: mod, parent: parent, now: func() time.Time { return mod.clock() }}
	for _, opt := range opts {
		opt(p)
	}
	return p
}

// Start begins polling. It is idempotent while running, and it re-reads
// the module state first, so sources managed by the CLI are picked up on
// every (re)start. Start fails on a cancelled parent context or an
// unreadable state file.
func (p *Poller) Start() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.parent.Err() != nil {
		return errStopped
	}
	if p.cancel != nil {
		return nil // already running, or still winding down
	}
	// Safe: no loop is active, so there are no in-flight checks.
	if err := p.module.Reload(); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(p.parent)
	done := make(chan struct{})
	p.cancel, p.done = cancel, done
	go func() {
		defer close(done)
		p.loop(ctx)
		p.mu.Lock()
		p.cancel = nil
		p.mu.Unlock()
	}()
	return nil
}

// Stop requests cancellation of the current loop. It never blocks.
func (p *Poller) Stop() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.cancel != nil {
		p.cancel()
	}
}

// Wait blocks until the current loop has exited. Shutdown only.
func (p *Poller) Wait() {
	p.mu.Lock()
	done := p.done
	p.mu.Unlock()
	if done != nil {
		<-done
	}
}

// Running reports whether the loop is active.
func (p *Poller) Running() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.cancel != nil
}

// loop polls once immediately, then on the tick until cancelled.
func (p *Poller) loop(ctx context.Context) {
	p.Poll(ctx)
	ticker := time.NewTicker(p.tickPeriod())
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.Poll(ctx)
		}
	}
}

// tickPeriod is how often due sources are evaluated: the shortest
// configured interval, never below MinInterval, so a short interval is
// honoured without one timer per source. Sources added later are picked
// up at the next Start (which reloads state).
func (p *Poller) tickPeriod() time.Duration {
	if p.tick > 0 {
		return p.tick
	}
	shortest := time.Duration(0)
	for _, rec := range p.module.Records() {
		if !rec.Source.Enabled {
			continue
		}
		iv := time.Duration(rec.Source.EffectiveInterval(p.module.cfg.Interval))
		if shortest == 0 || iv < shortest {
			shortest = iv
		}
	}
	if shortest == 0 {
		shortest = time.Duration(p.module.cfg.Interval)
	}
	if shortest < time.Duration(MinInterval) {
		return time.Duration(MinInterval)
	}
	return shortest
}

// Poll performs one pass over the sources that are due and reports how
// many were checked. Exported so tests and tooling can trigger a pass;
// the loop uses it too.
func (p *Poller) Poll(ctx context.Context) int {
	now := p.now()
	def := p.module.cfg.Interval
	checked := 0
	for _, rec := range p.module.Records() {
		if !rec.Source.Enabled {
			continue
		}
		if !rec.Observation.Due(rec.Source.EffectiveInterval(def), now) {
			continue
		}
		if ctx.Err() != nil {
			break
		}
		p.module.check(ctx, rec)
		checked++
	}
	p.mu.Lock()
	p.lastPoll = now
	p.polls++
	p.mu.Unlock()
	return checked
}

// Snapshot returns the module snapshot with live runtime state filled
// in: whether polling is running and when the last pass ran.
func (p *Poller) Snapshot() Snapshot {
	snap := p.module.Snapshot()
	p.mu.Lock()
	snap.Running = p.cancel != nil
	snap.LastPoll = p.lastPoll
	p.mu.Unlock()
	return snap
}

// Message is the one-line operator-facing status used by the node's
// managed-service panel.
func (p *Poller) Message() string {
	snap := p.module.Snapshot()
	p.mu.Lock()
	running, last := p.cancel != nil, p.lastPoll
	p.mu.Unlock()
	state := "Paused."
	if running {
		state = "Polling."
	}
	when := "never"
	if !last.IsZero() {
		when = last.Format(time.RFC3339)
	}
	return fmt.Sprintf("%s %d sources (%d active, %d error). Last pass: %s. Interval: %s.",
		state, snap.Summary.Sources, snap.Summary.Enabled, snap.Summary.Errors, when, p.module.cfg.Interval)
}
