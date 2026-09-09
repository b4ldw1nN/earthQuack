package node

import (
	"strconv"
	"strings"
)

// cpuTimes is one /proc/stat aggregate "cpu" line: cumulative jiffies
// since boot split into idle (idle+iowait) and total (all fields).
// Utilization between two readings is 100*(1 - idleDelta/totalDelta).
type cpuTimes struct {
	idle  uint64
	total uint64
}

// parseProcStat parses the aggregate "cpu" line of /proc/stat content.
// It returns ok=false when no usable aggregate line exists (empty
// input, malformed numbers, all-zero totals) so callers record "no
// data" instead of fabricating a utilization.
//
// The aggregate line has 10+ fields after "cpu":
//
//	cpu user nice system idle iowait irq softirq steal guest guest_nice
//
// idle = idle+iowait; total = sum of all fields. Guest times already
// count inside user/nice on Linux, but summing every field can only
// inflate the denominator, which errs toward conservatism (never
// toward >100% on sane input); utilization is clamped to [0,100]
// at use time anyway.
func parseProcStat(data string) (cpuTimes, bool) {
	for _, line := range strings.Split(data, "\n") {
		f := strings.Fields(line)
		if len(f) == 0 || f[0] != "cpu" {
			continue
		}
		if len(f) < 5 {
			return cpuTimes{}, false // need at least user..idle
		}
		var vals []uint64
		for _, s := range f[1:] {
			v, err := strconv.ParseUint(s, 10, 64)
			if err != nil {
				return cpuTimes{}, false
			}
			vals = append(vals, v)
		}
		var total uint64
		for _, v := range vals {
			total += v
		}
		if total == 0 {
			return cpuTimes{}, false
		}
		idle := vals[3] // idle
		if len(vals) > 4 {
			idle += vals[4] // + iowait
		}
		return cpuTimes{idle: idle, total: total}, true
	}
	return cpuTimes{}, false
}

// cpuUtilization returns the utilization percent between two readings.
// ok=false means "cannot compute": identical readings (no time
// passed), a counter reset/shrink, or a zero total delta. Callers
// must record nil, never zero, in that case — zero would claim the
// machine is idle when in fact nothing is known.
func cpuUtilization(prev, cur cpuTimes) (pct float64, ok bool) {
	if cur.total <= prev.total {
		return 0, false // reset or no time passed
	}
	totalDelta := cur.total - prev.total
	if cur.idle < prev.idle {
		return 0, false // idle shrank: counters reset
	}
	idleDelta := cur.idle - prev.idle
	if idleDelta > totalDelta {
		return 0, false
	}
	pct = 100 * (1 - float64(idleDelta)/float64(totalDelta))
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	return pct, true
}
