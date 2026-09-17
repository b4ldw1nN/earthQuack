package wallpaper

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// telegramServer is a minimal fake Bot API for tests. It records calls,
// simulates forum-topic creation/reuse, and can inject 429 + Retry-After.
type telegramServer struct {
	mu           sync.Mutex
	created      map[string]int64
	nextID       int64
	sendDocs     []string
	createCalls  int
	retry429     []int
	calls        []string
	staleThreads map[int64]bool // thread ids Telegram has "forgotten"
	failCreate   bool           // make createForumTopic fail outright
	failTopics   bool           // make getForumTopics fail outright
}

func newTelegramServer() *telegramServer {
	return &telegramServer{created: map[string]int64{}, nextID: 100, retry429: []int{}, staleThreads: map[int64]bool{}}
}

// markStale makes a thread id look deleted server-side.
func (s *telegramServer) markStale(id int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.staleThreads[id] = true
}

func (s *telegramServer) handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		s.calls = append(s.calls, r.URL.Path)
		method := methodFromPath(r.URL.Path)
		s.mu.Unlock()

		switch method {
		case "createForumTopic":
			s.mu.Lock()
			if s.failCreate {
				s.mu.Unlock()
				writeJSONStatus(w, http.StatusInternalServerError, map[string]any{"ok": false, "description": "Internal Server Error"})
				return
			}
			name := r.FormValue("name")
			id, ok := s.created[name]
			var result map[string]any
			switch {
			case ok && s.staleThreads[id]:
				// The previous topic was deleted server-side: creating
				// it again succeeds with a brand new thread id.
				delete(s.staleThreads, id)
				s.nextID++
				id = s.nextID
				s.created[name] = id
				s.createCalls++
				result = map[string]any{"ok": true, "result": map[string]any{"message_thread_id": id, "name": name}}
			case ok:
				result = map[string]any{"ok": false, "description": "there is already a topic with the same name"}
			default:
				s.nextID++
				id = s.nextID
				s.created[name] = id
				s.createCalls++
				result = map[string]any{"ok": true, "result": map[string]any{"message_thread_id": id, "name": name}}
			}
			s.mu.Unlock()
			writeJSON(w, result)
		case "getForumTopics":
			s.mu.Lock()
			if s.failTopics {
				s.mu.Unlock()
				writeJSONStatus(w, http.StatusInternalServerError, map[string]any{"ok": false, "description": "Internal Server Error"})
				return
			}
			topics := []any{}
			for name, id := range s.created {
				topics = append(topics, map[string]any{"message_thread_id": id, "name": name})
			}
			s.mu.Unlock()
			writeJSON(w, map[string]any{"ok": true, "result": map[string]any{"total_count": len(topics), "topics": topics}})
		case "sendDocument":
			if len(s.retry429) > 0 {
				s.mu.Lock()
				secs := s.retry429[0]
				s.retry429 = s.retry429[1:]
				s.mu.Unlock()
				writeJSONStatus(w, http.StatusTooManyRequests, map[string]any{
					"ok": false, "description": "Too Many Requests",
					"parameters": map[string]any{"retry_after": secs},
				})
				return
			}
			fields, fname := readSendDocument(r)
			threadID, _ := strconv.ParseInt(fields["message_thread_id"], 10, 64)
			s.mu.Lock()
			stale := s.staleThreads[threadID]
			s.mu.Unlock()
			if stale {
				writeJSONStatus(w, http.StatusBadRequest, map[string]any{
					"ok": false, "description": "Bad Request: message thread not found",
				})
				return
			}
			s.mu.Lock()
			s.sendDocs = append(s.sendDocs, fname)
			s.mu.Unlock()
			writeJSON(w, map[string]any{"ok": true, "result": map[string]any{"message_id": 1}})
		default:
			writeJSON(w, map[string]any{"ok": false, "description": "unknown method " + method})
		}
	})
}

