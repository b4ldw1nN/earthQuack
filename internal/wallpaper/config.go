package wallpaper

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// Default source directory, matching the original wallpaper-backup
// behaviour (~/Pictures/Wallpapers). It is only a default — the source
// is configurable and never hardcoded as the sole option.
func DefaultSource() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join("~", "Pictures", "Wallpapers")
	}
	return filepath.Join(home, "Pictures", "Wallpapers")
}

// defaultStateDir returns ~/.local/share/earthquack/wallpaper — the
// default home for uploads.json / failed.json / topics.json / sync.json.
// It can be pointed at an existing archive (e.g. the original
// wallpaper-backup directory) to adopt it without re-uploading.
func defaultStateDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join("~", ".local", "share", "earthquack", "wallpaper")
	}
	return filepath.Join(home, ".local", "share", "earthquack", "wallpaper")
}

// Environment-var names the provider and module read. Credentials are
// NEVER embedded in code; they are read from the environment/config.
const (
	EnvTelegramToken  = "TELEGRAM_BOT_TOKEN"
	EnvTelegramChatID = "TELEGRAM_CHAT_ID"
	EnvAPIBase        = "TELEGRAM_API_BASE" // test hook, not a secret
	EnvSource         = "EARTHQUACK_WALLPAPER_SOURCE"
	EnvState          = "EARTHQUACK_WALLPAPER_STATE"
	EnvProvider       = "EARTHQUACK_WALLPAPER_PROVIDER"
)

// EnvConfig is the fully-resolved composition-root configuration.
type EnvConfig struct {
	// Config is the provider-agnostic module config.
	Config
	// ProviderName selects the provider to build ("telegram" default).
	ProviderName string
	// Telegram holds provider credentials resolved from the environment.
	Telegram TelegramConfig
	// Enabled reports whether wallpaper appears configured (a source
	// or credentials are present) and thus should be registered with
	// the node's service/capability system.
	Enabled bool
}

// resolveEnv merges an explicit source override (from flags/config)
// with environment defaults and credential env vars.
//
//	source:   explicit > EARTHQUACK_WALLPAPER_SOURCE > ~/Pictures/Wallpapers
//	state:    explicit > EARTHQUACK_WALLPAPER_STATE > ~/.local/share/earthquack/wallpaper
//	provider: explicit > EARTHQUACK_WALLPAPER_PROVIDER > telegram
func resolveEnv(explicitSource, explicitState string, getenv func(string) string) EnvConfig {
	if getenv == nil {
		getenv = os.Getenv
	}
	source := explicitSource
	if source == "" {
		source = getenv(EnvSource)
	}
	if source == "" {
		source = DefaultSource()
	}
	state := explicitState
	if state == "" {
		state = getenv(EnvState)
	}
	if state == "" {
		state = defaultStateDir()
	}
	provider := getenv(EnvProvider)
	if provider == "" {
		provider = "telegram"
	}

	roots := filepath.SplitList(source)
	for i := range roots {
		roots[i] = expandHome(roots[i])
	}
	source = strings.Join(roots, string(os.PathListSeparator))
	state = expandHome(state)

	ec := EnvConfig{
		Config: Config{
			Source:   source,
			StateDir: state,
		},
		ProviderName: provider,
		Telegram: TelegramConfig{
			Token:   getenv(EnvTelegramToken),
			ChatID:  getenv(EnvTelegramChatID),
			APIBase: getenv(EnvAPIBase),
		},
	}
	// Wallpaper is "enabled" when the operator has configured a source
	// explicitly OR supplied provider credentials.
	if getenv(EnvSource) != "" || getenv(EnvState) != "" || getenv(EnvTelegramToken) != "" || getenv(EnvTelegramChatID) != "" {
		ec.Enabled = true
	}
	return ec
}

// expandHome replaces a leading "~/" with the user's home directory.
func expandHome(p string) string {
	if p == "~" || strings.HasPrefix(p, "~/") {
		if home, err := os.UserHomeDir(); err == nil {
			if p == "~" {
				return home
			}
			return filepath.Join(home, p[2:])
		}
	}
	return p
}

// IsConfigured reports whether wallpaper appears configured purely from
// the environment — an explicit source/state override or provider
// credentials present. The node uses this (combined with the config
// file's wallpaper.enabled declaration) to decide whether to register
// the wallpaper capability/service. Credentials are never echoed.
func IsConfigured(getenv func(string) string) bool {
	return getenv(EnvSource) != "" ||
		getenv(EnvState) != "" ||
		getenv(EnvTelegramToken) != "" ||
		getenv(EnvTelegramChatID) != ""
}

// BuildProvider constructs the provider named by name. It is the only
// place provider selection happens (the composition root), so the
// module never references a concrete provider. client may be nil to use
// the default production HTTP client.
func BuildProvider(name, stateDir string, tg TelegramConfig, client *http.Client) (ArchiveProvider, error) {
	switch name {
	case "", "telegram":
		return NewTelegramProvider(tg, stateDir, client)
	default:
		return nil, fmt.Errorf("wallpaper: unknown provider %q", name)
	}
}
