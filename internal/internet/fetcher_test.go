package internet

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func testFetcher(t *testing.T, client *http.Client) *Fetcher {
	t.Helper()
	return NewFetcher(client, 5*time.Second, 1<<20, "test-agent/1.0")
}

func TestFetcherSuccessAndHeaders(t *testing.T) {
	var gotUA, gotAccept, gotNoneMatch, gotModSince string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUA = r.Header.Get("User-Agent")
		gotAccept = r.Header.Get("Accept")
		gotNoneMatch = r.Header.Get("If-None-Match")
		gotModSince = r.Header.Get("If-Modified-Since")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("ETag", `"abc"`)
		w.Header().Set("Last-Modified", "Wed, 01 Jan 2025 00:00:00 GMT")
		_, _ = w.Write([]byte("body"))
	}))
	defer srv.Close()

	resp, err := testFetcher(t, nil).Get(context.Background(), srv.URL, Conditional{ETag: `"old"`, LastModified: "yesterday"})
	if err != nil {
		t.Fatal(err)
	}
	if string(resp.Body) != "body" || resp.StatusCode != http.StatusOK {
		t.Fatalf("response = %+v", resp)
	}
	if resp.ETag != `"abc"` || resp.LastModified != "Wed, 01 Jan 2025 00:00:00 GMT" {
		t.Fatalf("validators not captured: %+v", resp)
	}
	if gotUA != "test-agent/1.0" {
		t.Fatalf("User-Agent = %q", gotUA)
	}
	if !strings.Contains(gotAccept, "application/rss+xml") {
		t.Fatalf("Accept = %q", gotAccept)
	}
	if gotNoneMatch != `"old"` || gotModSince != "yesterday" {
		t.Fatalf("conditional headers = %q %q", gotNoneMatch, gotModSince)
	}
}

func TestFetcherErrorStatuses(t *testing.T) {
	for _, status := range []int{http.StatusNotFound, http.StatusInternalServerError, http.StatusTooManyRequests} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(status)
		}))
		_, err := testFetcher(t, nil).Get(context.Background(), srv.URL+"/missing", Conditional{})
		srv.Close()
		var httpErr *HTTPError
		if !errors.As(err, &httpErr) {
			t.Fatalf("status %d: error = %T %v, want *HTTPError", status, err, err)
		}
		if httpErr.StatusCode != status {
			t.Fatalf("status code = %d, want %d", httpErr.StatusCode, status)
		}
	}
}

func TestFetcherFollowsRedirectsAndStops(t *testing.T) {
	final := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("final"))
	}))
	defer final.Close()
	moved := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, final.URL, http.StatusFound)
	}))
	defer moved.Close()

	resp, err := testFetcher(t, nil).Get(context.Background(), moved.URL, Conditional{})
	if err != nil {
		t.Fatal(err)
	}
	if string(resp.Body) != "final" {
		t.Fatalf("redirect was not followed: %+v", resp)
	}

	// A redirect cycle must fail, not loop forever.
	loop := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/again", http.StatusFound)
	}))
	defer loop.Close()
	start := time.Now()
	if _, err := testFetcher(t, nil).Get(context.Background(), loop.URL+"/again", Conditional{}); err == nil {
		t.Fatal("redirect loop must fail")
	} else if time.Since(start) > 3*time.Second {
		t.Fatal("redirect handling is too slow to fail a loop")
	}
}

func TestFetcherSizeLimit(t *testing.T) {
	huge := strings.Repeat("x", 64)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, huge)
	}))
	defer srv.Close()

	f := NewFetcher(nil, 5*time.Second, 16, "t")
	_, err := f.Get(context.Background(), srv.URL, Conditional{})
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversized body: err = %v, want ErrTooLarge", err)
	}
}

func TestFetcherTimeout(t *testing.T) {
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(2 * time.Second):
			_, _ = w.Write([]byte("too late"))
		case <-r.Context().Done():
		}
	}))
	defer slow.Close()

	f := NewFetcher(nil, 100*time.Millisecond, 1<<20, "t")
	start := time.Now()
	_, err := f.Get(context.Background(), slow.URL, Conditional{})
	if err == nil {
		t.Fatal("slow server must time out")
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("timeout took %v", elapsed)
	}
}

func TestFetcherCancelledContext(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("x"))
	}))
	defer srv.Close()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := testFetcher(t, nil).Get(ctx, srv.URL, Conditional{})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled request: err = %v, want context.Canceled", err)
	}
}

func TestFetcherRejectsNonHTTP(t *testing.T) {
	_, err := testFetcher(t, nil).Get(context.Background(), "gopher://example.com/x", Conditional{})
	if err == nil {
		t.Fatal("non-http scheme must fail")
	}
}
