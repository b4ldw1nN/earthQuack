package node

import (
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeCPU is a deterministic cpuReader for tests.
type fakeCPU struct {
	mu   sync.Mutex
	vals []float64
	ok   []bool
	i    int
}

func (f *fakeCPU) Utilization() (float64, bool, time.Time) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.i >= len(f.vals) {
		return 0, false, time.Time{}
	}
	v := f.vals[f.i]
	ok := f.ok[f.i]
	f.i++
	return v, ok, time.Time{}
}

func makeCPU(vals []float64) *fakeCPU {
	f := &fakeCPU{vals: vals, ok: make([]bool, len(vals))}
	for i := range f.ok {
		f.ok[i] = true
	}
	return f
}

// newHistoryReg returns a registry wired with deterministic fakes.
func newHistoryReg(t *testing.T) (*Registry, *fakeSysProvider, *fakeNetworkStatsProvider) {
	t.Helper()
	reg, err := NewRegistry("machine:h", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	sys := &fakeSysProvider{info: testSystem()}
	net := &fakeNetworkStatsProvider{info: testNetworkStats()}
	reg.SetSysInfoProvider(sys)
	reg.SetNetworkStatsProvider(net)
	return reg, sys, net
}

func TestHistoryEmpty(t *testing.T) {
	h := NewHistory(0, 0)
	if got := h.Len(); got != 0 {
		t.Fatalf("len = %d, want 0", got)
	}
	if s := h.Samples(); len(s) != 0 {
		t.Fatalf("samples = %d, want 0", len(s))
	}
	if e := h.Events(); len(e) != 0 {
		t.Fatalf("events = %d, want 0", len(e))
	}
}

func TestHistoryChronologicalAndCapacity(t *testing.T) {
	h := NewHistory(3, 3)
	base := time.Unix(0, 0)
	for i := 0; i < 5; i++ {
		h.AddSample(MetricSample{Time: base.Add(time.Duration(i) * time.Minute), MemUsed: uint64(i)})
	}
	s := h.Samples()
	if len(s) != 3 {
		t.Fatalf("capacity: want 3, got %d", len(s))
	}
	if s[0].MemUsed != 2 || s[1].MemUsed != 3 || s[2].MemUsed != 4 {
		t.Fatalf("eviction wrong: %+v", s)
	}
	if !s[0].Time.Before(s[1].Time) || !s[1].Time.Before(s[2].Time) {
		t.Fatalf("not chronological")
	}
}

func TestHistoryEventsCapacity(t *testing.T) {
	h := NewHistory(2, 2)
	for i := 0; i < 4; i++ {
		h.AddEvent(HistoryEvent{Kind: "x", To: fmt.Sprint(i)})
	}
	e := h.Events()
	if len(e) != 2 || e[0].To != "2" || e[1].To != "3" {
		t.Fatalf("event eviction wrong: %+v", e)
	}
}

func TestHistoryConcurrentReadWrite(t *testing.T) {
	h := NewHistory(0, 0)
	var wg sync.WaitGroup
	for w := 0; w < 8; w++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < 500; i++ {
				h.AddSample(MetricSample{MemUsed: uint64(id)})
				_ = h.Samples()
				_ = h.Events()
				_ = h.Len()
			}
		}(w)
	}
	wg.Wait()
	if h.Len() > MaxHistorySamples {
		t.Fatalf("over capacity: %d", h.Len())
	}
}

func TestParseProcStat(t *testing.T) {
	line := "cpu  476849 18 151200 11988525 12842 42332 20361 0 0 0\n"
	ct, ok := parseProcStat(line)
	if !ok {
		t.Fatal("failed parse")
	}
	if ct.idle != 11988525+12842 {
		t.Errorf("idle = %d", ct.idle)
	}
	if ct.total != 476849+18+151200+11988525+12842+42332+20361 {
		t.Errorf("total wrong: %d", ct.total)
	}
}

func TestParseProcStatInvalid(t *testing.T) {
	for _, body := range []string{"", "cpu", "cpu 1 2 3\n", "cpu a b c d e\n", "mem foo\n"} {
		if _, ok := parseProcStat(body); ok {
			t.Errorf("invalid input accepted: %q", body)
		}
	}
	if _, ok := parseProcStat("cpu0 1 2 3 4 5\ncpu 1 x 3 4 5\n"); ok {
		t.Error("malformed aggregate accepted")
	}
}

func TestCPUUtilizationSecondSample(t *testing.T) {
	prev := cpuTimes{idle: 100, total: 1000}
	cur := cpuTimes{idle: 400, total: 2000}
	pct, ok := cpuUtilization(prev, cur)
	if !ok || pct < 69.9 || pct > 70.1 {
		t.Fatalf("utilization = %v %v, want ~70", pct, ok)
	}
}

