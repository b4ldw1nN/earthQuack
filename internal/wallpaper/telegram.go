package wallpaper

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// TelegramConfig carries the Telegram-specific provider configuration.
// Credentials come exclusively from the caller (environment/config at
// the composition root); they are never hardcoded.
type TelegramConfig struct {
	// Token is the bot token (TELEGRAM_BOT_TOKEN). Kept private, never
	// logged, never rendered.
	Token string
	// ChatID is the target chat/supergroup id (TELEGRAM_CHAT_ID).
	ChatID string
	// APIBase overrides the Bot API origin (test hook; default
	// https://api.telegram.org). Not a secret.
	APIBase string
}

// telegramMaxSize is Telegram's 50 MB Bot API upload limit — the same
// limit enforced by the original wallpaper-backup implementation.
const telegramMaxSize = 50 * 1024 * 1024

// topicsFileName is the Telegram provider's own state file mapping a
// forum-topic name (category) to its numeric thread id. It is re-used
// from the original implementation so no topics are duplicated.
const topicsFileName = "topics.json"

// defaultTelegramBase is the public Bot API endpoint.
const defaultTelegramBase = "https://api.telegram.org"

// telegramHTTPTimeout bounds a single Bot API request (connect + read).
// The original implementation used 20s/120s; we cap total at 3 minutes.
const telegramHTTPTimeout = 3 * time.Minute

// TelegramError is the provider's typed error for Bot API failures.
// It carries a machine-readable description but never the bot token.
type TelegramError struct {
	Code        int    // HTTP status when known
	Description string // Telegram's error description (for humans)
	RetryAfter  int    // seconds Telegram asked us to wait (0 = none)
}

func (e *TelegramError) Error() string {
	if e.Description != "" {
		return fmt.Sprintf("telegram: %s", e.Description)
	}
	return fmt.Sprintf("telegram: error code %d", e.Code)
}

// TelegramProvider archives files to a Telegram forum. It owns all
// Telegram-specific behaviour: Bot API calls, forum-topic creation and
// lookup, topic-id reuse, sendDocument, retry/backoff, and Retry-After.
type TelegramProvider struct {
	cfg      TelegramConfig
	base     string
	stateDir string
	client   *http.Client
	backoff  Backoff
	sleep    func(time.Duration)
	mu       sync.Mutex
	topics   map[string]int64
}

// NewTelegramProvider builds a provider. The topics map is loaded from
// the state directory if present so existing topic ids are reused and
// no duplicate topics are ever created.
func NewTelegramProvider(cfg TelegramConfig, stateDir string, client *http.Client) (*TelegramProvider, error) {
	base := cfg.APIBase
	if base == "" {
		base = defaultTelegramBase
	}
	base = strings.TrimRight(base, "/")
	if cfg.Token == "" {
		return nil, fmt.Errorf("telegram: TELEGRAM_BOT_TOKEN is required")
	}
	if cfg.ChatID == "" {
		return nil, fmt.Errorf("telegram: TELEGRAM_CHAT_ID is required")
	}
	if client == nil {
		client = &http.Client{Timeout: telegramHTTPTimeout}
	}
	p := &TelegramProvider{
		cfg:      cfg,
		base:     base,
		stateDir: stateDir,
		client:   client,
		backoff:  DefaultBackoff,
		sleep:    time.Sleep,
		topics:   map[string]int64{},
	}
	if err := p.loadTopics(); err != nil {
		return nil, err
	}
	return p, nil
}

// Name implements ArchiveProvider.
func (p *TelegramProvider) Name() string { return "telegram" }

// MaxUploadSize implements SizeLimitedProvider.
func (p *TelegramProvider) MaxUploadSize() int64 { return telegramMaxSize }

// Topics returns a snapshot of the known category→thread-id map.
func (p *TelegramProvider) Topics() map[string]int64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make(map[string]int64, len(p.topics))
	for k, v := range p.topics {
		out[k] = v
	}
	return out
}

// Upload streams one file to its forum topic. category→topic resolution
// (with creation) happens inside the provider; the module never learns
// message_thread_id.
//
// If Telegram reports that the cached topic no longer exists (a topic
// deleted server-side), the stale mapping is discarded, the topic is
// recreated, and the upload is retried once — the same recovery the
// original wallpaper-backup implementation performs.
func (p *TelegramProvider) Upload(ctx context.Context, f ArchiveFile) error {
	topicID, err := p.ensureTopic(ctx, f.Category)
	if err != nil {
		return err
	}
	err = p.sendDocument(ctx, f, topicID)
	if err == nil {
		return nil
	}
	if !isStaleTopicError(err) {
		return err
	}
	recovered, rerr := p.recreateTopic(ctx, f.Category)
	if rerr != nil {
		// Report the original upload failure; the recovery failure is
		// secondary and would otherwise mask what went wrong.
		return fmt.Errorf("%w (topic recovery failed: %v)", err, rerr)
	}
	return p.sendDocument(ctx, f, recovered)
}

