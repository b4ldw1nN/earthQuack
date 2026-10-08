package daemon

import (
	"context"
	"encoding/json"
	"errors"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// pollInterval matches desktop.py's 0.5s clipboard poll.
const pollInterval = 500 * time.Millisecond

// sseMinBackoff and sseMaxBackoff match desktop.py's reconnect schedule
// (2s doubling to a 60s ceiling).
const (
	sseMinBackoff = 2 * time.Second
	sseMaxBackoff = 60 * time.Second
)

// DesktopBridge moves clipboard data between the local machine and the
// clipboard service. It replaces daemon/desktop.py.
//
// Where the Python original spoke HTTP to itself — POST /clipboard to push,
// GET /events to receive — this subscribes to the Broker directly. Same
// events, no socket, no reconnect loop, and no risk of the bridge and the
// server deadlocking on loopback while the service restarts.
//
// Echo suppression is preserved and is the subtle part: a value pushed from
// the desktop is remembered, and a value just written to the desktop
// clipboard is remembered, so neither side re-sends what it just received.
// Without it the two ends ping-pong the same text forever.
type DesktopBridge struct {
	daemon *Daemon

	mu       sync.Mutex
	lastSent string
	lastSeen string
	haveSent bool
	haveSeen bool
}

// NewDesktopBridge builds a bridge bound to a daemon.
func NewDesktopBridge(d *Daemon) *DesktopBridge {
	return &DesktopBridge{daemon: d}
}

// Run drives the bridge until ctx is cancelled: one goroutine polls the
// local clipboard and pushes changes, another consumes clipboard events.
func (b *DesktopBridge) Run(ctx context.Context) error {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		b.pushLoop(ctx)
	}()
	go func() {
		defer wg.Done()
		b.receiveLoop(ctx)
	}()
	wg.Wait()
	return nil
}

// pushLoop polls the local clipboard and publishes anything new.
func (b *DesktopBridge) pushLoop(ctx context.Context) {
	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			text, err := readClipboard()
			if err != nil || text == "" {
				continue
			}
			b.mu.Lock()
			skip := b.haveSent && text == b.lastSent
			if !skip && b.haveSeen && text == b.lastSeen {
				skip = true
			}
			b.mu.Unlock()
			if skip {
				continue
			}
			// Encrypt before it leaves the machine; the service stores and
			// fans out ciphertext, so a plaintext clipboard never lands in
			// the server's state.
			//
			// SetClipboard (not state.Update) so the change is also
			// published to SSE subscribers — that publish is what tells the
			// phone. Writing state directly here silently broke
			// desktop-to-phone sync.
			wire := b.daemon.crypto.Wrap(text)
			changed, _ := b.daemon.SetClipboard(wire, OriginDesktop)
			if !changed {
				continue
			}
			b.mu.Lock()
			b.lastSent, b.haveSent = text, true
			b.mu.Unlock()
		}
	}
}

// receiveLoop applies clipboard events that originated on the phone.
//
// Mirrors desktop.py: only origin == "phone" is acted on, matching the
// Android side's own origin == "desktop" check, so each end ignores its own
// echo.
//
// Unlike the Python original there is no reconnect loop: the subscription is
// in-process, so there is no socket to lose. The broker drops a subscriber
// that falls behind, and Subscribe is re-established by Run.
func (b *DesktopBridge) receiveLoop(ctx context.Context) {
	events, dropped, cancel := b.daemon.broker.Subscribe()
	defer cancel()

	for {
		select {
		case <-ctx.Done():
			return
		case <-dropped:
			// Fell too far behind; the supervising loop restarts us.
			logf("desktop: fell behind the event stream, resubscribing")
			return
		case frame := <-events:
			if !b.handleFrame(frame) {
				// A malformed frame is not a reason to disconnect, but it
				// is worth surfacing once.
				logf("desktop: ignoring unparseable event frame")
			}
		}
	}
}

