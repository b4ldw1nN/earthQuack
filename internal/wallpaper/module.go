package wallpaper

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// Config is the module's provider-agnostic configuration.
type Config struct {
	// Source is the absolute wallpaper directory to scan.
	Source string
	// StateDir is where uploads.json / failed.json / sync.json live.
	StateDir string
}

// SyncEvent is a coarse lifecycle event emitted by the module. These map
// onto the earthQuack node event model (the node History ring) at the
// integration layer; the module itself only reports them through a sink
// and never persists events.
type SyncEvent string

const (
	SyncStarted   SyncEvent = "wallpaper.sync.started"
	SyncCompleted SyncEvent = "wallpaper.sync.completed"
	SyncFailed    SyncEvent = "wallpaper.sync.failed"
)

// Module orchestrates a wallpaper archive. It is provider-agnostic: it
// depends only on the ArchiveProvider interface.
type Module struct {
	cfg      Config
	provider ArchiveProvider
	state    *State
	index    *fileIndex
	clock    func() time.Time
	out      io.Writer
	tty      bool
	events   func(SyncEvent, string)
}

// Option configures a Module.
type Option func(*Module)

// WithClock overrides the clock (for tests).
func WithClock(c func() time.Time) Option { return func(m *Module) { m.clock = c } }

// WithOutput routes human-readable output to w with tty-aware progress.
func WithOutput(w io.Writer, tty bool) Option {
	return func(m *Module) { m.out = w; m.tty = tty }
}

// WithEventSink reports lifecycle events through fn. The sink is
// optional; it is how an integration reuses the node history ring.
func WithEventSink(fn func(SyncEvent, string)) Option {
	return func(m *Module) { m.events = fn }
}

// NewModule builds a wallpaper module over the given provider.
func NewModule(cfg Config, provider ArchiveProvider, opts ...Option) (*Module, error) {
	if provider == nil {
		return nil, fmt.Errorf("wallpaper: provider is required")
	}
	state, err := NewState(cfg.StateDir)
	if err != nil {
		return nil, err
	}
	index, err := loadFileIndex(cfg.StateDir)
	if err != nil {
		return nil, err
	}
	m := &Module{
		cfg:      cfg,
		provider: provider,
		state:    state,
		index:    index,
		clock:    time.Now,
		out:      os.Stderr,
	}
	for _, o := range opts {
		o(m)
	}
	return m, nil
}

// State exposes the underlying archive state (tests + status).
func (m *Module) State() *State { return m.state }

// Provider exposes the provider name (status).
func (m *Module) Provider() string { return m.provider.Name() }

// Source returns the configured source directory.
func (m *Module) Source() string { return m.cfg.Source }

// ScannedFile is one discovered file plus its content digest.
type ScannedFile struct {
	Discovered
	Digest  string
	Size    int64
	MtimeNS int64
	Err     error // set when the file could not be stat'ed or hashed
}

// scanStats records how much work a scan had to do. It is purely
// reporting metadata; the digest decisions themselves live in State.
type scanStats struct {
	Hashed  int // files whose content had to be read to get a digest
	Cached  int // digests reused from the file index (unchanged files)
	Adopted int // digests adopted from an existing archive record
	Pruned  int // stale index entries dropped
}

// changed reports whether the scan produced index entries that differ
// from what is already on disk (i.e. the index is worth persisting).
func (s scanStats) changed() bool { return s.Hashed+s.Adopted+s.Pruned > 0 }

// scan computes the full discovered set with digests. It is shared by
// sync, status and dry-run so behaviour is identical.
//
// Digests are obtained in order of increasing cost, exactly like the
// updated wallpaper-backup implementation:
//
//  1. the local file index (path + size + mtime_ns match) — no read;
//  2. an existing archive record for the same path and size — no read
//     (this is what lets an adopted archive avoid re-hashing itself);
//  3. otherwise SHA-256 the file once, streaming.
func (m *Module) scan() ([]ScannedFile, scanStats, error) {
	return m.scanContext(context.Background())
}

