// Package wallpaper implements the first earthQuack module: archiving
// wallpapers (and, by extension, any image tree) to a destination-
// specific provider.
//
// Dependency direction is deliberately kept clean:
//
//	Wallpaper module ──▶ ArchiveProvider (interface)
//	                           │
//	                           ├── TelegramProvider
//	                           └── (future: S3, disk, ...)
//
// The module talks only in provider-agnostic terms (source path,
// filename, category). Telegram-specific concepts such as
// message_thread_id and forum topics live exclusively inside the
// Telegram provider and never leak into the module.
package wallpaper

import (
	"context"
	"errors"
)

// ErrTooLarge is returned by a provider when a file exceeds that
// provider's maximum upload size. The module treats it as a permanent
// validation failure (recorded in failed state, never retried
// endlessly).
var ErrTooLarge = errors.New("wallpaper: file exceeds provider upload size limit")

// ArchiveFile describes a single file to archive using provider-
// agnostic terms. The provider is responsible for mapping Category to
// its destination-specific concept (e.g. a Telegram forum topic).
type ArchiveFile struct {
	// SourcePath is the absolute path on disk whose bytes must be
	// streamed to the destination. Uploads preserve the original bytes.
	SourcePath string
	// Filename is the base name to present at the destination.
	Filename string
	// Category is the logical grouping for the file: the name of the
	// source folder, or the special category "Unsorted" for files that
	// sit directly at the root of the source directory.
	Category string
	// Digest is the SHA-256 hex digest of the file content. Providers
	// may include it in captions/metadata; it is never used to resize,
	// recompress, or rewrite the file.
	Digest string
	// Size is the exact file size in bytes.
	Size int64
	// MtimeNS is the file's modification time in nanoseconds. It is
	// local fingerprint metadata used by the module's file index; a
	// provider never needs it and may ignore it.
	MtimeNS int64
}

// Unsorted is the category assigned to files that do not belong to any
// subfolder of the source directory.
const Unsorted = "Unsorted"

// ArchiveProvider archives files to a destination-specific backend.
// Implementations own all destination state creation and reuse (for
// example, Telegram forum-topic creation and topic-ID reuse).
type ArchiveProvider interface {
	// Name returns the stable provider identifier ("telegram", ...).
	Name() string

	// Upload streams the file described by f to the provider's
	// destination. On success it returns nil; on failure it returns an
	// error describing what happened. The module records failures in
	// failed state and does not attempt to interpret provider-internal
	// errors.
	Upload(ctx context.Context, f ArchiveFile) error
}

// SizeLimitedProvider is an optional capability of an ArchiveProvider:
// it reports the maximum upload size in bytes for validation before an
// upload is attempted. 0 means no limit.
type SizeLimitedProvider interface {
	MaxUploadSize() int64
}

var _ ArchiveProvider = (*TelegramProvider)(nil)

// passiveProvider is a stand-in ArchiveProvider used by read-only
// commands (status, scan, dry-run) when no destination provider is
// configured or its credentials are absent. It never uploads and has no
// size limit, so read-only operations never require a live backend.
type passiveProvider struct {
	name string
}

func (p passiveProvider) Name() string { return p.name }
func (passiveProvider) Upload(context.Context, ArchiveFile) error {
	return errors.New("wallpaper: no archive provider configured")
}
