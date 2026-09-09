package node

import (
	"context"
	"sort"
	"strconv"
	"time"
)

// TelemetrySampler periodically records local metric samples plus
// health/service transition events into the Registry's History.
//
// Architecture (never invert it):
//
//	collector (this sampler, on a ticker)
//	    ↓
//	history (bounded rings in the Registry)
//	    ↓
//	dashboard / API (read-only; they never sample, never read /proc)
//
// Sampling is deliberately NOT coupled to service probing: the
// ServiceRefresher owns port probes, this sampler owns telemetry
// snapshots. They share only the interval constant and the Registry.
// The dashboard never triggers collection — a browser request only
// reads already-recorded history.
//
// Lifecycle: call Run in a goroutine; context cancellation exits the
// loop. Collection never fails loudly: unreadable sources yield a
// skipped sample or nil metric fields, never a crash, never faked
// data.
type TelemetrySampler struct {
	reg            *Registry
	interval       time.Duration
	cpu            cpuReader
	prevRX         uint64
	prevTX         uint64
	prevT          time.Time
	havePrev       bool
	prevSvc        map[string]ServiceStatus
	prevHlth       HealthStatus
	haveHlth       bool
	prevPeerOnline map[Identity]bool
}

// cpuReader abstracts the CPU utilization source so tests can inject a
// fake. Production uses the linux/proc-backed cpuSampler.
type cpuReader interface {
	Utilization() (float64, bool, time.Time)
}

// NewTelemetrySampler returns a sampler for the local node.
// interval <= 0 selects DefaultHistoryInterval.
func NewTelemetrySampler(reg *Registry, interval time.Duration) *TelemetrySampler {
	if interval <= 0 {
		interval = DefaultHistoryInterval
	}
	return &TelemetrySampler{
		reg:            reg,
		interval:       interval,
		cpu:            newCPUSampler(),
		prevPeerOnline: make(map[Identity]bool),
	}
}

func buildEvent(n Node, kind, name, from, to, msg string) HistoryEvent {
	t := n.LastSeen
	if t.IsZero() {
		t = time.Now()
	}
	return HistoryEvent{
		Time:    t,
		Node:    n.Identity,
		Kind:    kind,
		Name:    name,
		From:    from,
		To:      to,
		Message: msg,
	}
}

// Sample performs one collection pass: snapshot memory + aggregate
// network counters, measure CPU utilization against the previous
// reading, derive traffic rates from counter deltas, record the
// sample, and emit health/service/ peer transition events. It never
// blocks long and never returns an error — failure is expressed as
// skipped samples or nil fields.
func (s *TelemetrySampler) Sample() {
	now := s.reg.now()

	var mem Memory
	var memOK bool
	if sys := s.reg.sysProvider; sys != nil {
		if si := sys.Collect(); si.Memory.Total > 0 {
			mem, memOK = si.Memory, true
		}
	}
	var rx, tx uint64
	var netOK bool
	if np := s.reg.netProvider; np != nil {
		if ns := np.Collect(); ns.HasInfo() {
			for _, iface := range ns.Interfaces {
				rx += iface.RXBytes
				tx += iface.TXBytes
			}
			netOK = true
		}
	}

	sample := MetricSample{Time: now}
	if memOK {
		sample.MemUsed = mem.Used
		sample.MemTotal = mem.Total
		sample.MemAvailable = mem.Available
	}
	if netOK {
		sample.NetRX = rx
		sample.NetTX = tx
		if s.havePrev && now.After(s.prevT) {
			dt := now.Sub(s.prevT).Seconds()
			if rx >= s.prevRX && tx >= s.prevTX && dt > 0 {
				rR := float64(rx-s.prevRX) / dt
				tR := float64(tx-s.prevTX) / dt
				sample.RXRate = &rR
				sample.TXRate = &tR
			}
		}
		s.prevRX, s.prevTX, s.prevT, s.havePrev = rx, tx, now, true
	}

	if pct, ok, _ := s.cpu.Utilization(); ok {
		sample.CPUPercent = &pct
	}

	if memOK || netOK || sample.CPUPercent != nil {
		s.reg.history.AddSample(sample)
	}

	s.recordTransitions()
	s.recordPeerLifecycle()
}

func (s *TelemetrySampler) recordTransitions() {
	local := s.reg.Local()
	curSvc := make(map[string]ServiceStatus, len(local.Services))
	for _, svc := range local.Services {
		curSvc[svc.Name] = svc.Status
	}
	if s.prevSvc != nil {
		names := make([]string, 0, len(curSvc))
		for name := range curSvc {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			prev, existed := s.prevSvc[name]
			if !existed || prev == curSvc[name] {
				continue
			}
			kind := "service"
			from := string(prev)
			to := string(curSvc[name])
			baseMsg := "service " + name + " " + from + " → " + to
			s.reg.history.AddEvent(buildEvent(local, kind, name, from, to, baseMsg))
		}
	}
	s.prevSvc = curSvc

	curHlth := HealthUnknown
	if local.Health != nil {
		curHlth = local.Health.Status
	}
	if s.haveHlth && curHlth != s.prevHlth {
		kind := "health"
		from := string(s.prevHlth)
		to := string(curHlth)
		baseMsg := "health " + from + " → " + to
		s.reg.history.AddEvent(buildEvent(local, kind, "", from, to, baseMsg))
	}
	s.prevHlth, s.haveHlth = curHlth, true
}

func (s *TelemetrySampler) recordPeerLifecycle() {
	localID := s.reg.Local().Identity
	peers := s.reg.peerSnapshot()
	for id, entry := range peers {
		if id == localID {
			continue
		}
		n := entry.node
		if !n.Registered {
			continue
		}
		currentOnline := n.Online
		prev, exists := s.prevPeerOnline[id]
		if !exists {
			s.prevPeerOnline[id] = currentOnline
			continue
		}
		if prev != currentOnline {
			kind := "node"
			from := strconv.FormatBool(prev)
			to := strconv.FormatBool(currentOnline)
			baseMsg := "node "
			if currentOnline {
				baseMsg += "online"
			} else {
				baseMsg += "offline"
			}
			s.reg.history.AddEvent(buildEvent(n, kind, "", from, to, baseMsg))
			s.prevPeerOnline[id] = currentOnline
		}
	}
}

// Run samples immediately, then on every tick until ctx is cancelled.
func (s *TelemetrySampler) Run(ctx context.Context) {
	s.Sample()
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.Sample()
		}
	}
}
