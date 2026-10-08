package internet

import (
	"context"
	"testing"
	"time"
)

func TestPollerLifecycle(t *testing.T) {
	mod := testModule(t, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p := NewPoller(ctx, mod, WithPollerTick(10*time.Millisecond))
	if p.Running() {
		t.Fatal("poller must not run before Start")
	}
	if err := p.Start(); err != nil {
		t.Fatal(err)
	}
	if !p.Running() {
		t.Fatal("poller must run after Start")
	}
	// Idempotent while running.
	if err := p.Start(); err != nil {
		t.Fatal(err)
	}
	p.Stop()
	p.Wait()
	if p.Running() {
		t.Fatal("poller must not run after Stop+Wait")
	}
	if err := p.Start(); err != nil {
		t.Fatalf("restart: %v", err)
	}
	cancel()
	p.Wait()
	if p.Running() {
		t.Fatal("shut down poller must report stopped")
	}
	if err := p.Start(); err == nil {
		t.Fatal("start after parent cancellation must fail")
	}
}

func TestPollerHonoursPerSourceInterval(t *testing.T) {
	srv := newTestServer(t, "body\n")
	var now = time.Unix(1_700_000_000, 0).UTC()
	mod, err := NewModule(Config{StateDir: t.TempDir()}, WithClock(func() time.Time { return now }))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := mod.Add(Source{ID: "fast", Type: TypeHTTP, URL: srv.srv.URL, Enabled: true, Interval: Duration(30 * time.Second)}); err != nil {
		t.Fatal(err)
	}
	if _, err := mod.Add(Source{ID: "slow", Type: TypeHTTP, URL: srv.srv.URL, Enabled: true, Interval: Duration(time.Hour)}); err != nil {
		t.Fatal(err)
	}

	p := NewPoller(context.Background(), mod, WithPollerTick(time.Millisecond))

	// First pass at t=0: both are due (never observed).
	if n := p.Poll(context.Background()); n != 2 {
		t.Fatalf("first pass checked %d, want 2", n)
	}
	// One second later: nothing is due again.
	now = now.Add(time.Second)
	if n := p.Poll(context.Background()); n != 0 {
		t.Fatalf("second pass checked %d, want 0", n)
	}
	// After 30 more seconds: only the fast source is due.
	now = now.Add(30 * time.Second)
	if n := p.Poll(context.Background()); n != 1 {
		t.Fatalf("third pass checked %d, want 1", n)
	}
	if rec, _ := mod.st().Get("slow"); rec.Observation.Checks != 1 {
		t.Fatalf("slow source checked %d times, want 1", rec.Observation.Checks)
	}
}

func TestPollerPicksUpCLISourcesOnRestart(t *testing.T) {
	srv := newTestServer(t, "body\n")
	dir := t.TempDir()
	mod, err := NewModule(Config{StateDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	p := NewPoller(context.Background(), mod, WithPollerTick(10*time.Millisecond))
	if err := p.Start(); err != nil {
		t.Fatal(err)
	}
	p.Stop()
	p.Wait()

	// A sibling process (the CLI) adds a source while the poller is
	// stopped. The next Start must pick it up.
	cliMod, err := NewModule(Config{StateDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := cliMod.Add(Source{ID: "late", Type: TypeHTTP, URL: srv.srv.URL, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if err := p.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { p.Stop(); p.Wait() }()
	found := false
	for _, src := range mod.Sources() {
		if src.ID == "late" {
			found = true
		}
	}
	if !found {
		t.Fatal("poller did not pick up the CLI-added source on restart")
	}
}

func TestPollerSnapshot(t *testing.T) {
	mod := testModule(t, nil)
	p := NewPoller(context.Background(), mod, WithPollerTick(time.Hour))
	if s := p.Snapshot(); s.Running || !s.Enabled || s.DefaultInterval != Duration(DefaultInterval) {
		t.Fatalf("idle snapshot = %+v", s)
	}
	if err := p.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { p.Stop(); p.Wait() }()
	// The loop polls immediately on start; allow the goroutine a moment.
	s := p.Snapshot()
	deadline := time.Now().Add(2 * time.Second)
	for s.Running && s.LastPoll.IsZero() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
		s = p.Snapshot()
	}
	if !s.Running || s.LastPoll.IsZero() {
		t.Fatalf("running snapshot = %+v", s)
	}
	if msg := p.Message(); msg == "" {
		t.Fatal("empty poller message")
	}
}
