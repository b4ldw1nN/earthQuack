package wallpaper

import (
	"errors"
	"math"
	"net/http"
	"syscall"
	"time"
)

// This file holds the re-usable retry policy that both the module and
// providers rely on. It is deliberately pure (no I/O, no sleeping) so
// it can be unit-tested without network access or real time.

// Backoff is a bounded exponential-backoff schedule.
//
//	delay(attempt) = Base * Factor^attempt, capped at Max
//
// attempt counts from 0 for the first retry (the first attempt is not
// scheduled by a delay).
type Backoff struct {
	// MaxRetries is the total number of retries after the initial
	// attempt. Total attempts = MaxRetries + 1.
	MaxRetries int
	// Base is the delay before the first retry.
	Base time.Duration
	// Factor multiplies the delay each successive retry.
	Factor float64
	// Max caps a single delay.
	Max time.Duration
}

// DefaultBackoff is a sensible schedule for network archive providers:
// ~2s, 4s, 8s, capped at 2m, for up to 5 retries (6 total attempts).
var DefaultBackoff = Backoff{
	MaxRetries: 5,
	Base:       2 * time.Second,
	Factor:     2.0,
	Max:        2 * time.Minute,
}

// Delay returns the backoff duration for the given retry attempt
// (0-based). attempt < 0 or attempt >= MaxRetries yields 0.
func (b Backoff) Delay(attempt int) time.Duration {
	if b.Base <= 0 {
		return 0
	}
	if attempt < 0 || attempt >= b.MaxRetries {
		return 0
	}
	d := float64(b.Base) * math.Pow(b.Factor, float64(attempt))
	if max := float64(b.Max); b.Max > 0 && d > max {
		d = max
	}
	return time.Duration(d)
}

// retryable returns whether an upload attempt should be retried after
// the given result. It covers network faults (dial/TLS/connection
// reset/timeouts) and the transient HTTP statuses the original
// wallpaper-backup implementation retries: 408 (request timeout), 429
// (rate limit) and every 5xx (server errors).
func retryable(err error, status int) bool {
	if err != nil {
		return isRetryableNetworkErr(err)
	}
	if status == http.StatusRequestTimeout || status == http.StatusTooManyRequests {
		return true
	}
	return status >= 500
}

// isRetryableNetworkErr classifies transport-level errors that are safe
// to retry: timeouts, temporary failures, and socket-level resets or
// refusals (which resolve through the error chain via errors.Is).
func isRetryableNetworkErr(err error) bool {
	if err == nil {
		return false
	}
	for _, se := range []syscall.Errno{syscall.ECONNRESET, syscall.ECONNREFUSED, syscall.ETIMEDOUT, syscall.EPIPE, syscall.ENETUNREACH, syscall.EHOSTUNREACH} {
		if errors.Is(err, se) {
			return true
		}
	}
	var ne interface {
		Timeout() bool
		Temporary() bool
	}
	if errors.As(err, &ne) {
		return ne.Timeout() || ne.Temporary()
	}
	return true // otherwise conservatively retryable for transport failures
}

// retryHint is parsed from a provider error/response and influences the
// retry delay (e.g. Telegram's Retry-After).
type retryHint struct {
	// retryAfter is the number of seconds to honour before the next try.
	retryAfter int
}

// effectiveDelay chooses between an honoured server hint (Retry-After)
// and the backoff schedule for the given attempt.
func effectiveDelay(b Backoff, attempt int, hint *retryHint, maxHint time.Duration) time.Duration {
	if hint != nil && hint.retryAfter > 0 {
		d := time.Duration(hint.retryAfter) * time.Second
		if maxHint > 0 && d > maxHint {
			return maxHint
		}
		return d
	}
	return b.Delay(attempt)
}