func methodFromPath(p string) string {
	return p[strings.LastIndex(p, "/")+1:]
}

func writeJSON(w http.ResponseWriter, v any) {
	writeJSONStatus(w, http.StatusOK, v)
}

func writeJSONStatus(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// readSendDocument consumes a streaming multipart sendDocument body once,
// returning the scalar fields and the document filename. It must be the
// only reader of the body (FormValue would consume it first).
func readSendDocument(r *http.Request) (map[string]string, string) {
	fields := map[string]string{}
	mr, err := r.MultipartReader()
	if err != nil {
		return fields, ""
	}
	filename := ""
	for {
		part, err := mr.NextPart()
		if err != nil {
			return fields, filename
		}
		if part.FileName() != "" {
			io.Copy(io.Discard, part)
			if part.FormName() == "document" {
				filename = part.FileName()
			}
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(part, 4096))
		fields[part.FormName()] = string(b)
	}
}

func TestTelegramTopicReuse(t *testing.T) {
	ts := newTelegramServer()
	srv := httptest.NewServer(ts.handler())
	defer srv.Close()

	state := t.TempDir()
	p, err := NewTelegramProvider(TelegramConfig{Token: "111:TOKEN", ChatID: "-100", APIBase: srv.URL}, state, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	src := writeFile(t, t.TempDir(), "x.png", []byte("data"))
	f := ArchiveFile{SourcePath: src, Filename: "x.png", Category: "Tokyo Night", Digest: "abc", Size: 1}
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatal(err)
	}
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatal(err)
	}
	if ts.createCalls != 1 {
		t.Fatalf("expected exactly 1 createForumTopic, got %d", ts.createCalls)
	}
	tp, err := NewTelegramProvider(TelegramConfig{Token: "111:TOKEN", ChatID: "-100", APIBase: srv.URL}, state, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	if id, ok := tp.Topics()["Tokyo Night"]; !ok || id == 0 {
		t.Fatalf("topic mapping not persisted/reused: %+v", tp.Topics())
	}
}

func TestTelegramNoDuplicateTopicWhenExists(t *testing.T) {
	ts := newTelegramServer()
	ts.mu.Lock()
	ts.created["Edge Runner"] = 500
	ts.mu.Unlock()

	srv := httptest.NewServer(ts.handler())
	defer srv.Close()
	p, err := NewTelegramProvider(TelegramConfig{Token: "t", ChatID: "-100", APIBase: srv.URL}, t.TempDir(), srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	f := ArchiveFile{SourcePath: writeFile(t, t.TempDir(), "e.png", []byte("data")), Filename: "e.png", Category: "Edge Runner", Digest: "d", Size: 1}
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatal(err)
	}
	if id, ok := p.Topics()["Edge Runner"]; !ok || id != 500 {
		t.Fatalf("duplicate topic should resolve to existing id 500, got %+v", p.Topics())
	}
}

func TestTelegramHonoursRetryAfter(t *testing.T) {
	ts := newTelegramServer()
	ts.retry429 = []int{1, 1}

	srv := httptest.NewServer(ts.handler())
	defer srv.Close()

	p, err := NewTelegramProvider(TelegramConfig{Token: "t", ChatID: "-100", APIBase: srv.URL}, t.TempDir(), srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	p.sleep = func(time.Duration) {}

	f := ArchiveFile{SourcePath: writeFile(t, t.TempDir(), "r.png", []byte("data")), Filename: "r.png", Category: "Unsorted", Digest: "d", Size: 1}
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatalf("expected eventual success after Retry-After: %v", err)
	}
}

func TestTelegramErrorNeverLeaksToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSONStatus(w, 400, map[string]any{"ok": false, "description": "Bad Request"})
	}))
	defer srv.Close()
	p, err := NewTelegramProvider(TelegramConfig{Token: "SECRET_TOKEN_DO_NOT_LEAK", ChatID: "-100", APIBase: srv.URL}, t.TempDir(), srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	f := ArchiveFile{SourcePath: writeFile(t, t.TempDir(), "z.png", []byte("data")), Filename: "z.png", Category: "Unsorted", Digest: "d", Size: 1}
	err = p.Upload(context.Background(), f)
	if err == nil {
		t.Fatal("expected an error from a 400 response")
	}
	if strings.Contains(err.Error(), "SECRET_TOKEN_DO_NOT_LEAK") {
		t.Fatal("telegram error must not contain the bot token")
	}
	if _, ok := err.(*TelegramError); !ok {
		t.Fatalf("expected *TelegramError, got %T", err)
	}
}

