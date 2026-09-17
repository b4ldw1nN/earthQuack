package wallpaper

import (
	"fmt"
	"io"
	"strings"
)

// progress is a minimal, dependency-free terminal progress line. It
// writes a single carriate-return-updated line like:
//
//	[12/34] Tokyo Night/lowpoly_street.png
//
// It stays usable over SSH and Termux (plain text only), and degrades
// gracefully when the writer is not a terminal: if tty is false it
// emits nothing at all (tests run with tty=false and capture nothing).
type progress struct {
	w       io.Writer
	tty     bool
	started bool
	width   int
}

func newProgress(w io.Writer, tty bool) *progress {
	return &progress{w: w, tty: tty, width: 50}
}

// update paints the current line. A blank label clears the line so it
// does not bleed into following output.
func (p *progress) update(done, total int, label string) {
	if !p.tty || p.w == nil {
		return
	}
	if label == "" {
		if p.started {
			fmt.Fprint(p.w, "\r\x1b[K")
			p.started = false
		}
		return
	}
	p.started = true
	prefix := fmt.Sprintf("[%d/%d] ", done, total)
	if len(label) > p.width {
		label = "…" + label[len(label)-p.width:]
	}
	fmt.Fprintf(p.w, "\r%s%s\x1b[K", prefix, strings.ToValidUTF8(label, "�"))
}

// finish clears the line, leaving the caller free to print a summary.
func (p *progress) finish() { p.update(0, 0, "") }
