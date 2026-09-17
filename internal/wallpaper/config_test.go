package wallpaper

import (
	"testing"
)

func TestResolveEnvPrecedence(t *testing.T) {
	env := map[string]string{
		EnvSource:         "/env/source",
		EnvState:          "/env/state",
		EnvProvider:       "telegram",
		EnvTelegramToken:  "tok",
		EnvTelegramChatID: "cid",
	}
	ec := resolveEnv("", "", func(k string) string { return env[k] })
	if ec.ProviderName != "telegram" {
		t.Fatalf("provider: %q", ec.ProviderName)
	}
	if ec.Config.Source != "/env/source" || ec.Config.StateDir != "/env/state" {
		t.Fatalf("env not respected: %+v", ec.Config)
	}
	if !ec.Enabled {
		t.Fatal("wallpaper should be enabled when credentials are present")
	}
	// Explicit overrides beat environment.
	ec2 := resolveEnv("/explicit", "/state2", func(k string) string { return env[k] })
	if ec2.Config.Source != "/explicit" || ec2.Config.StateDir != "/state2" {
		t.Fatalf("explicit overrides not respected: %+v", ec2.Config)
	}
}

func TestResolveEnvDefaults(t *testing.T) {
	ec := resolveEnv("", "", func(string) string { return "" })
	if ec.Config.Source != DefaultSource() {
		t.Fatalf("default source: %q", ec.Config.Source)
	}
	if ec.ProviderName != "telegram" {
		t.Fatalf("default provider: %q", ec.ProviderName)
	}
	if ec.Enabled {
		t.Fatal("wallpaper must not be enabled without config/credentials")
	}
}

func TestBuildProviderUnsupported(t *testing.T) {
	if _, err := BuildProvider("s3", "/st", TelegramConfig{Token: "t", ChatID: "c"}, nil); err == nil {
		t.Fatal("unsupported provider must error")
	}
}

func TestBuildProviderTelegram(t *testing.T) {
	p, err := BuildProvider("telegram", t.TempDir(), TelegramConfig{Token: "t", ChatID: "c"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if p.Name() != "telegram" {
		t.Fatalf("name: %q", p.Name())
	}
}
