package daemon

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// watchInterval matches watch_send_folder.py's 0.3s scan.
const watchInterval = 300 * time.Millisecond

// SendFolderWatcher uploads files dropped into a directory to the phone and
// removes them on success. It replaces daemon/watch_send_folder.py.
//
// It keeps the original's polling model rather than using inotify: the
// directory is watched across reboots and network shares, where a
// filesystem event API is unreliable, and a 0.3s poll of a handful of
// entries costs nothing.
type SendFolderWatcher struct {
	dir        string
	script     string
	authToken  string
	host       string
	filePort   int
	interval   time.Duration
	uploadFunc func(ctx context.Context, path, name string) error
}

// NewSendFolderWatcher builds a watcher for dir. script is the uploader to
// invoke; when it is missing or fails, uploads fall back to an in-process
// HTTP POST so the watcher still works on a machine without the helper.
func NewSendFolderWatcher(dir, script, authToken, host string, filePort int) *SendFolderWatcher {
	w := &SendFolderWatcher{
		dir:       dir,
		script:    script,
		authToken: authToken,
		host:      host,
		filePort:  filePort,
		interval:  watchInterval,
	}
	w.uploadFunc = w.uploadViaScript
	return w
}

// Run watches until ctx is cancelled.
func (w *SendFolderWatcher) Run(ctx context.Context) error {
	if err := os.MkdirAll(w.dir, 0o700); err != nil {
		return fmt.Errorf("daemon: send folder: %w", err)
	}
	logf("watch: %s (drop files here to send to the phone)", w.dir)

	// Seed with what is already there so a restart does not re-send files
	// that were present at startup — matching the Python original.
	seen := make(map[string]bool)
	if entries, err := os.ReadDir(w.dir); err == nil {
		for _, e := range entries {
			seen[e.Name()] = true
		}
	}

	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			w.scan(ctx, seen)
		}
	}
}

// scan sends any newly appeared file.
func (w *SendFolderWatcher) scan(ctx context.Context, seen map[string]bool) {
	entries, err := os.ReadDir(w.dir)
	if err != nil {
		// A transient read failure must not be fatal; the next tick retries.
		return
	}
	for _, e := range entries {
		name := e.Name()
		if seen[name] {
			continue
		}
		seen[name] = true
		// Hidden and partial files are ignored: a dotfile is usually a
		// transfer in progress, not something to send.
		if strings.HasPrefix(name, ".") || e.IsDir() {
			continue
		}
		path := filepath.Join(w.dir, name)
		if err := w.uploadFunc(ctx, path, name); err != nil {
			// Leave the file in place so the next run can retry it.
			logf("watch: send %s failed: %v", name, err)
			delete(seen, name)
			continue
		}
		if err := os.Remove(path); err != nil {
			logf("watch: could not remove %s: %v", name, err)
			continue
		}
		logf("watch: sent and removed %s", name)
	}
}

// uploadViaScript shells out to clip-send.sh, the same path the Python
// watcher used.
func (w *SendFolderWatcher) uploadViaScript(ctx context.Context, path, name string) error {
	if w.script == "" {
		return w.uploadInProcess(ctx, path, name)
	}
	if _, err := os.Stat(w.script); err != nil {
		// The helper is not installed next to the binary; fall back rather
		// than failing every send.
		return w.uploadInProcess(ctx, path, name)
	}
	cmd := exec.CommandContext(ctx, "bash", w.script, path)
	var out bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &out
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("clip-send.sh: %w: %s", err, strings.TrimSpace(out.String()))
	}
	return nil
}

// uploadInProcess posts the file straight to the file service, which is
// what clip-send.sh does. Sending X-Origin: desktop stages it and signals
// the phone.
func (w *SendFolderWatcher) uploadInProcess(ctx context.Context, path, name string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()

	url := fmt.Sprintf("http://%s:%d/upload", w.host, w.filePort)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, f)
	if err != nil {
		return err
	}
	req.Header.Set("X-Filename", name)
	req.Header.Set("X-Origin", OriginDesktop)
	req.Header.Set("Content-Type", "application/octet-stream")
	if w.authToken != "" {
		req.Header.Set("Authorization", "Bearer "+w.authToken)
	}
	if info, err := f.Stat(); err == nil {
		req.ContentLength = info.Size()
	}

	client := &http.Client{Timeout: 2 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("upload returned %s", resp.Status)
	}
	return nil
}
