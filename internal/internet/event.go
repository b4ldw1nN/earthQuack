package internet

import "time"

// EventType is the machine-readable kind of a microscope event. Only
// transitions are emitted: the steady state (UNCHANGED) is not an event
// — current state is available through the snapshot instead. This is
// the same convention the node's own event model uses.
type EventType string

const (
	// EventSourceNew means a source was observed successfully for the
	// first time (earthQuack had no previous fingerprint).
	EventSourceNew EventType = "internet.source.new"
	// EventSourceChanged means the normalized content differs from the
	// last observation. This is the primary event of the module and
	// carries enough information for consumers to react without
	// knowing how the source was fetched.
	EventSourceChanged EventType = "internet.source.changed"
	// EventSourceError means a source entered the error state. It is
	// emitted once per outage, not once per failed poll.
	EventSourceError EventType = "internet.source.error"
	// EventSourceRecovered means a source that was in the error state
	// was observed successfully again.
	EventSourceRecovered EventType = "internet.source.recovered"
)

// Event is a structured observation transition. It is intentionally
// data, not action: consumers (dashboard, notifications, timeline,
// research, AI) decide what a change means. Nothing here triggers work.
type Event struct {
	// Type is the transition kind.
	Type EventType `json:"type"`
	// SourceID is the stable source identifier.
	SourceID string `json:"source_id"`
	// SourceName is the display name of the source.
	SourceName string `json:"source_name,omitempty"`
	// SourceType is the normalized source type (http, rss).
	SourceType SourceType `json:"source_type"`
	// URL is the observed endpoint.
	URL string `json:"url"`
	// Status is the resulting state.
	Status Status `json:"status"`
	// PreviousFingerprint is the normalized content hash before this
	// observation (empty for a new source).
	PreviousFingerprint string `json:"previous_fingerprint,omitempty"`
	// CurrentFingerprint is the normalized content hash after this
	// observation.
	CurrentFingerprint string `json:"current_fingerprint,omitempty"`
	// ObservedAt is when the observation happened.
	ObservedAt time.Time `json:"observed_at"`
	// Error is the failure message for error events. It never contains
	// credentials: these are fetch/normalize messages only.
	Error string `json:"error,omitempty"`
}

// eventSink receives every emitted event. A nil sink means the module
// simply records state (useful for read-only CLI commands).
type eventSink func(Event)

// emit forwards one event to the sink, if configured.
func (m *Module) emit(ev Event) {
	if m.sink != nil {
		m.sink(ev)
	}
}
