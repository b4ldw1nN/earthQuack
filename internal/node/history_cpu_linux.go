//go:build linux

package node

import (
	"os"
	"time"
)

// cpuSampler measures CPU utilization from two /proc/stat readings,
// following the established provider pattern: Linux implementation
// here, stub in history_cpu_other.go, injectable reader, pure parsers
// in history_cpu.go, hermetic tests. No shelling out.
type cpuSampler struct {
	read func(string) ([]byte, error) // nil => os.ReadFile
	prev cpuTimes
	have bool
}

const procStatPath = "/proc/stat"

// newCPUSampler returns the default Linux CPU sampler.
func newCPUSampler() *cpuSampler {
	return &cpuSampler{}
}

// Utilization reads /proc/stat and returns the utilization since the
// previous call. ok=false on the first call (nothing to compare
// against yet) and whenever the reading is unusable — the caller must
// record nil, never a fabricated zero. now is taken by the caller
// and is not needed here; the kernel counters carry their own delta.
func (c *cpuSampler) Utilization() (float64, bool, time.Time) {
	read := c.read
	if read == nil {
		read = os.ReadFile
	}
	b, err := read(procStatPath)
	if err != nil {
		return 0, false, time.Time{}
	}
	cur, ok := parseProcStat(string(b))
	if !ok {
		return 0, false, time.Time{}
	}
	prev, have := c.prev, c.have
	c.prev, c.have = cur, true
	if !have {
		return 0, false, time.Time{} // first sample: no delta exists
	}
	pct, ok := cpuUtilization(prev, cur)
	if !ok {
		return 0, false, time.Time{}
	}
	return pct, true, time.Time{}
}
