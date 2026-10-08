package internet

import (
	"context"
	"fmt"
	"io"
	"sort"
	"strings"
)

// cmdRemove deletes a source.
func cmdRemove(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, state := stateFlagSet("internet remove")
	m, ok := openModule(fs, rest, state, getenv, stderr)
	if !ok {
		return 2
	}
	id, ok := one(fs.Args(), "source", stderr)
	if !ok {
		return 2
	}
	if err := m.Remove(id); err != nil {
		fmt.Fprintf(stderr, "%v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "removed %s\n", id)
	return 0
}

// cmdToggle enables or disables a source.
func cmdToggle(rest []string, stdout, stderr io.Writer, getenv func(string) string, enable bool) int {
	verb, past := "disable", "disabled"
	if enable {
		verb, past = "enable", "enabled"
	}
	fs, state := stateFlagSet("internet " + verb)
	m, ok := openModule(fs, rest, state, getenv, stderr)
	if !ok {
		return 2
	}
	id, ok := one(fs.Args(), "source", stderr)
	if !ok {
		return 2
	}
	if err := m.SetEnabled(id, enable); err != nil {
		fmt.Fprintf(stderr, "%v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "%s %s\n", past, id)
	return 0
}

// cmdCheck observes one source (or all enabled sources) right now. It is
// the only command that touches the network, and it does so exactly as
// often as asked.
func cmdCheck(rest []string, stdout, stderr io.Writer, getenv func(string) string) int {
	fs, state := stateFlagSet("internet check")
	all := fs.Bool("all", false, "check every enabled source")
	timeout := fs.Duration("timeout", DefaultTimeout*2, "overall timeout for the check")
	m, ok := openModule(fs, rest, state, getenv, stderr)
	if !ok {
		return 2
	}
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	var results []Result
	switch {
	case *all:
		results = m.CheckAll(ctx)
	case len(fs.Args()) == 1:
		res, err := m.Check(ctx, fs.Args()[0])
		if err != nil {
			fmt.Fprintf(stderr, "%v\n", err)
			return 1
		}
		results = append(results, res)
	default:
		fmt.Fprintln(stderr, "internet: check requires a source id or --all")
		return 2
	}

	sort.Slice(results, func(i, j int) bool { return results[i].SourceID < results[j].SourceID })
	failed := 0
	for _, res := range results {
		fmt.Fprintf(stdout, "%-24s %-9s %s\n", res.SourceID, strings.ToUpper(string(res.Status)), resultDetail(res))
		if res.Status == StatusError {
			failed++
		}
	}
	if failed > 0 {
		return 1
	}
	return 0
}

// resultDetail renders the human-readable part of a check result.
func resultDetail(res Result) string {
	if res.Error != nil {
		return res.Error.Error()
	}
	if res.Status == StatusChanged {
		return fmt.Sprintf("%s → %s", short(res.PreviousFingerprint), short(res.CurrentFingerprint))
	}
	return short(res.CurrentFingerprint)
}