// topicFilePath returns the topics.json path in the state dir.
func (p *TelegramProvider) topicFilePath() string {
	return filepath.Join(p.stateDir, topicsFileName)
}

// loadTopics reads the existing topics.json (category → thread id) so
// previously created forums are reused verbatim.
func (p *TelegramProvider) loadTopics() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	m, err := readJSONObject[int64](p.topicFilePath())
	if err != nil {
		return err
	}
	for k, v := range m {
		p.topics[k] = v
	}
	return nil
}

// saveTopics atomically persists the category→thread-id map.
func (p *TelegramProvider) saveTopics() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return writeJSONAtomic(p.topicFilePath(), p.topics, true)
}

// ensureTopic returns the thread id for a category, creating the forum
// topic only if it does not already exist. It never creates duplicate
// topics: a race/duplicate error is resolved by looking the topic up
// by name.
func (p *TelegramProvider) ensureTopic(ctx context.Context, category string) (int64, error) {
	if id, ok := p.cachedTopic(category); ok {
		return id, nil
	}

	id, err := p.createForumTopic(ctx, category)
	if err == nil {
		if serr := p.rememberTopic(category, id); serr != nil {
			return 0, serr
		}
		return id, nil
	}
	// The topic already exists (duplicate creation or concurrent sync).
	if isDuplicateTopicError(err) {
		if found, ferr := p.findTopicID(ctx, category); ferr == nil && found != 0 {
			if serr := p.rememberTopic(category, found); serr != nil {
				return 0, serr
			}
			return found, nil
		}
	}
	return 0, err
}

// recreateTopic discards a stale category→thread mapping and creates a
// fresh forum topic for it, falling back to a lookup when the name is
// already taken server-side. It is the recovery path for a topic that
// the cached state claims exists but Telegram has since removed.
func (p *TelegramProvider) recreateTopic(ctx context.Context, category string) (int64, error) {
	p.forgetTopic(category)
	id, err := p.createForumTopic(ctx, category)
	if err != nil {
		if isDuplicateTopicError(err) {
			if found, ferr := p.findTopicID(ctx, category); ferr == nil && found != 0 {
				if serr := p.rememberTopic(category, found); serr != nil {
					return 0, serr
				}
				return found, nil
			}
		}
		return 0, err
	}
	if serr := p.rememberTopic(category, id); serr != nil {
		return 0, serr
	}
	return id, nil
}

// cachedTopic reads the category→thread-id map under the mutex.
func (p *TelegramProvider) cachedTopic(category string) (int64, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	id, ok := p.topics[category]
	return id, ok
}

// forgetTopic drops a cached mapping (e.g. after a stale-topic error).
func (p *TelegramProvider) forgetTopic(category string) {
	p.mu.Lock()
	delete(p.topics, category)
	p.mu.Unlock()
}

// rememberTopic records and persists a category→thread-id mapping.
func (p *TelegramProvider) rememberTopic(category string, id int64) error {
	p.mu.Lock()
	p.topics[category] = id
	p.mu.Unlock()
	return p.saveTopics()
}

// findTopicID scans the (paginated) forum topics for one matching name.
func (p *TelegramProvider) findTopicID(ctx context.Context, name string) (int64, error) {
	offset := 0
	for {
		values := url.Values{
			"chat_id": {p.cfg.ChatID},
			"limit":   {"100"},
		}
		if offset > 0 {
			values.Set("offset", strconv.Itoa(offset))
		}
		resp, err := p.callJSON(ctx, "getForumTopics", values)
		if err != nil {
			return 0, err
		}
		topics := decodeTopics(resp["topics"])
		for _, t := range topics {
			if t.Name == name {
				return t.ThreadID, nil
			}
		}
		if len(topics) < 100 {
			return 0, nil
		}
		offset += len(topics)
	}
}

// forumTopic is one element of getForumTopics.
type forumTopic struct {
	ThreadID int64
	Name     string
}

// decodeTopics normalises the varying shapes of getForumTopics results
// (a bare array, or an object with a "topics" array).
func decodeTopics(raw any) []forumTopic {
	var out []forumTopic
	b, _ := json.Marshal(raw)
	var plain []struct {
		MessageThreadID int64  `json:"message_thread_id"`
		Name            string `json:"name"`
	}
	if err := json.Unmarshal(b, &plain); err == nil && len(plain) > 0 {
		for _, t := range plain {
			out = append(out, forumTopic{ThreadID: t.MessageThreadID, Name: t.Name})
		}
		return out
	}
	var obj struct {
		Topics []struct {
			MessageThreadID int64  `json:"message_thread_id"`
			Name            string `json:"name"`
		} `json:"topics"`
	}
	if err := json.Unmarshal(b, &obj); err == nil {
		for _, t := range obj.Topics {
			out = append(out, forumTopic{ThreadID: t.MessageThreadID, Name: t.Name})
		}
	}
	return out
}

