package main

import (
	"encoding/base64"
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"testing"

	"github.com/b4ldw1nN/earthquack/internal/node"
)

func TestResolveAESKey(t *testing.T) {
	fileKey := base64.StdEncoding.EncodeToString(make([]byte, 32))
	path := filepath.Join(t.TempDir(), "config.json")
	data, err := json.Marshal(map[string]string{"clipboard_aes_key": fileKey})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := node.LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, tt := range []struct {
		name string
		env  string
		cfg  *node.Config
		want string
	}{
		{"file key", "", cfg, fileKey},
		{"config wins over stale environment", "env-key", cfg, fileKey},
		{"environment fallback with empty config key", "env-key", &node.Config{}, "env-key"},
		{"environment without config", "env-key", nil, "env-key"},
		{"empty config", "", &node.Config{}, ""},
		{"no config", "", nil, ""},
	} {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv("CLIPBOARD_AES_KEY", tt.env)
			if got := resolveAESKey(tt.cfg); got != tt.want {
				t.Fatal("AES key resolution did not match expected source")
			}
		})
	}
}

// TestResolveConfigPathFindsConfigOutsideCwd is the regression test for
// running `earthquack-node wallpaper sync` from a directory other than the
// repository. The config path used to default to the relative "config.json",
// so from any other working directory no config was loaded at all: the
// wallpaper credential file went unread and the command failed with
// "telegram: TELEGRAM_BOT_TOKEN is required".
func TestResolveConfigPathFindsConfigOutsideCwd(t *testing.T) {
	noEnv := func(string) string { return "" }

	// Two install shapes must both work from an unrelated working
	// directory: a portable install with the config beside the binary,
	// and the ~/.local/bin install where the config lives in the user's
	// XDG config directory.
	for _, tt := range []struct {
		name  string
		setup func(t *testing.T) (exePath, userDir, want string)
	}{
		{
			name: "config beside the binary",
			setup: func(t *testing.T) (string, string, string) {
				binDir := t.TempDir()
				want := filepath.Join(binDir, "config.json")
				if err := os.WriteFile(want, []byte("{}"), 0o600); err != nil {
					t.Fatal(err)
				}
				// A user config dir exists but holds no config, so the
				// binary-relative file must be the one that is found.
				return filepath.Join(binDir, "earthquack-node"), t.TempDir(), want
			},
		},
		{
			name: "config in the user config dir",
			setup: func(t *testing.T) (string, string, string) {
				userDir := t.TempDir()
				if err := os.MkdirAll(filepath.Join(userDir, "earthquack"), 0o700); err != nil {
					t.Fatal(err)
				}
				want := filepath.Join(userDir, "earthquack", "config.json")
				if err := os.WriteFile(want, []byte("{}"), 0o600); err != nil {
					t.Fatal(err)
				}
				// ~/.local/bin install: no config.json beside the binary.
				return filepath.Join(t.TempDir(), "earthquack-node"), userDir, want
			},
		},
	} {
		t.Run(tt.name, func(t *testing.T) {
			exe, userDir, want := tt.setup(t)
			// A working directory with no config.json anywhere near it.
			t.Chdir(t.TempDir())

			got := resolveConfigPath("", false, noEnv, exe, userDir)
			if got != want {
				t.Fatalf("default config must be found outside the cwd: got %q, want %q", got, want)
			}
			// The relocated path must survive the round trip to a real,
			// loadable config: this is the path the wallpaper CLI takes.
			cfg, err := node.LoadConfig(got)
			if err != nil || cfg == nil {
				t.Fatalf("relocated config must load: cfg=%v err=%v", cfg, err)
			}
		})
	}
}

