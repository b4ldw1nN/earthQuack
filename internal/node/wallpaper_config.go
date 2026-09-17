package node

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// WallpaperEnvironment applies node settings as defaults without mutating
// process environment. Credentials are read only from an explicitly configured
// file; shell expansion/execution is never performed. Environment wins.
func WallpaperEnvironment(cfg *WallpaperConfig, getenv func(string) string) (func(string) string, error) {
	defaults := map[string]string{}
	if cfg != nil {
		defaults["EARTHQUACK_WALLPAPER_SOURCE"] = cfg.Source
		if len(cfg.Sources) > 0 {
			defaults["EARTHQUACK_WALLPAPER_SOURCE"] = strings.Join(cfg.Sources, string(os.PathListSeparator))
		}
		defaults["EARTHQUACK_WALLPAPER_STATE"] = cfg.StateDir
		defaults["EARTHQUACK_WALLPAPER_PROVIDER"] = cfg.Provider
		if cfg.EnvFile != "" {
			path := cfg.EnvFile
			if strings.HasPrefix(path, "~/") {
				home, err := os.UserHomeDir()
				if err != nil {
					return nil, err
				}
				path = filepath.Join(home, path[2:])
			}
			f, err := os.Open(path)
			if err != nil {
				return nil, fmt.Errorf("wallpaper credential file: %w", err)
			}
			defer f.Close()
			scanner := bufio.NewScanner(f)
			for scanner.Scan() {
				line := strings.TrimSpace(scanner.Text())
				if line == "" || strings.HasPrefix(line, "#") {
					continue
				}
				line = strings.TrimPrefix(line, "export ")
				key, value, ok := strings.Cut(line, "=")
				key = strings.TrimSpace(key)
				if !ok {
					return nil, fmt.Errorf("wallpaper credential file: expected KEY=VALUE")
				}
				if key != "TELEGRAM_BOT_TOKEN" && key != "TELEGRAM_CHAT_ID" {
					continue
				}
				value = strings.TrimSpace(value)
				if len(value) >= 2 && ((value[0] == '"' && value[len(value)-1] == '"') || (value[0] == '\'' && value[len(value)-1] == '\'')) {
					value = value[1 : len(value)-1]
				}
				defaults[key] = value
			}
			if err := scanner.Err(); err != nil {
				return nil, fmt.Errorf("wallpaper credential file: %w", err)
			}
		}
	}
	return func(key string) string {
		if value := getenv(key); value != "" {
			return value
		}
		return defaults[key]
	}, nil
}
