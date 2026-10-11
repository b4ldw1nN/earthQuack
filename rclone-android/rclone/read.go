package rclone

// Byte-range reads for the app's video streaming pipeline.
//
// # Why this exists
//
// Everything else in this file transports whole objects: `operations/copyfile`
// copies an object from a remote into a local path, and rclone's own `cat` path
// is not exposed over RC at all. Playing a 2 GB video is none of those — the
// player needs a few hundred kilobytes at the start, then whatever it seeks to.
//
// The RC surface linked into this AAR (see the import block in rclone.go) was
// checked method by method against the rclone v1.75.1 source before this was
// written: of `operations/*`, `sync/*`, `config/*`, `options/*`, `core/*`,
// `backend/command` and `rc/*`, **not one** takes an offset and returns bytes.
// `core/command` shells out to `os.Executable()`, which an Android process does
// not have, so it is not an escape hatch either. Rather than dump the whole
// object into a buffer and slice it — which for a video means downloading the
// file twice, once for metadata and once for the part being watched — this
// package adds exactly one method that reads a range.
//
// # What it does
//
//	earthquack/read  {srcFs, srcRemote, offset, length}
//	               -> {length, data(base64), size, eof}
//
// It is the same resolution path `operations/copyfile` uses
// (`rc.GetFsAndRemoteNamed`), so `srcFs`/`srcRemote` mean exactly what they
// mean there, and the backend is the one rclone's fs cache already holds.
//
// The read itself is `Object.Open` with a `fs.SeekOption`. That matters for
// three reasons:
//
//   - `SeekOption.Mandatory()` is true, so a backend that cannot honour a range
//     fails the call instead of silently streaming the rest of the file. A
//     video player asking for 512 KB must not cost 2 GB of transfer, and the
//     caller treats a failure here as "this remote cannot stream" and offers a
//     download instead.
//   - Backends that do support ranges implement it natively and cheaply: the
//     local backend seeks the file descriptor, and HTTP-based backends
//     (drive, webdav, s3, onedrive) set a `Range: bytes=offset-` header, so
//     only the requested bytes cross the network.
//   - It is rclone's own open path, so retries, auth and token refresh behave
//     exactly as they do for every other operation.
//
// # What it deliberately does not do
//
//   - It does not hold an open file handle between calls. Every call opens,
//     reads and closes. A player issues a handful of requests per second, each
//     of which is a bounded read; keeping handles open would need a lifecycle
//     of its own (and a way to invalidate it when the fs cache is cleared) for
//     no measurable gain.
//   - It does not log data, paths or credentials. Nothing here prints.
//   - It is not adaptive streaming. One file, byte ranges, no transcoding.
import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"

	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/rc"
)

// maxReadBytes caps one read. The caller asks for fixed-size chunks, so this
// only exists to stop a malformed request from allocating without bound: the
// buffer is allocated before anything is read.
const maxReadBytes = 8 << 20 // 8 MiB

// readMethod is the RC path. Namespaced to this app rather than to an
// upstream namespace, because it is not an upstream method and must not
// shadow one if rclone later adds `operations/read`.
const readMethod = "earthquack/read"

func init() {
	rc.Add(rc.Call{
		Path:  readMethod,
		Fn:    rcReadRange,
		Title: "Read a byte range of a remote object",
		Help: `This takes the following parameters:

- srcFs - a remote name string e.g. "drive:"
- srcRemote - a path within that remote e.g. "films/holiday.mp4"
- offset - byte offset to start reading from
- length - number of bytes to read, capped at 8 MiB

The result is as returned from the range read:

- length - number of bytes actually read
- data - base64 encoded bytes
- size - size of the object in bytes, -1 when the backend reports none
- eof - whether this read reached the end of the object

Unlike operations/copyfile this reads a single range and is what the app's
video player talks to. A backend that cannot honour a range fails the call
rather than reading the whole object, because the seek option passed to the
backend is mandatory.
`,
	})
}

// rcReadRange performs one bounded, seekable read.
func rcReadRange(ctx context.Context, in rc.Params) (rc.Params, error) {
	f, remote, err := rc.GetFsAndRemoteNamed(ctx, in, "srcFs", "srcRemote")
	if err != nil {
		return nil, err
	}

	offset, err := in.GetInt64("offset")
	if err != nil {
		return nil, err
	}
	length, err := in.GetInt64("length")
	if err != nil {
		return nil, err
	}
	switch {
	case offset < 0:
		return nil, errors.New("offset must not be negative")
	case length <= 0:
		return nil, errors.New("length must be greater than zero")
	case length > maxReadBytes:
		return nil, fmt.Errorf("length must not exceed %d bytes", maxReadBytes)
	}

	obj, err := f.NewObject(ctx, remote)
	if err != nil {
		return nil, err
	}
	size := obj.Size()

	// Offset past the end of the object: nothing to read, and no need to ask
	// the backend. Keeps a client bug from becoming a backend round trip.
	if size >= 0 && offset >= size {
		return rc.Params{
			"length": int64(0),
			"data":   "",
			"size":   size,
			"eof":    true,
		}, nil
	}

	rd, err := obj.Open(ctx, &fs.SeekOption{Offset: offset})
	if err != nil {
		return nil, err
	}
	defer func() { _ = rd.Close() }()

	buf := make([]byte, length)
	// ErrUnexpectedEOF and EOF are normal here: a range that runs off the end
	// of the object is a valid short read, not an error.
	n, err := io.ReadFull(rd, buf)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
		return nil, err
	}

	return rc.Params{
		"length": int64(n),
		"data":   base64.StdEncoding.EncodeToString(buf[:n]),
		"size":   size,
		"eof":    size < 0 || offset+int64(n) >= size,
	}, nil
}
