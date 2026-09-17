// Package node.
//
// daemonmgr.go supervises the earthQuack Python subprocess (app.py) that
// implements the clipboard (8875) and file-transfer (8876) services. The Go
// node is the single entry point: it starts this subprocess on startup,
// restarts it if it dies, and stops it on shutdown, so a single
// `go run ./cmd/earthquack-node` runs the whole earthQuack stack.
package node

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"sync"
	"time"
)

// PythonDaemonConfig carries everything needed to start the Python
// daemon subprocess. Host/ports mirror CONFIG.py; the AES key must match
// the android app so clipboard data decrypts to plaintext there.
type PythonDaemonConfig struct {
	RepoDir       string // directory containing daemon/app.py
	Host          string // bind / server host (Tailscale IP)
	ClipboardPort string
	FilePort      string
	AESKey        string // base64 32-byte key, or "" to disable AES
}

// DaemonManager owns and supervises the earthQuack Python daemon
// subprocess. It is safe for concurrent use.
// Stop pauses supervision; Start resumes it. Shutdown is terminal.
// Exactly one goroutine calls Wait for each child.
type DaemonManager struct {
	cfg            PythonDaemonConfig
	opMu           sync.Mutex
	mu             sync.Mutex
	cmd            *exec.Cmd
	done           chan struct{}
	enabled        bool
	closed         bool
	started        bool
	commandFactory func() *exec.Cmd // test seam, never supplied by HTTP clients
}

func NewDaemonManager(cfg PythonDaemonConfig) *DaemonManager {
	return &DaemonManager{cfg: cfg}
}

// command builds the exec.Cmd that runs the Python daemon with the
// environment the daemon expects.
func (m *DaemonManager) command() *exec.Cmd {
	if m.commandFactory != nil {
		return m.commandFactory()
	}
	cmd := exec.Command("python3", "app.py")
	cmd.Dir = m.cfg.RepoDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Env = append(os.Environ(),
		"EARTHQUACK_HOST="+m.cfg.Host,
		"EARTHQUACK_PORT="+m.cfg.ClipboardPort,
		"EARTHQUACK_FILE_PORT="+m.cfg.FilePort,
		"CLIPBOARD_SERVER_HOST="+m.cfg.Host,
		"CLIPBOARD_SERVER_PORT="+m.cfg.ClipboardPort,
		"CLIPBOARD_FILE_PORT="+m.cfg.FilePort,
	)
	if m.cfg.AESKey != "" {
		cmd.Env = append(cmd.Env, "CLIPBOARD_AES_KEY="+m.cfg.AESKey)
	}
	return cmd
}

func (m *DaemonManager) startLocked() error {
	if m.cmd != nil {
		return nil
	}
	cmd := m.command()
	if err := cmd.Start(); err != nil {
		return err
	}
	done := make(chan struct{})
	m.cmd, m.done = cmd, done
	log.Printf("earthquack: started python daemon (pid %d)", cmd.Process.Pid)
	go func() {
		err := cmd.Wait()
		m.mu.Lock()
		m.cmd = nil
		close(done)
		m.mu.Unlock()
		log.Printf("earthquack: python daemon exited (%v)", err)
	}()
	return nil
}

func (m *DaemonManager) Start() error {
	m.opMu.Lock()
	defer m.opMu.Unlock()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return fmt.Errorf("daemon manager is shut down")
	}
	m.enabled = true
	return m.startLocked()
}

// BeginRestartLoop retries crashes/start failures at a bounded rate.
func (m *DaemonManager) BeginRestartLoop(ctx context.Context) {
	m.mu.Lock()
	if m.started || m.closed {
		m.mu.Unlock()
		return
	}
	m.started = true
	m.mu.Unlock()
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				m.Shutdown()
				return
			case <-ticker.C:
				m.opMu.Lock()
				m.mu.Lock()
				if m.closed {
					m.mu.Unlock()
					m.opMu.Unlock()
					return
				}
				if m.enabled {
					if err := m.startLocked(); err != nil {
						log.Printf("earthquack: daemon start failed: %v", err)
					}
				}
				m.mu.Unlock()
				m.opMu.Unlock()
			}
		}
	}()
}

func (m *DaemonManager) stop(final bool) {
	m.opMu.Lock()
	defer m.opMu.Unlock()
	m.mu.Lock()
	m.enabled = false
	m.closed = m.closed || final
	cmd, done := m.cmd, m.done
	m.mu.Unlock()
	if cmd != nil {
		// Terminate only our own child, never an externally supplied PID.
		_ = cmd.Process.Kill()
		<-done
	}
}

func (m *DaemonManager) Stop()     { m.stop(false) }
func (m *DaemonManager) Shutdown() { m.stop(true) }

func (m *DaemonManager) Snapshot() ManagedState {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.cmd != nil {
		return ManagedState{Running: true, Message: "Daemon running; port health is shown in the service list."}
	}
	if m.enabled && !m.closed {
		return ManagedState{Message: "Not running; automatic restart pending."}
	}
	return ManagedState{Message: "Stopped; automatic restart paused."}
}
