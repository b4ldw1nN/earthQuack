package node

import (
	"context"
	"fmt"
	"log"
	"strings"
	"sync"

	"github.com/b4ldw1nN/earthquack/internal/daemon"
)

// daemon_go.go adapts the in-process Go sync services to the same managed
// service shape as the Python subprocess, so the node can run either one
// behind a single switch.
//
// Why both exist: the Python daemon in daemon/ is the incumbent and is still
// the default. The Go rewrite in internal/daemon is verified against it by
// differential tests (internal/daemon/parity_test.go). Switching is one
// environment variable, and reverting is setting it back and restarting —
// which is only possible because the rewrite never changes the wire
// protocol the Android app depends on.
//
// Setting EARTHQUACK_DAEMON_IMPL=go also retires four problems at once: the
// child process that could be orphaned (no Pdeathsig), the log line per
// second when the child could not start, the log.Fatalf paths that skipped
// cleanup, and the full os.Environ() handed to a process that had no use for
// the node's secrets.

// GoDaemonConfig mirrors PythonDaemonConfig so the node builds either
// implementation from the same inputs.
type GoDaemonConfig struct {
	Host          string
	ClipboardPort string
	FilePort      string
	AESKey        string
	AuthToken     string
	StageDir      string
	PhoneSaveDir  string
	SendFolder    string
	SendScript    string
	DesktopBridge bool
}

// GoDaemon runs the sync services inside the node process.
//
// It is safe for concurrent use: the dashboard's Start and Stop controls and
// the node's shutdown path all call in concurrently.
type GoDaemon struct {
	d *daemon.Daemon

	mu      sync.Mutex
	ctx     context.Context
	started bool
}

// NewGoDaemon builds the in-process daemon.
func NewGoDaemon(cfg GoDaemonConfig) (*GoDaemon, error) {
	// Ports arrive as strings because both implementations are configured
	// from the same environment variables; parsing here keeps the node's
	// call site identical for either one.
	clipPort, err := parsePort(cfg.ClipboardPort, "clipboard")
	if err != nil {
		return nil, err
	}
	filePort, err := parsePort(cfg.FilePort, "file transfer")
	if err != nil {
		return nil, err
	}
	d, err := daemon.New(daemon.Config{
		Host:          cfg.Host,
		ClipboardPort: clipPort,
		FilePort:      filePort,
		AuthToken:     cfg.AuthToken,
		AESKey:        cfg.AESKey,
		StageDir:      cfg.StageDir,
		PhoneSaveDir:  cfg.PhoneSaveDir,
		SendFolder:    cfg.SendFolder,
		SendScript:    cfg.SendScript,
		DesktopBridge: cfg.DesktopBridge,
	})
	if err != nil {
		return nil, fmt.Errorf("go sync daemon: %w", err)
	}
	return &GoDaemon{d: d}, nil
}

func parsePort(raw, what string) (int, error) {
	var n int
	if _, err := fmt.Sscanf(strings.TrimSpace(raw), "%d", &n); err != nil || n <= 0 || n > 65535 {
		return 0, fmt.Errorf("go sync daemon: invalid %s port %q", what, raw)
	}
	return n, nil
}

// Start binds the sync service listeners. It is a no-op when already
// running, so a double click on the dashboard's Start is harmless.
func (g *GoDaemon) Start() error {
	g.mu.Lock()
	if g.started {
		g.mu.Unlock()
		return nil
	}
	if g.ctx == nil {
		g.ctx = context.Background()
	}
	ctx := g.ctx
	g.mu.Unlock()

	if err := g.d.Start(ctx); err != nil {
		return err
	}
	g.mu.Lock()
	g.started = true
	g.mu.Unlock()
	return nil
}

// Stop closes the listeners. A later Start brings them back.
func (g *GoDaemon) Stop() {
	if err := g.d.Stop(); err != nil {
		log.Printf("earthquack: go sync daemon stop: %v", err)
	}
	g.mu.Lock()
	g.started = false
	g.mu.Unlock()
}

// Shutdown is terminal.
func (g *GoDaemon) Shutdown() {
	if err := g.d.Shutdown(); err != nil {
		log.Printf("earthquack: go sync daemon shutdown: %v", err)
	}
	g.mu.Lock()
	g.started = false
	g.mu.Unlock()
}

// Snapshot reports the state the dashboard shows for this service.
func (g *GoDaemon) Snapshot() ManagedState {
	if g.d.Running() {
		return ManagedState{Running: true, Message: "Sync services running in-process; port health is shown in the service list."}
	}
	g.mu.Lock()
	started := g.started
	g.mu.Unlock()
	if started {
		return ManagedState{Message: "Not running."}
	}
	return ManagedState{Message: "Stopped; start to bring the sync services up."}
}
