// Package internet implements the earthQuack Internet Microscope: a
// module that observes explicitly configured Internet sources (web
// pages, RSS/Atom feeds) and turns meaningful external changes into
// structured earthQuack events.
//
// Architecture (never invert it):
//
//	Internet (web · RSS/Atom · future: GitHub · APIs)
//	    ↓
//	Fetcher        bounded, safe HTTP: timeout, redirect cap, size cap, context
//	    ↓
//	Normalize      deterministic canonical representation, per source type
//	    ↓
//	Fingerprint    SHA-256 of the canonical representation
//	    ↓
//	State          sources.json in the module state dir, atomic writes
//	    ↓
//	Check          NEW · CHANGED · UNCHANGED · ERROR
//	    ↓
//	Event sink     structured Event; the node bridges it into its History ring
//
// The module is deliberately NOT an AI agent, a scraper framework, a
// crawler or an automation engine: it acquires and observes, and it
// emits events. Consumers (dashboard, notifications, research, AI)
// read those events and the Snapshot below — they never learn how a
// source was fetched.
//
// Scope: only explicitly configured URLs are fetched, at most one
// request per source per interval. There is no link following, no
// crawling, no JavaScript rendering, no headless browser, no proxies.
// That is also why there is no robots.txt gate: robots.txt governs
// crawlers that *discover* URLs, not an operator asking earthQuack to
// watch one specific endpoint they chose.
//
// Dependencies: standard library only, like internal/wallpaper.
package internet

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// Environment variables the module reads. They never carry secrets.
const (
	// EnvState overrides the module state directory.
	EnvState = "EARTHQUACK_INTERNET_STATE"
	// EnvInterval overrides the default poll interval (Go duration string).
	EnvInterval = "EARTHQUACK_INTERNET_INTERVAL"
)

// Module tuning. These are defaults, not hard-coded behaviour: the
// interval is configurable per module and per source, and the state
// directory is configurable.
const (
	// DefaultInterval is the poll interval for a source that does not
	// declare one. Deliberately gentle: the microscope observes, it
	// does not hammer.
	DefaultInterval = 15 * time.Minute
	// MinInterval bounds how often a single source may be polled.
	MinInterval = 30 * time.Second
	// DefaultTimeout bounds one HTTP request (connection + body).
	DefaultTimeout = 20 * time.Second
	// DefaultMaxBytes caps one response body. Larger responses fail
	// rather than being silently truncated.
	DefaultMaxBytes = 5 << 20 // 5 MiB
	// DefaultUserAgent identifies earthQuack to origin servers.
	DefaultUserAgent = "earthQuack-InternetMicroscope/0.1 (+https://github.com/b4ldw1nN/earthQuack)"
)

// Config is the module's composition-level configuration. Sources
// themselves are NOT declared here: they are module state, managed by
// the CLI and persisted in the state directory (see state.go), so that
// there is exactly one source of truth.
type Config struct {
	// StateDir is where sources.json lives.
	StateDir string
	// Interval is the default poll interval for sources that do not
	// declare their own. Zero selects DefaultInterval.
	Interval Duration
	// Timeout bounds one HTTP request. Zero selects DefaultTimeout.
	Timeout time.Duration
	// MaxBytes caps one response body. Zero selects DefaultMaxBytes.
	MaxBytes int64
	// UserAgent identifies earthQuack. Empty selects DefaultUserAgent.
	UserAgent string
}

// WithDefaults returns the configuration with unset fields resolved to
// the module defaults.
func (c Config) WithDefaults() Config {
	if c.Interval <= 0 {
		c.Interval = Duration(DefaultInterval)
	}
	if c.Interval < Duration(MinInterval) {
		c.Interval = Duration(MinInterval)
	}
	if c.Timeout <= 0 {
		c.Timeout = DefaultTimeout
	}
	if c.MaxBytes <= 0 {
		c.MaxBytes = DefaultMaxBytes
	}
	if c.UserAgent == "" {
		c.UserAgent = DefaultUserAgent
	}
	if c.StateDir == "" {
		c.StateDir = DefaultStateDir()
	}
	return c
}

// DefaultStateDir returns ~/.local/share/earthquack/internet, the
// default home for the module's sources.json.
func DefaultStateDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join("~", ".local", "share", "earthquack", "internet")
	}
	return filepath.Join(home, ".local", "share", "earthquack", "internet")
}

// ConfigFromEnv resolves the module configuration from the environment,
// with the module defaults applied. An unparsable interval is an error
// rather than a silently ignored setting.
func ConfigFromEnv(getenv func(string) string) (Config, error) {
	if getenv == nil {
		getenv = os.Getenv
	}
	cfg := Config{StateDir: getenv(EnvState)}
	if raw := getenv(EnvInterval); raw != "" {
		d, err := ParseDuration(raw)
		if err != nil {
			return Config{}, fmt.Errorf("internet: %s: %w", EnvInterval, err)
		}
		cfg.Interval = d
	}
	return cfg.WithDefaults(), nil
}

// IsConfigured reports whether the module is explicitly enabled through
// the environment (an explicit state directory or a declared interval).
// The node combines this with its own config declaration to decide
// whether the module is part of this node.
func IsConfigured(getenv func(string) string) bool {
	if getenv == nil {
		getenv = os.Getenv
	}
	return getenv(EnvState) != "" || getenv(EnvInterval) != ""
}

// Duration is a time.Duration that marshals as a readable Go duration
// string ("15m0s") and accepts either such a string or a bare number of
// seconds when unmarshalling. It is used so sources.json, the node
// config and the API stay human-readable without a second field.
type Duration time.Duration

// String renders the duration like time.Duration does.
func (d Duration) String() string { return time.Duration(d).String() }

// MarshalJSON encodes the duration as a string, e.g. "15m0s".
func (d Duration) MarshalJSON() ([]byte, error) {
	return []byte(`"` + time.Duration(d).String() + `"`), nil
}

// UnmarshalJSON accepts a duration string ("15m", "1h30m") or a bare
// number of seconds (900). Anything else is an error — a malformed
// interval must never be silently replaced by a default.
func (d *Duration) UnmarshalJSON(data []byte) error {
	raw := string(data)
	if len(raw) >= 2 && raw[0] == '"' && raw[len(raw)-1] == '"' {
		return d.Set(raw[1 : len(raw)-1])
	}
	seconds, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return fmt.Errorf("internet: invalid interval %s: expected a duration string or seconds", raw)
	}
	*d = Duration(time.Duration(seconds * float64(time.Second)))
	return nil
}

// Set parses a duration string ("15m") or a decimal number of seconds.
func (d *Duration) Set(s string) error {
	parsed, err := ParseDuration(s)
	if err != nil {
		return err
	}
	*d = parsed
	return nil
}

// ParseDuration parses a Go duration string ("15m") or a bare number of
// seconds ("900").
func ParseDuration(s string) (Duration, error) {
	if s == "" {
		return 0, fmt.Errorf("internet: empty interval")
	}
	if d, err := time.ParseDuration(s); err == nil {
		return Duration(d), nil
	}
	seconds, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, fmt.Errorf("internet: invalid interval %q", s)
	}
	return Duration(time.Duration(seconds * float64(time.Second))), nil
}
