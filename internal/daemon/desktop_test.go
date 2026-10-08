package daemon

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeClipboard replaces the platform clipboard tools for the duration of a
// test, so bridge behaviour can be exercised without a display server.
type fakeClipboard struct {
	mu       sync.Mutex
	value    string
	written  []string
	readErr  error
	writeErr error
}

func (f *fakeClipboard) read() (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.readErr != nil {
		return "", f.readErr
	}
	return f.value, nil
}

func (f *fakeClipboard) write(text string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.writeErr != nil {
		return f.writeErr
	}
	f.written = append(f.written, text)
	f.value = text
	return nil
}

func (f *fakeClipboard) lastWritten() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.written) == 0 {
		return ""
	}
	return f.written[len(f.written)-1]
}

// writeCount reports how many times the clipboard was written. It takes the
// lock because the bridge writes from its own goroutine while the test reads.
func (f *fakeClipboard) writeCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.written)
}

// installFakeClipboard swaps in a fake for the duration of the test.
func installFakeClipboard(t *testing.T) *fakeClipboard {
	t.Helper()
	f := &fakeClipboard{}
	origReader, origWriter := clipboardReader, clipboardWriter
	clipboardReader, clipboardWriter = f.read, f.write
	t.Cleanup(func() {
		clipboardReader, clipboardWriter = origReader, origWriter
	})
	return f
}

// newBridgeTestDaemon builds a daemon with the bridge enabled and a short
// poll interval so tests do not have to wait 500ms per cycle.
func newBridgeTestDaemon(t *testing.T, clip *fakeClipboard) *Daemon {
	t.Helper()
	cfg := Config{
		Host:            "127.0.0.1",
		ClipboardPort:   freePort(t),
		FilePort:        freePort(t),
		AuthToken:       "bridge-token",
		StageDir:        t.TempDir(),
		PhoneSaveDir:    t.TempDir(),
		DesktopBridge:   true,
		DiscoverTimeout: time.Second,
		// Pin the version ledger to a temp dir. Without this a test would
		// read and write the real ~/.local/share state, so versions would
		// leak between tests and assert against whatever the last run left.
		ClipboardStateDir: t.TempDir(),
	}
	d, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := d.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = d.Shutdown() })
	return d
}

