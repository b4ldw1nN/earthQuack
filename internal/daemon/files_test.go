package daemon

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newTestFileServer builds a file service over temp dirs with a recording
// signal function, plus an authenticated handler.
func newTestFileServer(t *testing.T) (*fileServer, http.Handler, *[]string) {
	t.Helper()
	var signals []string
	fs, err := newFileServer(fileServerConfig{
		StageDir:  t.TempDir(),
		PhoneSave: t.TempDir(),
		Signal: func(eventType string, data any) {
			signals = append(signals, eventType)
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return fs, newFileHandler(fs, "test-token"), &signals
}

// upload posts body to /upload with the given headers.
func upload(t *testing.T, srv, filename, origin string, body []byte) (int, map[string]any) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, srv+"/upload", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer test-token")
	req.ContentLength = int64(len(body))
	if filename != "" {
		req.Header.Set("X-Filename", filename)
	}
	if origin != "" {
		req.Header.Set("X-Origin", origin)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// TestUploadFromPhoneLandsOnDisk covers the phone→desktop direction: the
// file must be saved to the download directory and the response must carry
// an empty id, because there is nothing for the phone to fetch afterwards.
func TestUploadFromPhoneLandsOnDisk(t *testing.T) {
	fs, handler, _ := newTestFileServer(t)
	srv := httptestServer(t, handler)

	content := []byte("pretend this is a photo")
	code, body := upload(t, srv, "holiday.jpg", OriginPhone, content)

	if code != http.StatusOK {
		t.Fatalf("upload status = %d, want 200", code)
	}
	if id, _ := body["id"].(string); id != "" {
		t.Errorf("phone→desktop must return an empty id, got %q", id)
	}
	if name, _ := body["name"].(string); name != "holiday.jpg" {
		t.Errorf("name = %q, want holiday.jpg", name)
	}
	if size, _ := body["size"].(float64); int(size) != len(content) {
		t.Errorf("size = %v, want %d", body["size"], len(content))
	}

	saved, err := os.ReadFile(filepath.Join(fs.phoneSave, "holiday.jpg"))
	if err != nil {
		t.Fatalf("uploaded file must exist on disk: %v", err)
	}
	if !bytes.Equal(saved, content) {
		t.Error("stored bytes differ from what was uploaded")
	}
}

// TestUploadFromPhoneDoesNotOverwrite is the behaviour the Python original
// implemented and a user would notice immediately if lost: dropping a second
// file with the same name must not destroy the first.
func TestUploadFromPhoneDoesNotOverwrite(t *testing.T) {
	fs, handler, _ := newTestFileServer(t)
	srv := httptestServer(t, handler)

	upload(t, srv, "notes.txt", OriginPhone, []byte("first"))
	_, body := upload(t, srv, "notes.txt", OriginPhone, []byte("second"))

	got, _ := body["name"].(string)
	if got == "notes.txt" {
		t.Fatal("the second upload must be renamed, not overwrite the first")
	}
	if !strings.HasPrefix(got, "notes_") || !strings.HasSuffix(got, ".txt") {
		t.Errorf("renamed file %q should keep the stem and extension", got)
	}

	first, err := os.ReadFile(filepath.Join(fs.phoneSave, "notes.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(first) != "first" {
		t.Error("the original file must be untouched")
	}
	second, err := os.ReadFile(filepath.Join(fs.phoneSave, got))
	if err != nil {
		t.Fatal(err)
	}
	if string(second) != "second" {
		t.Error("the second upload must land under its new name")
	}
}

// TestUploadRejectsPathTraversal is a security fix over the Python original,
// which joined the client-supplied X-Filename straight onto the directory
// and so allowed "../../.bashrc" to escape.
//
// The property under test is containment, not cosmetic tidiness: whatever
// the client sends, every byte written must land directly inside the save
// directory. A hostile name may survive as a literal filename (for example
// "..%2f..%2fetc%2fpasswd" is just an odd string, not a traversal), which is
// harmless; what must never happen is a write outside the directory.
func TestUploadRejectsPathTraversal(t *testing.T) {
	fs, handler, _ := newTestFileServer(t)
	srv := httptestServer(t, handler)

	// Traversal attempts are sanitised down to a safe base name and stored.
	traversal := []string{
		"../../../etc/passwd",
		"..%2f..%2fetc%2fpasswd",
		"sub/dir/../../escape.txt",
		"/etc/shadow",
		"....//....//x",
		"a/b",
	}
	for _, name := range traversal {
		if code, _ := upload(t, srv, name, OriginPhone, []byte("pwned")); code != http.StatusOK {
			t.Errorf("upload %q: status = %d, want 200 (sanitised, not rejected)", name, code)
		}
	}

	// Names that reduce to nothing usable are refused outright rather than
	// written as a dotfile.
	for _, name := range []string{"..", ".", "/"} {
		if code, _ := upload(t, srv, name, OriginPhone, []byte("pwned")); code != http.StatusBadRequest {
			t.Errorf("upload %q: status = %d, want 400", name, code)
		}
	}

	// Every file written must be an immediate child of the save directory.
	saveDir, err := filepath.Abs(fs.phoneSave)
	if err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(fs.phoneSave)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) == 0 {
		t.Fatal("expected the uploads to be stored")
	}
	for _, e := range entries {
		full := filepath.Join(saveDir, e.Name())
		resolved, err := filepath.EvalSymlinks(full)
		if err != nil {
			t.Fatal(err)
		}
		if filepath.Dir(resolved) != saveDir {
			t.Errorf("file escaped the save directory: %q resolved to %q", e.Name(), resolved)
		}
		if strings.ContainsRune(e.Name(), os.PathSeparator) {
			t.Errorf("stored name still contains a path separator: %q", e.Name())
		}
	}

	// The classic traversal target must be untouched.
	if _, err := os.Stat("/etc/passwd"); err != nil {
		t.Error("the test needs /etc/passwd to exist to prove it was not overwritten")
	}
}

// TestStageForPhoneSignalsThePhone covers the other direction: the file is
// staged, given an id, and announced with the exact event shape Android's
// handleFileReadyEvent parses.
func TestStageForPhoneSignalsThePhone(t *testing.T) {
	var captured []string
	var capturedData []map[string]any
	fs, err := newFileServer(fileServerConfig{
		StageDir:  t.TempDir(),
		PhoneSave: t.TempDir(),
		Signal: func(eventType string, data any) {
			captured = append(captured, eventType)
			if m, ok := data.(map[string]any); ok {
				capturedData = append(capturedData, m)
			}
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptestServer(t, newFileHandler(fs, "test-token"))

	content := []byte("staged payload")
	code, body := upload(t, srv, "report.pdf", OriginDesktop, content)
	if code != http.StatusOK {
		t.Fatalf("upload status = %d", code)
	}
	id, _ := body["id"].(string)
	if id == "" {
		t.Fatal("desktop→phone must return a staging id")
	}

	// The staged file must exist where the ledger says.
	fs.mu.Lock()
	entry := fs.staged[id]
	fs.mu.Unlock()
	if entry == nil {
		t.Fatal("staged ledger must contain the new id")
	}
	stored, err := os.ReadFile(entry.Path)
	if err != nil || !bytes.Equal(stored, content) {
		t.Errorf("staged content mismatch: err=%v", err)
	}

	// The signal must use the event name and field names Android expects.
	if len(captured) != 1 || captured[0] != "file_ready" {
		t.Fatalf("expected one file_ready signal, got %v", captured)
	}
	data := capturedData[0]
	for _, field := range []string{"type", "id", "name", "size"} {
		if _, ok := data[field]; !ok {
			t.Errorf("file_ready signal missing %q: %v", field, data)
		}
	}
	if data["type"] != "file_ready" {
		t.Errorf(`signal "type" field = %v, want file_ready`, data["type"])
	}
}

// TestDownloadAndRange covers the status codes and headers the phone's
// resumable download depends on.
func TestDownloadAndRange(t *testing.T) {
	fs, handler, _ := newTestFileServer(t)
	srv := httptestServer(t, handler)

	content := []byte("0123456789ABCDEFGHIJ") // 20 bytes
	_, body := upload(t, srv, "data.bin", OriginDesktop, content)
	id, _ := body["id"].(string)

	get := func(path, rangeHeader string) (*http.Response, []byte) {
		req, _ := http.NewRequest(http.MethodGet, srv+path, nil)
		req.Header.Set("Authorization", "Bearer test-token")
		if rangeHeader != "" {
			req.Header.Set("Range", rangeHeader)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		return resp, raw
	}

	t.Run("full download", func(t *testing.T) {
		resp, raw := get("/download/"+id, "")
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}
		if !bytes.Equal(raw, content) {
			t.Errorf("body = %q, want %q", raw, content)
		}
		if resp.Header.Get("Accept-Ranges") != "bytes" {
			t.Error("Accept-Ranges: bytes is required for resumable downloads")
		}
		if cd := resp.Header.Get("Content-Disposition"); !strings.Contains(cd, "data.bin") {
			t.Errorf("Content-Disposition = %q, want it to name the file", cd)
		}
	})

	t.Run("open ended range is what the phone sends", func(t *testing.T) {
		resp, raw := get("/download/"+id, "bytes=10-")
		if resp.StatusCode != http.StatusPartialContent {
			t.Fatalf("status = %d, want 206", resp.StatusCode)
		}
		if string(raw) != "ABCDEFGHIJ" {
			t.Errorf("body = %q, want ABCDEFGHIJ", raw)
		}
		if cr := resp.Header.Get("Content-Range"); cr != "bytes 10-19/20" {
			t.Errorf("Content-Range = %q, want bytes 10-19/20", cr)
		}
	})

	t.Run("bounded range", func(t *testing.T) {
		resp, raw := get("/download/"+id, "bytes=2-5")
		if resp.StatusCode != http.StatusPartialContent {
			t.Fatalf("status = %d, want 206", resp.StatusCode)
		}
		if string(raw) != "2345" {
			t.Errorf("body = %q, want 2345", raw)
		}
	})

	t.Run("suffix range", func(t *testing.T) {
		// The Python original could not parse this and silently returned the
		// whole file; RFC 7233 says it means the final N bytes.
		resp, raw := get("/download/"+id, "bytes=-4")
		if resp.StatusCode != http.StatusPartialContent {
			t.Fatalf("status = %d, want 206", resp.StatusCode)
		}
		if string(raw) != "GHIJ" {
			t.Errorf("body = %q, want GHIJ", raw)
		}
	})

	t.Run("unknown id is 404", func(t *testing.T) {
		resp, _ := get("/download/nope", "")
		if resp.StatusCode != http.StatusNotFound {
			t.Errorf("status = %d, want 404", resp.StatusCode)
		}
	})

	t.Run("vanished staged file is 410", func(t *testing.T) {
		_, gone := upload(t, srv, "doomed.bin", OriginDesktop, []byte("x"))
		goneID, _ := gone["id"].(string)
		if err := os.Remove(filepath.Join(fs.stageDir, goneID)); err != nil {
			t.Fatal(err)
		}
		resp, _ := get("/download/"+goneID, "")
		if resp.StatusCode != http.StatusGone {
			t.Errorf("status = %d, want 410", resp.StatusCode)
		}
	})
}

// TestListStagedFiles covers GET /files.
func TestListStagedFiles(t *testing.T) {
	_, handler, _ := newTestFileServer(t)
	srv := httptestServer(t, handler)

	// Empty first.
	req, _ := http.NewRequest(http.MethodGet, srv+"/files", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var list []map[string]any
	json.NewDecoder(resp.Body).Decode(&list)
	resp.Body.Close()
	if len(list) != 0 {
		t.Errorf("expected an empty list initially, got %v", list)
	}

	upload(t, srv, "a.txt", OriginDesktop, []byte("a"))
	upload(t, srv, "b.txt", OriginDesktop, []byte("bb"))

	req, _ = http.NewRequest(http.MethodGet, srv+"/files", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	list = nil
	json.NewDecoder(resp.Body).Decode(&list)
	if len(list) != 2 {
		t.Fatalf("expected 2 staged files, got %d", len(list))
	}
	// Every field the desktop tooling reads must be present.
	for _, entry := range list {
		for _, field := range []string{"id", "name", "size", "path", "ts"} {
			if _, ok := entry[field]; !ok {
				t.Errorf("staged entry missing %q: %v", field, entry)
			}
		}
	}
}

// TestFileServiceRequiresAuth mirrors the clipboard service: the Python file
// server had no authentication at all.
func TestFileServiceRequiresAuth(t *testing.T) {
	_, handler, _ := newTestFileServer(t)
	srv := httptestServer(t, handler)

	for _, path := range []string{"/files", "/download/anything"} {
		if code := statusOnly(t, http.MethodGet, srv+path, "", nil); code != http.StatusUnauthorized {
			t.Errorf("GET %s without a token must be 401, got %d", path, code)
		}
	}
	if code := statusOnly(t, http.MethodPost, srv+"/upload", "", nil); code != http.StatusUnauthorized {
		t.Errorf("POST /upload without a token must be 401, got %d", code)
	}
	if code := statusOnly(t, http.MethodGet, srv+"/files", "test-token", nil); code != http.StatusOK {
		t.Errorf("GET /files with a valid token must be 200, got %d", code)
	}
}

// TestParseRange covers the parsing table directly, including the shapes the
// Python implementation mishandled.
func TestParseRange(t *testing.T) {
	const total = 20
	for _, tt := range []struct {
		name      string
		header    string
		wantStart int64
		wantEnd   int64
		wantPart  bool
		wantOK    bool
	}{
		{"absent", "", 0, 19, false, true},
		{"open ended", "bytes=5-", 5, 19, true, true},
		{"bounded", "bytes=5-9", 5, 9, true, true},
		{"suffix", "bytes=-4", 16, 19, true, true},
		{"suffix longer than file", "bytes=-100", 0, 19, true, true},
		{"end past EOF is clamped", "bytes=18-999", 18, 19, true, true},
		{"start at zero is a range", "bytes=0-", 0, 19, true, true},
		{"start past EOF", "bytes=50-", 0, 0, false, false},
		{"inverted", "bytes=9-2", 0, 0, false, false},
		{"empty suffix", "bytes=-", 0, 0, false, false},
		{"unknown unit is ignored", "items=1-2", 0, 19, false, true},
		{"garbage is ignored", "not-a-range", 0, 19, false, true},
		{"first of a set wins", "bytes=3-6,10-12", 3, 6, true, true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			start, end, partial, ok := parseRange(tt.header, total)
			if ok != tt.wantOK {
				t.Fatalf("ok = %v, want %v (start=%d end=%d)", ok, tt.wantOK, start, end)
			}
			if !ok {
				return
			}
			if start != tt.wantStart || end != tt.wantEnd || partial != tt.wantPart {
				t.Errorf("got start=%d end=%d partial=%v; want %d %d %v",
					start, end, partial, tt.wantStart, tt.wantEnd, tt.wantPart)
			}
			if end-start+1 <= 0 {
				t.Errorf("range must cover at least one byte, got %d..%d", start, end)
			}
		})
	}
}

// TestCleanupStagedRemovesExpiredFiles covers the retention sweep the Python
// service never had, which meant /tmp/cs-files only emptied on reboot.
func TestCleanupStagedRemovesExpiredFiles(t *testing.T) {
	fs, err := newFileServer(fileServerConfig{
		StageDir:  t.TempDir(),
		PhoneSave: t.TempDir(),
		Signal:    func(string, any) {},
	})
	if err != nil {
		t.Fatal(err)
	}
	// One old entry and one fresh entry.
	oldPath := filepath.Join(fs.stageDir, "old")
	newPath := filepath.Join(fs.stageDir, "new")
	os.WriteFile(oldPath, []byte("old"), 0o600)
	os.WriteFile(newPath, []byte("new"), 0o600)

	fs.mu.Lock()
	fs.staged["old"] = &StagedEntry{ID: "old", Path: oldPath, TS: 1}
	fs.staged["new"] = &StagedEntry{ID: "new", Path: newPath, TS: float64(nowUnix())}
	fs.mu.Unlock()

	removed := fs.cleanupStaged(time.Hour)
	if removed != 1 {
		t.Errorf("removed = %d, want 1", removed)
	}
	if _, err := os.Stat(oldPath); !os.IsNotExist(err) {
		t.Error("the expired file should have been deleted")
	}
	if _, err := os.Stat(newPath); err != nil {
		t.Error("the fresh file must be kept")
	}
	fs.mu.Lock()
	_, stillListed := fs.staged["old"]
	fs.mu.Unlock()
	if stillListed {
		t.Error("an expired entry must be dropped from the ledger too")
	}
}

func nowUnix() int64 { return time.Now().Unix() }

func TestConfigFromEnvDefaults(t *testing.T) {
	empty := func(string) string { return "" }
	cfg, err := ConfigFromEnv(empty)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ClipboardPort != DefaultClipboardPort || cfg.FilePort != DefaultFilePort {
		t.Errorf("ports = %d/%d, want %d/%d", cfg.ClipboardPort, cfg.FilePort,
			DefaultClipboardPort, DefaultFilePort)
	}
	// The Python original defaulted the host to a hardcoded Tailscale IP.
	// That must not come back.
	if cfg.Host == "100.92.160.31" {
		t.Error("the machine-specific fallback IP must not be reproduced")
	}
	if cfg.Host != "127.0.0.1" {
		t.Errorf("unset host should fall back to loopback, got %q", cfg.Host)
	}
}

func TestConfigFromEnvRejectsIdenticalPorts(t *testing.T) {
	same := func(key string) string {
		if key == "EARTHQUACK_PORT" || key == "EARTHQUACK_FILE_PORT" {
			return "9000"
		}
		return ""
	}
	if _, err := ConfigFromEnv(same); err == nil {
		t.Error("two services on one port must be rejected, not silently shadowed")
	}
}

func TestConfigFromEnvHonoursLegacyAliases(t *testing.T) {
	// clip-send.sh and friends still use the CLIPBOARD_* names.
	legacy := func(key string) string {
		switch key {
		case "CLIPBOARD_SERVER_HOST":
			return "100.64.1.2"
		case "CLIPBOARD_SERVER_PORT":
			return "9999"
		}
		return ""
	}
	cfg, err := ConfigFromEnv(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Host != "100.64.1.2" {
		t.Errorf("legacy host alias ignored: %q", cfg.Host)
	}
	if cfg.ClipboardPort != 9999 {
		t.Errorf("legacy port alias ignored: %d", cfg.ClipboardPort)
	}
}

func TestConfigFromEnvIgnoresGarbagePorts(t *testing.T) {
	// A malformed port must fall back rather than crash the daemon, which
	// is what the Python int() did at import time.
	garbage := func(key string) string {
		if key == "EARTHQUACK_PORT" {
			return "not-a-port"
		}
		return ""
	}
	cfg, err := ConfigFromEnv(garbage)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ClipboardPort != DefaultClipboardPort {
		t.Errorf("garbage port should fall back to %d, got %d", DefaultClipboardPort, cfg.ClipboardPort)
	}
}
