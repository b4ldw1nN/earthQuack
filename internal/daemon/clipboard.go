package daemon

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
)

// keepaliveInterval matches the Python server's 15-second SSE comment.
// The Android client relies on traffic to keep its connection open, and
// intermediate proxies can drop silent streams.
const keepaliveInterval = 15 * time.Second

// AppName is the service identifier reported by /health. It matches
// config.APP_NAME so existing health checks keep matching.
const AppName = "earthQuack"

// Origins recognised on the wire. The Android app only applies clipboard
// events whose origin is not its own (EarthQuackService.handleSseEvent
// bails unless origin == "desktop"), and the desktop bridge only writes
// values whose origin is "phone". Echo suppression depends on these.
const (
	OriginPhone   = "phone"
	OriginDesktop = "desktop"
)

// clipboardServer is the :8875 service: clipboard state plus the SSE
// fan-out, a rewrite of daemon/server.py.
type clipboardServer struct {
	state *ClipboardState
	// set is the shared write path (Daemon.SetClipboard). It is injected
	// rather than reaching for the daemon so the handler stays testable
	// without one, and so there is exactly one place that decides whether a
	// change is published.
	set    func(clipboard, origin string) (bool, Snapshot)
	broker *Broker
}

// newClipboardHandler builds the :8875 handler.
//
// token gates every route but /health; an empty token fails closed.
func newClipboardHandler(state *ClipboardState, broker *Broker, token string) http.Handler {
	return newClipboardHandlerWithSetter(state, broker, token, nil)
}

// newClipboardHandlerWithSetter builds the :8875 handler with an explicit
// write path. When set is nil the handler updates state and publishes itself,
// which is what the standalone tests use.
func newClipboardHandlerWithSetter(state *ClipboardState, broker *Broker, token string, set func(string, string) (bool, Snapshot)) http.Handler {
	if set == nil {
		set = func(clipboard, origin string) (bool, Snapshot) {
			changed, snap := state.Update(clipboard, origin)
			if changed {
				_ = broker.Publish("clipboard", snap)
			}
			return changed, snap
		}
	}
	s := &clipboardServer{state: state, broker: broker, set: set}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/clipboard", s.handleClipboard)
	mux.HandleFunc("/events", s.handleEvents)
	mux.HandleFunc("/signal", s.handleSignal)
	mux.HandleFunc("/", notFound)
	return bearerAuth(token, mux)
}

func notFound(w http.ResponseWriter, _ *http.Request) {
	writeJSONError(w, http.StatusNotFound, "not found")
}

func (s *clipboardServer) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"service": AppName,
	})
}

// handleClipboard serves GET (read current state) and POST (set it).
func (s *clipboardServer) handleClipboard(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, s.state.Get())
	case http.MethodPost:
		s.postClipboard(w, r)
	default:
		w.Header().Set("Allow", "GET, POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// clipboardRequest is the POST /clipboard body. Both fields are required;
// the Python original raised KeyError and returned 400 when either was
// missing, so a partial body is rejected here too.
type clipboardRequest struct {
	Clipboard *string `json:"clipboard"`
	Origin    *string `json:"origin"`
}

func (s *clipboardServer) postClipboard(w http.ResponseWriter, r *http.Request) {
	var req clipboardRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxClipboardBody)).Decode(&req); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	if req.Clipboard == nil || req.Origin == nil {
		writeJSONError(w, http.StatusBadRequest, "clipboard and origin are required")
		return
	}

	changed, snap := s.set(*req.Clipboard, *req.Origin)
	_ = changed
	// Logging happens inside the shared write path (Daemon.SetClipboard) so
	// that a bridge push and a phone post are reported the same way.
	writeJSON(w, http.StatusOK, map[string]any{"version": snap.Version})
}

// maxClipboardBody caps a clipboard POST. Clipboard payloads are text; a
// multi-megabyte body is a client bug or an attempt to exhaust memory.
const maxClipboardBody = 4 << 20 // 4 MiB

// handleEvents is the SSE stream.
//
// Framing matches daemon/server.py exactly: headers, then periodic
// ": keepalive\n\n" comments, then "event: <type>\ndata: <json>\n\n"
// blocks. Both existing clients parse that shape literally.
func (s *clipboardServer) handleEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeJSONError(w, http.StatusInternalServerError, "streaming unsupported")
		return
	}

	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("Connection", "keep-alive")
	// Proxies that buffer would defeat SSE entirely.
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	events, dropped, cancel := s.broker.Subscribe()
	defer cancel()

	ticker := time.NewTicker(keepaliveInterval)
	defer ticker.Stop()

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case <-dropped:
			// The broker dropped us for falling behind. Close the stream so
			// the client reconnects with its own backoff rather than sitting
			// on a silent half-dead socket.
			return
		case <-ticker.C:
			if _, err := w.Write([]byte(keepalive)); err != nil {
				return
			}
			flusher.Flush()
		case frame := <-events:
			if _, err := w.Write(frame); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

// handleSignal republishes an arbitrary JSON body to every SSE subscriber
// under the event type given by its "type" field (default "signal").
//
// This is how clip-open.sh asks the phone to open a URL, and how the file
// service announces a staged file. The Python version published only to
// its own process's subscribers; here the same broker instance serves both,
// so no loopback HTTP call is needed.
func (s *clipboardServer) handleSignal(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSONError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var body map[string]any
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxSignalBody)).Decode(&body); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	eventType, _ := body["type"].(string)
	if eventType == "" {
		eventType = "signal"
	}
	if err := s.broker.Publish(eventType, body); err != nil {
		logf("clipboard: publish %s: %v", eventType, err)
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// maxSignalBody caps a /signal payload. clip-open.sh sends a URL and a
// handful of fields; anything near this size is not a signal.
const maxSignalBody = 64 << 10 // 64 KiB

// writeJSON writes v as a JSON response with the status and content type
// the Python server used.
func writeJSON(w http.ResponseWriter, status int, v any) {
	body, err := json.Marshal(v)
	if err != nil {
		// Everything written here is a fixed-shape struct, so this is
		// unreachable in practice; a 500 beats a truncated body.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":"encode failed"}`))
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Length", itoa(len(body)))
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func writeJSONError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

// itoa avoids pulling strconv into every call site for one conversion.
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

// logf writes a daemon log line to stderr, matching the Python daemon's
// unbuffered prints so operators see the same stream under systemd.
func logf(format string, args ...any) {
	logPrintf("earthquack: "+format, args...)
}

// shutdownContext returns a context bounded by d for a graceful listener
// shutdown.
func shutdownContext(d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), d)
}