func TestTelegramMultipartContainsDocument(t *testing.T) {
	ts := newTelegramServer()
	srv := httptest.NewServer(ts.handler())
	defer srv.Close()
	p, err := NewTelegramProvider(TelegramConfig{Token: "t", ChatID: "-100", APIBase: srv.URL}, t.TempDir(), srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	src := writeFile(t, t.TempDir(), "with space.png", []byte("data"))
	f := ArchiveFile{SourcePath: src, Filename: "with space.png", Category: "Catppuccin Latte", Digest: "d", Size: 4}
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatal(err)
	}
	if len(ts.sendDocs) != 1 || ts.sendDocs[0] != "with space.png" {
		t.Fatalf("document filename not delivered: %+v", ts.sendDocs)
	}
}

func TestTelegramRejectsMissingCredentials(t *testing.T) {
	if _, err := NewTelegramProvider(TelegramConfig{Token: "", ChatID: "x"}, t.TempDir(), nil); err == nil {
		t.Error("missing token must error")
	}
	if _, err := NewTelegramProvider(TelegramConfig{Token: "t", ChatID: ""}, t.TempDir(), nil); err == nil {
		t.Error("missing chat id must error")
	}
}

// TestTelegramRecoversStaleTopic covers the retry path of the original
// wallpaper-backup implementation: when Telegram says the cached topic no
// longer exists, the provider recreates it and re-sends the file.
func TestTelegramRecoversStaleTopic(t *testing.T) {
	ts := newTelegramServer()
	srv := httptest.NewServer(ts.handler())
	defer srv.Close()

	state := t.TempDir()
	p, err := NewTelegramProvider(TelegramConfig{Token: "t", ChatID: "-100", APIBase: srv.URL}, state, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	p.sleep = func(time.Duration) {}
	src := writeFile(t, t.TempDir(), "a.png", []byte("data"))
	f := ArchiveFile{SourcePath: src, Filename: "a.png", Category: "Anime", Digest: "d", Size: 4}

	// First upload creates the topic and succeeds.
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatal(err)
	}
	first := p.Topics()["Anime"]
	if first == 0 {
		t.Fatal("expected a topic to be created")
	}
	if ts.createCalls != 1 {
		t.Fatalf("want 1 topic creation, got %d", ts.createCalls)
	}

	// The topic is deleted server-side: the cached id is now stale.
	ts.markStale(first)
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatalf("upload must recover from a stale topic: %v", err)
	}
	recovered := p.Topics()["Anime"]
	if recovered == first {
		t.Fatalf("stale topic id must be replaced, still %d", recovered)
	}
	if ts.createCalls != 2 {
		t.Fatalf("want the topic recreated (2 creations), got %d", ts.createCalls)
	}
	if len(ts.sendDocs) != 2 {
		t.Fatalf("want the document re-sent after recovery, got %v", ts.sendDocs)
	}
	// The recovered mapping must be persisted for the next run.
	persisted, err := readJSONObject[int64](filepath.Join(state, topicsFileName))
	if err != nil {
		t.Fatal(err)
	}
	if persisted["Anime"] != recovered {
		t.Fatalf("topics.json must hold the recovered id: %v", persisted)
	}
}