// createForumTopic creates a forum topic and returns its thread id.
func (p *TelegramProvider) createForumTopic(ctx context.Context, name string) (int64, error) {
	resp, err := p.callJSON(ctx, "createForumTopic", url.Values{
		"chat_id": {p.cfg.ChatID},
		"name":    {name},
	})
	if err != nil {
		return 0, err
	}
	var id int64
	if raw, ok := resp["message_thread_id"]; ok {
		var n json.Number
		if err := json.Unmarshal(raw, &n); err == nil {
			id, _ = n.Int64()
		}
	}
	return id, nil
}

// isDuplicateTopicError reports whether a createForumTopic failure means
// the topic already exists (in which case we resolve it by lookup).
func isDuplicateTopicError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "already exists") ||
		strings.Contains(s, "already been created") ||
		strings.Contains(s, "same name") ||
		strings.Contains(s, "name is not unique")
}

// staleTopicMarkers are the Telegram descriptions that mean the cached
// forum topic is gone server-side and must be recreated. They mirror the
// detection the original wallpaper-backup implementation performs.
var staleTopicMarkers = []string{
	"message thread not found",
	"thread not found",
	"topic not found",
}

// isStaleTopicError reports whether an error means the cached forum
// topic no longer exists (so it must be recreated before retrying).
func isStaleTopicError(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	for _, marker := range staleTopicMarkers {
		if strings.Contains(s, marker) {
			return true
		}
	}
	return false
}

