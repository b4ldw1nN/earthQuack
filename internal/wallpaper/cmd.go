package wallpaper

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"time"
)

// RunCLI is the entry point for `earthquack-node wallpaper ...`. It
// returns a process exit code (0 = success, non-zero = error). The CLI
// is the composition root: it resolves config, builds the provider, and
// drives the provider-agnostic module.
func RunCLI(args []string) int {
	return RunCLIWithIO(args, os.Stdin, os.Stdout, os.Stderr, os.Getenv)
}

// RunCLIWithIO is RunCLI with injectable streams + env (for tests).
func RunCLIWithIO(args []string, _ io.Reader, stdout, stderr io.Writer, getenv func(string) string) int {
	if len(args) < 1 {
		usage(stderr)
		return 2
	}
	sub := args[0]
	rest := args[1:]

	switch sub {
	case "status":
		return cmdStatus(rest, stdout, stderr, getenv)
	case "scan":
		return cmdScan(rest, stdout, stderr, getenv)
	case "sync":
		return cmdSync(rest, stdout, stderr, getenv)
	case "retry-failed":
		return cmdRetryFailed(rest, stdout, stderr, getenv)
	case "help", "-h", "--help":
		usage(stdout)
		return 0
	default:
		fmt.Fprintf(stderr, "wallpaper: unknown subcommand %q\n\n", sub)
		usage(stderr)
		return 2
	}
}

// commonFlags returns a FlagSet with the shared configuration flags.
func commonFlags(name string) (*flag.FlagSet, *string, *string, *string) {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	source := fs.String("source", "", "wallpaper source (default $EARTHQUACK_WALLPAPER_SOURCE or ~/Pictures/Wallpapers)")
	state := fs.String("state", "", "state dir for uploads/topics/failed/files (default $EARTHQUACK_WALLPAPER_STATE or ~/.local/share/earthquack/wallpaper)")
	provider := fs.String("provider", "", "archive provider (default telegram)")
	return fs, source, state, provider
}

// resolve returns the resolved EnvConfig from flags + environment.
func resolve(fs *flag.FlagSet, rest []string, getenv func(string) string) (EnvConfig, error) {
	if err := fs.Parse(rest); err != nil {
		return EnvConfig{}, err
	}
	src := fs.Lookup("source")
	st := fs.Lookup("state")
	pr := fs.Lookup("provider")
	ec := resolveEnv(src.Value.String(), st.Value.String(), getenv)
	if pr.Value.String() != "" {
		ec.ProviderName = pr.Value.String()
	}
	return ec, nil
}

// buildCLIModule builds the module. When allowPassive is true (read-only
// commands) and no live provider is configured, a passive provider is
// used so the command still works without credentials.
func buildCLIModule(ec EnvConfig, out io.Writer, allowPassive bool) (*Module, error) {
	provider, err := BuildProvider(ec.ProviderName, ec.StateDir, ec.Telegram, nil)
	if err != nil {
		if allowPassive {
			provider = passiveProvider{name: ec.ProviderName}
		} else {
			return nil, err
		}
	}
	return NewModule(ec.Config, provider,
		WithOutput(out, isTerminal(out)),
		WithEventSink(func(e SyncEvent, msg string) {
			fmt.Fprintf(out, "event %s: %s\n", e, msg)
		}),
	)
}

// cmdStatus prints the module status snapshot (read-only, no provider).
func cmdStatus(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, _, _, _ := commonFlags("wallpaper status")
	ec, err := resolve(fs, rest, getenv)
	if err != nil {
		fmt.Fprintf(stderr, "invalid flags: %v\n", err)
		return 2
	}
	m, err := buildCLIModule(ec, stdout, true)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	st, err := m.Status()
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	printStatus(stdout, st)
	return 0
}

// cmdScan lists discovered files + categories (read-only, no provider).
func cmdScan(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, source, _, _ := commonFlags("wallpaper scan")
	ec, err := resolve(fs, rest, getenv)
	if err != nil {
		fmt.Fprintf(stderr, "invalid flags: %v\n", err)
		return 2
	}
	_ = source
	m, err := buildCLIModule(ec, stdout, true)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	discovered, err := Scan(ec.Source)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	_ = m
	fmt.Fprintf(stdout, "Scanning %s\n", ec.Source)
	fmt.Fprintf(stdout, "Found %d supported image(s)\n\n", len(discovered))
	for _, f := range discovered {
		fmt.Fprintf(stdout, "  %-20s %s\n", "["+f.Category+"]", f.Rel)
	}
	return 0
}

