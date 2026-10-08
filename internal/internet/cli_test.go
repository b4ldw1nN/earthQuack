package internet

import (
	"bytes"
	"strings"
	"testing"
)

// cliEnv points the CLI at an isolated state dir with no overrides.
func cliEnv(state string) func(string) string {
	return func(key string) string {
		if key == EnvState {
			return state
		}
		return ""
	}
}

func runInternet(t *testing.T, args []string, env func(string) string) (string, string, int) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := RunCLIWithIO(args, &stdout, &stderr, env)
	return stdout.String(), stderr.String(), code
}

func TestInternetStatusEmpty(t *testing.T) {
	out, _, code := runInternet(t, []string{"status"}, cliEnv(t.TempDir()))
	if code != 0 {
		t.Fatalf("exit=%d", code)
	}
	for _, want := range []string{"Internet Microscope Status", "Sources:  0", "Active:   0", "Errors:   0"} {
		if !strings.Contains(out, want) {
			t.Errorf("status missing %q:\n%s", want, out)
		}
	}
}

func TestInternetAddEnableDisableRemove(t *testing.T) {
	env := cliEnv(t.TempDir())

	if out, _, code := runInternet(t, []string{"add", "-id", "page", "-url", "https://example.com", "-type", "http"}, env); code != 0 {
		t.Fatalf("add exit=%d, out=%s", code, out)
	}
	if _, serr, code := runInternet(t, []string{"add", "-id", "page", "-url", "https://example.com"}, env); code == 0 {
		t.Fatalf("duplicate add must fail, stderr=%s", serr)
	}
	if _, serr, code := runInternet(t, []string{"add", "-id", "bad", "-url", "gopher://x"}, env); code == 0 {
		t.Fatalf("invalid url must fail, stderr=%s", serr)
	}
	if _, serr, code := runInternet(t, []string{"add", "-id", "fast", "-url", "https://e.com", "-interval", "1s"}, env); code == 0 {
		t.Fatalf("sub-minimum interval must fail, stderr=%s", serr)
	}

	out, _, code := runInternet(t, []string{"sources"}, env)
	if code != 0 {
		t.Fatalf("sources exit=%d", code)
	}
	if !strings.Contains(out, "page") || !strings.Contains(out, "https://example.com") {
		t.Errorf("sources missing the added source:\n%s", out)
	}

	if out, _, code := runInternet(t, []string{"disable", "page"}, env); code != 0 || !strings.Contains(out, "disabled page") {
		t.Fatalf("disable exit=%d, out=%s", code, out)
	}
	out, _, _ = runInternet(t, []string{"sources"}, env)
	if !strings.Contains(out, "no") {
		t.Errorf("disabled source must render as no:\n%s", out)
	}
	if out, _, code := runInternet(t, []string{"enable", "page"}, env); code != 0 || !strings.Contains(out, "enabled page") {
		t.Fatalf("enable exit=%d, out=%s", code, out)
	}
	if _, serr, code := runInternet(t, []string{"enable", "missing"}, env); code == 0 {
		t.Fatalf("enabling an unknown source must fail, stderr=%s", serr)
	}
	if out, _, code := runInternet(t, []string{"remove", "page"}, env); code != 0 || !strings.Contains(out, "removed page") {
		t.Fatalf("remove exit=%d, out=%s", code, out)
	}
	out, _, _ = runInternet(t, []string{"sources"}, env)
	if strings.Contains(out, "page ") {
		t.Errorf("removed source still listed:\n%s", out)
	}
}

func TestInternetCheckAgainstLocalServer(t *testing.T) {
	srv := newTestServer(t, "revision one\n")
	env := cliEnv(t.TempDir())
	if _, _, code := runInternet(t, []string{"add", "-id", "page", "-url", srv.srv.URL}, env); code != 0 {
		t.Fatalf("add exit=%d", code)
	}

	out, _, code := runInternet(t, []string{"check", "page"}, env)
	if code != 0 {
		t.Fatalf("check exit=%d, out=%s", code, out)
	}
	if !strings.Contains(out, "NEW") {
		t.Errorf("first check must print NEW:\n%s", out)
	}
	out, _, code = runInternet(t, []string{"check", "page"}, env)
	if code != 0 || !strings.Contains(out, "UNCHANGED") {
		t.Errorf("second check must print UNCHANGED: exit=%d out=%s", code, out)
	}
	srv.setBody("revision two\n")
	out, _, code = runInternet(t, []string{"check", "page"}, env)
	if code != 0 || !strings.Contains(out, "CHANGED") {
		t.Errorf("third check must print CHANGED: exit=%d out=%s", code, out)
	}
	if out, serr, code := runInternet(t, []string{"check", "--all"}, env); code != 0 {
		t.Errorf("check --all exit=%d, out=%s err=%s", code, out, serr)
	} else if !strings.Contains(out, "UNCHANGED") {
		t.Errorf("check --all must print UNCHANGED:\n%s", out)
	}
	out2, _, _ := runInternet(t, []string{"remove", "page"}, env)
	_ = out2
}

func TestInternetCheckFailureExitCode(t *testing.T) {
	env := cliEnv(t.TempDir())
	if _, _, code := runInternet(t, []string{"add", "-id", "dead", "-url", "http://127.0.0.1:1/"}, env); code != 0 {
		t.Fatalf("add exit=%d", code)
	}
	out, _, code := runInternet(t, []string{"check", "-timeout", "500ms", "dead"}, env)
	if code != 1 {
		t.Fatalf("failed check exit=%d, want 1, out=%s", code, out)
	}
	if !strings.Contains(out, "ERROR") {
		t.Errorf("failed check must print ERROR:\n%s", out)
	}
	if _, _, code := runInternet(t, []string{"check", "missing"}, env); code == 0 {
		t.Fatal("checking an unknown source must fail")
	}
}

func TestInternetHelp(t *testing.T) {
	out, _, code := runInternet(t, []string{"help"}, cliEnv(t.TempDir()))
	if code != 0 {
		t.Fatalf("help exit=%d", code)
	}
	for _, want := range []string{"status", "sources", "add", "remove", "enable", "disable", "check"} {
		if !strings.Contains(out, want) {
			t.Errorf("help missing %q", want)
		}
	}
	if _, _, code := runInternet(t, []string{"bogus"}, cliEnv(t.TempDir())); code != 2 {
		t.Fatalf("unknown subcommand exit=%d, want 2", code)
	}
	if _, _, code := runInternet(t, nil, cliEnv(t.TempDir())); code != 2 {
		t.Fatalf("no subcommand exit=%d, want 2", code)
	}
}