// TestDesktopBridgeDeliversPhoneClipboard is the regression test for the
// bug where main.go built GoDaemonConfig without setting DesktopBridge, so
// the bridge never started: the node looked healthy, the HTTP service
// worked, and the desktop clipboard simply never updated.
func TestDesktopBridgeDeliversPhoneClipboard(t *testing.T) {
	clip := installFakeClipboard(t)
	d := newBridgeTestDaemon(t, clip)

	// Give the bridge a moment to subscribe before publishing.
	time.Sleep(100 * time.Millisecond)

	// The phone posts through the same path the real service uses.
	changed, _ := d.State().Update(d.crypto.Wrap("text from the phone"), OriginPhone)
	if !changed {
		t.Fatal("state update should report a change")
	}
	if err := d.Broker().Publish("clipboard", d.State().Get()); err != nil {
		t.Fatal(err)
	}

	// The bridge must decrypt and write it to the local clipboard.
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if clip.lastWritten() == "text from the phone" {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("bridge did not write the phone clipboard after %d attempt(s)", clip.writeCount())
}

// TestDesktopBridgePushesLocalClipboard covers the other direction.
func TestDesktopBridgePushesLocalClipboard(t *testing.T) {
	clip := installFakeClipboard(t)
	clip.mu.Lock()
	clip.value = "typed on the desktop"
	clip.mu.Unlock()

	d := newBridgeTestDaemon(t, clip)

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		snap := d.State().Get()
		if snap.Origin == OriginDesktop {
			if got := d.crypto.Unwrap(snap.Clipboard); got != "typed on the desktop" {
				t.Fatalf("stored value decrypts to %q, want the typed text", got)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("bridge never pushed the local clipboard: %+v", d.State().Get())
}

// TestDesktopBridgeSuppressesEcho is the guard against the failure that
// makes a clipboard sync unusable: the two ends bounce the same value back
// and forth forever, burning CPU and spamming the event stream.
func TestDesktopBridgeSuppressesEcho(t *testing.T) {
	clip := installFakeClipboard(t)
	d := newBridgeTestDaemon(t, clip)
	time.Sleep(100 * time.Millisecond)

	// Seed the fake clipboard with the state the daemon already holds, as if
	// the bridge had just written it. The bridge must not re-push it.
	d.State().Update(d.crypto.Wrap("same value"), OriginPhone)
	if err := d.Broker().Publish("clipboard", d.State().Get()); err != nil {
		t.Fatal(err)
	}
	time.Sleep(300 * time.Millisecond) // let the bridge consume it

	before := d.State().Get().Version
	time.Sleep(2 * time.Second) // several poll cycles

	if after := d.State().Get().Version; after != before {
		t.Errorf("echo loop: version climbed from %d to %d with no user action", before, after)
	}
	if n := clip.writeCount(); n > 1 {
		t.Errorf("bridge wrote the clipboard %d times for one inbound value; echo suppression failed", n)
	}
}

// TestDesktopBridgeIgnoresOwnOrigin: the bridge must not act on events it
// originated, matching the Android side's own origin check. Without it the
// desktop would re-copy its own text forever.
func TestDesktopBridgeIgnoresOwnOrigin(t *testing.T) {
	clip := installFakeClipboard(t)
	d := newBridgeTestDaemon(t, clip)
	time.Sleep(100 * time.Millisecond)

	// A desktop-origin event must be ignored by the receiving side.
	if err := d.Broker().Publish("clipboard", Snapshot{
		Clipboard: d.crypto.Wrap("my own text"), Origin: OriginDesktop, Version: 1,
	}); err != nil {
		t.Fatal(err)
	}
	time.Sleep(400 * time.Millisecond)
	if got := clip.lastWritten(); got != "" {
		t.Errorf("bridge wrote %q from a desktop-origin event; it should ignore its own echo", got)
	}
}

// TestDesktopBridgePushPublishesToSubscribers is the regression test for the
// bug that made desktop-to-phone sync silently do nothing: the bridge called
// state.Update directly instead of going through the shared write path, so
// the value was stored but never published. The phone's SSE stream stayed
// silent, and because the HTTP path (which the parity tests cover) did
// publish, every other signal looked healthy.
//
// The assertion is deliberately on the *event*, not the stored state: state
// was already correct in the broken version.
func TestDesktopBridgePushPublishesToSubscribers(t *testing.T) {
	d := newBridgeTestDaemonWithClipboard(t, "text typed on the desktop")

	// Subscribe exactly as the phone does, before the push happens.
	events, _, cancel := d.Broker().Subscribe()
	defer cancel()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case frame := <-events:
			if !strings.Contains(string(frame), "event: clipboard") {
				continue
			}
			// The payload must carry the desktop origin and a version, which
			// is what the Android client checks before applying a value.
			var payload struct {
				Clipboard string `json:"clipboard"`
				Origin    string `json:"origin"`
				Version   int    `json:"version"`
			}
			data := extractData(string(frame))
			if err := json.Unmarshal([]byte(data), &payload); err != nil {
				t.Fatalf("published payload is not JSON: %v (%s)", err, data)
			}
			if payload.Origin != OriginDesktop {
				t.Errorf("published origin = %q, want desktop", payload.Origin)
			}
			if payload.Version < 1 {
				t.Errorf("published version = %d, want >= 1", payload.Version)
			}
			// The published value is ciphertext; a subscriber holding the
			// shared key must recover the typed text.
			if got := d.crypto.Unwrap(payload.Clipboard); got != "text typed on the desktop" {
				t.Errorf("published payload decrypts to %q", got)
			}
			return
		case <-time.After(50 * time.Millisecond):
		}
	}
	t.Fatal("the desktop bridge never published a clipboard event: the phone would never be told")
}

// extractData pulls the JSON payload out of an SSE frame.
func extractData(frame string) string {
	_, data, found := strings.Cut(frame, "data: ")
	if !found {
		return ""
	}
	return strings.TrimSpace(data)
}

// newBridgeTestDaemonWithClipboard builds a bridge daemon whose fake clipboard
// already holds the given text, so the push loop has something to publish.
func newBridgeTestDaemonWithClipboard(t *testing.T, text string) *Daemon {
	t.Helper()
	f := installFakeClipboard(t)
	f.mu.Lock()
	f.value = text
	f.mu.Unlock()
	return newBridgeTestDaemon(t, f)
}

// TestDesktopBridgeSurvivesBrokenClipboard: a machine with no clipboard tool
// must not spin or crash the bridge — the HTTP services must keep working, so
// the phone can still reach the node.
func TestDesktopBridgeSurvivesBrokenClipboard(t *testing.T) {
	clip := installFakeClipboard(t)
	clip.mu.Lock()
	clip.readErr = context.DeadlineExceeded
	clip.writeErr = context.DeadlineExceeded
	clip.mu.Unlock()

	d := newBridgeTestDaemon(t, clip)
	time.Sleep(100 * time.Millisecond)

	// Drive a real inbound update through the HTTP path the phone uses.
	base := baseURL(d.cfg.Host, d.cfg.ClipboardPort)
	raw, err := marshalJSON(map[string]string{
		"clipboard": "anything", "origin": OriginPhone,
	})
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(http.MethodPost, base+"/clipboard", strings.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer bridge-token")
	req.Header.Set("Content-Type", "application/json")
	if code := doStatus(t, req); code != http.StatusOK {
		t.Fatalf("POST /clipboard = %d, want 200 even with a broken clipboard", code)
	}
	time.Sleep(400 * time.Millisecond)

	// The daemon and its state must be unaffected by the clipboard failure.
	if !d.Running() {
		t.Fatal("daemon stopped because the clipboard is broken")
	}
	if got := d.State().Get().Version; got != 1 {
		t.Errorf("the accepted update must still be recorded, version = %d", got)
	}
	// And the service must still answer for the phone.
	if code := statusOnly(t, http.MethodGet, base+"/clipboard", "bridge-token", nil); code != http.StatusOK {
		t.Errorf("service must stay available, got %d", code)
	}
}

// TestDesktopBridgeWarnsOnce guards the log-volume property: a permanently
// broken clipboard reports once, not once per poll.
func TestDesktopBridgeWarnsOnce(t *testing.T) {
	clipboardWarned.Delete("read")
	defer clipboardWarned.Delete("read")

	var logged []string
	orig := logPrintf
	logPrintf = func(format string, args ...any) {
		logged = append(logged, format)
	}
	defer func() { logPrintf = orig }()

	installFakeClipboard(t)
	for i := 0; i < 50; i++ {
		warnClipboardOnce("read")
	}
	if len(logged) != 1 {
		t.Errorf("expected exactly 1 warning for 50 failures, got %d: %v", len(logged), logged)
	}
}

// TestConfigDesktopBridgeDefault documents that the bridge is part of the
// sync service, not an optional extra. main.go must set it explicitly; this
// guards the default for other callers.
func TestConfigDesktopBridgeDefault(t *testing.T) {
	empty := func(string) string { return "" }
	cfg, err := ConfigFromEnv(empty)
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.DesktopBridge {
		t.Error("the desktop bridge must default to enabled")
	}
	// Explicit opt-out for a headless homeserver that only serves the phone.
	off := func(key string) string {
		if key == "EARTHQUACK_DESKTOP_BRIDGE" {
			return "0"
		}
		return ""
	}
	cfg, err = ConfigFromEnv(off)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DesktopBridge {
		t.Error("EARTHQUACK_DESKTOP_BRIDGE=0 must disable the bridge")
	}
}

// TestFileTransferIsSilent pins the decision that a transfer produces no
// desktop notification. The Python original shelled out to notify-send here,
// which also interpolated the client-supplied filename into a shell command.
//
// The absence is enforced structurally: fileServerConfig has no Notify field,
// so the code cannot spawn one. This test documents the behaviour end to end
// — a transfer completes with nothing but its own log line.
func TestFileTransferIsSilent(t *testing.T) {
	fs, handler, signals := newTestFileServer(t)
	srv := httptestServer(t, handler)

	code, body := upload(t, srv, "silent.jpg", OriginPhone, []byte("bytes"))
	if code != 200 {
		t.Fatalf("upload failed: %d", code)
	}
	if _, err := os.Stat(filepath.Join(fs.phoneSave, "silent.jpg")); err != nil {
		t.Fatalf("file must still be stored: %v", err)
	}

	// A phone→desktop transfer notifies nobody: no SSE event (the phone
	// already knows), no notification hook.
	if len(*signals) != 0 {
		t.Errorf("a phone→desktop transfer must not signal subscribers, got %v", *signals)
	}
	_ = body
}
