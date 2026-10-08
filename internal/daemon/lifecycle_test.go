package daemon

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

// startTestDaemon brings up a real daemon on ephemeral loopback ports.
//
// The ports are discovered by binding :0 first and releasing, which is fine
// for a test but is why production code must never do it.
func startTestDaemon(t *testing.T, mutate func(*Config)) *Daemon {
	t.Helper()
	clipPort := freePort(t)
	filePort := freePort(t)
	for filePort == clipPort {
		filePort = freePort(t)
	}
	cfg := Config{
		Host:          "127.0.0.1",
		ClipboardPort: clipPort,
		FilePort:      filePort,
		AuthToken:     "lifecycle-token",
		StageDir:      t.TempDir(),
		PhoneSaveDir:  t.TempDir(),
		StagedTTL:     0, // no background sweeper in lifecycle tests
		DesktopBridge: false,
		SendFolder:    "",
		SendScript:    "",
		// Pin the version ledger to a temp dir so tests never read or write
		// the real ~/.local/share state; otherwise version numbers leak
		// between tests and assertions depend on execution order.
		ClipboardStateDir: t.TempDir(),
		DiscoverTimeout:   time.Second,
	}
	if mutate != nil {
		mutate(&cfg)
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

func baseURL(host string, port int) string {
	return "http://" + host + ":" + itoa(port)
}

// TestDaemonLifecycleStartStopStart covers the dashboard contract: Stop must
// take the listeners down and a later Start must bring them back, because the
// node exposes Start/Stop for these services.
func TestDaemonLifecycleStartStopStart(t *testing.T) {
	d := startTestDaemon(t, nil)
	clip := baseURL(d.cfg.Host, d.cfg.ClipboardPort)

	get := func() int {
		req, _ := http.NewRequest(http.MethodGet, clip+"/health", nil)
		return doStatus(t, req)
	}
	if got := get(); got != http.StatusOK {
		t.Fatalf("after Start the clipboard service must answer, got %d", got)
	}
	if !d.Running() {
		t.Error("Running must be true after Start")
	}

	if err := d.Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if d.Running() {
		t.Error("Running must be false after Stop")
	}
	if _, err := http.Get(clip + "/health"); err == nil {
		t.Error("after Stop the listener must refuse connections")
	}

	// Restart.
	if err := d.Start(context.Background()); err != nil {
		t.Fatalf("Start after Stop: %v", err)
	}
	if got := get(); got != http.StatusOK {
		t.Fatalf("after restart the clipboard service must answer, got %d", got)
	}

	// Stopping twice is a no-op, not an error.
	if err := d.Stop(); err != nil {
		t.Errorf("second Stop: %v", err)
	}
	// Shutdown is terminal.
	if err := d.Shutdown(); err != nil {
		t.Errorf("Shutdown: %v", err)
	}
	if err := d.Start(context.Background()); err == nil {
		t.Error("Start after Shutdown must fail")
	}
}

// TestDaemonStartIsIdempotent: a double Start (which the dashboard can
// produce) must not try to bind the same port twice and fail.
func TestDaemonStartIsIdempotent(t *testing.T) {
	d := startTestDaemon(t, nil)
	if err := d.Start(context.Background()); err != nil {
		t.Fatalf("a repeated Start must be a no-op, got %v", err)
	}
	if !d.Running() {
		t.Error("Running must still be true")
	}
}

// TestDaemonPortClashIsReported is the fix for the Python supervisor's
// worst failure mode: a port clash used to be retried silently every five
// seconds forever. Start must return the error to the caller.
func TestDaemonPortClashIsReported(t *testing.T) {
	first := startTestDaemon(t, nil)

	clash := Config{
		Host:              "127.0.0.1",
		ClipboardPort:     first.cfg.ClipboardPort, // already bound
		FilePort:          freePort(t),
		StageDir:          t.TempDir(),
		PhoneSaveDir:      t.TempDir(),
		ClipboardStateDir: t.TempDir(),
	}
	d, err := New(clash)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = d.Shutdown() })

	err = d.Start(context.Background())
	if err == nil {
		t.Fatal("binding an occupied port must fail loudly")
	}
	if !strings.Contains(err.Error(), "listen") {
		t.Errorf("error should name the failure, got %v", err)
	}
	if d.Running() {
		t.Error("a failed Start must leave the daemon stopped")
	}
}