func TestCPUUtilizationFirstSample(t *testing.T) {
	c := newCPUSampler()
	if _, ok, _ := c.Utilization(); ok {
		t.Fatal("first sample must yield no utilization")
	}
}

func TestCPUUtilizationReset(t *testing.T) {
	if _, ok := cpuUtilization(cpuTimes{idle: 0, total: 5000}, cpuTimes{idle: 0, total: 100}); ok {
		t.Fatal("reset must not yield utilization")
	}
	if _, ok := cpuUtilization(cpuTimes{idle: 900, total: 1000}, cpuTimes{idle: 100, total: 1500}); ok {
		t.Fatal("idle shrink must not yield utilization")
	}
	if _, ok := cpuUtilization(cpuTimes{idle: 5, total: 100}, cpuTimes{idle: 5, total: 100}); ok {
		t.Fatal("zero delta must not yield utilization")
	}
	p, ok := cpuUtilization(cpuTimes{idle: 0, total: 100}, cpuTimes{idle: 100, total: 200})
	if !ok || p != 0 {
		t.Fatalf("all-idle = %v %v", p, ok)
	}
}

func TestSparkline(t *testing.T) {
	if got := sparkline(0, func(int) (float64, bool) { return 0, true }); got != "—" {
		t.Fatalf("empty sparkline = %q", got)
	}
	pick := func(i int) (float64, bool) { return float64(i + 1), true }
	out := sparkline(4, pick)
	if len([]rune(out)) != 4 {
		t.Fatalf("sparkline length = %d", len([]rune(out)))
	}
	if strings.ContainsRune(out, '—') {
		t.Fatal("sparkline contains placeholder while data present")
	}
}

func TestEventSequencesAndMonotonicity(t *testing.T) {
	h := NewHistory(10, 10)
	for i := 0; i < 5; i++ {
		h.AddEvent(HistoryEvent{Kind: "service", Name: "svc", From: "stopped", To: "running"})
	}
	events := h.Events()
	if len(events) != 5 {
		t.Fatalf("want 5 events, got %d", len(events))
	}
	for i := 0; i < 5; i++ {
		if events[i].Seq != uint64(i) {
			t.Errorf("event %d: want Seq %d, got %d", i, i, events[i].Seq)
		}
	}
}

func TestEventConversionAndTypes(t *testing.T) {
	h := NewHistory(10, 10)
	h.AddEvent(HistoryEvent{Kind: "service", Name: "clipboard", From: "running", To: "stopped"})
	h.AddEvent(HistoryEvent{Kind: "service", Name: "clipboard", From: "stopped", To: "running"})
	h.AddEvent(HistoryEvent{Kind: "health", From: "healthy", To: "degraded"})
	h.AddEvent(HistoryEvent{Kind: "health", From: "degraded", To: "healthy"})
	h.AddEvent(HistoryEvent{Kind: "node", Node: "machine:peer", From: "true", To: "false"})
	h.AddEvent(HistoryEvent{Kind: "node", Node: "machine:peer", From: "false", To: "true"})

	rec := h.RecentEvents(10)
	if len(rec) != 6 {
		t.Fatalf("want 6 events, got %d", len(rec))
	}
	// RecentEvents is newest first
	expected := []struct {
		typ  EventType
		node Identity
	}{
		{EvNodeOnline, "machine:peer"},
		{EvNodeOffline, "machine:peer"},
		{EvHealthRecovered, ""},
		{EvHealthDegraded, ""},
		{EvServiceRecovered, ""},
		{EvServiceStopped, ""},
	}
	for i, want := range expected {
		if rec[i].Type != want.typ {
			t.Errorf("[%d] want type %s, got %s", i, want.typ, rec[i].Type)
		}
		if want.node != "" && rec[i].Node != want.node {
			t.Errorf("[%d] want node %s, got %s", i, want.node, rec[i].Node)
		}
	}
}

func TestRecentEventsCopyAndLimit(t *testing.T) {
	h := NewHistory(10, 10)
	for i := 0; i < 10; i++ {
		h.AddEvent(HistoryEvent{Kind: "health", From: "healthy", To: "degraded", Message: fmt.Sprintf("msg-%d", i)})
	}
	lim3 := h.RecentEvents(3)
	if len(lim3) != 3 {
		t.Fatalf("want 3 events, got %d", len(lim3))
	}
	// Newest first: msg-9, msg-8, msg-7
	if lim3[0].Message != "msg-9" || lim3[1].Message != "msg-8" || lim3[2].Message != "msg-7" {
		t.Errorf("ordering wrong: %+v", lim3)
	}

	// Mutating returned slice doesn't mutate history
	lim3[0].Message = "mutated"
	fresh := h.RecentEvents(1)
	if fresh[0].Message != "msg-9" {
		t.Errorf("underlying storage was mutated: %q", fresh[0].Message)
	}
}
