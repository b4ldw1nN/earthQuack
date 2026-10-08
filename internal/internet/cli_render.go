package internet

import (
	"fmt"
	"io"
	"strings"
	"time"
)

// printStatus renders the module status snapshot.
func printStatus(w io.Writer, snap Snapshot) {
	fmt.Fprintln(w, "╭──────────────────────────────────────────╮")
	fmt.Fprintln(w, "│        Internet Microscope Status         │")
	fmt.Fprintln(w, "╰──────────────────────────────────────────╯")
	fmt.Fprintln(w)
	fmt.Fprintf(w, "State dir:        %s\n", snap.StateDir)
	fmt.Fprintf(w, "Default interval: %s\n", snap.DefaultInterval)
	fmt.Fprintf(w, "Polling:          %s\n", runningLabel(snap.Running))
	if !snap.LastPoll.IsZero() {
		fmt.Fprintf(w, "Last poll:        %s\n", snap.LastPoll.Format(time.RFC3339))
	}
	fmt.Fprintln(w)
	fmt.Fprintf(w, "Sources:  %d\n", snap.Summary.Sources)
	fmt.Fprintf(w, "Active:   %d\n", snap.Summary.Enabled)
	fmt.Fprintf(w, "Errors:   %d\n", snap.Summary.Errors)
	fmt.Fprintf(w, "Changed:  %d\n", snap.Summary.Changed)
	fmt.Fprintf(w, "New:      %d\n", snap.Summary.New)
	fmt.Fprintf(w, "Pending:  %d\n", snap.Summary.Pending)
}

// printSources renders one line per source, followed by the last error
// when the source has one.
func printSources(w io.Writer, snap Snapshot) {
	if len(snap.Sources) == 0 {
		fmt.Fprintln(w, "No sources configured. Add one with:")
		fmt.Fprintln(w, "  earthquack-node internet add -id <id> -type http -url <url>")
		return
	}
	fmt.Fprintf(w, "%-22s %-8s %-9s %-8s %-10s %s\n", "ID", "TYPE", "STATE", "ENABLED", "INTERVAL", "URL")
	for _, src := range snap.Sources {
		state := src.Status
		if state == "" {
			state = StatusPending
		}
		fmt.Fprintf(w, "%-22s %-8s %-9s %-8s %-10s %s\n",
			src.ID, src.Type, state, yesNo(src.Enabled), src.IntervalOr(snap.DefaultInterval), src.URL)
		if src.LastError != "" {
			fmt.Fprintf(w, "  error: %s\n", src.LastError)
		}
	}
}

// parseMeta parses "k=v,k2=v2" into a map, ignoring empty input.
func parseMeta(raw string) map[string]string {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	out := map[string]string{}
	for _, pair := range strings.Split(raw, ",") {
		key, value, ok := strings.Cut(pair, "=")
		key = strings.TrimSpace(key)
		if !ok || key == "" {
			continue
		}
		out[key] = strings.TrimSpace(value)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func enabledLabel(enabled bool) string {
	if enabled {
		return "enabled"
	}
	return "disabled"
}

func runningLabel(running bool) string {
	if running {
		return "running"
	}
	return "not running in this process"
}

func yesNo(v bool) string {
	if v {
		return "yes"
	}
	return "no"
}

// short abbreviates a fingerprint for human output.
func short(fingerprint string) string {
	if len(fingerprint) <= 12 {
		return fingerprint
	}
	return fingerprint[:12]
}

func usage(w io.Writer) {
	fmt.Fprintln(w, "Usage: earthquack-node internet <command> [flags]")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Commands:")
	fmt.Fprintln(w, "  status                 Show state dir, interval and counts")
	fmt.Fprintln(w, "  sources                List sources with their last observation")
	fmt.Fprintln(w, "  add                    Define a source (-id, -url, -type, -interval, -disabled, -meta)")
	fmt.Fprintln(w, "  remove <source>        Delete a source and its history")
	fmt.Fprintln(w, "  enable <source>        Start observing a source")
	fmt.Fprintln(w, "  disable <source>       Stop observing a source, keeping its history")
	fmt.Fprintln(w, "  check <source>|--all   Observe now: new / changed / unchanged / error")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Flags:")
	fmt.Fprintln(w, "  -state <dir>   state dir (default $"+EnvState+" or ~/.local/share/earthquack/internet)")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Source types:", supportedTypes(), "— http is a generic endpoint, rss parses RSS/Atom feeds.")
	fmt.Fprintln(w, "The node picks up CLI source changes when polling (re)starts.")
}
