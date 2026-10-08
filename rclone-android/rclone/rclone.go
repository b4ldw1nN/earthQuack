// Package rclone is the EarthQuack-owned gomobile shim around upstream rclone.
//
// # Provenance and licensing
//
// This shim is written for EarthQuack and is original work. It is NOT derived
// from gulp79/rclone-extra or gulp79/Round-Sync:
//
//   - Round-Sync is GPL-3.0 and cannot be linked into this MIT-licensed project.
//   - rclone-extra ships no LICENSE file at all, so it is all-rights-reserved
//     by default and must not be copied either.
//
// Everything this package needs already exists upstream, MIT-licensed, in
// github.com/rclone/rclone/librclone/librclone, which exports exactly
// Initialize(), Finalize() and RPC(method, input). This file is a thin,
// independently written adapter over that public API plus the backend
// registrations that Android cannot discover at runtime.
//
// # Why the boundary is this small
//
// The whole native surface is three calls. Everything else — versioning,
// remotes, listing, transfers, jobs — is expressed as rclone's documented JSON
// RPC (https://rclone.org/rc/), which is a stable, versioned, HTTP-shaped
// interface. A narrow boundary means the Kotlin side owns the typed API and the
// Go side stays a transport, which also keeps this file auditable.
//
// # Linking in RPC methods
//
// A method is callable only if the package whose init() registers it is linked
// in. librclone imports the core fs/rc machinery but not the command packages,
// so fs/operations and fs/sync must be imported explicitly or every
// operations/* and sync/* call returns 404 "couldn't find method" — which looks
// like a typo in the method name rather than a missing import. The import
// block below is therefore load-bearing, not decorative.
//
// # What Initialize actually does
//
// It points rclone at a caller-chosen rclone.conf via config.SetConfigPath,
// then calls librclone.Initialize(), which does log.InitLogging(),
// configfile.Install() and accounting.Start(ctx).
//
// Two things worth knowing, both from reading upstream rather than guessing:
//
//   - A missing config file is not an error. fs/config's LoadedData treats
//     ErrorConfigFileNotFound as "use defaults" and continues, so a fresh
//     install with no rclone.conf initialises fine and reports an empty
//     remote list.
//   - Config writes are already crash-safe. fs/config/configfile guards its
//     storage with a mutex and writes via os.CreateTemp + os.Rename, keeping
//     a .old backup. There is no need for extra locking on our side, and
//     concurrent RPCs that touch the config are safe.
//
// # What Finalize does NOT do
//
// Upstream librclone.Finalize() is runtime.GC() and nothing else; its own
// source carries the TODO "what about unfinished async jobs?". It does NOT
// cancel in-flight jobs and does NOT tear down backends. Callers must cancel
// jobs themselves (core/stop on the job id) before shutting down, or accept
// that outstanding work is abandoned when the process dies. This package
// exposes that upstream call as Shutdown() — see its doc comment for why the
// name differs.
package rclone

import (
	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/librclone/librclone"

	// RC command registration.
	//
	// rclone exposes each RPC method through an init() in the package that owns
	// it, and a method is only callable if that package is linked in. librclone
	// itself imports only the core fs/rc machinery, so it does NOT pull these
	// in — and the failure mode is deceptive:
	//
	//	operations/list -> 404 "couldn't find method \"operations/list\""
	//
	// which reads like a wrong method name rather than a missing import. These
	// two blank imports are what make operations/* and sync/* callable.
	_ "github.com/rclone/rclone/fs/operations"
	_ "github.com/rclone/rclone/fs/sync"

	// Backend registration. rclone discovers backends through blank imports at
	// link time; there is no runtime plugin mechanism on Android, so the set of
	// supported providers is a build-time decision made here and nowhere else.
	//
	// The PoC imports only `local` so the AAR stays small enough to iterate on.
	// See README.md for how to widen this without touching the Kotlin layer.
	_ "github.com/rclone/rclone/backend/local"
)

// RcloneResult is the reply to a single RPC call.
//
// It mirrors upstream's (output string, status int) pair. Status is an HTTP
// status code as rclone's RPC defines it: 200 on success, 4xx for a bad
// request or unknown method, 5xx for an internal failure. Output is always a
// JSON document — on error it is rclone's standard error envelope.
type RcloneResult struct {
	Output string
	Status int
}

// Initialize prepares rclone for use. Call once per process, before any RPC.
//
// configPath selects where rclone reads and writes rclone.conf:
//
//   - a real path — used as the config file, and its parent directory is
//     exported to backends as RCLONE_CONFIG_DIR automatically by rclone
//   - "" — in-memory config only; nothing is persisted, which is useful for
//     tests and for a "no remotes configured" session
//
// This is rclone's own exported config.SetConfigPath, not a reimplementation
// and not an env-var trick. It is called before librclone.Initialize so that
// anything the config package does during startup already sees the right path.
//
// The config file does NOT need to exist. rclone treats a missing file as
// "use defaults" and carries on, so a fresh install with no rclone.conf
// initialises cleanly and reports an empty remote list rather than failing.
// That behaviour is rclone's (fs/config LoadedData handles
// ErrorConfigFileNotFound), not something this package adds.
//
// Rclone reads RCLONE_CONFIG and --config as alternative mechanisms, but
// neither is appropriate here: --config is parsed out of os.Args, which an
// Android process does not meaningfully have, and mutating the environment
// from Kotlin is not possible. SetConfigPath is the supported API for an
// embedded rclone.
//
// It is not safe to call concurrently, and the Kotlin side serialises it.
func Initialize(configPath string) error {
	// Validate first so a bad path fails before rclone is half-started.
	// SetConfigPath rejects empty-ish in-memory sentinels and reserved paths
	// such as /dev/null's slot, and normalises to an absolute path.
	if err := config.SetConfigPath(configPath); err != nil {
		return err
	}
	librclone.Initialize()
	return nil
}

// Shutdown prepares for process exit.
//
// It is deliberately thin: upstream librclone.Finalize() only runs a GC. It
// does not cancel running jobs. Callers should cancel outstanding jobs via
// core/stop first if they care about a clean drain.
//
// Named Shutdown rather than Finalize because gobind derives a Java static
// method per exported Go function, and a static `finalize()` collides with
// Object.finalize() — javac rejects it outright:
//
//	Rclone.java: error: finalize() in Rclone cannot override finalize() in Object
//
// Upstream's Finalize is an implementation detail of the C-shim world anyway;
// this is the Android-facing name and it matches the Kotlin engine surface.
func Shutdown() {
	librclone.Finalize()
}

// RPC performs a single rclone RPC call.
//
// method is an RC method name such as "core/version" or "operations/list",
// and input is a JSON object of parameters (pass "{}" when there are none).
// The returned Output is always JSON; parse it on the Kotlin side and never
// treat it as free text.
//
// Upstream RPC recovers from panics internally and reports them as a 500 with
// an error envelope, so a backend failure cannot crash the host process.
func RPC(method, input string) *RcloneResult {
	output, status := librclone.RPC(method, input)
	return &RcloneResult{Output: output, Status: status}
}
