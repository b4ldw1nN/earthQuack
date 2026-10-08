package daemon

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Defaults matching daemon/config.py and the shell helpers, so a node
// configured the old way keeps the same addresses.
const (
	DefaultClipboardPort = 8875
	DefaultFilePort      = 8876
	DefaultStageDir      = "/tmp/cs-files"
)

// Config is the resolved configuration for the Go sync services.
//
// The environment variable names are the ones daemon/config.py already
// read, so an existing deployment needs no changes. Notably the legacy
// CLIPBOARD_SERVER_HOST / CLIPBOARD_SERVER_PORT aliases are still
// honoured, because clip-send.sh, clip-open.sh and desktop.py all use
// them.
type Config struct {
	// Host is the bind address for both services.
	Host string
	// ClipboardPort and FilePort are the service ports.
	ClipboardPort int
	FilePort      int
	// AuthToken gates every route but /health. Empty fails closed.
	AuthToken string
	// AESKey is a base64 32-byte key shared with the Android app.
	AESKey string
	// StageDir holds Desktop→Phone files awaiting pickup.
	StageDir string
	// PhoneSaveDir is where Phone→Desktop uploads land.
	PhoneSaveDir string
	// StagedTTL is how long an uncollected staged file survives.
	StagedTTL time.Duration
	// ClipboardStateDir holds the clipboard version ledger. Empty means the
	// XDG default. It is the only persistent state the services keep.
	ClipboardStateDir string
	// DesktopBridge enables the local clipboard bridge (wl-paste/wl-copy).
	DesktopBridge bool
	// SendFolder is watched for files to push to the phone; empty disables.
	SendFolder string
	// SendScript is the uploader invoked for watched files.
	SendScript string
	// DiscoverTimeout bounds Tailscale peer discovery at startup.
	DiscoverTimeout time.Duration
}

// ConfigFromEnv resolves configuration from the environment, mirroring
// daemon/config.py's precedence but without its hardcoded fallback IP.
//
// The Python original defaulted SERVER_HOST to the literal
// "100.92.160.31", a machine-specific Tailscale address. That default is
// deliberately not reproduced: a wrong default silently binds nothing (or
// the wrong interface), so an unset host is an explicit error instead.
func ConfigFromEnv(getenv func(string) string) (Config, error) {
	if getenv == nil {
		getenv = os.Getenv
	}
	cfg := Config{
		ClipboardPort:   envInt(getenv, "EARTHQUACK_PORT", envInt(getenv, "CLIPBOARD_SERVER_PORT", DefaultClipboardPort)),
		FilePort:        envInt(getenv, "EARTHQUACK_FILE_PORT", DefaultFilePort),
		AuthToken:       strings.TrimSpace(getenv("EARTHQUACK_AUTH_TOKEN")),
		AESKey:          strings.TrimSpace(getenv("CLIPBOARD_AES_KEY")),
		StageDir:        envOr(getenv, "EARTHQUACK_STAGE_DIR", DefaultStageDir),
		StagedTTL:       time.Duration(envInt(getenv, "EARTHQUACK_STAGE_TTL_HOURS", 24)) * time.Hour,
		DiscoverTimeout: 3 * time.Second,
		DesktopBridge:   getenv("EARTHQUACK_DESKTOP_BRIDGE") != "0",
		SendFolder:      strings.TrimSpace(getenv("EARTHQUACK_SEND_FOLDER")),
		SendScript:      strings.TrimSpace(getenv("EARTHQUACK_SEND_SCRIPT")),
	}
	if cfg.SendFolder == "" {
		if home, err := os.UserHomeDir(); err == nil {
			cfg.SendFolder = filepath.Join(home, "send-to-phone")
		}
	}
	if cfg.SendScript == "" {
		if exe, err := os.Executable(); err == nil {
			cfg.SendScript = filepath.Join(filepath.Dir(exe), "clip-send.sh")
		}
	}
	if cfg.PhoneSaveDir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return Config{}, fmt.Errorf("daemon: cannot resolve home directory: %w", err)
		}
		cfg.PhoneSaveDir = filepath.Join(home, "Downloads", "from-phone")
	}

	cfg.Host = firstNonEmpty(
		getenv("EARTHQUACK_HOST"),
		getenv("CLIPBOARD_SERVER_HOST"),
	)
	if cfg.Host == "" || cfg.Host == "YOUR_TAILSCALE_IP" || cfg.Host == "auto" {
		// Discovery fills this in when the node runs on a tailnet host.
		// Loopback is the safe fallback: it keeps the daemon usable on a
		// machine with no Tailscale, and discovery overrides it when peers
		// are found.
		cfg.Host = "127.0.0.1"
	}
	if cfg.ClipboardPort == cfg.FilePort {
		return Config{}, fmt.Errorf("daemon: clipboard and file ports must differ (both %d)", cfg.ClipboardPort)
	}
	return cfg, nil
}

func envOr(getenv func(string) string, key, def string) string {
	if v := strings.TrimSpace(getenv(key)); v != "" {
		return v
	}
	return def
}

// envInt reads a positive integer, falling back to def on anything
// unparseable. The Python original would raise on a malformed value and
// take the whole daemon down at import time.
func envInt(getenv func(string) string, key string, def int) int {
	raw := strings.TrimSpace(getenv(key))
	if raw == "" {
		return def
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n <= 0 || n > 65535 {
		return def
	}
	return n
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if s := strings.TrimSpace(v); s != "" {
			return s
		}
	}
	return ""
}
