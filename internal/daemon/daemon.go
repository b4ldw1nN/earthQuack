package daemon

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// logPrintf is indirected so tests can capture daemon output.
var logPrintf = log.Printf

// envLookup is indirected for the same reason.
var envLookup = os.Getenv

// Daemon runs the earthQuack sync services in-process.
//
// This is the Go replacement for the Python subprocess that
// internal/node/daemonmgr.go supervises. Three consequences of moving the
// work into the same process:
//
//   - No child process to orphan, restart or reap. The node's supervisor
//     exists only because the services were Python; with them in-process a
//     crash is the node's crash, and systemd (or the Go process itself)
//     handles recovery.
//   - No os.Environ() inheritance, so the services stop receiving every
//     secret the node happens to hold.
//   - The file service signals the phone through a shared Broker instead of
//     an HTTP round trip to loopback.
type Daemon struct {
	cfg    Config
	state  *ClipboardState
	broker *Broker
	crypto *Crypto
	files  *fileServer

	mu      sync.Mutex
	started bool
	closed  bool
	servers []*http.Server
	cancel  context.CancelFunc
	wg      sync.WaitGroup
}

// New builds a Daemon from cfg, validating everything that can fail before
// any port is bound.
//
// Empty directories are defaulted here rather than only in ConfigFromEnv, so
// a caller that assembles a Config by hand — the node does — gets the same
// behaviour as one built from the environment.
func New(cfg Config) (*Daemon, error) {
	cfg = cfg.withDefaults()

	crypto, err := NewCrypto(cfg.AESKey)
	if err != nil {
		return nil, err
	}
	// A persistent version ledger: the Android client drops any event whose
	// version is not higher than the last one it saw, so a counter that reset
	// on every start would wedge it after the first restart.
	state, err := NewClipboardStateWithLedger(cfg.clipboardStateDir())
	if err != nil {
		// Losing the ledger must not stop the daemon; it only risks the
		// wedge described above. Clipboard sync still works.
		logf("clipboard: version ledger unavailable (%v); version restarts at 0", err)
		state = NewClipboardState()
	}
	if v := state.Get().Version; v > 0 {
		logf("clipboard: resuming version counter at %d", v)
	}
	broker := NewBroker()

	d := &Daemon{cfg: cfg, state: state, broker: broker, crypto: crypto}

	files, err := newFileServer(fileServerConfig{
		StageDir:  cfg.StageDir,
		PhoneSave: cfg.PhoneSaveDir,
		Signal:    func(eventType string, data any) { _ = broker.Publish(eventType, data) },
	})
	if err != nil {
		return nil, err
	}
	d.files = files
	return d, nil
}

// withDefaults fills in the values an operator may legitimately leave unset.
// Directories get real, documented locations; nothing is written to disk
// here, only resolved.
func (c Config) withDefaults() Config {
	if c.Host == "" {
		c.Host = "127.0.0.1"
	}
	if c.ClipboardPort == 0 {
		c.ClipboardPort = DefaultClipboardPort
	}
	if c.FilePort == 0 {
		c.FilePort = DefaultFilePort
	}
	if c.StageDir == "" {
		c.StageDir = DefaultStageDir
	}
	if c.PhoneSaveDir == "" {
		if home, err := os.UserHomeDir(); err == nil {
			c.PhoneSaveDir = filepath.Join(home, "Downloads", "from-phone")
		}
	}
	if c.SendFolder == "" {
		if home, err := os.UserHomeDir(); err == nil {
			c.SendFolder = filepath.Join(home, "send-to-phone")
		}
	}
	return c
}

// Config returns the resolved configuration.
func (d *Daemon) Config() Config { return d.cfg }

// State exposes the clipboard state, so a caller in the same process (the
// desktop bridge, tests) does not need to go over HTTP.
func (d *Daemon) State() *ClipboardState { return d.state }

// Broker exposes the event broker.
func (d *Daemon) Broker() *Broker { return d.broker }

