// History returns the local node's bounded telemetry history. Peer
// histories are never fabricated: only the local node is sampled, so
// this is always the local history. Discovered peers have no history
// at all; registered remote peers will gain theirs when multi-node
// collection exists (their own node serves it via /api/history).
package node

func (r *Registry) History() *History {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.history
}

// LocalHistory returns up to the last n metric samples plus recent
// events for the dashboard and the history endpoint. It reads only —
// it never samples, never touches /proc, never probes.
func (r *Registry) LocalHistory(n int) (samples []MetricSample, events []HistoryEvent) {
	h := r.History()
	samples = h.Samples()
	if n > 0 && len(samples) > n {
		samples = samples[len(samples)-n:]
	}
	events = h.Events()
	return samples, events
}
