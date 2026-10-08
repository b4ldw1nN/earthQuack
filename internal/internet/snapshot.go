package internet

import "time"

// Summary is the dashboard/API headline for the module: how many
// sources exist, how many are active, and how many are in each state.
type Summary struct {
	// Sources is the total number of configured sources.
	Sources int `json:"sources"`
	// Enabled is the number of sources that are active (polled).
	Enabled int `json:"enabled"`
	// Errors is the number of sources currently in the error state.
	Errors int `json:"errors"`
	// Changed is the number of sources whose last observation differed
	// from the one before it.
	Changed int `json:"changed"`
	// New is the number of sources observed successfully exactly once.
	New int `json:"new"`
	// Pending is the number of sources never observed yet.
	Pending int `json:"pending"`
}

// Summarize computes the headline counts from the module's records.
func Summarize(records []Record) Summary {
	var s Summary
	for _, rec := range records {
		s.Sources++
		if rec.Source.Enabled {
			s.Enabled++
		}
		switch rec.Observation.Status {
		case StatusError:
			s.Errors++
		case StatusChanged:
			s.Changed++
		case StatusNew:
			s.New++
		case StatusPending:
			s.Pending++
		}
	}
	return s
}

// Snapshot is the module's read-only state: everything the dashboard,
// the API and the CLI need, with no way to mutate anything. Running and
// LastPoll are filled in by the poller; a module with no poller in this
// process (the CLI) reports running=false.
type Snapshot struct {
	// Enabled reports whether the module is part of this node. The node
	// serves an enabled=false snapshot when it has no module at all, so
	// every consumer can render the same shape.
	Enabled bool `json:"enabled"`
	// Running reports whether a poller is currently polling.
	Running bool `json:"running"`
	// StateDir is where sources.json lives (local path, operator-facing).
	StateDir string `json:"state_dir,omitempty"`
	// DefaultInterval is the poll interval used by sources that do not
	// declare their own.
	DefaultInterval Duration `json:"default_interval"`
	// LastPoll is when the last poll pass started, if any.
	LastPoll time.Time `json:"last_poll,omitempty"`
	// Summary is the headline count block.
	Summary Summary `json:"summary"`
	// Sources is one row per configured source.
	Sources []SourceStatus `json:"sources"`
}

// Snapshot returns the current read-only view of the module. It reads
// persisted state only: it never fetches, never probes, and never
// mutates.
func (m *Module) Snapshot() Snapshot {
	return m.snapshotFrom(m.st().Records())
}

func (m *Module) snapshotFrom(records []Record) Snapshot {
	snap := Snapshot{
		Enabled:         true,
		StateDir:        m.st().Dir(),
		DefaultInterval: m.cfg.Interval,
		Summary:         Summarize(records),
		Sources:         make([]SourceStatus, 0, len(records)),
	}
	for _, rec := range records {
		snap.Sources = append(snap.Sources, SourceStatus{Source: rec.Source, Observation: rec.Observation})
	}
	return snap
}

// DisabledSnapshot is the snapshot served for a node where the module
// is not enabled. It is a valid empty view rather than a 404, so the
// dashboard and API render consistently everywhere.
func DisabledSnapshot() Snapshot {
	return Snapshot{
		DefaultInterval: Duration(DefaultInterval),
		Sources:         []SourceStatus{},
	}
}