// SetClipboard is the single write path for clipboard state: it stores the
// value and, only if it actually changed, publishes the resulting snapshot to
// every SSE subscriber.
//
// Both writers must go through here — the HTTP handler serving the phone,
// and the desktop bridge pushing local changes. They previously had separate
// implementations, and the bridge's version updated state without publishing,
// so a clipboard change typed on the desktop was stored but never announced:
// the phone's event stream stayed silent and desktop-to-phone sync silently
// did nothing while every other signal looked healthy. Centralising it makes
// that class of divergence impossible.
func (d *Daemon) SetClipboard(clipboard, origin string) (bool, Snapshot) {
	changed, snap := d.state.Update(clipboard, origin)
	// Log every accepted update from either writer, here rather than in the
	// HTTP handler, so a bridge push is as visible as a phone post.
	//
	// Only the origin and version are recorded, never the text and never
	// the ciphertext: a clipboard can hold a password. The point is to make
	// an inbound client observable — a phone that cannot read its own
	// clipboard sends nothing at all, and without this that is
	// indistinguishable from a server that dropped the request.
	logf("clipboard: update origin=%s version=%d changed=%v", origin, snap.Version, changed)
	if changed {
		if err := d.broker.Publish("clipboard", snap); err != nil {
			// The state is already committed, so the write stands; report
			// success with the new version and let clients reconcile on
			// their next poll rather than rejecting an accepted update.
			logf("clipboard: publish: %v", err)
		}
	}
	return changed, snap
}

// Start binds both listeners and serves them.
//
// It is restartable: the node's dashboard exposes Start and Stop for the
// sync services, so a user pressing Stop and then Start must get working
// listeners back. Starting an already-running Daemon is a no-op.
//
// Binding happens synchronously so a port clash is reported to the caller
// instead of being logged and ignored — the Python daemon's supervisor
// would retry a dead ThreadingHTTPServer every five seconds forever without
// ever surfacing the cause.
func (d *Daemon) Start(parent context.Context) error {
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return errors.New("daemon: shut down")
	}
	if d.started {
		d.mu.Unlock()
		return nil
	}
	// A fresh child context per run so Stop can cancel this generation of
	// background work without poisoning the caller's context.
	ctx, cancel := context.WithCancel(parent)
	d.cancel = cancel
	d.started = true
	d.mu.Unlock()

	clipboardHandler := newClipboardHandlerWithSetter(d.state, d.broker, d.cfg.AuthToken, d.SetClipboard)
	fileHandler := newFileHandler(d.files, d.cfg.AuthToken)

	clipSrv, err := d.listenAndServe(ctx, "clipboard", d.cfg.ClipboardPort, clipboardHandler)
	if err != nil {
		cancel()
		d.markStopped()
		return err
	}
	if _, err := d.listenAndServe(ctx, "file-transfer", d.cfg.FilePort, fileHandler); err != nil {
		// Do not leave the first listener running if the second fails.
		_ = clipSrv.Close()
		cancel()
		d.markStopped()
		return err
	}

	logPrintf("earthquack: clipboard service on http://%s:%d (auth=%v, aes=%v)",
		d.cfg.Host, d.cfg.ClipboardPort, d.cfg.AuthToken != "", d.crypto.Enabled())
	logPrintf("earthquack: file-transfer service on http://%s:%d",
		d.cfg.Host, d.cfg.FilePort)
	logPrintf("earthquack: phone uploads land in %s", d.cfg.PhoneSaveDir)

	d.startBackground(ctx)
	return nil
}

func (d *Daemon) markStopped() {
	d.mu.Lock()
	d.started = false
	d.cancel = nil
	d.servers = nil
	d.mu.Unlock()
}

func (d *Daemon) listenAndServe(ctx context.Context, name string, port int, handler http.Handler) (*http.Server, error) {
	addr := net.JoinHostPort(d.cfg.Host, fmt.Sprint(port))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("daemon: %s: listen %s: %w", name, addr, err)
	}
	srv := &http.Server{
		Handler: handler,
		// A header timeout is the minimum defence against a Slowloris
		// connection pinning a goroutine forever. WriteTimeout is
		// deliberately unset: GET /events is a long-lived SSE stream and a
		// blanket write deadline would sever every subscriber on a timer.
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 16,
		BaseContext:       func(net.Listener) context.Context { return ctx },
	}
	d.servers = append(d.servers, srv)

	d.wg.Add(1)
	go func() {
		defer d.wg.Done()
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logPrintf("earthquack: %s stopped: %v", name, err)
		}
	}()
	return srv, nil
}

