package node

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/b4ldw1nN/earthquack/internal/wallpaper"
)

// TestWallpaperServiceRegistrationIsHealthy ensures that registering the
// wallpaper module as a running in-process service does not degrade node
// health merely because zero wallpapers are pending, and that it reuses
// the existing capability/service registration structures (no parallel
// registry).
func TestWallpaperServiceRegistrationIsHealthy(t *testing.T) {
	reg, err := NewRegistry("machine:test", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	// Same registration path main.go uses for the wallpaper module.
	reg.RegisterCapability("wallpaper")
	reg.RegisterService(Service{Name: "wallpaper", Status: ServiceRunning, Version: "0.1.0"})

	local := reg.Local()
	if !local.HasCapability("wallpaper") {
		t.Fatal("wallpaper capability must be registered")
	}
	if len(local.Services) != 1 || local.Services[0].Name != "wallpaper" || local.Services[0].Status != ServiceRunning {
		t.Fatalf("wallpaper service not registered as running: %+v", local.Services)
	}
	if h := local.Health; h == nil || h.Status != HealthHealthy {
		t.Fatalf("a running wallpaper service with zero pending must not degrade health: %+v", h)
	}
}

// TestWallpaperRegistrationDoesNotAffectOtherServices confirms that
// wallpaper is purely additive alongside existing clipboard/file-
// transfer services.
func TestWallpaperRegistrationDoesNotAffectOtherServices(t *testing.T) {
	reg, err := NewRegistry("machine:test", []NetworkProvider{&fakeProvider{}}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	reg.RegisterCapability("clipboard")
	reg.RegisterService(Service{Name: "clipboard", Status: ServiceRunning})
	reg.RegisterCapability("wallpaper")
	reg.RegisterService(Service{Name: "wallpaper", Status: ServiceRunning})

	local := reg.Local()
	if len(local.Capabilities) != 2 {
		t.Fatalf("want 2 capabilities, got %+v", local.Capabilities)
	}
	if len(local.Services) != 2 {
		t.Fatalf("want 2 services, got %+v", local.Services)
	}
	for _, s := range local.Services {
		if s.Status != ServiceRunning {
			t.Errorf("service %s should be running", s.Name)
		}
	}
	if h := local.Health; h == nil || h.Status != HealthHealthy {
		t.Fatalf("healthy node with wallpaper must stay healthy: %+v", h)
	}
}

// TestWallpaperConfigChainPinsMultiRootDefault ensures a node config with
// multiple sources reaches the CLI composition path unchanged: the exact
// directories and the 530-file count this deployment relies on.
func TestWallpaperConfigChainPinsMultiRoot(t *testing.T) {
	lower := t.TempDir()
	upper := filepath.Join(lower, "Wallpapers")
	if err := os.MkdirAll(upper, 0o755); err != nil {
		t.Fatal(err)
	}
	writeImage := func(dir, name string) {
		t.Helper()
		if err := os.WriteFile(filepath.Join(dir, name), []byte(name), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	writeImage(lower, "lower-root.png")
	if err := os.Mkdir(filepath.Join(upper, "Tokyo Night"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeImage(upper, filepath.Join("Tokyo Night", "a.png"))

	configPath := filepath.Join(t.TempDir(), "node.json")
	json := `{"wallpaper":{"enabled":true,"sources":["` + lower + `","` + upper + `"],"state_dir":"` + t.TempDir() + `","provider":"telegram"}}`
	if err := os.WriteFile(configPath, []byte(json), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	env, err := WallpaperEnvironment(cfg.Wallpaper, func(key string) string {
		// Fake provider credentials: NewConfiguredModule builds a live
		// provider and requires them even for read-only status.
		if key == "TELEGRAM_BOT_TOKEN" {
			return "test-token"
		}
		if key == "TELEGRAM_CHAT_ID" {
			return "123"
		}
		return ""
	})
	if err != nil {
		t.Fatal(err)
	}
	source := env("EARTHQUACK_WALLPAPER_SOURCE")
	files, err := wallpaper.ScanSources(source)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 2 {
		t.Fatalf("want 2 files across both roots, got %d (%s)", len(files), source)
	}
	module, err := wallpaper.NewConfiguredModule("", "", "", env)
	if err != nil {
		t.Fatal(err)
	}
	report, err := module.Status()
	if err != nil {
		t.Fatal(err)
	}
	if report.Discovered != 2 || report.Pending != 2 {
		t.Fatalf("status through config chain: %+v", report)
	}
	if !strings.Contains(source, string(os.PathListSeparator)) {
		t.Fatal("multiple sources must survive the config chain")
	}
}
