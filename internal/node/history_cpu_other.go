//go:build !linux

package node

import (
	"time"
)

// cpuSampler is a stub for platforms without /proc/stat (Windows and
// Android node support is deferred). Utilization always reports
// unavailable so history records nil — never a fabricated zero.
type cpuSampler struct{}

func newCPUSampler() *cpuSampler { return &cpuSampler{} }

func (c *cpuSampler) Utilization() (float64, bool, time.Time) {
	return 0, false, time.Time{}
}