func (m *Module) scanContext(ctx context.Context) ([]ScannedFile, scanStats, error) {
	if err := ctx.Err(); err != nil {
		return nil, scanStats{}, err
	}
	discovered, err := ScanSources(m.cfg.Source)
	if err != nil {
		return nil, scanStats{}, err
	}
	var stats scanStats
	present := make(map[string]struct{}, len(discovered))
	byPath := m.state.UploadsBySourcePath()
	out := make([]ScannedFile, 0, len(discovered))

	for _, d := range discovered {
		if err := ctx.Err(); err != nil {
			return nil, stats, err
		}
		present[d.Path] = struct{}{}
		info, serr := os.Stat(d.Path)
		if serr != nil {
			// An unreadable file is a reportable validation failure.
			out = append(out, ScannedFile{Discovered: d, Err: fmt.Errorf("wallpaper: stat %s: %w", d.Path, serr)})
			continue
		}
		size, mtime := info.Size(), mtimeNS(info.ModTime())
		sf := ScannedFile{Discovered: d, Size: size, MtimeNS: mtime}

		if digest, ok := m.index.lookup(d.Path, size, mtime); ok {
			stats.Cached++
			sf.Digest = digest
			out = append(out, sf)
			continue
		}
		if rec, ok := byPath[d.Path]; ok && rec.Size == size && rec.Digest != "" {
			stats.Adopted++
			sf.Digest = rec.Digest
			m.index.set(d.Path, size, mtime, rec.Digest)
			out = append(out, sf)
			continue
		}
		digest, herr := SHA256(d.Path)
		if herr != nil {
			out = append(out, ScannedFile{Discovered: d, Size: size, MtimeNS: mtime, Err: herr})
			continue
		}
		stats.Hashed++
		sf.Digest = digest
		m.index.set(d.Path, size, mtime, digest)
		out = append(out, sf)
	}

	for _, root := range filepath.SplitList(m.cfg.Source) {
		absolute, err := filepath.Abs(root)
		if err != nil {
			return nil, stats, err
		}
		stats.Pruned += m.index.prune(absolute, present)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Rel < out[j].Rel })
	return out, stats, nil
}

// saveIndex persists the local file index when the scan changed it.
// Failing to persist is never fatal (the index is only a cache), so it
// is reported as a warning.
func (m *Module) saveIndex(stats scanStats) {
	if !stats.changed() {
		return
	}
	if err := m.index.save(); err != nil && m.out != nil {
		fmt.Fprintf(m.out, "  warn: %v\n", err)
	}
}

// maxUploadSize returns the provider's size cap, or 0 when unknown.
func (m *Module) maxUploadSize() int64 {
	if s, ok := m.provider.(SizeLimitedProvider); ok {
		return s.MaxUploadSize()
	}
	return 0
}

// toFile maps a ScannedFile to a provider-agnostic ArchiveFile.
func (m *Module) toFile(s ScannedFile) ArchiveFile {
	return ArchiveFile{
		SourcePath: s.Path,
		Filename:   filepath.Base(s.Path),
		Category:   s.Category,
		Digest:     s.Digest,
		Size:       s.Size,
		MtimeNS:    s.MtimeNS,
	}
}

// validationError reports whether a file must fail before reaching the
// provider (too large for the provider, or unreadable).
func (m *Module) validationError(s ScannedFile) error {
	if s.Err != nil {
		return s.Err
	}
	if limit := m.maxUploadSize(); limit > 0 && s.Size > limit {
		return fmt.Errorf("%w (%d > %d bytes)", ErrTooLarge, s.Size, limit)
	}
	return nil
}

// Report describes the outcome of a sync (or dry-run).
type Report struct {
	DryRun bool

	Discovered int
	Archived   int // already in uploads (skipped)
	Pending    int // not yet archived
	Uploaded   int
	Failed     int
	Invalid    int // would/does fail validation (e.g. too large)
	Topics     int // distinct categories referenced by discovered files
	Hashed     int // files whose content had to be read to compute a digest
	Skipped    int // retry entries resolved without uploading (already archived)

	// ByCategory is a sorted per-category pending tally.
	ByCategory []CategoryCount
}

// CategoryCount is a sorted per-category pending tally for reporting.
type CategoryCount struct {
	Category string
	Pending  int
}

// partition classifies scanned files into already-archived, pending,
// and invalid sets, and tallies per-category pending counts. It is a
// read-only view of state, so it is safe to call for a dry-run. Topics
// is the count of distinct destination categories the discovered files
// map to, independent of how many of them still need archiving.
func (m *Module) partition(files []ScannedFile) Report {
	var r Report
	byCat := map[string]int{}
	seenCat := map[string]struct{}{}
	for _, f := range files {
		r.Discovered++
		seenCat[f.Category] = struct{}{}
		switch {
		case m.state.IsArchived(f.Digest):
			r.Archived++
		default:
			if v := m.validationError(f); v != nil {
				r.Invalid++
			} else {
				r.Pending++
				byCat[f.Category]++
			}
		}
	}
	r.Topics = len(seenCat)
	for cat := range byCat {
		r.ByCategory = append(r.ByCategory, CategoryCount{Category: cat, Pending: byCat[cat]})
	}
	sort.Slice(r.ByCategory, func(i, j int) bool { return r.ByCategory[i].Category < r.ByCategory[j].Category })
	return r
}

