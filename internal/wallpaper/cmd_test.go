package wallpaper

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// noCredsEnv returns an env with source/state but no provider creds.
func noCredsEnv(t *testing.T, src, state string) map[string]string {
	t.Helper()
	return map[string]string{
		EnvSource: src,
		EnvState:  state,
	}
}

func getenvFor(env map[string]string) func(string) string {
	return func(k string) string { return env[k] }
}

func TestWallpaperStatusCommand(t *testing.T) {
	src := t.TempDir()
	state := t.TempDir()
	writeFile(t, src, "Tokyo Night/a.png", []byte("one"))
	writeFile(t, src, "b.webp", []byte("two"))

	env := noCredsEnv(t, src, state) // status needs no credentials
	var out bytes.Buffer
	code := RunCLIWithIO([]string{"status", "-source", src, "-state", state}, nil, &out, &out, getenvFor(env))
	if code != 0 {
		t.Fatalf("exit=%d, out=%s", code, out.String())
	}
	got := out.String()
	for _, want := range []string{"Wallpaper Backup Status", "Discovered files:    2", "Pending files:       2", "Provider:  telegram", "Last sync:           never"} {
		if !strings.Contains(got, want) {
			t.Errorf("status missing %q:\n%s", want, got)
		}
	}
	// status must not create state files.
	for _, f := range []string{uploadFileName, failedFileName, syncFileName} {
		if _, err := statPath(t, state, f); err == nil {
			t.Errorf("status must not create %s", f)
		}
	}
}

func TestWallpaperDryRunCommandIsPassiveAndClean(t *testing.T) {
	src := t.TempDir()
	state := t.TempDir()
	writeFile(t, src, "Tokyo Night/a.png", []byte("one"))
	writeFile(t, src, "b.webp", []byte("two"))

	// No credentials at all — dry-run must still work and change nothing.
	var out bytes.Buffer
	code := RunCLIWithIO([]string{"sync", "--dry-run", "-source", src, "-state", state}, nil, &out, &out, func(string) string {
		if false {
			t.Fatal()
		}
		return ""
	})
	if code != 0 {
		t.Fatalf("exit=%d, out=%s", code, out.String())
	}
	got := out.String()
	for _, want := range []string{"DRY RUN", "nothing was uploaded or changed", "Discovered: 2", "Pending:    2", "Uploaded:   0"} {
		if !strings.Contains(got, want) {
			t.Errorf("dry-run missing %q:\n%s", want, got)
		}
	}
	for _, f := range []string{uploadFileName, failedFileName, syncFileName} {
		if exists(t, state, f) {
			t.Errorf("dry-run must not create %s", f)
		}
	}
}

func TestWallpaperSyncCommandRequiresCredentials(t *testing.T) {
	src := t.TempDir()
	state := t.TempDir()
	writeFile(t, src, "a.png", []byte("one"))
	var out bytes.Buffer
	code := RunCLIWithIO([]string{"sync", "-source", src, "-state", state}, nil, &out, &out, getenvFor(noCredsEnv(t, src, state)))
	if code == 0 {
		t.Fatalf("real sync without credentials must fail, out=%s", out.String())
	}
}

func TestWallpaperEmptyRetryFailedSucceedsWithoutCredentials(t *testing.T) {
	// An empty failed set is a read-only report: it must work even with
	// no provider available.
	src := t.TempDir()
	state := t.TempDir()
	writeFile(t, src, "a.png", []byte("one"))
	var out bytes.Buffer
	code := RunCLIWithIO([]string{"retry-failed", "-source", src, "-state", state}, nil, &out, &out, getenvFor(noCredsEnv(t, src, state)))
	if code != 0 {
		t.Fatalf("exit=%d, out=%s", code, out.String())
	}
	got := out.String()
	for _, want := range []string{"Sync summary", "Uploaded:   0", "Failed:     0"} {
		if !strings.Contains(got, want) {
			t.Errorf("retry-failed missing %q:\n%s", want, got)
		}
	}
}

func TestWallpaperUnknownCommandExitsUsage(t *testing.T) {
	var out bytes.Buffer
	if code := RunCLIWithIO([]string{"bogus"}, nil, &out, &out, getenvFor(map[string]string{})); code != 2 {
		t.Fatalf("want usage exit 2, got %d", code)
	}
}

func statPath(t *testing.T, dir, name string) (string, error) {
	t.Helper()
	if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
		return "", err
	}
	return name, nil
}

func exists(t *testing.T, dir, name string) bool {
	t.Helper()
	_, err := statPath(t, dir, name)
	return err == nil
}
