package internet

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

// maxRedirects caps a redirect chain. Exceeding it fails the check
// rather than following a loop.
const maxRedirects = 5

// ErrTooLarge is returned when a response body exceeds the configured
// size limit. It is a permanent failure for that response: truncating
// and fingerprinting a partial body would record a false change.
var ErrTooLarge = errors.New("internet: response exceeds size limit")

// HTTPError is a non-success HTTP status. It carries the status code so
// callers and operators can see *why* a source errored without parsing
// a message.
type HTTPError struct {
	URL        string
	StatusCode int
	Status     string
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("internet: %s returned %s", e.URL, e.Status)
}

// Conditional carries the validators from the previous observation, so
// an unchanged source can answer 304 Not Modified and transfer no body.
type Conditional struct {
	ETag         string
	LastModified string
}

// Response is one successful HTTP response, already bounded and read
// into memory (the size limit guarantees it is small).
type Response struct {
	// URL is the final URL after redirects.
	URL string
	// StatusCode is the HTTP status (200, 203, ... ; 304 sets NotModified).
	StatusCode int
	// ContentType is the response media type, informational only.
	ContentType string
	// Body is the response body. Empty when NotModified.
	Body []byte
	// ETag and LastModified are the validators to replay next time.
	ETag         string
	LastModified string
	// NotModified reports a 304: the origin says "same as what you saw".
	NotModified bool
	// Bytes is the number of body bytes received.
	Bytes int64
}

// Fetcher performs one bounded HTTP GET per call: it never follows links
// found in a response, never retries (the next scheduled poll is the
// retry — see module.go), and never reads more than the configured
// limit.
type Fetcher struct {
	client    *http.Client
	timeout   time.Duration
	maxBytes  int64
	userAgent string
}

// NewFetcher returns a fetcher. A nil client builds a default one with
// a bounded redirect policy (5 hops); a caller-supplied client keeps
// its own transport but its redirect policy is replaced if it has none,
// so an unbounded redirect chain can never be configured by accident.
func NewFetcher(client *http.Client, timeout time.Duration, maxBytes int64, userAgent string) *Fetcher {
	if client == nil {
		client = &http.Client{}
	}
	if client.CheckRedirect == nil {
		client.CheckRedirect = limitRedirects
	}
	if timeout <= 0 {
		timeout = DefaultTimeout
	}
	if maxBytes <= 0 {
		maxBytes = DefaultMaxBytes
	}
	if userAgent == "" {
		userAgent = DefaultUserAgent
	}
	return &Fetcher{client: client, timeout: timeout, maxBytes: maxBytes, userAgent: userAgent}
}

// limitRedirects bounds a redirect chain.
func limitRedirects(req *http.Request, via []*http.Request) error {
	if len(via) >= maxRedirects {
		return fmt.Errorf("internet: stopped after %d redirects", maxRedirects)
	}
	return nil
}

// Get fetches url with the given conditional validators. Every failure
// mode is expressed as an error: HTTP status handling, connection
// errors, timeouts, cancellation, oversized bodies and malformed
// responses all surface here so the caller can record ERROR.
func (f *Fetcher) Get(ctx context.Context, rawURL string, cond Conditional) (Response, error) {
	ctx, cancel := context.WithTimeout(ctx, f.timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return Response{}, fmt.Errorf("internet: request: %w", err)
	}
	req.Header.Set("User-Agent", f.userAgent)
	req.Header.Set("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.9, text/plain;q=0.9, application/json;q=0.9, */*;q=0.5")
	if cond.ETag != "" {
		req.Header.Set("If-None-Match", cond.ETag)
	}
	if cond.LastModified != "" {
		req.Header.Set("If-Modified-Since", cond.LastModified)
	}

	resp, err := f.client.Do(req)
	if err != nil {
		// A cancelled caller context is not a source failure; it is the
		// node shutting down or a stop request. Preserve the sentinel so
		// the caller can avoid recording a spurious ERROR.
		if ctx.Err() != nil {
			return Response{}, fmt.Errorf("internet: %s: %w", rawURL, ctx.Err())
		}
		return Response{}, fmt.Errorf("internet: %s: %w", rawURL, err)
	}
	defer resp.Body.Close()

	out := Response{
		URL:          resp.Request.URL.String(),
		StatusCode:   resp.StatusCode,
		ContentType:  resp.Header.Get("Content-Type"),
		ETag:         resp.Header.Get("ETag"),
		LastModified: resp.Header.Get("Last-Modified"),
	}
	if resp.StatusCode == http.StatusNotModified {
		out.NotModified = true
		return out, nil
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return out, &HTTPError{URL: rawURL, StatusCode: resp.StatusCode, Status: resp.Status}
	}

	// Read one byte past the limit so an oversized body is detected and
	// rejected rather than silently truncated.
	body, err := io.ReadAll(io.LimitReader(resp.Body, f.maxBytes+1))
	if err != nil {
		return out, fmt.Errorf("internet: %s: read body: %w", rawURL, err)
	}
	if int64(len(body)) > f.maxBytes {
		return out, fmt.Errorf("internet: %s: %w (%d bytes limit)", rawURL, ErrTooLarge, f.maxBytes)
	}
	out.Body = body
	out.Bytes = int64(len(body))
	return out, nil
}