// Sync runs a full sync. When dryRun is true it performs no uploads,
// creates no provider destinations, writes no state (including the file
// index and the Telegram topics map), and mutates no archive state.
//
// A real sync refreshes the local file index (files.json) after
// scanning, so subsequent status/dry-run passes reuse cached digests
// instead of re-reading unchanged files.
func (m *Module) Sync(ctx context.Context, dryRun bool) (Report, error) {
	files, stats, err := m.scanContext(ctx)
	if err != nil {
		return Report{}, err
	}
	if err := ctx.Err(); err != nil {
		return Report{}, err
	}
	m.emit(SyncStarted, fmt.Sprintf("scanning %s", m.cfg.Source))

	if dryRun {
		// Dry-run is complete: nothing was uploaded, no state touched,
		// no provider destinations created.
		r := m.partition(files)
		r.DryRun = true
		r.Hashed = stats.Hashed
		m.emit(SyncCompleted, "dry-run complete: no uploads performed")
		return r, nil
	}

	m.saveIndex(stats)

	// Hand the provider only files that are new and pass validation.
	pending := make([]ScannedFile, 0)
	for _, f := range files {
		if m.state.IsArchived(f.Digest) {
			continue
		}
		if m.validationError(f) != nil {
			continue // counted as Invalid; not attempted
		}
		pending = append(pending, f)
	}

	report := m.partition(files)
	report.Hashed = stats.Hashed
	prog := newProgress(m.out, m.tty)
	done := 0
	total := len(pending)
	for _, f := range pending {
		if err := ctx.Err(); err != nil {
			return report, err
		}
		prog.update(done, total, f.Rel)
		af := m.toFile(f)
		if err := m.provider.Upload(ctx, af); err != nil {
			if ctx.Err() != nil {
				return report, ctx.Err()
			}
			report.Failed++
			_ = m.state.MarkFailed(f.Path, af, err, m.clock())
			if m.out != nil {
				fmt.Fprintf(m.out, "\r\x1b[K✗ FAILED %s: %v\n", f.Rel, err)
			}
		} else {
			report.Uploaded++
			if err := m.state.MarkUploaded(f.Path, af, m.clock()); err != nil {
				// State write failure is safe (upload already happened):
				// surface it but keep going.
				fmt.Fprintf(m.out, "\r\x1b[K  warn: %v\n", err)
			}
			fmt.Fprintf(m.out, "\r\x1b[K✓ %s\n", f.Rel)
		}
		done++
	}
	prog.finish()

	m.state.RecordLastSync(m.clock())

	if report.Failed > 0 {
		m.emit(SyncFailed, fmt.Sprintf("%d of %d uploads failed", report.Failed, report.Pending))
	} else {
		m.emit(SyncCompleted, fmt.Sprintf("%d uploaded, %d failed", report.Uploaded, report.Failed))
	}
	return report, nil
}

// Status is a point-in-time snapshot of the module.
type Status struct {
	Source      string
	StateDir    string
	Provider    string
	Discovered  int
	Archived    int
	Pending     int
	Failed      int
	Invalid     int
	Topics      int
	Cached      int // digests answered from files.json without reading content
	Hashed      int // files whose content had to be read during this scan
	LastSync    time.Time
	HasLastSync bool
}

// Status scans the source and reports current counts without uploading
// or mutating any state. It is read-only: the file index is consulted
// (which is what keeps status cheap on a large, unchanged archive) but
// never written, so Cached/Hashed describe the state of files.json as it
// exists on disk.
func (m *Module) Status() (Status, error) {
	files, stats, err := m.scan()
	if err != nil {
		return Status{}, err
	}
	r := m.partition(files)
	ls, ok := m.state.LastSync()
	return Status{
		Source:      m.cfg.Source,
		StateDir:    m.state.Dir(),
		Provider:    m.provider.Name(),
		Discovered:  r.Discovered,
		Archived:    r.Archived,
		Pending:     r.Pending,
		Failed:      len(m.state.Failed()),
		Invalid:     r.Invalid,
		Topics:      r.Topics,
		Cached:      stats.Cached,
		Hashed:      stats.Hashed,
		LastSync:    ls,
		HasLastSync: ok,
	}, nil
}