// TestDaemonEndToEnd exercises the whole path the phone uses: fetch state,
// set a clipboard value, observe it on the SSE stream, and receive a staged
// file announcement.
func TestDaemonEndToEnd(t *testing.T) {
	d := startTestDaemon(t, nil)
	clip := baseURL(d.cfg.Host, d.cfg.ClipboardPort)
	files := baseURL(d.cfg.Host, d.cfg.FilePort)
	const token = "lifecycle-token"

	// Subscribe before mutating, so no event is missed.
	events := openSSE(t, clip, token)

	// Phone sets the clipboard.
	body := map[string]string{"clipboard": "from the phone", "origin": OriginPhone}
	raw, _ := marshalJSON(body)
	req, _ := http.NewRequest(http.MethodPost, clip+"/clipboard", strings.NewReader(raw))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	if code := doStatus(t, req); code != http.StatusOK {
		t.Fatalf("POST /clipboard = %d, want 200", code)
	}

	frame := readFrame(t, events)
	if !strings.Contains(frame, "event: clipboard") {
		t.Errorf("expected a clipboard event, got %q", frame)
	}

	// The stored state must match what was set.
	req, _ = http.NewRequest(http.MethodGet, clip+"/clipboard", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var snap map[string]any
	json.NewDecoder(resp.Body).Decode(&snap)
	if snap["clipboard"] != "from the phone" || snap["origin"] != OriginPhone {
		t.Errorf("stored state = %v, want the value just posted", snap)
	}
	if v, _ := snap["version"].(float64); v != 1 {
		t.Errorf("version = %v, want 1", snap["version"])
	}

	// Desktop stages a file; the phone is told over the same stream.
	content := "a file from the desktop"
	up, _ := http.NewRequest(http.MethodPost, files+"/upload", strings.NewReader(content))
	up.Header.Set("Authorization", "Bearer "+token)
	up.Header.Set("X-Filename", "notes.md")
	up.Header.Set("X-Origin", OriginDesktop)
	if code := doStatus(t, up); code != http.StatusOK {
		t.Fatalf("POST /upload = %d, want 200", code)
	}
	fileFrame := readFrame(t, events)
	if !strings.Contains(fileFrame, "event: file_ready") {
		t.Errorf("expected a file_ready event, got %q", fileFrame)
	}
	for _, want := range []string{`"notes.md"`, `"id"`, `"size"`} {
		if !strings.Contains(fileFrame, want) {
			t.Errorf("file_ready frame missing %s: %q", want, fileFrame)
		}
	}
}

// TestDaemonAuthAppliesToBothServices: the token the node already holds must
// gate 8875 and 8876 identically.
func TestDaemonAuthAppliesToBothServices(t *testing.T) {
	d := startTestDaemon(t, nil)
	for _, svc := range []struct {
		name string
		url  string
		path string
	}{
		{"clipboard", baseURL(d.cfg.Host, d.cfg.ClipboardPort), "/clipboard"},
		{"file", baseURL(d.cfg.Host, d.cfg.FilePort), "/files"},
	} {
		t.Run(svc.name, func(t *testing.T) {
			if code := statusOnly(t, http.MethodGet, svc.url+svc.path, "", nil); code != http.StatusUnauthorized {
				t.Errorf("no token: got %d, want 401", code)
			}
			if code := statusOnly(t, http.MethodGet, svc.url+svc.path, "wrong", nil); code != http.StatusUnauthorized {
				t.Errorf("bad token: got %d, want 401", code)
			}
			if code := statusOnly(t, http.MethodGet, svc.url+svc.path, "lifecycle-token", nil); code != http.StatusOK {
				t.Errorf("good token: got %d, want 200", code)
			}
		})
	}
}

// TestDaemonNoAuthConfiguredFailsClosed: without a token both services must
// refuse traffic rather than serving the way the Python daemon did.
func TestDaemonNoAuthConfiguredFailsClosed(t *testing.T) {
	d := startTestDaemon(t, func(c *Config) { c.AuthToken = "" })
	for _, u := range []string{
		baseURL(d.cfg.Host, d.cfg.ClipboardPort) + "/clipboard",
		baseURL(d.cfg.Host, d.cfg.FilePort) + "/files",
	} {
		if code := statusOnly(t, http.MethodGet, u, "anything", nil); code != http.StatusServiceUnavailable {
			t.Errorf("%s with no token configured: got %d, want 503", u, code)
		}
	}
	// Health stays public so the node's service probe still sees them up.
	if code := statusOnly(t, http.MethodGet, baseURL(d.cfg.Host, d.cfg.ClipboardPort)+"/health", "", nil); code != http.StatusOK {
		t.Errorf("/health must remain public, got %d", code)
	}
}

// TestDaemonRejectsBadAESKeyAtStartup: a typo in the clipboard key must be a
// startup failure, never a silent downgrade to plaintext.
func TestDaemonRejectsBadAESKeyAtStartup(t *testing.T) {
	_, err := New(Config{
		ClipboardPort:     freePort(t),
		FilePort:          freePort(t),
		AESKey:            "clearly-not-a-32-byte-key",
		StageDir:          t.TempDir(),
		PhoneSaveDir:      t.TempDir(),
		ClipboardStateDir: t.TempDir(),
	})
	if err == nil {
		t.Fatal("an invalid AES key must fail New(), not be ignored")
	}
}

// --- helpers ---

func doStatus(t *testing.T, req *http.Request) int {
	t.Helper()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", req.Method, req.URL, err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return resp.StatusCode
}

func openSSE(t *testing.T, base, token string) <-chan []byte {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, base+"/events", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := (&http.Client{}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /events = %s", resp.Status)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return frameChan(resp.Body)
}
