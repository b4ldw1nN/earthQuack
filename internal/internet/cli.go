package internet

import (
	"flag"
	"fmt"
	"io"
	"os"
)

// RunCLI is the entry point for `earthquack-node internet ...`. It
// returns a process exit code (0 = success, non-zero = error).
func RunCLI(args []string) int {
	return RunCLIWithIO(args, os.Stdout, os.Stderr, os.Getenv)
}

// RunCLIWithIO is RunCLI with injectable streams + env (for tests). The
// CLI is a composition root: it resolves configuration, builds the
// module and drives it. It never starts a poller — `check` performs
// exactly the checks it is asked for.
func RunCLIWithIO(args []string, stdout, stderr io.Writer, getenv func(string) string) int {
	if len(args) < 1 {
		usage(stderr)
		return 2
	}
	sub, rest := args[0], args[1:]
	switch sub {
	case "status":
		return cmdStatus(rest, stdout, stderr, getenv)
	case "sources":
		return cmdSources(rest, stdout, stderr, getenv)
	case "add":
		return cmdAdd(rest, stdout, stderr, getenv)
	case "remove":
		return cmdRemove(rest, stdout, stderr, getenv)
	case "enable", "disable":
		return cmdToggle(rest, stdout, stderr, getenv, sub == "enable")
	case "check":
		return cmdCheck(rest, stdout, stderr, getenv)
	case "help", "-h", "--help":
		usage(stdout)
		return 0
	default:
		fmt.Fprintf(stderr, "internet: unknown subcommand %q\n\n", sub)
		usage(stderr)
		return 2
	}
}

// stateFlagSet returns a FlagSet with the shared state-directory flag.
func stateFlagSet(name string) (*flag.FlagSet, *string) {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	state := fs.String("state", "",
		"state dir for sources.json (default $"+EnvState+" or ~/.local/share/earthquack/internet)")
	return fs, state
}

// openModule builds the module from flags + environment.
func openModule(fs *flag.FlagSet, rest []string, state *string, getenv func(string) string, stderr io.Writer) (*Module, bool) {
	if err := fs.Parse(rest); err != nil {
		fmt.Fprintf(stderr, "invalid flags: %v\n", err)
		return nil, false
	}
	cfg, err := ConfigFromEnv(getenv)
	if err != nil {
		fmt.Fprintf(stderr, "internet: %v\n", err)
		return nil, false
	}
	if *state != "" {
		cfg.StateDir = *state
	}
	module, err := NewModule(cfg)
	if err != nil {
		fmt.Fprintf(stderr, "internet: %v\n", err)
		return nil, false
	}
	return module, true
}

// one returns the single positional argument, or reports a usage error.
func one(args []string, what string, stderr io.Writer) (string, bool) {
	if len(args) != 1 {
		fmt.Fprintf(stderr, "internet: expected exactly one %s argument\n", what)
		return "", false
	}
	return args[0], true
}

// cmdStatus prints the module summary (read-only, no network).
func cmdStatus(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, state := stateFlagSet("internet status")
	m, ok := openModule(fs, rest, state, getenv, stderr)
	if !ok {
		return 2
	}
	printStatus(stdout, m.Snapshot())
	return 0
}

// cmdSources lists the configured sources (read-only, no network).
func cmdSources(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, state := stateFlagSet("internet sources")
	m, ok := openModule(fs, rest, state, getenv, stderr)
	if !ok {
		return 2
	}
	printSources(stdout, m.Snapshot())
	return 0
}

// cmdAdd defines a new source.
func cmdAdd(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, state := stateFlagSet("internet add")
	id := fs.String("id", "", "stable source id (required)")
	name := fs.String("name", "", "display name (default: the id)")
	typ := fs.String("type", string(TypeHTTP), "source type: "+supportedTypes())
	url := fs.String("url", "", "endpoint to observe (required, http/https)")
	interval := fs.String("interval", "", "poll interval (default: module interval)")
	disabled := fs.Bool("disabled", false, "add the source without enabling it")
	meta := fs.String("meta", "", "optional metadata as key=value pairs, comma separated")
	m, ok := openModule(fs, rest, state, getenv, stderr)
	if !ok {
		return 2
	}
	src := Source{
		ID:       *id,
		Name:     *name,
		Type:     SourceType(*typ),
		URL:      *url,
		Enabled:  !*disabled,
		Metadata: parseMeta(*meta),
	}
	if *interval != "" {
		d, err := ParseDuration(*interval)
		if err != nil {
			fmt.Fprintf(stderr, "internet: %v\n", err)
			return 2
		}
		src.Interval = d
	}
	added, err := m.Add(src)
	if err != nil {
		fmt.Fprintf(stderr, "%v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "added %s (%s): %s [%s, %s]\n",
		added.ID, added.Type, added.URL,
		enabledLabel(added.Enabled), added.EffectiveInterval(m.Config().Interval))
	if !added.Enabled {
		fmt.Fprintf(stdout, "source is disabled; enable it with: internet enable %s\n", added.ID)
	}
	return 0
}