// callJSON performs a Bot API POST with url-encoded values and returns
// the "result" payload. Transport errors are surfaced as TelegramError.
// On HTTP 429 it records Telegram's Retry-After hint.
func (p *TelegramProvider) callJSON(ctx context.Context, method string, values url.Values) (map[string]json.RawMessage, error) {
	endpoint := fmt.Sprintf("%s/bot%s/%s", p.base, p.cfg.Token, method)
	body := strings.NewReader(values.Encode())
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, body)
	if err != nil {
		return nil, fmt.Errorf("telegram: build %s request: %w", method, err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := p.client.Do(req)
	if err != nil {
		return nil, &TelegramError{Code: 0, Description: fmt.Sprintf("%s request failed", method)}
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, &TelegramError{Code: resp.StatusCode, Description: method + " response unreadable"}
	}

	var envelope struct {
		OK          bool            `json:"ok"`
		Description string          `json:"description"`
		Result      json.RawMessage `json:"result"`
		Params      *struct {
			RetryAfter int `json:"retry_after"`
		} `json:"parameters"`
	}
	_ = json.Unmarshal(raw, &envelope)

	if resp.StatusCode == http.StatusTooManyRequests && envelope.Params != nil {
		return nil, &TelegramError{Code: resp.StatusCode, Description: "Too Many Requests", RetryAfter: envelope.Params.RetryAfter}
	}
	if resp.StatusCode >= 400 {
		return nil, &TelegramError{Code: resp.StatusCode, Description: firstNonEmpty(envelope.Description, "HTTP "+strconv.Itoa(resp.StatusCode))}
	}
	if !envelope.OK {
		return nil, &TelegramError{Code: resp.StatusCode, Description: firstNonEmpty(envelope.Description, "ok=false")}
	}
	out := map[string]json.RawMessage{}
	_ = json.Unmarshal(envelope.Result, &out)
	return out, nil
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// sendDocument streams one file to a forum topic via sendDocument,
// retrying transient failures with exponential backoff and honouring
// Telegram's Retry-After. Files are streamed in bounded chunks; the
// file bytes are never loaded fully into memory, resized, recompressed,
// or otherwise modified.
func (p *TelegramProvider) sendDocument(ctx context.Context, f ArchiveFile, topicID int64) error {
	caption := fmt.Sprintf("%s\nSize: %.2f MB\nSHA256: %s", f.Filename, float64(f.Size)/(1024*1024), f.Digest)

	var lastErr error
	for attempt := 0; attempt <= p.backoff.MaxRetries; attempt++ {
		if attempt > 0 {
			delay := effectiveDelay(p.backoff, attempt-1, retryHintFromErr(lastErr), 60*time.Second)
			if delay > 0 {
				select {
				case <-ctx.Done():
					return ctx.Err()
				default:
				}
				p.sleep(delay)
			}
		}

		err := p.sendDocumentOnce(ctx, f, topicID, caption)
		if err == nil {
			return nil
		}
		lastErr = err
		_, status := telegramErrorParts(err)
		if !retryable(err, status) {
			return err
		}
	}
	if lastErr != nil {
		return fmt.Errorf("telegram: upload failed after %d attempts: %w", p.backoff.MaxRetries+1, lastErr)
	}
	return lastErr
}

func telegramErrorParts(err error) (error, int) {
	var te *TelegramError
	if ok := asTelegramError(err, &te); ok && te != nil {
		return te, te.Code
	}
	return err, 0
}

func asTelegramError(err error, target **TelegramError) bool {
	te, ok := err.(*TelegramError)
	if ok {
		*target = te
	}
	return ok
}

// retryHintFromErr extracts a Retry-After hint from a TelegramError.
func retryHintFromErr(err error) *retryHint {
	var te *TelegramError
	if asTelegramError(err, &te) && te != nil && te.RetryAfter > 0 {
		return &retryHint{retryAfter: te.RetryAfter}
	}
	return nil
}

// sendDocumentOnce performs a single streaming multipart sendDocument.
func (p *TelegramProvider) sendDocumentOnce(ctx context.Context, f ArchiveFile, topicID int64, caption string) error {
	file, err := os.Open(f.SourcePath)
	if err != nil {
		return fmt.Errorf("telegram: open %s: %w", f.SourcePath, err)
	}
	defer file.Close()

	contentType := contentTypeForFile(f.Filename)
	fields := map[string]string{
		"chat_id":           p.cfg.ChatID,
		"message_thread_id": strconv.FormatInt(topicID, 10),
		"caption":           caption,
	}

	// Stream the body through an io.Pipe to keep memory bounded.
	pr, pw := io.Pipe()
	mw := multipart.NewWriter(pw)

	go func() {
		var werr error
		for k, v := range fields {
			if werr = mw.WriteField(k, v); werr != nil {
				break
			}
		}
		if werr == nil {
			h := textproto.MIMEHeader{}
			h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="document"; filename="%s"`, escapeQuotes(f.Filename)))
			h.Set("Content-Type", contentType)
			var part io.Writer
			part, werr = mw.CreatePart(h)
			if werr == nil {
				_, werr = io.CopyBuffer(part, file, make([]byte, 1<<20))
			}
		}
		cerr := mw.Close()
		if werr != nil {
			pw.CloseWithError(werr)
		} else {
			pw.CloseWithError(cerr)
		}
	}()

	endpoint := fmt.Sprintf("%s/bot%s/sendDocument", p.base, p.cfg.Token)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, pr)
	if err != nil {
		return fmt.Errorf("telegram: build sendDocument: %w", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	// Body is streamed via io.Pipe with chunked transfer encoding; no
	// whole-file buffering in memory. (Setting Content-Length by hand is
	// brittle against multipart encoding differences, so we let the
	// transport stream it.)

	resp, err := p.client.Do(req)
	if err != nil {
		return &TelegramError{Code: 0, Description: "sendDocument request failed"}
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return &TelegramError{Code: resp.StatusCode, Description: "sendDocument response unreadable"}
	}

	var envelope struct {
		OK          bool   `json:"ok"`
		Description string `json:"description"`
		Params      *struct {
			RetryAfter int `json:"retry_after"`
		} `json:"parameters"`
	}
	_ = json.Unmarshal(raw, &envelope)

	if resp.StatusCode == http.StatusTooManyRequests && envelope.Params != nil {
		return &TelegramError{Code: resp.StatusCode, Description: "Too Many Requests", RetryAfter: envelope.Params.RetryAfter}
	}
	if resp.StatusCode >= 400 || !envelope.OK {
		desc := firstNonEmpty(envelope.Description, "HTTP "+strconv.Itoa(resp.StatusCode))
		return &TelegramError{Code: resp.StatusCode, Description: desc}
	}
	return nil
}

func escapeQuotes(s string) string {
	s = strings.ReplaceAll(s, "\\", "\\\\")
	return strings.ReplaceAll(s, "\"", "\\\"")
}

var mimeByExt = map[string]string{
	".jpg":  "image/jpeg",
	".jpeg": "image/jpeg",
	".png":  "image/png",
	".webp": "image/webp",
	".gif":  "image/gif",
	".bmp":  "image/bmp",
	".tif":  "image/tiff",
	".tiff": "image/tiff",
	".avif": "image/avif",
}

func contentTypeForFile(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if ct, ok := mimeByExt[ext]; ok {
		return ct
	}
	return "application/octet-stream"
}
