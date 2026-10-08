package daemon

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// uploadChunk is the streaming copy size for uploads and downloads. It
// matches daemon/file_server.py's CHUNK so memory use on a large phone
// transfer is identical.
const uploadChunk = 256 * 1024 // 256 KiB

// StagedEntry is one Desktop→Phone pending file, as returned by GET /files.
//
// The JSON field names are the contract: Android's FileTransferService
// reads id, name and size from the file_ready signal, and GET /files is
// consumed by the desktop tooling. `path` is included because the Python
// service returned it; note it discloses a server-local path to any
// authenticated client.
type StagedEntry struct {
	ID   string  `json:"id"`
	Name string  `json:"name"`
	Size int64   `json:"size"`
	Path string  `json:"path"`
	TS   float64 `json:"ts"`
}

// fileServer is the :8876 service, a rewrite of daemon/file_server.py.
//
// The one structural change: announcing a staged file to the phone no
// longer goes through an HTTP POST back to 127.0.0.1:8875. The Python
// service did that because it was a separate process. Here both services
// share one Broker, so Signal is a direct call. That removes a loopback
// dependency, a 3-second timeout on the send path, and a failure mode
// where a clipboard-server outage silently broke file transfer.
type fileServer struct {
	stageDir  string
	phoneSave string
	signal    func(eventType string, data any)
	mu        sync.Mutex
	staged    map[string]*StagedEntry
}

// fileServerConfig carries the directories and collaborators the file
// service needs.
type fileServerConfig struct {
	StageDir  string
	PhoneSave string
	Signal    func(eventType string, data any)
}

func newFileServer(cfg fileServerConfig) (*fileServer, error) {
	if cfg.Signal == nil {
		return nil, errors.New("daemon: file server needs a signal function")
	}
	for _, dir := range []string{cfg.StageDir, cfg.PhoneSave} {
		if dir == "" {
			return nil, errors.New("daemon: file server needs both a stage dir and a phone save dir")
		}
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, fmt.Errorf("daemon: file server mkdir %s: %w", dir, err)
		}
	}
	return &fileServer{
		stageDir:  cfg.StageDir,
		phoneSave: cfg.PhoneSave,
		signal:    cfg.Signal,
		staged:    make(map[string]*StagedEntry),
	}, nil
}

func newFileHandler(fs *fileServer, token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/upload", fs.handleUpload)
	mux.HandleFunc("/files", fs.handleFiles)
	mux.HandleFunc("/download/", fs.handleDownload)
	mux.HandleFunc("/", notFound)
	return bearerAuth(token, mux)
}

// randomID returns a 32-character hex id, matching uuid4().hex in Python.
func randomID() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", fmt.Errorf("daemon: random id: %w", err)
	}
	return hex.EncodeToString(raw), nil
}

// handleUpload accepts a file.
//
// Direction is decided by X-Origin, exactly as before:
//   - "phone"  → save under ~/Downloads/from-phone/, never overwriting
//   - anything else → stage and signal the phone
func (fs *fileServer) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	name := r.Header.Get("X-Filename")
	if name == "" {
		id, err := randomID()
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "could not allocate id")
			return
		}
		name = "file_" + id[:6]
	}
	// A filename is attacker-controlled and lands in a path, so reduce it
	// to a base name. The Python original joined it directly, which let
	// "../../.bashrc" escape the target directory.
	name = filepath.Base(filepath.FromSlash(name))
	if name == "." || name == string(filepath.Separator) || name == ".." {
		writeJSONError(w, http.StatusBadRequest, "invalid filename")
		return
	}
	origin := r.Header.Get("X-Origin")
	if origin == "" {
		origin = "unknown"
	}

	if origin == OriginPhone {
		fs.receiveFromPhone(w, name, r)
		return
	}
	fs.stageForPhone(w, name, r)
}