// RetryFailed re-attempts only the files recorded in failed.json, using
// the same upload path (and therefore the same retry/backoff) as a
// normal sync.
//
// Each entry is re-verified before it is retried, because the file may
// have changed (or been archived) since the failure was recorded:
//
//   - the file must still exist, be a regular file, have a supported
//     image extension, and be within the provider's size limit;
//   - its content is re-hashed, and an entry whose digest is already in
//     uploads.json is cleared as resolved (counted as Skipped);
//   - otherwise it is re-uploaded; success updates the success ledger
//     and clears the failed entry, while failure refreshes the entry.
//
// Entries that cannot be resolved are preserved (state is never
// silently dropped) and counted as Failed, so the caller can tell that
// work remains.
func (m *Module) RetryFailed(ctx context.Context) (Report, error) {
	failed := m.state.Failed()
	m.emit(SyncStarted, fmt.Sprintf("retrying %d failed file(s)", len(failed)))

	paths := make([]string, 0, len(failed))
	for p := range failed {
		paths = append(paths, p)
	}
	sort.Strings(paths)

	report := Report{Pending: len(paths)}
	prog := newProgress(m.out, m.tty)
	done := 0
	for _, src := range paths {
		rec := failed[src]
		prog.update(done, len(paths), src)
		done++

		info, statErr := os.Stat(src)
		if statErr != nil {
			report.Failed++
			fmt.Fprintf(m.out, "\r\x1b[KSTILL FAILED missing: %s\n", src)
			continue
		}
		if !info.Mode().IsRegular() {
			report.Failed++
			fmt.Fprintf(m.out, "\r\x1b[KSTILL FAILED not a file: %s\n", src)
			continue
		}
		if !IsSupportedImage(src) {
			report.Failed++
			fmt.Fprintf(m.out, "\r\x1b[KSTILL FAILED unsupported type: %s\n", src)
			continue
		}
		if limit := m.maxUploadSize(); limit > 0 && info.Size() > limit {
			report.Failed++
			fmt.Fprintf(m.out, "\r\x1b[KSTILL FAILED too large: %s\n", src)
			continue
		}

		// Re-verify the content: it may have changed, and it may in
		// fact have been archived since the failure was written.
		digest, herr := SHA256(src)
		report.Hashed++
		if herr != nil {
			report.Failed++
			fmt.Fprintf(m.out, "\r\x1b[KSTILL FAILED unreadable: %s: %v\n", src, herr)
			continue
		}
		if m.state.IsArchived(digest) {
			report.Skipped++
			if err := m.state.ClearFailed(src); err != nil {
				fmt.Fprintf(m.out, "\r\x1b[K  warn: %v\n", err)
			}
			m.refreshIndex(src, info, digest)
			fmt.Fprintf(m.out, "\r\x1b[K✓ already archived, cleared: %s\n", src)
			continue
		}

		af := ArchiveFile{
			SourcePath: src,
			Filename:   filepath.Base(src),
			Category:   rec.Category,
			Digest:     digest,
			Size:       info.Size(),
			MtimeNS:    mtimeNS(info.ModTime()),
		}
		if err := m.provider.Upload(ctx, af); err != nil {
			report.Failed++
			_ = m.state.MarkFailed(src, af, err, m.clock())
			fmt.Fprintf(m.out, "\r\x1b[K✗ FAILED %s: %v\n", src, err)
		} else {
			report.Uploaded++
			if err := m.state.MarkUploaded(src, af, m.clock()); err != nil {
				fmt.Fprintf(m.out, "\r\x1b[K  warn: %v\n", err)
			}
			m.refreshIndex(src, info, digest)
			fmt.Fprintf(m.out, "\r\x1b[K✓ %s\n", src)
		}
	}
	prog.finish()

	// Retries refresh index entries for the files they resolved, so the
	// next scan reuses those digests instead of re-hashing.
	if report.Uploaded+report.Skipped > 0 {
		if err := m.index.save(); err != nil && m.out != nil {
			fmt.Fprintf(m.out, "  warn: %v\n", err)
		}
	}

	if report.Failed > 0 {
		m.emit(SyncFailed, fmt.Sprintf("%d of %d retries unresolved", report.Failed, len(paths)))
	} else {
		m.emit(SyncCompleted, fmt.Sprintf("%d retried successfully, %d already archived", report.Uploaded, report.Skipped))
	}
	return report, nil
}

// refreshIndex updates the in-memory file index for a file whose digest
// was just (re)confirmed outside a full scan.
func (m *Module) refreshIndex(path string, info os.FileInfo, digest string) {
	if info == nil || digest == "" {
		return
	}
	m.index.set(path, info.Size(), mtimeNS(info.ModTime()), digest)
}

// emit forwards a lifecycle event to the configured sink, if any.
func (m *Module) emit(e SyncEvent, msg string) {
	if m.events != nil {
		m.events(e, msg)
	}
}
