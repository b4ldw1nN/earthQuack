package wallpaper

import (
	"context"
	"errors"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestConfiguredModuleSync(t *testing.T) {
	fake := newTelegramServer()
	srv := httptest.NewServer(fake.handler())
	defer srv.Close()
	source, state := t.TempDir(), t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "test.jpg"), []byte("test wallpaper"), 0600); err != nil {
		t.Fatal(err)
	}
	env := map[string]string{EnvTelegramToken: "test-token", EnvTelegramChatID: "123", EnvAPIBase: srv.URL, EnvSource: "/wrong/source", EnvState: "/wrong/state"}
	m, err := NewConfiguredModule(source, state, "telegram", func(key string) string { return env[key] })
	if err != nil {
		t.Fatal(err)
	}
	report, err := m.Sync(context.Background(), false)
	if err != nil || report.Uploaded != 1 {
		t.Fatalf("report=%+v err=%v", report, err)
	}
	m, err = NewConfiguredModule(source, state, "telegram", func(key string) string { return env[key] })
	if err != nil {
		t.Fatal(err)
	}
	report, err = m.Sync(context.Background(), false)
	if err != nil || report.Uploaded != 0 || report.Archived != 1 {
		t.Fatalf("report=%+v err=%v", report, err)
	}
}

func TestSyncCancelledBeforeScanDoesNotWrite(t *testing.T) {
	source, state := t.TempDir(), t.TempDir()
	m, err := NewModule(Config{Source: source, StateDir: state}, passiveProvider{name: "test"})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := m.Sync(ctx, false); !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
	entries, err := os.ReadDir(state)
	if err != nil || len(entries) != 0 {
		t.Fatalf("state changed: %v %v", entries, err)
	}
}
