package internet

import (
	"context"
	"time"
)

// check performs one fetch → normalize → fingerprint → compare →
// persist → emit pass for a single source. It never returns an error:
// failure is a recorded state (StatusError), which is exactly what the
// change detector has to distinguish from "unchanged".
func (m *Module) check(ctx context.Context, rec Record) Result {
	src := rec.Source
	prev := rec.Observation
	now := m.clock()
	st := m.st()

	result := Result{
		SourceID:            src.ID,
		Name:                src.DisplayName(),
		Status:              prev.Status,
		PreviousFingerprint: prev.Fingerprint,
		CurrentFingerprint:  prev.Fingerprint,
		ObservedAt:          now,
	}

	resp, err := m.fetcher.Get(ctx, src.URL, Conditional{ETag: prev.ETag, LastModified: prev.LastModified})
	if err != nil {
		if interrupted(ctx, err) {
			result.Error = err
			return result
		}
		return m.recordError(st, src, prev, err, now, result)
	}

	obs := prev
	obs.Checks++
	obs.ObservedAt = now
	obs.LastSuccessAt = now
	obs.LastError = ""
	obs.ETag = resp.ETag
	obs.LastModified = resp.LastModified

	if resp.NotModified {
		// The origin vouches for the last observation. This is the
		// strongest possible "unchanged": no body was transferred.
		obs.Status = StatusUnchanged
		if err := st.Put(Record{Source: src, Observation: obs}); err != nil {
			result.Error = err
			return result
		}
		if prev.Status == StatusError {
			m.emitChange(src, EventSourceRecovered, StatusUnchanged, prev.Fingerprint, prev.Fingerprint, now, "")
		}
		result.Status = StatusUnchanged
		return result
	}

	fingerprint, err := Fingerprint(src.Type, resp.Body)
	if err != nil {
		return m.recordError(st, src, prev, err, now, result)
	}
	obs.Fingerprint = fingerprint

	switch {
	case prev.Fingerprint == "":
		obs.Status = StatusNew
		obs.PreviousFingerprint = ""
		obs.LastChangedAt = now
		obs.Changes++
	case fingerprint != prev.Fingerprint:
		obs.Status = StatusChanged
		obs.PreviousFingerprint = prev.Fingerprint
		obs.LastChangedAt = now
		obs.Changes++
	default:
		obs.Status = StatusUnchanged
	}

	if err := st.Put(Record{Source: src, Observation: obs}); err != nil {
		result.Error = err
		return result
	}

	result.Status = obs.Status
	result.CurrentFingerprint = fingerprint

	// Recovery is reported before the content transition, so a consumer
	// sees "the source is reachable again" separately from "and its
	// content is different".
	if prev.Status == StatusError {
		m.emitChange(src, EventSourceRecovered, obs.Status, prev.Fingerprint, fingerprint, now, "")
	}
	switch obs.Status {
	case StatusNew:
		m.emitChange(src, EventSourceNew, StatusNew, "", fingerprint, now, "")
	case StatusChanged:
		m.emitChange(src, EventSourceChanged, StatusChanged, prev.Fingerprint, fingerprint, now, "")
	}
	return result
}

// recordError persists a failure, keeping the last known fingerprint so
// recovery can still be detected. The event is emitted only on the
// transition into the error state, so a permanently broken source does
// not emit an event every interval.
func (m *Module) recordError(st *State, src Source, prev Observation, err error, now time.Time, result Result) Result {
	obs := prev
	obs.Status = StatusError
	obs.ObservedAt = now
	obs.Checks++
	obs.Errors++
	obs.LastError = err.Error()
	if putErr := st.Put(Record{Source: src, Observation: obs}); putErr != nil {
		result.Error = putErr
		return result
	}
	if prev.Status != StatusError {
		m.emitChange(src, EventSourceError, StatusError, prev.Fingerprint, prev.Fingerprint, now, err.Error())
	}
	result.Status = StatusError
	result.Error = err
	return result
}

// emitChange builds and emits one transition event.
func (m *Module) emitChange(src Source, typ EventType, status Status, previous, current string, at time.Time, errMsg string) {
	m.emit(Event{
		Type:                typ,
		SourceID:            src.ID,
		SourceName:          src.DisplayName(),
		SourceType:          src.Type,
		URL:                 src.URL,
		Status:              status,
		PreviousFingerprint: previous,
		CurrentFingerprint:  current,
		ObservedAt:          at,
		Error:               errMsg,
	})
}
