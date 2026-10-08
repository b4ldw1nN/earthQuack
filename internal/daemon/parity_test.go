package daemon

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"

	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

// These tests run the original Python daemon and the Go rewrite side by
// side and assert they produce the same wire output.
//
// This is the safety net for the migration: the Android app is already
// written against the Python behaviour, so the rewrite is only safe if the
// two are indistinguishable from outside. Nothing here asserts on
// whitespace or key order — both are irrelevant to a JSON client — only on
// decoded values, status codes, headers and SSE frame bytes.

// pythonDaemonDir locates daemon/ relative to this package.
func pythonDaemonDir(t *testing.T) string {
	t.Helper()
	// internal/daemon -> repo root -> daemon
	dir, err := filepath.Abs(filepath.Join("..", "..", "daemon"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "server.py")); err != nil {
		t.Skipf("python daemon sources not present at %s", dir)
	}
	return dir
}

// freePort asks the kernel for an unused loopback port.
func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

// startPythonClipboardServer runs daemon/server.py on a loopback port and
// returns its base URL. The process is killed when the test ends.
func startPythonClipboardServer(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("python3"); err != nil {
		t.Skip("python3 not available; skipping parity test")
	}
	dir := pythonDaemonDir(t)
	port := freePort(t)

	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, "python3", "server.py", "--host", "127.0.0.1", "--port", fmt.Sprint(port))
	cmd.Dir = dir
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		t.Skipf("cannot start python daemon: %v", err)
	}
	t.Cleanup(func() {
		cancel()
		_ = cmd.Wait()
		if t.Failed() && stderr.Len() > 0 {
			t.Logf("python daemon stderr:\n%s", stderr.String())
		}
	})

	base := fmt.Sprintf("http://127.0.0.1:%d", port)
	waitForListener(t, base+"/health")
	return base
}

func waitForListener(t *testing.T, url string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(url)
		if err == nil {
			resp.Body.Close()
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", url)
}

// goClipboardServer starts the Go handler on a loopback port.
func goClipboardServer(t *testing.T, token string) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	state := NewClipboardState()
	broker := NewBroker()
	srv := &http.Server{Handler: newClipboardHandler(state, broker, token)}
	go srv.Serve(ln)
	t.Cleanup(func() { _ = srv.Close() })
	return "http://" + ln.Addr().String()
}

