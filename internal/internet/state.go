package internet

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
)

// sourcesFileName is the single file the module persists. It holds both
// the source declarations and the last observation of each source, so
// one atomic write keeps configuration and observed state consistent
// with each other.
const sourcesFileName = "sources.json"

// stateVersion tags the on-disk format, so a future incompatible change
// can be detected instead of misread.
const stateVersion = 1

// sourcesFile is the on-disk shape of sources.json.
type sourcesFile struct {
	Version int      `json:"version"`
	Sources []Record `json:"sources"`
}

// State is the module's persistent bookkeeping: the configured sources
// and what the microscope last observed about each. It is safe for
// concurrent use; every mutation is written atomically, so a crash
// never leaves a torn file and readers never observe a half-written
// state.
type State struct {
	mu      sync.Mutex
	dir     string
	records map[string]*Record
}

// NewState loads the module state from dir, creating the directory if
// needed. A missing sources.json is an empty state, not an error; a
// malformed one is an error — silently discarding a corrupt state would
// turn every known source into a fresh NEW event.
func NewState(dir string) (*State, error) {
	if dir == "" {
		dir = DefaultStateDir()
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("internet: state dir %s: %w", dir, err)
	}
	s := &State{dir: dir, records: map[string]*Record{}}
	path := filepath.Join(dir, sourcesFileName)
	var file sourcesFile
	found, err := readJSON(path, &file)
	if err != nil {
		return nil, err
	}
	if !found {
		return s, nil
	}
	for _, rec := range file.Sources {
		if rec.ID == "" {
			return nil, fmt.Errorf("internet: state %s: source without id", path)
		}
		if _, dup := s.records[rec.ID]; dup {
			return nil, fmt.Errorf("internet: state %s: duplicate source id %q", path, rec.ID)
		}
		copied := rec
		if copied.Observation.Status == "" {
			copied.Observation.Status = StatusPending
		}
		s.records[rec.ID] = &copied
	}
	return s, nil
}

// Dir returns the state directory.
func (s *State) Dir() string { return s.dir }

// recordsLocked returns every record sorted by id. Caller holds s.mu.
func (s *State) recordsLocked() []Record {
	out := make([]Record, 0, len(s.records))
	for _, rec := range s.records {
		out = append(out, *rec)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

// Records returns every source with its observation, sorted by id.
func (s *State) Records() []Record {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.recordsLocked()
}

// Sources returns only the source declarations, sorted by id.
func (s *State) Sources() []Source {
	records := s.Records()
	out := make([]Source, 0, len(records))
	for _, rec := range records {
		out = append(out, rec.Source)
	}
	return out
}

// Get returns the record for id.
func (s *State) Get(id string) (Record, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec, ok := s.records[id]
	if !ok {
		return Record{}, false
	}
	return *rec, true
}

// Add stores a new source. The source is normalized first, so an
// invalid definition can never be persisted. Adding an existing id is
// an error: sources are never replaced implicitly.
func (s *State) Add(src Source) (Source, error) {
	normalized, err := src.Normalize()
	if err != nil {
		return Source{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.records[normalized.ID]; exists {
		return Source{}, fmt.Errorf("internet: source %q already exists", normalized.ID)
	}
	s.records[normalized.ID] = &Record{Source: normalized, Observation: Observation{Status: StatusPending}}
	if err := s.saveLocked(); err != nil {
		delete(s.records, normalized.ID)
		return Source{}, err
	}
	return normalized, nil
}

// Remove deletes a source and its observation.
func (s *State) Remove(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec, ok := s.records[id]
	if !ok {
		return fmt.Errorf("internet: unknown source %q", id)
	}
	delete(s.records, id)
	if err := s.saveLocked(); err != nil {
		s.records[id] = rec
		return err
	}
	return nil
}

// SetEnabled enables or disables a source without touching its history.
func (s *State) SetEnabled(id string, enabled bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec, ok := s.records[id]
	if !ok {
		return fmt.Errorf("internet: unknown source %q", id)
	}
	rec.Source.Enabled = enabled
	if err := s.saveLocked(); err != nil {
		rec.Source.Enabled = !enabled
		return err
	}
	return nil
}

// Put persists an updated observation for an existing source. The
// source declaration itself is not modified, so an observation can
// never rewrite what the operator configured.
func (s *State) Put(rec Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	current, ok := s.records[rec.ID]
	if !ok {
		return fmt.Errorf("internet: unknown source %q", rec.ID)
	}
	previous := current.Observation
	current.Observation = rec.Observation
	if err := s.saveLocked(); err != nil {
		current.Observation = previous
		return err
	}
	return nil
}

// saveLocked writes the whole state atomically. Caller holds s.mu.
func (s *State) saveLocked() error {
	file := sourcesFile{Version: stateVersion, Sources: s.recordsLocked()}
	return writeJSONAtomic(filepath.Join(s.dir, sourcesFileName), file)
}

// readJSON decodes a JSON file into v. A missing file is reported as
// (false, nil): callers treat "absent" and "empty" differently from
// "unreadable".
func readJSON(path string, v any) (bool, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return false, nil
		}
		return false, fmt.Errorf("internet: read state %s: %w", path, err)
	}
	if err := json.Unmarshal(data, v); err != nil {
		return false, fmt.Errorf("internet: invalid JSON %s: %w", path, err)
	}
	return true, nil
}

// writeJSONAtomic writes v to path via a temp file, fsync and rename, so
// readers never observe a torn write. The file is created 0600: module
// state is private to the operator.
//
// The wallpaper module keeps its own identical helper. These modules
// stay dependency-free instead of sharing a package with one consumer
// each; extracting a shared internal/atomicfile is a possible later
// refactor, not a v0.1 requirement.
func writeJSONAtomic(path string, v any) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("internet: mkdir %s: %w", dir, err)
	}
	f, err := os.CreateTemp(dir, filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("internet: %w", err)
	}
	tmp := f.Name()
	cleanup := func() {
		f.Close()
		os.Remove(tmp)
	}

	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		cleanup()
		return fmt.Errorf("internet: encode %s: %w", path, err)
	}
	_ = f.Chmod(0o600)
	if err := f.Sync(); err != nil {
		cleanup()
		return fmt.Errorf("internet: %w", err)
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("internet: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("internet: %w", err)
	}
	return nil
}