func (fs *fileServer) receiveFromPhone(w http.ResponseWriter, name string, r *http.Request) {
	dest := filepath.Join(fs.phoneSave, name)
	if _, err := os.Stat(dest); err == nil {
		// Never clobber an existing download: keep the extension, append a
		// short random suffix, as the Python original did.
		ext := filepath.Ext(name)
		stem := strings.TrimSuffix(name, ext)
		id, err := randomID()
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "could not allocate id")
			return
		}
		dest = filepath.Join(fs.phoneSave, stem+"_"+id[:6]+ext)
	}
	written, err := streamToFile(w, r, dest)
	if err != nil {
		logf("file: phone→desktop %s: %v", dest, err)
		writeJSONError(w, http.StatusInternalServerError, "could not store upload")
		return
	}
	logf("file: phone→desktop %s (%d bytes)", dest, written)
	// No desktop notification: a file arriving from the phone is silent.
	// The Python original shelled out to notify-send here, which also
	// interpolated the client-supplied filename into a shell command.
	// The Python original returned an empty id for this direction: the file
	// is already on the desktop, so there is nothing for the phone to fetch.
	writeJSON(w, http.StatusOK, map[string]any{"id": "", "name": filepath.Base(dest), "size": written})
}

func (fs *fileServer) stageForPhone(w http.ResponseWriter, name string, r *http.Request) {
	id, err := randomID()
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "could not allocate id")
		return
	}
	dest := filepath.Join(fs.stageDir, id)
	written, err := streamToFile(w, r, dest)
	if err != nil {
		logf("file: desktop→phone %s: %v", dest, err)
		writeJSONError(w, http.StatusInternalServerError, "could not stage upload")
		return
	}
	entry := &StagedEntry{
		ID:   id,
		Name: name,
		Size: written,
		Path: dest,
		TS:   float64(time.Now().UnixNano()) / 1e9,
	}
	fs.mu.Lock()
	fs.staged[id] = entry
	fs.mu.Unlock()

	logf("file: desktop→phone staged %s id=%s (%d bytes)", name, id, written)

	// Announce to the phone. The shape is the contract Android parses in
	// handleFileReadyEvent: type, id, name, size.
	fs.signal("file_ready", map[string]any{
		"type": "file_ready",
		"id":   id,
		"name": name,
		"size": written,
	})
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "name": name, "size": written})
}

// handleFiles lists staged Desktop→Phone files.
func (fs *fileServer) handleFiles(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	fs.mu.Lock()
	out := make([]*StagedEntry, 0, len(fs.staged))
	for _, e := range fs.staged {
		out = append(out, e)
	}
	fs.mu.Unlock()
	writeJSON(w, http.StatusOK, out)
}

// handleDownload serves a staged file, with Range support so the phone can
// resume an interrupted transfer.
//
// Status codes follow the original: 404 for an unknown id, 410 when the
// file has since disappeared from the stage directory, 206 for a ranged
// request.
func (fs *fileServer) handleDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/download/")
	if id == "" || strings.Contains(id, "/") {
		writeJSONError(w, http.StatusNotFound, "not found")
		return
	}
	fs.mu.Lock()
	entry := fs.staged[id]
	fs.mu.Unlock()
	if entry == nil {
		writeJSONError(w, http.StatusNotFound, "not found")
		return
	}

	f, err := os.Open(entry.Path)
	if err != nil {
		if os.IsNotExist(err) {
			// Gone: the stage was cleaned but the ledger still lists it.
			writeJSONError(w, http.StatusGone, "staged file no longer exists")
			return
		}
		writeJSONError(w, http.StatusInternalServerError, "could not open staged file")
		return
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "could not stat staged file")
		return
	}
	total := info.Size()

	start, end, partial, ok := parseRange(r.Header.Get("Range"), total)
	if !ok {
		writeJSONError(w, http.StatusRequestedRangeNotSatisfiable, "range not satisfiable")
		return
	}

	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", entry.Name))
	h.Set("Accept-Ranges", "bytes")
	h.Set("Content-Length", strconv.FormatInt(end-start+1, 10))
	if partial {
		h.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, total))
		w.WriteHeader(http.StatusPartialContent)
	} else {
		w.WriteHeader(http.StatusOK)
	}
	if r.Method == http.MethodHead {
		return
	}

	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return
	}
	n, err := io.CopyN(w, f, end-start+1)
	if err != nil && !errors.Is(err, io.EOF) {
		// A client that hangs up mid-transfer is normal, not an error worth
		// alarming about; the bytes sent so far are still useful to it.
		logf("file: download %s interrupted after %d bytes: %v", entry.Name, n, err)
		return
	}
	logf("file: sent %s (%d bytes) to phone", entry.Name, n)
}