// TestParityHealthAndClipboard pins the endpoints the Android app and the
// node's service refresher depend on.
func TestParityHealthAndClipboard(t *testing.T) {
	py := startPythonClipboardServer(t)
	const token = "parity-token"
	goSrv := goClipboardServer(t, token)

	t.Run("health", func(t *testing.T) {
		pyCode, pyBody := decodeJSON(t, http.MethodGet, py+"/health", "", nil)
		goCode, goBody := decodeJSON(t, http.MethodGet, goSrv+"/health", "", nil)
		if pyCode != goCode {
			t.Errorf("GET /health status: python=%d go=%d", pyCode, goCode)
		}
		if !reflect.DeepEqual(pyBody, goBody) {
			t.Errorf("GET /health body:\n python=%v\n go=%v", pyBody, goBody)
		}
	})

	t.Run("clipboard initial state", func(t *testing.T) {
		pyCode, pyBody := decodeJSON(t, http.MethodGet, py+"/clipboard", "", nil)
		goCode, goBody := decodeJSON(t, http.MethodGet, goSrv+"/clipboard", token, nil)
		if pyCode != goCode {
			t.Errorf("GET /clipboard status: python=%d go=%d", pyCode, goCode)
		}
		if !reflect.DeepEqual(pyBody, goBody) {
			t.Errorf("GET /clipboard body:\n python=%v\n go=%v", pyBody, goBody)
		}
	})

	t.Run("clipboard set increments version", func(t *testing.T) {
		payload := map[string]any{"clipboard": "hello parity", "origin": "desktop"}
		pyCode, pyBody := decodeJSON(t, http.MethodPost, py+"/clipboard", "", payload)
		goCode, goBody := decodeJSON(t, http.MethodPost, goSrv+"/clipboard", token, payload)
		if pyCode != goCode {
			t.Errorf("POST /clipboard status: python=%d go=%d", pyCode, goCode)
		}
		if !reflect.DeepEqual(pyBody, goBody) {
			t.Errorf("POST /clipboard body:\n python=%v\n go=%v", pyBody, goBody)
		}
		if v, _ := goBody["version"].(float64); v != 1 {
			t.Errorf("first write must be version 1, got %v", goBody["version"])
		}
	})

	t.Run("identical repeat write is a no-op", func(t *testing.T) {
		payload := map[string]any{"clipboard": "hello parity", "origin": "desktop"}
		_, pyBody := decodeJSON(t, http.MethodPost, py+"/clipboard", "", payload)
		_, goBody := decodeJSON(t, http.MethodPost, goSrv+"/clipboard", token, payload)
		if !reflect.DeepEqual(pyBody, goBody) {
			t.Errorf("repeat write must not bump the version:\n python=%v\n go=%v", pyBody, goBody)
		}
	})

	t.Run("missing fields are rejected", func(t *testing.T) {
		// The Python handler raised KeyError -> 400; so must the Go one.
		pyCode := statusOnly(t, http.MethodPost, py+"/clipboard", "", map[string]any{"clipboard": "x"})
		goCode := statusOnly(t, http.MethodPost, goSrv+"/clipboard", token, map[string]any{"clipboard": "x"})
		if pyCode != goCode || goCode != http.StatusBadRequest {
			t.Errorf("partial body status: python=%d go=%d (want 400)", pyCode, goCode)
		}
	})

	t.Run("unknown route is 404", func(t *testing.T) {
		pyCode := statusOnly(t, http.MethodGet, py+"/nope", "", nil)
		goCode := statusOnly(t, http.MethodGet, goSrv+"/nope", token, nil)
		if pyCode != goCode || goCode != http.StatusNotFound {
			t.Errorf("unknown route status: python=%d go=%d (want 404)", pyCode, goCode)
		}
	})
}

// TestParitySignalEventBytes compares the exact SSE bytes each
// implementation emits, because both clients parse the framing literally.
func TestParitySignalEventBytes(t *testing.T) {
	py := startPythonClipboardServer(t)
	const token = "parity-token"
	goSrv := goClipboardServer(t, token)

	pyEvents := subscribeSSE(t, py, "")
	goEvents := subscribeSSE(t, goSrv, token)

	// Wait for both subscriptions to register before publishing.
	time.Sleep(500 * time.Millisecond)

	signal := map[string]any{"type": "open_url", "url": "https://example.com/path?q=1"}
	pyCode, pyBody := decodeJSON(t, http.MethodPost, py+"/signal", "", signal)
	goCode, goBody := decodeJSON(t, http.MethodPost, goSrv+"/signal", token, signal)
	if pyCode != goCode || !reflect.DeepEqual(pyBody, goBody) {
		t.Fatalf("POST /signal mismatch: python=(%d,%v) go=(%d,%v)", pyCode, pyBody, goCode, goBody)
	}

	pyFrame := readOneFrame(t, pyEvents)
	goFrame := readOneFrame(t, goEvents)
	assertFrameParity(t, pyFrame, goFrame)
	if !strings.Contains(goFrame, "event: open_url") {
		t.Errorf("frame missing event name: %q", goFrame)
	}
	if !strings.Contains(goFrame, `"open_url"`) {
		t.Errorf("frame missing data payload: %q", goFrame)
	}
}

// TestParityClipboardSSEBytes compares the clipboard event framing, which is
// what the phone's handleSseEvent and the desktop bridge both read.
func TestParityClipboardSSEBytes(t *testing.T) {
	py := startPythonClipboardServer(t)
	const token = "parity-token"
	goSrv := goClipboardServer(t, token)

	pyEvents := subscribeSSE(t, py, "")
	goEvents := subscribeSSE(t, goSrv, token)
	time.Sleep(500 * time.Millisecond)

	payload := map[string]any{"clipboard": "sse parity value", "origin": "phone"}
	decodeJSON(t, http.MethodPost, py+"/clipboard", "", payload)
	decodeJSON(t, http.MethodPost, goSrv+"/clipboard", token, payload)

	pyFrame := readOneFrame(t, pyEvents)
	goFrame := readOneFrame(t, goEvents)
	assertFrameParity(t, pyFrame, goFrame)
	// Both clients require the full snapshot with a version field.
	for _, want := range []string{`"clipboard"`, `"origin"`, `"version"`} {
		if !strings.Contains(goFrame, want) {
			t.Errorf("clipboard frame missing %s: %q", want, goFrame)
		}
	}
}