// TestResolveConfigPathPrecedence pins the documented order: an explicit
// flag, then the environment, then the working directory, then the binary's
// own directory, then the user config directory. Each candidate file
// carries a distinct marker so the assertion proves *which* file was
// chosen, independent of whether it is reported as a relative or absolute
// path. An explicit value is never relocated, so it is never silently
// swapped for one of the fallbacks.
func TestResolveConfigPathPrecedence(t *testing.T) {
	noEnv := func(string) string { return "" }
	envWith := func(v string) func(string) string {
		return func(key string) string {
			if key == "EARTHQUACK_NODE_CONFIG" {
				return v
			}
			return ""
		}
	}
	// A config file identified by the capability it declares.
	writeMarked := func(t *testing.T, path, marker string) {
		t.Helper()
		body := `{"capabilities":["` + marker + `"]}`
		if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	// Returns the first capability of the config at path, or "" for none.
	markerOf := func(t *testing.T, path string) string {
		t.Helper()
		cfg, err := node.LoadConfig(path)
		if err != nil {
			// A deliberately non-existent explicit path must error rather
			// than fall through to another candidate.
			t.Fatalf("loading %q: %v", path, err)
		}
		if cfg == nil || len(cfg.Capabilities) == 0 {
			return ""
		}
		return cfg.Capabilities[0]
	}

	// A working directory with a config, a portable install with a config
	// beside the binary, and a user config directory with a config.
	cwd := t.TempDir()
	writeMarked(t, filepath.Join(cwd, "config.json"), "from-cwd")
	binDir := t.TempDir()
	writeMarked(t, filepath.Join(binDir, "config.json"), "from-binary")
	userDir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(userDir, "earthquack"), 0o700); err != nil {
		t.Fatal(err)
	}
	writeMarked(t, filepath.Join(userDir, "earthquack", "config.json"), "from-user")
	envFile := t.TempDir()
	writeMarked(t, filepath.Join(envFile, "node.json"), "from-env")
	flagFile := t.TempDir()
	writeMarked(t, filepath.Join(flagFile, "node.json"), "from-flag")
	// A binary with a config beside it (portable install), and one
	// without (the ~/.local/bin install).
	exe := filepath.Join(binDir, "earthquack-node")
	loneExe := filepath.Join(t.TempDir(), "earthquack-node")
	// A user config directory with no config in it.
	emptyUserDir := t.TempDir()
	// A working directory with no config.json in it.
	emptyCwd := t.TempDir()

	for _, tt := range []struct {
		name    string
		flagVal string
		flagSet bool
		getenv  func(string) string
		exe     string
		userDir string
		chdir   string
		want    string
	}{
		{"explicit flag wins and is not relocated", filepath.Join(flagFile, "node.json"), true, envWith(filepath.Join(envFile, "node.json")), exe, userDir, cwd, "from-flag"},
		{"explicit empty flag means no config", "", true, noEnv, exe, userDir, cwd, ""},
		{"environment wins over every file", "", false, envWith(filepath.Join(envFile, "node.json")), exe, userDir, cwd, "from-env"},
		{"cwd wins over the binary and user directories", "", false, noEnv, exe, userDir, cwd, "from-cwd"},
		{"binary directory wins over the user directory", "", false, noEnv, exe, userDir, emptyCwd, "from-binary"},
		{"user directory is the last fallback", "", false, noEnv, loneExe, userDir, emptyCwd, "from-user"},
		{"nothing found means no config", "", false, noEnv, loneExe, emptyUserDir, emptyCwd, ""},
	} {
		t.Run(tt.name, func(t *testing.T) {
			t.Chdir(tt.chdir)
			got := resolveConfigPath(tt.flagVal, tt.flagSet, tt.getenv, tt.exe, tt.userDir)
			if tt.flagSet && tt.flagVal == "" {
				// "" is the explicit "load no config" signal, and
				// main skips LoadConfig entirely for it.
				if got != "" {
					t.Fatalf("got %q, want no config", got)
				}
				return
			}
			if marker := markerOf(t, got); marker != tt.want {
				t.Fatalf("resolved %q which declared %q, want %q", got, marker, tt.want)
			}
		})
	}
}

// TestUserConfigDirFollowsXDGSpec checks the per-user configuration
// directory honours XDG_CONFIG_HOME and otherwise falls back to ~/.config.
func TestUserConfigDirFollowsXDGSpec(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", "/custom/xdg")
	if got, want := userConfigDir(), "/custom/xdg"; got != want {
		t.Fatalf("XDG_CONFIG_HOME must win: got %q, want %q", got, want)
	}
	t.Setenv("XDG_CONFIG_HOME", "")
	if got, want := userConfigDir(), filepath.Join(home, ".config"); got != want {
		t.Fatalf("default must be ~/.config: got %q, want %q", got, want)
	}
}

// TestFlagProvidedDetectsExplicitConfig guards the distinction between an
// explicit --config (honoured as given) and the default (eligible for the
// binary-relative fallback) that resolveConfigPath depends on.
func TestFlagProvidedDetectsExplicitConfig(t *testing.T) {
	newSet := func() *flag.FlagSet {
		fs := flag.NewFlagSet("test", flag.ContinueOnError)
		fs.String("config", "", "")
		fs.String("port", "0", "")
		return fs
	}

	// Default: nothing provided, so the fallback may apply.
	fs := newSet()
	if err := fs.Parse(nil); err != nil {
		t.Fatal(err)
	}
	if flagProvided(fs, "config") {
		t.Fatal("an unparsed --config must not count as provided")
	}

	// Explicit value: honoured exactly as given.
	fs = newSet()
	if err := fs.Parse([]string{"--config", "/some/path.json"}); err != nil {
		t.Fatal(err)
	}
	if !flagProvided(fs, "config") {
		t.Fatal("--config on the command line must count as provided")
	}

	// Explicitly disabling the config is still "provided".
	fs = newSet()
	if err := fs.Parse([]string{"--config", ""}); err != nil {
		t.Fatal(err)
	}
	if !flagProvided(fs, "config") {
		t.Fatal("--config '' must count as provided so no config is loaded")
	}

	// Another flag does not imply the config flag.
	fs = newSet()
	if err := fs.Parse([]string{"--port", "9000"}); err != nil {
		t.Fatal(err)
	}
	if flagProvided(fs, "config") {
		t.Fatal("an unrelated flag must not make --config look provided")
	}
}
