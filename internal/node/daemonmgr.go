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
	"log"
	"os"
	"os/exec"
	"sync"
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
type DaemonManager struct {
	cfg      PythonDaemonConfig
	cmd      *exec.Cmd
	mu       sync.Mutex
	wg       sync.WaitGroup
	stop     chan struct{}
	stopOnce sync.Once
	started  bool
}

// NewDaemonManager prepares a manager for the given config. Call Start
// to launch the child and BeginRestartLoop to supervise it.
func NewDaemonManager(cfg PythonDaemonConfig) *DaemonManager {
	return &DaemonManager{cfg: cfg, stop: make(chan struct{})}
}

// command builds the exec.Cmd that runs the Python daemon with the
// environment the daemon expects.
func (m *DaemonManager) command() *exec.Cmd {
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

// Start launches the Python daemon. If it is already running it is a
// no-op. Errors return a non-nil error.
func (m *DaemonManager) Start() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.cmd != nil && m.cmd.Process != nil {
		return nil // already running
	}
	m.cmd = m.command()
	if err := m.cmd.Start(); err != nil {
		return err
	}
	log.Printf("earthquack: started python daemon (pid %d)", m.cmd.Process.Pid)
	return nil
}

// BeginRestartLoop supervises the child until ctx is done or Stop is
// called: if the daemon exits, it is restarted. Runs in its own
// goroutine; safe to call once.
func (m *DaemonManager) BeginRestartLoop(ctx context.Context) {
	m.mu.Lock()
	if m.started {
		m.mu.Unlock()
		return
	}
	m.started = true
	m.mu.Unlock()

	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		for {
			m.mu.Lock()
			cmd := m.cmd
			m.mu.Unlock()
			if cmd == nil || cmd.Process == nil {
				if err := m.Start(); err != nil {
					log.Printf("earthquack: daemon start failed: %v", err)
				}
				cmd = m.cmd
			}
			err := cmd.Wait()
			select {
			case <-ctx.Done():
				log.Printf("earthquack: context done — not restarting daemon")
				return
			case <-m.stop:
				log.Printf("earthquack: stop requested — not restarting daemon")
				return
			default:
			}
			log.Printf("earthquack: python daemon exited (%v) — restarting", err)
			m.mu.Lock()
			m.cmd = nil
			m.mu.Unlock()
		}
	}()
}

// Stop gracefully terminates the child daemon and waits for the
// supervision loop to finish. It is safe to call multiple times.
func (m *DaemonManager) Stop() {
	m.mu.Lock()
	if !m.started {
		m.mu.Unlock()
		return
	}
	// Use a one-shot sync.Once so close(m.stop) runs at most once.
	m.stopOnce.Do(func() {
		close(m.stop)
	})
	cmd := m.cmd
	m.mu.Unlock()

	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}
	m.wg.Wait()
}
