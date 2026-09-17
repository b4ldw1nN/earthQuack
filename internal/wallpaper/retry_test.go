package wallpaper

import (
	"errors"
	"net"
	"syscall"
	"testing"
	"time"
)

func TestBackoffExponentialBounded(t *testing.T) {
	b := Backoff{MaxRetries: 4, Base: time.Second, Factor: 2, Max: 8 * time.Second}
	// attempt 0 → 2s? No: base=1s * 2^0 = 1s, then 2s, 4s, 8s (capped).
	want := []time.Duration{time.Second, 2 * time.Second, 4 * time.Second, 8 * time.Second}
	for i := 0; i < b.MaxRetries; i++ {
		if d := b.Delay(i); d != want[i] {
			t.Errorf("attempt %d: got %v, want %v", i, d, want[i])
		}
	}
	// Past MaxRetries → 0 (bounded).
	if d := b.Delay(b.MaxRetries); d != 0 {
		t.Errorf("out of bounds retries should delay 0, got %v", d)
	}
}

func TestBackoffDoesNotOvershootMax(t *testing.T) {
	b := Backoff{MaxRetries: 10, Base: time.Second, Factor: 4, Max: 5 * time.Second}
	for i := 0; i < b.MaxRetries; i++ {
		if d := b.Delay(i); d > b.Max {
			t.Errorf("attempt %d: delay %v exceeds cap %v", i, d, b.Max)
		}
	}
}

func TestIsRetryableNetworkErr(t *testing.T) {
	if !isRetryableNetworkErr(&net.DNSError{IsTimeout: true}) {
		t.Error("timeout should be retryable")
	}
	if !isRetryableNetworkErr(&net.OpError{Err: syscall.ECONNRESET}) {
		t.Error("connection reset should be retryable")
	}
	if !isRetryableNetworkErr(errors.New("dial tcp: i/o timeout")) {
		t.Error("dial timeout should be retryable")
	}
	if !isRetryableNetworkErr(&net.OpError{Err: syscall.ECONNREFUSED}) {
		t.Error("connection refused should be retryable")
	}
}

func TestRetryableStatusClassification(t *testing.T) {
	if !retryable(nil, 429) {
		t.Error("429 must be retryable")
	}
	if !retryable(nil, 408) {
		t.Error("408 must be retryable (the Python script retries it)")
	}
	if !retryable(nil, 500) {
		t.Error("500 must be retryable")
	}
	if !retryable(nil, 502) {
		t.Error("502 must be retryable")
	}
	if !retryable(nil, 503) {
		t.Error("503 must be retryable")
	}
	if !retryable(nil, 504) {
		t.Error("504 must be retryable")
	}
	if retryable(nil, 400) {
		t.Error("400 must NOT be retryable (permanent)")
	}
	if retryable(nil, 403) {
		t.Error("403 must NOT be retryable (permanent)")
	}
	if retryable(nil, 200) {
		t.Error("200 must not be retryable")
	}
}

func TestEffectiveDelayHonoursRetryAfter(t *testing.T) {
	b := Backoff{MaxRetries: 5, Base: 2 * time.Second, Factor: 2, Max: time.Minute}
	h := &retryHint{retryAfter: 7}
	if d := effectiveDelay(b, 0, h, 60*time.Second); d != 7*time.Second {
		t.Errorf("want honour 7s Retry-After, got %v", d)
	}
	// Cap the hint.
	if d := effectiveDelay(b, 0, h, 3*time.Second); d != 3*time.Second {
		t.Errorf("want hint capped at 3s, got %v", d)
	}
	// No hint → backoff schedule.
	if d := effectiveDelay(b, 0, nil, 0); d != 2*time.Second {
		t.Errorf("want backoff base 2s, got %v", d)
	}
}
