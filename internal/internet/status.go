package internet

import "time"

// Status is the outcome of the most recent observation of a source. The
// four states the microscope must distinguish, plus pending for a
// source that has not been observed yet.
type Status string

const (
	// StatusPending means the source has never been observed.
	StatusPending Status = "pending"
	// StatusNew means the first successful observation: earthQuack had
	// no previous fingerprint for this source.
	StatusNew Status = "new"
	// StatusChanged means the normalized content differs from the last
	// observation.
	StatusChanged Status = "changed"
	// StatusUnchanged means the normalized content is identical to the
	// last observation. This is the healthy steady state.
	StatusUnchanged Status = "unchanged"
	// StatusError means the source could not be fetched or normalized.
	// The last known fingerprint is kept, so recovery can still be
	// detected.
	StatusError Status = "error"
)

// Observation is everything the microscope learned about one source.
// It is measured state — never configured — and it is persisted so it
// survives a restart.
type Observation struct {
	// Status is the outcome of the last check.
	Status Status `json:"status"`
	// Fingerprint is the current normalized content hash (empty until
	// the first successful observation).
	Fingerprint string `json:"fingerprint,omitempty"`
	// PreviousFingerprint is the fingerprint the content had before the
	// most recent change. It is what change events report as "from".
	PreviousFingerprint string `json:"previous_fingerprint,omitempty"`
	// ObservedAt is when the last check ran, successful or not.
	ObservedAt time.Time `json:"observed_at,omitempty"`
	// LastSuccessAt is when the source was last fetched and normalized
	// successfully.
	LastSuccessAt time.Time `json:"last_success_at,omitempty"`
	// LastChangedAt is when the content last differed from the previous
	// observation.
	LastChangedAt time.Time `json:"last_changed_at,omitempty"`
	// LastError is the most recent failure, cleared on success. It is
	// an operator-facing message: no credentials are ever placed here.
	LastError string `json:"last_error,omitempty"`
	// Checks, Changes and Errors are lifetime counters for this source.
	Checks  int `json:"checks,omitempty"`
	Changes int `json:"changes,omitempty"`
	Errors  int `json:"errors,omitempty"`
	// ETag and LastModified are the validators returned by the origin,
	// replayed on the next request so an unchanged source can answer
	// 304 Not Modified without transferring a body.
	ETag         string `json:"etag,omitempty"`
	LastModified string `json:"last_modified,omitempty"`
}

// Due reports whether the source should be polled at now, given the
// module default interval. A never-observed source is always due.
func (o Observation) Due(interval Duration, now time.Time) bool {
	if o.ObservedAt.IsZero() {
		return true
	}
	return !now.Before(o.ObservedAt.Add(time.Duration(interval)))
}

// Record pairs a source declaration with everything observed about it.
// It is the unit of persistence (sources.json) and of the read-only
// snapshot the API and dashboard serve.
type Record struct {
	Source
	Observation `json:"observation"`
}

// Record returns the source as a persisted record with no history.
func (s Source) Record() Record {
	return Record{Source: s, Observation: Observation{Status: StatusPending}}
}

// SourceStatus is one row of the read-only snapshot served to the API
// and rendered by the dashboard: the source, plus what was observed.
// The embedded structs flatten into a single JSON object.
type SourceStatus struct {
	Source
	Observation
}

// IntervalOr returns the effective interval for display, given the
// module default.
func (s SourceStatus) IntervalOr(def Duration) Duration {
	return s.Source.EffectiveInterval(def)
}