// TestTelegramStaleTopicRecoveryFailureIsReported ensures a failure to
// recover is never swallowed: the caller still sees the original
// upload error (which is what gets recorded in failed.json).
func TestTelegramStaleTopicRecoveryFailureIsReported(t *testing.T) {
	ts := newTelegramServer()
	srv := httptest.NewServer(ts.handler())
	defer srv.Close()

	state := t.TempDir()
	if err := writeJSONAtomic(filepath.Join(state, topicsFileName), map[string]int64{"Anime": 42}, true); err != nil {
		t.Fatal(err)
	}
	p, err := NewTelegramProvider(TelegramConfig{Token: "t", ChatID: "-100", APIBase: srv.URL}, state, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	p.sleep = func(time.Duration) {}

	// The cached topic is gone and topic creation is unavailable.
	ts.markStale(42)
	ts.mu.Lock()
	ts.failCreate = true
	ts.failTopics = true
	ts.mu.Unlock()

	src := writeFile(t, t.TempDir(), "a.png", []byte("data"))
	f := ArchiveFile{SourcePath: src, Filename: "a.png", Category: "Anime", Digest: "d", Size: 4}
	err = p.Upload(context.Background(), f)
	if err == nil {
		t.Fatal("expected an error when the stale topic cannot be recovered")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "thread not found") {
		t.Fatalf("the original failure must be reported: %v", err)
	}
	if !strings.Contains(err.Error(), "topic recovery failed") {
		t.Fatalf("the recovery failure must be reported too: %v", err)
	}
}

// TestTelegramStaleTopicFallsBackToLookup covers the other recovery
// branch: recreation reports the name as taken, so the provider resolves
// the live topic by lookup instead of creating a duplicate.
func TestTelegramStaleTopicFallsBackToLookup(t *testing.T) {
	ts := newTelegramServer()
	srv := httptest.NewServer(ts.handler())
	defer srv.Close()

	state := t.TempDir()
	if err := writeJSONAtomic(filepath.Join(state, topicsFileName), map[string]int64{"Anime": 42}, true); err != nil {
		t.Fatal(err)
	}
	p, err := NewTelegramProvider(TelegramConfig{Token: "t", ChatID: "-100", APIBase: srv.URL}, state, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	p.sleep = func(time.Duration) {}

	// Thread 42 is gone, but a live topic with the same name exists (7).
	ts.markStale(42)
	ts.mu.Lock()
	ts.created["Anime"] = 7
	ts.mu.Unlock()

	src := writeFile(t, t.TempDir(), "a.png", []byte("data"))
	f := ArchiveFile{SourcePath: src, Filename: "a.png", Category: "Anime", Digest: "d", Size: 4}
	if err := p.Upload(context.Background(), f); err != nil {
		t.Fatalf("upload must recover via lookup: %v", err)
	}
	if got := p.Topics()["Anime"]; got != 7 {
		t.Fatalf("want the live topic id 7, got %d", got)
	}
	if ts.createCalls != 0 {
		t.Fatalf("no duplicate topic may be created, got %d creations", ts.createCalls)
	}
	if len(ts.sendDocs) != 1 {
		t.Fatalf("want the document delivered once, got %v", ts.sendDocs)
	}
}

func TestIsStaleTopicError(t *testing.T) {
	stale := []string{
		"telegram: Bad Request: message thread not found",
		"telegram: Bad Request: thread not found",
		"telegram: topic not found",
		"telegram: upload failed after 3 attempts: Bad Request: MESSAGE THREAD NOT FOUND",
	}
	for _, msg := range stale {
		if !isStaleTopicError(errors.New(msg)) {
			t.Errorf("%q must be classified as a stale topic", msg)
		}
	}
	for _, msg := range []string{"", "telegram: Too Many Requests", "telegram: Bad Request: chat not found"} {
		if isStaleTopicError(errors.New(msg)) {
			t.Errorf("%q must NOT be classified as a stale topic", msg)
		}
	}
	if isStaleTopicError(nil) {
		t.Error("nil must not be a stale topic error")
	}
}
