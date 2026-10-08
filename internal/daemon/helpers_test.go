package daemon

import (
	"bufio"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

// Test-only helpers shared across the package's test files.

// httptestServer starts handler on a random loopback port and returns its
// base URL. The server is closed when the test ends.
func httptestServer(t *testing.T, handler http.Handler) string {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return srv.URL
}

// newRequest builds a request with an optional JSON body.
func newRequest(method, url string, body any) (*http.Request, error) {
	var reader io.Reader = strings.NewReader("")
	if body != nil {
		raw, err := marshalJSON(body)
		if err != nil {
			return nil, err
		}
		reader = strings.NewReader(raw)
		req, err := http.NewRequest(method, url, reader)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		return req, nil
	}
	return http.NewRequest(method, url, reader)
}

// do executes req and returns the status code, discarding the body.
func do(t *testing.T, req *http.Request) int {
	t.Helper()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return resp.StatusCode
}

// statusOnly fetches url and returns only the status code, for assertions
// where the error body is intentionally not JSON.
func statusOnly(t *testing.T, method, url, token string, body any) int {
	t.Helper()
	req, err := newRequest(method, url, body)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return do(t, req)
}

// marshalJSON encodes v as JSON for request bodies.
func marshalJSON(v any) (string, error) {
	raw, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

// decodeJSON fetches url and returns the status plus the decoded JSON body,
// for assertions that compare payload values.
func decodeJSON(t *testing.T, method, url, token string, body any) (int, map[string]any) {
	t.Helper()
	req, err := newRequest(method, url, body)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("%s %s: body is not a JSON object: %v", method, url, err)
	}
	return resp.StatusCode, out
}

// frameChan adapts an SSE response body into a channel of event blocks,
// splitting on the blank line that terminates each block and discarding
// keepalive comments.
func frameChan(r io.Reader) <-chan []byte {
	out := make(chan []byte, 16)
	go func() {
		defer close(out)
		sc := bufio.NewScanner(r)
		sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
		var frame strings.Builder
		sawField := false
		for sc.Scan() {
			line := sc.Text()
			switch {
			case strings.HasPrefix(line, ":"):
				continue // keepalive comment
			case line == "":
				if sawField {
					out <- []byte(frame.String())
					frame.Reset()
					sawField = false
				}
			default:
				frame.WriteString(line)
				frame.WriteString("\n")
				sawField = true
			}
		}
	}()
	return out
}

// TestMain disables the on-disk version ledger for the whole package.
//
// Every test then gets an in-memory counter. Without this a test that builds
// a Config without ClipboardStateDir would read and write the real
// ~/.local/share/earthquack/clipboard ledger, so version assertions would
// depend on execution order and on whatever a previous run left behind.
// Tests that specifically exercise persistence name a t.TempDir() instead.
func TestMain(m *testing.M) {
	versionLedgerEnabled = func() bool { return false }
	os.Exit(m.Run())
}