// parseRange parses a single byte range against a known total size.
//
// This is a deliberate improvement on the Python original, which did
// int(spec.split("-")[0]) and therefore mis-handled open-ended and suffix
// ranges. The shapes the phone actually sends (bytes=N-, bytes=N-M) parse
// identically, so resumable downloads are unaffected; bytes=-N now means
// "last N bytes" as RFC 7233 requires instead of silently returning the
// whole file.
func parseRange(header string, total int64) (start, end int64, partial, ok bool) {
	if total <= 0 {
		return 0, 0, false, header == ""
	}
	if header == "" {
		return 0, total - 1, false, true
	}
	const prefix = "bytes="
	if len(header) < len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		// An unrecognised unit is ignored per RFC 7233: serve the whole
		// entity rather than failing.
		return 0, total - 1, false, true
	}
	spec := strings.TrimSpace(header[len(prefix):])
	// Only the first range of a possible set is honoured; multi-range
	// would require multipart/byteranges, which no client here asks for.
	if comma := strings.IndexByte(spec, ','); comma >= 0 {
		spec = spec[:comma]
	}
	dash := strings.IndexByte(spec, '-')
	if dash < 0 {
		return 0, 0, false, false
	}
	fromStr := strings.TrimSpace(spec[:dash])
	toStr := strings.TrimSpace(spec[dash+1:])

	if fromStr == "" {
		// Suffix form: bytes=-N means the final N bytes.
		if toStr == "" {
			return 0, 0, false, false
		}
		n, err := strconv.ParseInt(toStr, 10, 64)
		if err != nil || n <= 0 {
			return 0, 0, false, false
		}
		if n > total {
			n = total
		}
		return total - n, total - 1, true, true
	}

	start, err := strconv.ParseInt(fromStr, 10, 64)
	if err != nil || start < 0 || start >= total {
		return 0, 0, false, false
	}
	end = total - 1
	if toStr != "" {
		if v, err := strconv.ParseInt(toStr, 10, 64); err == nil {
			if v < start {
				return 0, 0, false, false
			}
			if v < end {
				end = v
			}
		}
	}
	return start, end, true, true
}

// streamToFile copies the request body to path in bounded chunks and
// returns the number of bytes written.
//
// It reads at most maxUpload bytes and rejects anything larger, so an
// unauthenticated-then-authenticated caller cannot fill the disk. The
// Python original trusted Content-Length entirely and would happily write
// whatever arrived.
func streamToFile(w http.ResponseWriter, r *http.Request, path string) (int64, error) {
	max := int64(maxUploadBytes)
	if r.ContentLength > max {
		return 0, fmt.Errorf("upload of %d bytes exceeds the %d byte limit", r.ContentLength, max)
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return 0, err
	}
	defer f.Close()

	// Read one byte past the limit so an oversized body with a lying
	// Content-Length is detected rather than silently truncated.
	limited := io.LimitReader(r.Body, max+1)
	written, err := io.Copy(f, io.LimitReader(limited, max+1))
	if err != nil {
		os.Remove(path)
		return 0, err
	}
	if written > max {
		os.Remove(path)
		return 0, fmt.Errorf("upload exceeds the %d byte limit", max)
	}
	if err := f.Sync(); err != nil {
		return written, fmt.Errorf("fsync: %w", err)
	}
	return written, nil
}

// maxUploadBytes caps a single transfer at 2 GiB: far beyond any clipboard
// payload, and low enough that a runaway upload cannot exhaust a home
// server's disk in one request.
const maxUploadBytes = 2 << 30

// cleanupStaged removes staged files older than maxAge and drops their
// ledger entries, so /tmp does not accumulate every file ever sent.
//
// The Python service never cleaned up: /tmp/cs-files only emptied on
// reboot. For a home server that is a slow disk leak.
func (fs *fileServer) cleanupStaged(maxAge time.Duration) int {
	cutoff := time.Now().Add(-maxAge)
	fs.mu.Lock()
	defer fs.mu.Unlock()
	removed := 0
	for id, e := range fs.staged {
		if time.Unix(0, int64(e.TS*1e9)).After(cutoff) {
			continue
		}
		if err := os.Remove(e.Path); err == nil {
			removed++
		}
		delete(fs.staged, id)
	}
	return removed
}