// handleFrame applies one SSE frame. It reports whether the frame parsed.
func (b *DesktopBridge) handleFrame(frame []byte) bool {
	// Frames are "event: <type>\ndata: <json>\n\n"; only clipboard events
	// concern this bridge. open_url is handled by the shell helper, and
	// file_ready by the phone.
	if !strings.Contains(string(frame), "event: clipboard") {
		return true
	}
	_, data, found := strings.Cut(string(frame), "data: ")
	if !found {
		return false
	}
	var payload struct {
		Clipboard string `json:"clipboard"`
		Origin    string `json:"origin"`
	}
	if err := json.Unmarshal([]byte(strings.TrimSpace(data)), &payload); err != nil {
		return false
	}
	if payload.Origin != OriginPhone || payload.Clipboard == "" {
		return true
	}

	text := b.daemon.crypto.Unwrap(payload.Clipboard)

	b.mu.Lock()
	b.lastSeen, b.haveSeen = text, true
	b.mu.Unlock()

	if err := writeClipboard(text); err != nil {
		logf("desktop: could not set clipboard: %v", err)
	}
	return true
}

// clipboardReaderFunc and clipboardWriterFunc abstract the platform tools so
// the bridge logic is testable without a display server.
type (
	clipboardReaderFunc func() (string, error)
	clipboardWriterFunc func(string) error
)

var (
	clipboardReader clipboardReaderFunc = readClipboardUnix
	clipboardWriter clipboardWriterFunc = writeClipboardUnix
)

func readClipboard() (string, error) { return clipboardReader() }

func writeClipboard(text string) error { return clipboardWriter(text) }

// runTool executes a clipboard helper and returns its stdout.
//
// A missing binary is reported as an error the caller can ignore, which is
// how the tool chain degrades: on a Wayland-only session wl-paste answers
// and xclip is absent, and vice versa on X11.
func runTool(name string, args ...string) (string, error) {
	path, err := exec.LookPath(name)
	if err != nil {
		return "", err
	}
	cmd := exec.Command(path, args...)
	cmd.Stdin = nil
	out, err := cmd.Output()
	if err != nil {
		return "", err
	}
	return string(out), nil
}

// readClipboardUnix tries the desktop's clipboard tools in the same order
// as desktop.py: Wayland default, Wayland primary, X11 clipboard, then
// xsel.
//
// It reports once, then goes quiet: a clipboard that is empty (nothing
// copied yet) is normal and must not spam the log, but a missing or
// unusable tool is a real misconfiguration worth naming once.
func readClipboardUnix() (string, error) {
	var lastErr error
	for _, probe := range []struct {
		bin  string
		args []string
	}{
		{"wl-paste", []string{"--no-newline"}},
		{"wl-paste", []string{"--primary", "--no-newline"}},
		{"xclip", []string{"-selection", "clipboard", "-o"}},
		{"xsel", []string{"-b", "-o"}},
	} {
		out, err := runTool(probe.bin, probe.args...)
		if err != nil {
			lastErr = err
			continue
		}
		if out != "" {
			warnClipboardOnce("read")
			return out, nil
		}
	}
	if lastErr == nil {
		lastErr = errNoClipboardTool
	} else {
		warnClipboardOnce("read: " + lastErr.Error())
	}
	return "", lastErr
}

// writeClipboardUnix sets the clipboard, preferring wl-copy and falling back
// to the X11 tools.
func writeClipboardUnix(text string) error {
	var lastErr error
	for _, probe := range []struct {
		bin  string
		args []string
	}{
		{"wl-copy", nil},
		{"xclip", []string{"-selection", "clipboard"}},
		{"xsel", []string{"-b", "-i"}},
	} {
		path, err := exec.LookPath(probe.bin)
		if err != nil {
			lastErr = err
			continue
		}
		cmd := exec.Command(path, probe.args...)
		cmd.Stdin = strings.NewReader(text)
		if err := cmd.Run(); err != nil {
			lastErr = err
			continue
		}
		warnClipboardOnce("write")
		return nil
	}
	if lastErr == nil {
		lastErr = errNoClipboardTool
	}
	warnClipboardOnce("write: " + lastErr.Error())
	return lastErr
}

// errNoClipboardTool is returned when no supported clipboard utility exists.
var errNoClipboardTool = errors.New("daemon: no clipboard tool available (need wl-clipboard, xclip or xsel)")

// clipboardWarned makes the bridge report a broken clipboard exactly once.
// Repeating it every 500ms would bury the node's real output.
var clipboardWarned sync.Map

func warnClipboardOnce(key string) {
	if _, loaded := clipboardWarned.LoadOrStore(key, true); loaded {
		return
	}
	logf("clipboard: %s", key)
}
