package node

import (
	"sync"
	"time"
)

// Telemetry history: bounded in-memory past measurements.
//
// This file holds the storage model only. Collection lives in
// history_collect.go; the CPU sampler in history_cpu*.go.
//
// Design:
//
//	current telemetry (SystemInfo / NetworkStatsInfo snapshots)
//	    ↓
//	TelemetrySampler.Sample() → MetricSample (one point, or skipped)
//	    ↓
//	History ring (≤ MaxHistorySamples, oldest discarded)
//	    ↓
//	API (/api/history) + dashboard (server-rendered sparklines)
//
// A MetricSample carries only graphable numbers: memory bytes,
// aggregate node-level network counters, and CPU utilization when the
// platform can measure it. It deliberately omits identity, hostname,
// capabilities, services, storage snapshots, and per-interface
// metadata — none of that is needed to draw history graphs.
//
// Health/service transitions are discrete Events, kept in a second
// small ring — never duplicated into every metric sample.

const (
	DefaultHistoryInterval = 30 * time.Second

	MaxHistorySamples = 120

	MaxHistoryEvents = 30
)

// MetricSample is one telemetry point: aggregate node-level numbers
// plus rates derived from cumulative kernel counters. Pointer fields
// distinguish "unavailable, do not plot" (nil) from a genuine zero.
type MetricSample struct {
	Time time.Time `json:"timestamp"`

	MemUsed      uint64 `json:"memory_used"`
	MemTotal     uint64 `json:"memory_total"`
	MemAvailable uint64 `json:"memory_available"`

	NetRX uint64 `json:"network_rx"`
	NetTX uint64 `json:"network_tx"`

	CPUPercent *float64 `json:"cpu_percent,omitempty"`

	RXRate *float64 `json:"rx_rate,omitempty"`
	TXRate *float64 `json:"tx_rate,omitempty"`
}

// History is a concurrency-safe pair of bounded rings.
type History struct {
	mu      sync.RWMutex
	samples []MetricSample
	events  []HistoryEvent
	maxN    int
	maxE    int
	nextSeq uint64
}

// NewHistory returns a History ring with the given capacity limits.
func NewHistory(n, e int) *History {
	return &History{maxN: max(0, n), maxE: max(0, e)}
}

// AddSample appends one metric sample, discarding the oldest when full.
func (h *History) AddSample(s MetricSample) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.samples = append(h.samples, s)
	if len(h.samples) > h.maxN {
		copy(h.samples, h.samples[len(h.samples)-h.maxN:])
		h.samples = h.samples[:h.maxN]
	}
}

// AddEvent appends one transition event, discarding the oldest when full.
func (h *History) AddEvent(e HistoryEvent) {
	h.mu.Lock()
	defer h.mu.Unlock()
	e.Seq = h.nextSeq
	h.nextSeq++
	if e.Time.IsZero() {
		e.Time = time.Now()
	}
	h.events = append(h.events, e)
	if len(h.events) > h.maxE {
		copy(h.events, h.events[len(h.events)-h.maxE:])
		h.events = h.events[:h.maxE]
	}
}

// Samples returns a chronological copy of the retained metric samples.
func (h *History) Samples() []MetricSample {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]MetricSample, len(h.samples))
	copy(out, h.samples)
	return out
}

// Events returns a chronological copy of the retained events.
func (h *History) Events() []HistoryEvent {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]HistoryEvent, len(h.events))
	copy(out, h.events)
	return out
}

// RecentEvents returns up to the last limit events as the public typed
// Event model, newest first. Times are normalized to UTC.
//
// The returned slice is a copy; it does not point into the ring.
func (h *History) RecentEvents(limit int) []Event {
	h.mu.RLock()
	defer h.mu.RUnlock()

	events := h.events
	if limit > 0 && len(events) > limit {
		events = events[len(events)-limit:]
	}

	out := make([]Event, 0, len(events))
	for _, e := range events {
		msg := e.Message
		if msg == "" {
			switch e.Kind {
			case "health":
				if e.From != "" || e.To != "" {
					msg = e.From + " → " + e.To
				}
			case "service":
				if e.Name != "" && (e.From != "" || e.To != "") {
					msg = e.Name + " " + e.From + " → " + e.To
				} else if e.From != "" || e.To != "" {
					msg = e.From + " → " + e.To
				}
			case "node":
				if e.To == "true" {
					msg = "node online"
				} else if e.To == "false" {
					msg = "node offline"
				}
			default:
				if e.From != "" || e.To != "" {
					msg = e.From + " → " + e.To
				}
			}
		}
		out = append(out, Event{
			Seq:     e.Seq,
			Time:    e.Time.UTC(),
			Node:    e.Node,
			Type:    eventTypeName(e.Kind, e.From, e.To),
			Name:    e.Name,
			Message: msg,
			Data:    e.Data,
		})
	}

	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out
}

// Len reports the number of retained metric samples.
func (h *History) Len() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.samples)
}