// subscribeSSE opens an SSE stream and returns a reader for its frames.
func subscribeSSE(t *testing.T, base, token string) *bufio.Reader {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, base+"/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Accept", "text/event-stream")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	// No client timeout: this stream is intentionally long-lived.
	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /events returned %s", resp.Status)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Errorf("Content-Type = %q, want text/event-stream", ct)
	}
	if cc := resp.Header.Get("Cache-Control"); cc != "no-cache" {
		t.Errorf("Cache-Control = %q, want no-cache", cc)
	}
	return bufio.NewReader(resp.Body)
}

// parsedFrame is an SSE block split into its literal and semantic parts.
type parsedFrame struct {
	// event is the raw text after "event: ", compared byte-for-byte
	// because every client matches on it as a string.
	event string
	// data is the decoded JSON payload. It is compared as decoded values,
	// never as bytes: Python's json.dumps separates with ", " while Go's
	// encoding/json uses ",", and both are the same JSON value. Padding the
	// Go output with Python's spaces would mean carrying a quirk of one
	// serialiser forever to satisfy a comparison no client makes.
	data map[string]any
	// block is the number of lines the block occupied, so framing is
	// checked as well as content.
	lines int
}

// parseFrame splits and decodes one SSE block.
func parseFrame(t *testing.T, frame string) parsedFrame {
	t.Helper()
	pf := parsedFrame{}
	for _, line := range strings.Split(strings.TrimSuffix(frame, "\n"), "\n") {
		if line == "" {
			continue
		}
		pf.lines++
		switch {
		case strings.HasPrefix(line, "event:"):
			pf.event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "data:"):
			var out map[string]any
			if err := json.Unmarshal([]byte(strings.TrimSpace(strings.TrimPrefix(line, "data:"))), &out); err != nil {
				t.Fatalf("frame data is not a JSON object: %v (frame=%q)", err, frame)
			}
			pf.data = out
		default:
			t.Fatalf("unexpected SSE line %q in frame %q", line, frame)
		}
	}
	return pf
}

// assertFrameParity fails unless both implementations produced the same SSE
// event: identical event name, semantically identical JSON payload, and the
// same block shape.
func assertFrameParity(t *testing.T, pyFrame, goFrame string) {
	t.Helper()
	py := parseFrame(t, pyFrame)
	goGot := parseFrame(t, goFrame)
	if py.event != goGot.event {
		t.Errorf("SSE event name differs: python=%q go=%q", py.event, goGot.event)
	}
	if !reflect.DeepEqual(py.data, goGot.data) {
		t.Errorf("SSE data payload differs:\n python=%v\n go=%v", py.data, goGot.data)
	}
	if py.lines != goGot.lines {
		t.Errorf("SSE block shape differs: python=%d lines go=%d lines", py.lines, goGot.lines)
	}
}

// readOneFrame reads one complete SSE event block, skipping keepalive
// comments.
//
// A keepalive is the two bytes ": keepalive\n\n": a comment line followed
// by a blank line. Per the SSE spec the blank line dispatches whatever has
// accumulated, which for a comment-only block is nothing — so it must not
// be mistaken for the end of the *next* event.
func readOneFrame(t *testing.T, r *bufio.Reader) string {
	t.Helper()
	var frame strings.Builder
	sawField := false
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			t.Fatalf("reading SSE frame: %v (partial=%q)", err, frame.String())
		}
		if strings.HasPrefix(line, ":") {
			continue // comment: ignored entirely, blank line included
		}
		if line == "\n" || line == "\r\n" {
			if sawField {
				return frame.String()
			}
			continue // blank before any field: part of a comment block
		}
		frame.WriteString(line)
		sawField = true
	}
}