// cmdSync runs a sync; -dry-run performs no uploads and no state change.
func cmdSync(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	dry := false
	fs := flag.NewFlagSet("wallpaper sync", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	source := fs.String("source", "", "")
	state := fs.String("state", "", "")
	provider := fs.String("provider", "", "")
	fs.BoolVar(&dry, "dry-run", false, "dry run: no uploads, no state changes")
	if err := fs.Parse(rest); err != nil {
		fmt.Fprintf(stderr, "invalid flags: %v\n", err)
		return 2
	}
	ec := resolveEnv(*source, *state, getenv)
	if *provider != "" {
		ec.ProviderName = *provider
	}

	m, err := buildCLIModule(ec, stderr, dry)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	if dry {
		fmt.Fprintln(stdout, "╭──────────────────────────────────────────╮")
		fmt.Fprintln(stdout, "│     WALLPAPER SYNC — DRY RUN             │")
		fmt.Fprintln(stdout, "╰──────────────────────────────────────────╯")
		fmt.Fprintln(stdout, "  No uploads, no topic creation, no state changes.")
		fmt.Fprintln(stdout)
	}
	ctx := context.Background()
	rep, err := m.Sync(ctx, dry)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	printReport(stdout, rep)
	if rep.Failed > 0 {
		return 1
	}
	return 0
}

// cmdRetryFailed retries files recorded in failed.json. Unlike an eager
// sync with pending files, an empty failed set needs no provider, so
// provider construction errors are downgraded to passive providers (a
// report, not an upload).
func cmdRetryFailed(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, _, _, _ := commonFlags("wallpaper retry-failed")
	ec, err := resolve(fs, rest, getenv)
	if err != nil {
		fmt.Fprintf(stderr, "invalid flags: %v\n", err)
		return 2
	}
	m, err := buildCLIModule(ec, stderr, true)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	ctx := context.Background()
	rep, err := m.RetryFailed(ctx)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return 1
	}
	// If any specific retry failed, make exit non-zero and surface the
	// failure — the passive provider can surface "not configured" here.
	printReport(stdout, rep)
	if rep.Failed > 0 {
		return 1
	}
	return 0
}

// printStatus renders the status snapshot.
func printStatus(w io.Writer, st Status) {
	fmt.Fprintln(w, "╭──────────────────────────────────────────╮")
	fmt.Fprintln(w, "│        Wallpaper Backup Status            │")
	fmt.Fprintln(w, "╰──────────────────────────────────────────╯")
	fmt.Fprintln(w)
	fmt.Fprintf(w, "Source:    %s\n", st.Source)
	fmt.Fprintf(w, "Provider:  %s\n", st.Provider)
	fmt.Fprintf(w, "State dir: %s\n", st.StateDir)
	fmt.Fprintln(w)
	fmt.Fprintf(w, "Discovered files:    %d\n", st.Discovered)
	fmt.Fprintf(w, "Archived files:      %d\n", st.Archived)
	fmt.Fprintf(w, "Pending files:       %d\n", st.Pending)
	fmt.Fprintf(w, "Invalid (too large): %d\n", st.Invalid)
	fmt.Fprintf(w, "Failed records:      %d\n", st.Failed)
	fmt.Fprintf(w, "Topics:              %d\n", st.Topics)
	fmt.Fprintf(w, "Digest cache hits:   %d\n", st.Cached)
	fmt.Fprintf(w, "Files hashed:        %d\n", st.Hashed)
	if st.HasLastSync {
		fmt.Fprintf(w, "Last sync:           %s\n", st.LastSync.Local().Format(time.RFC3339))
	} else {
		fmt.Fprintln(w, "Last sync:           never")
	}
}

// printReport renders sync/dry-run/retry results.
func printReport(w io.Writer, rep Report) {
	fmt.Fprintln(w)
	if rep.DryRun {
		fmt.Fprintln(w, "— DRY RUN — nothing was uploaded or changed —")
	}
	fmt.Fprintln(w, "Sync summary")
	fmt.Fprintf(w, "  Discovered: %d\n", rep.Discovered)
	fmt.Fprintf(w, "  Archived:   %d\n", rep.Archived)
	fmt.Fprintf(w, "  Pending:    %d\n", rep.Pending)
	fmt.Fprintf(w, "  Uploaded:   %d\n", rep.Uploaded)
	fmt.Fprintf(w, "  Failed:     %d\n", rep.Failed)
	fmt.Fprintf(w, "  Invalid:    %d\n", rep.Invalid)
	fmt.Fprintf(w, "  Skipped:    %d\n", rep.Skipped)
	fmt.Fprintf(w, "  Hashed:     %d\n", rep.Hashed)
	fmt.Fprintf(w, "  Topics:     %d\n", rep.Topics)
	if len(rep.ByCategory) > 0 {
		fmt.Fprintln(w, "  Pending by category:")
		for _, c := range rep.ByCategory {
			fmt.Fprintf(w, "    %-22s %d\n", c.Category, c.Pending)
		}
	}
}

// isTerminal is intentionally conservative: live progress is only shown
// on a TTY. In ordinary redirection/pipes (and all tests) it stays off.
func isTerminal(w io.Writer) bool {
	if f, ok := w.(*os.File); ok {
		fi, err := f.Stat()
		return err == nil && (fi.Mode()&os.ModeCharDevice) != 0
	}
	return false
}

func usage(w io.Writer) {
	fmt.Fprintln(w, "Usage: earthquakes-node wallpaper <command> [flags]")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Commands:")
	fmt.Fprintln(w, "  status           Show source/provider/archive/counts/state")
	fmt.Fprintln(w, "  scan             List discovered wallpaper files + categories")
	fmt.Fprintln(w, "  sync             Upload new/changed wallpapers")
	fmt.Fprintln(w, "  sync --dry-run   Show what would upload without uploading")
	fmt.Fprintln(w, "  retry-failed     Retry uploads recorded in failed.json")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Flags:")
	fmt.Fprintln(w, "  -source <dir>   wallpaper source (default $EARTHQUACK_WALLPAPER_SOURCE or ~/Pictures/Wallpapers)")
	fmt.Fprintln(w, "  -state <dir>    state dir (default $EARTHQUACK_WALLPAPER_STATE or ~/.local/share/earthquack/wallpaper)")
	fmt.Fprintln(w, "  -provider <p>   archive provider (default telegram)")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Credentials come from the environment:")
	fmt.Fprintln(w, "  TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID")
}
