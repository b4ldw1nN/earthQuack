package node

import (
	"fmt"
	"strings"
)

// History presentation helpers. The dashboard template has no FuncMap,
// so history sections are rendered as pre-built HTML strings in Go
// (pure server-side; no JS, no charting dependency). The node-level
// aggregate counters and CPU percent are tiny enough that a
// full-width unicode block sparkline is compact and dependency-free.

// Numeric helper: convert any sample field to a plain float64 series.
// The template cannot call conversions, so dashboard.go builds these.
type seriesOf func(i int) (float64, bool)

// sparkline renders a sparkline for the n samples using the selector.
func sparkline(n int, pick seriesOf) string {
	if n <= 0 {
		return "—"
	}
	vals := make([]float64, 0, n)
	for i := 0; i < n; i++ {
		if v, ok := pick(i); ok && v >= 0 {
			vals = append(vals, v)
		}
	}
	if len(vals) == 0 {
		return "—"
	}
	max := vals[0]
	for _, v := range vals[1:] {
		if v > max {
			max = v
		}
	}
	blocks := []rune{' ', '▂', '▃', '▄', '▅', '▆', '▇', '█'}
	if max <= 0 {
		return strings.Repeat(" ", len(vals))
	}
	var b strings.Builder
	b.Grow(len(vals) * 3)
	for _, v := range vals {
		idx := int(v / max * float64(len(blocks)-1))
		if idx < 0 {
			idx = 0
		}
		if idx > len(blocks)-1 {
			idx = len(blocks) - 1
		}
		b.WriteRune(blocks[idx])
	}
	return b.String()
}

// historyRow is one dashboard history line (metric label + sparkline
// + latest value). Pre-rendered in Go and embedded in the template.
type historyRow struct {
	Label string
	Bars  string
	Value string
}

// buildHistoryRows converts bounded samples into presentation rows.
// latest formats the newest usable value; missing series render
// "—" rather than a fabricated zero.
func buildHistoryRows(samples []MetricSample) []historyRow {
	if len(samples) == 0 {
		return nil
	}
	memPick := func(i int) (float64, bool) {
		if samples[i].MemTotal == 0 {
			return 0, false
		}
		return float64(samples[i].MemUsed) / float64(samples[i].MemTotal) * 100, true
	}
	cpuPick := func(i int) (float64, bool) {
		if samples[i].CPUPercent == nil {
			return 0, false
		}
		return *samples[i].CPUPercent, true
	}
	rxPick := func(i int) (float64, bool) { return float64(samples[i].NetRX), true }
	txPick := func(i int) (float64, bool) { return float64(samples[i].NetTX), true }

	rows := []historyRow{
		{Label: "CPU %", Bars: sparkline(len(samples), cpuPick), Value: latestStr(samples, "cpu")},
		{Label: "Memory %", Bars: sparkline(len(samples), memPick), Value: latestStr(samples, "mem")},
		{Label: "RX", Bars: sparkline(len(samples), rxPick), Value: latestStr(samples, "rx")},
		{Label: "TX", Bars: sparkline(len(samples), txPick), Value: latestStr(samples, "tx")},
	}
	return rows
}

func latestStr(samples []MetricSample, kind string) string {
	if len(samples) == 0 {
		return "—"
	}
	last := samples[len(samples)-1]
	switch kind {
	case "cpu":
		if last.CPUPercent != nil {
			return fmt.Sprintf("%.0f%%", *last.CPUPercent)
		}
	case "mem":
		if last.MemTotal > 0 {
			return fmt.Sprintf("%.0f%%", float64(last.MemUsed)/float64(last.MemTotal)*100)
		}
	case "rx":
		return bytesHuman(last.NetRX)
	case "tx":
		return bytesHuman(last.NetTX)
	}
	return "—"
}

// formatHistoryEvents renders recent transition events as one
// plain-text newline-joined block for the dashboard, newest-first.
// Format: "15:04 HEALTHY → DEGRADED · service clipboard running →
// stopped". Escaped by html/template at render time.
func formatHistoryEvents(events []HistoryEvent) string {
	if len(events) == 0 {
		return ""
	}
	out := make([]string, 0, len(events))
	for _, e := range events {
		s := ""
		if !e.Time.IsZero() {
			s = e.Time.Format("15:04")
		}
		switch e.Kind {
		case "health":
			s += " " + e.From + " → " + e.To
		case "service":
			name := e.Name
			if name == "" {
				name = e.Message
			}
			s += " " + name + " " + e.From + " → " + e.To
		default:
			s += " " + e.Message
		}
		out = append(out, s)
	}
	// Newest first reads best in a status strip.
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return strings.Join(out, "\n")
}