// startBackground launches the components that are not HTTP listeners:
// the desktop clipboard bridge, the send-folder watcher, and staged-file
// cleanup.
func (d *Daemon) startBackground(ctx context.Context) {
	if d.cfg.DesktopBridge {
		// Log explicitly. The bridge is silent by design, and when it
		// fails to start nothing else reports it — a node that looks
		// healthy while never syncing the desktop clipboard is exactly the
		// failure that is hard to notice.
		logPrintf("earthquack: desktop clipboard bridge enabled")
		bridge := NewDesktopBridge(d)
		d.spawn(ctx, "desktop-bridge", bridge.Run)
	} else {
		logPrintf("earthquack: desktop clipboard bridge disabled (EARTHQUACK_DESKTOP_BRIDGE=0)")
	}
	if d.cfg.SendFolder != "" {
		watcher := NewSendFolderWatcher(d.cfg.SendFolder, d.cfg.SendScript, d.cfg.AuthToken, d.cfg.Host, d.cfg.FilePort)
		d.spawn(ctx, "send-folder", watcher.Run)
	}
	if d.files != nil && d.cfg.StagedTTL > 0 {
		d.spawn(ctx, "stage-cleanup", func(ctx context.Context) error {
			return d.files.cleanupLoop(ctx, d.cfg.StagedTTL)
		})
	}
}

// spawn runs a supervised component: on return it logs and retries with
// backoff, so one failing component cannot take the daemon down and a
// permanently broken component cannot spin the log.
func (d *Daemon) spawn(ctx context.Context, name string, fn func(context.Context) error) {
	d.wg.Add(1)
	go func() {
		defer d.wg.Done()
		backoff := time.Second
		const maxBackoff = time.Minute
		for {
			err := fn(ctx)
			if ctx.Err() != nil {
				return
			}
			if err != nil {
				logPrintf("earthquack: %s failed: %v — retrying in %s", name, err, backoff)
			} else {
				logPrintf("earthquack: %s exited — restarting in %s", name, backoff)
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(backoff):
			}
			backoff *= 2
			if backoff > maxBackoff {
				backoff = maxBackoff
			}
		}
	}()
}

// cleanupLoop periodically removes stale staged files.
func (fs *fileServer) cleanupLoop(ctx context.Context, ttl time.Duration) error {
	interval := time.Hour
	if ttl < interval {
		interval = ttl
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if n := fs.cleanupStaged(ttl); n > 0 {
				logPrintf("earthquack: removed %d expired staged file(s)", n)
			}
		}
	}
}

// Stop shuts the listeners down and waits for background components.
//
// It is safe to call more than once, and a later Start brings the services
// back up.
func (d *Daemon) Stop() error {
	d.mu.Lock()
	if !d.started {
		d.mu.Unlock()
		return nil
	}
	servers := d.servers
	cancel := d.cancel
	d.started = false
	d.servers = nil
	d.cancel = nil
	d.mu.Unlock()

	// Cancel background work first so nothing new arrives while the
	// listeners drain.
	if cancel != nil {
		cancel()
	}

	// A generous shutdown window: the SSE handlers block until their
	// request context is cancelled, and cancelling the parent context is
	// what actually unblocks them.
	ctx, done := shutdownContext(10 * time.Second)
	defer done()

	var firstErr error
	for _, srv := range servers {
		if err := srv.Shutdown(ctx); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	d.wg.Wait()
	return firstErr
}

// Shutdown stops the daemon for good; a later Start fails.
func (d *Daemon) Shutdown() error {
	d.mu.Lock()
	d.closed = true
	d.mu.Unlock()
	return d.Stop()
}

// Running reports whether the listeners are up, for the node's managed
// service snapshot.
func (d *Daemon) Running() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.started
}
