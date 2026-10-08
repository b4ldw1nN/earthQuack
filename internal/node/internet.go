package node

import (
	"fmt"

	"github.com/b4ldw1nN/earthquack/internal/internet"
)

// InternetConfig is this node's declaration that the Internet Microscope
// module is part of it. Like every other config field it is declarations
// only: the sources themselves are module state, managed by the CLI and
// persisted in the module state directory (see internal/internet). No
// observation, fingerprint or error is ever configurable.
type InternetConfig struct {
	Enabled bool `json:"enabled,omitempty"`
	// StateDir is where the module keeps sources.json. Empty means the
	// module default (~/.local/share/earthquack/internet).
	StateDir string `json:"state_dir,omitempty"`
	// Interval is the default poll interval for sources that do not
	// declare their own, as a duration string ("15m").
	Interval string `json:"interval,omitempty"`
}

// InternetProvider is the read-only view of the Internet Microscope.
// The node never fetches, never polls and never re-implements change
// detection: it renders and forwards exactly what the module reports.
// The module owns its implementation; consumers only see the snapshot
// and the events.
type InternetProvider interface {
	Snapshot() internet.Snapshot
}

// SetInternetProvider attaches the module's read-only view to the
// registry. It is called once by the composition root (main.go); tests
// inject a fake. The default (nil) is a node without the module.
func (r *Registry) SetInternetProvider(p InternetProvider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.internet = p
}

// InternetSnapshot returns the module's current snapshot. A node without
// the module returns an enabled=false snapshot rather than an error, so
// the API and the dashboard always render a complete shape.
func (r *Registry) InternetSnapshot() internet.Snapshot {
	r.mu.RLock()
	p := r.internet
	r.mu.RUnlock()
	if p == nil {
		return internet.DisabledSnapshot()
	}
	return p.Snapshot()
}

// InternetEvent converts one module event into the node's own history
// event shape, so Internet changes travel through the same event ring as
// service, health and node transitions — one event system, not two.
//
// The transition is expressed in From/To as the previous and current
// fingerprints, and the structured details the module reports (source
// id/name/type, url, both fingerprints, observed time, error) are
// preserved in Data for consumers such as notifications, a timeline,
// research tooling or AI agents.
func InternetEvent(ev internet.Event) HistoryEvent {
	h := HistoryEvent{
		Time:    ev.ObservedAt,
		Kind:    string(ev.Type),
		Name:    ev.SourceName,
		From:    ev.PreviousFingerprint,
		To:      ev.CurrentFingerprint,
		Message: InternetEventMessage(ev),
		Data: map[string]string{
			"source_id":   ev.SourceID,
			"source_type": string(ev.SourceType),
			"url":         ev.URL,
			"status":      string(ev.Status),
			"observed_at": ev.ObservedAt.UTC().Format("2006-01-02T15:04:05Z"),
		},
	}
	if ev.SourceName != "" {
		h.Data["source_name"] = ev.SourceName
	}
	if ev.PreviousFingerprint != "" {
		h.Data["previous_fingerprint"] = ev.PreviousFingerprint
	}
	if ev.CurrentFingerprint != "" {
		h.Data["current_fingerprint"] = ev.CurrentFingerprint
	}
	if ev.Error != "" {
		h.Data["error"] = ev.Error
	}
	return h
}

// InternetEventMessage renders the one-line human message shown in the
// dashboard event stream.
func InternetEventMessage(ev internet.Event) string {
	name := ev.SourceName
	if name == "" {
		name = ev.SourceID
	}
	switch ev.Type {
	case internet.EventSourceNew:
		return fmt.Sprintf("internet source %q observed for the first time (%s)", name, shortFingerprint(ev.CurrentFingerprint))
	case internet.EventSourceChanged:
		return fmt.Sprintf("internet source %q changed (%s → %s)",
			name, shortFingerprint(ev.PreviousFingerprint), shortFingerprint(ev.CurrentFingerprint))
	case internet.EventSourceError:
		return fmt.Sprintf("internet source %q failed: %s", name, ev.Error)
	case internet.EventSourceRecovered:
		return fmt.Sprintf("internet source %q recovered", name)
	default:
		return fmt.Sprintf("internet source %q %s", name, ev.Status)
	}
}

// shortFingerprint abbreviates a content hash for display; events keep
// the full hashes in Data.
func shortFingerprint(fp string) string {
	if len(fp) <= 12 {
		return fp
	}
	return fp[:12]
}
