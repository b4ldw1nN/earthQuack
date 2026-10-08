# earthQuack Node — Home Server Roadmap

**Status:** planning document. Nothing here is implemented unless marked ✅.
**Scope:** turning `earthquack-node` from a supervised desktop sync daemon into
a long-running home server that is safe to leave switched on and safe to
expose beyond the tailnet.

Everything below was verified against the tree at commit `17b1875` (working
tree included) unless it is explicitly labelled *inferred*. File:line
references are the evidence.

---

## ⚠ Read this first — the roadmap has been partly overtaken

This document was written **before** the Python data plane was rewritten in Go
(`internal/daemon/`, selected with `EARTHQUACK_DAEMON_IMPL=go`). That changed
three things here, so read this section before following any of the phases.

### 1. Blast radius grew, and that reorders the work

The clipboard server, file server, node API, dashboard, wallpaper sync,
internet poller and telemetry now share **one process**. Previously a crash in
the data plane was contained: the Python child died and
`internal/node/daemonmgr.go` restarted it in one second, while the node kept
serving.

Now there is no containment boundary, and `recover()` still appears **zero**
times in the tree. A panic in any handler takes down clipboard sync, the
dashboard and wallpaper archival simultaneously.

**This promotes §4.2 (panic recovery) and §2.1 (server timeouts) from
"reliability nicety" to "do this first".** They are no longer lower priority
than TLS or systemd; they are the two things standing between one bad request
and a total outage.

### 2. Some findings are already fixed, or moot

| Roadmap item | Status |
|---|---|
| §3.2 Python daemon accepts any caller (`:8875`/`:8876` unauthenticated) | ✅ **Fixed.** Bearer token required, fail-closed without one. `internal/daemon/auth.go` |
| §3.2 `os.Environ()` leaks the auth token to the child | ✅ **Moot.** No child process; the services are in-process |
| §4.2 orphaned child (no `Pdeathsig`) | ✅ **Moot.** No child process to orphan |
| §4.3 `log.Fatalf` orphans the Python daemon (5 sites) | ✅ **Moot.** No child to orphan |
| §4.4 1 Hz restart loop + unbounded log spam | ✅ **Moot.** No supervisor loop; backoff now exists for the in-process components |
| §4.2 no panic recovery | ⚠️ **Still open, and worse** — see above |
| §4.6 `wallpaper.State` has no mutex | ⚠️ **Still open.** The new `internal/daemon` does this correctly; `internal/wallpaper/state.go:107` still does not |
| §4.7 Telegram backoff sleep is not cancellable | ⚠️ **Still open** (`internal/wallpaper/telegram.go:453`) |
| §2.1 `http.Server` has no timeouts | ⚠️ **Half done.** The new daemon listeners have them (`internal/daemon/daemon.go`); the node's own `:8890` server (`cmd/earthquack-node/main.go:410`) still has none |
| §1 auth token is public in git history | 🔴 **Still open, and still the live token** |

### 3. One new persistent-state file appeared

`~/.local/share/earthquack/clipboard/version.json` holds the clipboard version
counter so it survives restarts. This was necessary: the Android client
discards any event whose version is not greater than the highest it has seen,
so a counter that reset each process wedged desktop→phone sync permanently.
It is the only on-disk state the sync services keep, and it holds a counter
only — never clipboard content.

### What this means for the order of work

Follow **§9 (Suggested order of work)** rather than the phases in sequence. The
recommended shape is:

1. **§1** rotate the leaked token — unchanged, still the single most urgent item
2. **§2.1 + §4.2** server timeouts and panic recovery — *promoted*, they now
   protect the whole node
3. **§6** CI + secret scanning — stops §1 recurring
4. Then the phases as originally written (TLS, persistence, systemd, metrics)

A roadmap that tells you to harden deleted code is worse than no roadmap, so
if you find another stale item, fix it here rather than working around it.

---

## 0. Where the project actually stands

Worth stating plainly, because it shapes every decision below.

**Genuinely solid, and worth protecting:**

| Property | Evidence |
|---|---|
| Zero third-party dependencies | `go.mod` is 3 lines, no `require`, no `go.sum`, no vendor dir |
| Strict config boundary | `DisallowUnknownFields` (`internal/node/config.go:84`) — config cannot fabricate identity or runtime state |
| Fail-closed auth | empty token ⇒ `503`, never an unauthenticated dashboard (`internal/node/auth.go:49-54`) |
| Constant-time token compare | `crypto/subtle` (`internal/node/auth.go:76`) |
| Real CSRF on the only mutating endpoint | 9-case table incl. cross-session (`internal/node/service_controls_test.go:61-83`) |
| Secrets never logged/returned | asserted by test, not just by convention |
| Content-addressed dedup + atomic state writes | `internal/wallpaper/state.go:312-352` (temp + fsync + rename) |
| Verified wire compatibility across a rewrite | `internal/daemon/parity_test.go` runs the old and new implementations side by side |
| Test depth on *logic* | 46 test files, 275 `Test*` funcs, no sleeps in retry-policy tests |

**The gap:** all of that quality is in **request handling and pure logic**.
There is essentially **no** coverage of, and no hardening for, **process
lifecycle, network exposure, or resource exhaustion** — which is precisely
what matters for a machine that runs for months and is reachable from a
network.

---

## 1. Act today — the auth token is public

**This is not a hardening suggestion. This is a live credential exposure.**

`config.json` was committed before `.gitignore` covered it, and the repository
is **public** (`github.com/b4ldw1nN/earthQuack`).

Verified retrievable right now, no authentication, no rate limit:

```text
GET https://raw.githubusercontent.com/b4ldw1nN/earthQuack/5f016f7/config.json
→ HTTP 200, contains  "token": "thisisworking"
```

`thisisworking` is the **currently live** token (`config.json:3`, and it is
what the dashboard login and every `/api/*` call accept). Commit `5f016f7`
("fix ui") is an ancestor of `origin/main`, so it is permanently reachable —
GitHub keeps orphaned commits addressable indefinitely, and third-party
archives (Software Heritage, grep.app, forks) may already have copied it.

### Remediation

1. **Rotate now.** Generate a new token, update `~/.config/earthquack/config.json`
   *and* the Android app (`ServerConfig`), and restart the node. Rotation is
   the only step that actually protects you.
2. **Then** purge the blob from history (`git filter-repo --path config.json
   --invert-paths`, force-push, and ask GitHub Support to GC unreachable
   objects). Understand this is hygiene, not remediation: assume the old
   value is already burned.
3. **The AES key is *not* public.** `I6sGdKJYq4+…` appears only in local,
   unpushed `cline` checkpoint commits, so the raw URLs 404. It is still in
   local history — purge it too, but note that rotating it requires updating
   the phone as well, since clipboard E2E depends on both sides matching.
4. **Adopt a pre-commit guard** so this cannot recur (§6).

### And check this right now

The Python daemon listens on **8875** and **8876** with **no authentication
whatsoever** — verified: `grep -niE 'authorization|bearer|token|secret' daemon/server.py daemon/file_server.py`
returns **nothing**. Exposed routes are `GET/POST /clipboard`, `GET /events`
(SSE), `POST /signal`, `GET /files`, `POST /upload`, `GET /download/<id>`.

Anyone who can reach those ports can **read and overwrite your clipboard**,
subscribe to a live feed of it, and **write arbitrary files into
`~/Downloads/from-phone/`**. AES-GCM encrypts the clipboard *payload* only —
not the transport — and only when a key is set.

```sh
ss -tlnp | grep -E '8875|8876'    # what is bound, and on which interface
```

If either is bound to `0.0.0.0` rather than the tailnet IP, treat it as
urgent: firewall it now. See §3.2 for the durable fix.

---

## 2. Phase 1 — Network hardening (before it leaves the tailnet)

The threat model today is "Tailscale = safe". Tailscale *is* a real
transport boundary, so binding to the tailnet IP is meaningful hygiene — but
three of the items below are exploitable **from inside the tailnet too**, and
one (the 8875/8876 exposure) defeats the boundary entirely.

### 2.1 `http.Server` has no timeouts at all — highest value per line changed

> **Status: half done.** The new in-process sync services (`internal/daemon`)
> already set `ReadHeaderTimeout`, `IdleTimeout` and `MaxHeaderBytes`. The
> node's own API server has not been touched yet.

`cmd/earthquack-node/main.go:410`:

```go
srv := &http.Server{Addr: addr, Handler: handler}
```

Repo-wide grep for `ReadHeaderTimeout|ReadTimeout|WriteTimeout|IdleTimeout|ListenAndServeTLS|tls\.`
returns **zero matches**. Consequences for a server that runs for months:

- **No `ReadHeaderTimeout`** → classic Slowloris. A handful of connections
  that never finish their headers pin a goroutine each, indefinitely. This is
  a one-line DoS against a service with no external reverse proxy in front.
- **No `IdleTimeout`** → keep-alive connections are never reaped. Over weeks
  of dashboard polling, file transfers and SSE streams, this accumulates
  goroutines and file descriptors.
- **No `MaxHeaderBytes`** → Go's 1 MB default applies to an endpoint that
  should need a few hundred bytes.

```go
srv := &http.Server{
    Addr:              addr,
    Handler:           handler,
    ReadHeaderTimeout: 10 * time.Second,
    ReadTimeout:       30 * time.Second,   // careful: SSE must opt out
    IdleTimeout:       120 * time.Second,
    MaxHeaderBytes:    1 << 16,
}
```

> **Caveat to design around, not just copy.** `ReadTimeout`/`WriteTimeout`
> cover the *whole* request including body read and response write. `GET
> /events` is a long-lived SSE stream (`internal/node/api.go:138`), so a
> blanket `WriteTimeout` would kill every subscriber on a timer. SSE needs
> either per-route timeout exemption (`http.ResponseController.SetWriteDeadline`)
> or those two fields left at zero — decide deliberately.

### 2.2 No TLS anywhere

No `ListenAndServeTLS`, no cert flags, no reverse-proxy integration. Today
the bearer token, the session cookie, **and the token typed into the login
form** all cross the network in cleartext. `EARTHQUACK_SECURE_COOKIE` exists
to set the cookie's `Secure` flag but is inert without TLS — it is currently
a footgun that gives false assurance.

See §7 for the decision this forces (where TLS terminates).

### 2.3 No security headers whatsoever

Zero matches for `Content-Security-Policy|X-Frame-Options|X-Content-Type-Options|Referrer-Policy|Strict-Transport-Security`.
Only `Content-Type` is ever set. The dashboard is server-rendered with
`html/template` and **zero JavaScript**, so a strict CSP is trivially cheap
here (`default-src 'none'; style-src 'self'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'`)
and buys a great deal. Templates are also re-parsed from the embed FS on
every request (`internal/node/dashboard.go:250` → `web/embed.go:22`), so
parse once at startup while you are in there.

### 2.4 Bind address is unsafe by default and never validated

`main.go:45` defaults `--host` to `0.0.0.0`, and `main.go:330` does
`net.JoinHostPort(*host, fmt.Sprint(*port))` — pure string formatting, no IP
parse, no range check. A typo silently widens exposure to the whole LAN.

For a home server the safe default is the tailnet address, or refusing to
start on a wildcard without an explicit acknowledgement. At minimum: validate
the host parses and the port is in range, and **log a loud warning when
binding a wildcard interface**.

### 2.5 No rate limiting; the one counter built for it is dead

`internal/node/auth.go:36` increments `unauthenticated` on every rejected
request — and **nothing ever reads, logs, or exports it**. `/login` is an
unauthenticated, password-equivalent endpoint with no body size limit
(`internal/node/login.go:51`, relying on `net/http`'s 10 MB
`parsePostForm` cap) and no rate limit. With a 32-byte random token brute
force is impractical, so this is about **request floods and log
amplification**, not credential guessing — but it is nearly free to fix, and
the instrumentation is already half-built.

---

## 3. Phase 2 — Credentials and identity

### 3.1 The single shared token does not scale to a home

One `EARTHQUACK_AUTH_TOKEN` is shared by the dashboard, every `/api/*` call,
the Android app, and every future client. Consequences for a house with
multiple people and devices:

- **No revocation.** Lose a phone? You must rotate the token, which logs out
  every other device.
- **No attribution.** Every audit entry is "the token", not "zoro's phone".
- **No scoping.** A device that may only *read* can also *read everything*.

Per-device credentials (identifier + secret, or signed tokens) is the change
that makes earthQuack a home server rather than a personal tool. It touches
the config schema, the auth middleware, and the Android app — so it is a
Phase 5 decision with Phase 2 consequences. Note the plumbing is half-there:
the token already lives in an `atomic.Value` explicitly "so the token could be
rotated at runtime later" (`internal/node/auth.go:29`) — **but no setter
exists**, so rotation still requires a restart.

### 3.2 The Python daemon needs authentication — ✅ DONE

> **Resolved by the Go rewrite.** The clipboard and file services now run
> in-process (`internal/daemon/`) and require the shared bearer token on every
> route except `/health`, failing closed with `503` when no token is
> configured (`internal/daemon/auth.go`). The Android app already sent the
> header, so it needed no change. Verified live on device in both directions.
>
> The original finding is kept below as the record of *why* it mattered, since
> the same class of bug is what §3.1 and §2.2 are still about.

Highest-severity item in this document after §1. Three options were considered:

| Option | Trade-off |
|---|---|
| **Bind to loopback, reverse-proxy through the Go node** | One auth surface, one TLS surface, one set of headers. Cost: the Go node must proxy SSE and range requests correctly. |
| **Add the bearer check in Python** | Cheapest change. Cost: two auth implementations to keep in sync; the phone sends the token already. |
| **Unix socket + `SO_PEERCRED`** | Strongest. Cost: both sides rewrite; Android needs a transport that supports it. |

The rewrite effectively delivered the first option's outcome without the
proxy: the services are in the node process, so there is exactly one auth
implementation and one place to reason about.

Also worth doing regardless, and now done: **the child no longer receives the
whole environment**. `cmd.Env = append(os.Environ(), …)`
(`internal/node/daemonmgr.go:61`) used to place `EARTHQUACK_AUTH_TOKEN` — plus
`TELEGRAM_BOT_TOKEN` — into a Python process with no use for them.

### 3.3 Secrets on disk are unmanaged

`config.json` currently holds the auth token and the AES key in plaintext and
is auto-loaded from the repo root. The docs *advise* mode `600`; nothing
*enforces* or checks it. For a home server, add a startup check that warns
(refuses, with an override) when a config containing secrets is group- or
world-readable.

---

## 4. Phase 3 — Reliability: surviving months unattended

### 4.1 No process supervision for the node itself

There is **no systemd unit for the Go node** — the only unit in the tree,
`clipboard-sync.service`, is legacy glue for the old C `clipsyncd`. No
Dockerfile, no Makefile, no CI. `docs/NODE.md:5` states the current intent as
"No systemd, no root, no Docker, no CI required" — correct for a desktop, and
exactly what must change for a home server.

A `--user` unit should provide: `Restart=always`, `RestartSec=5`,
`After=network-online.target tailscaled.service`, `Wants=network-online.target`,
and hardening — `NoNewPrivileges`, `PrivateTmp`, `ProtectSystem=strict`,
`ProtectHome=read-only` (plus `ReadWritePaths=` for the state dirs),
`RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6`, `MemoryMax=`.

The node currently also cannot survive its own startup: `EARTHQUACK_REPO`
defaults to `"."` (`main.go:139`), so the Python daemon only resolves if the
cwd happens to be the repo — the same class of bug as the config-path issue
fixed earlier today. The unit must set `WorkingDirectory=` or an absolute
`EARTHQUACK_REPO`.

### 4.2 A panic anywhere kills the whole node — 🔴 PROMOTED, do this early

> **This is now the highest-priority reliability item**, because the Go rewrite
> removed the containment boundary. When the data plane was a Python child, a
> panic there cost you clipboard sync for one second. Now every subsystem
> shares one process, so a single panic takes down the dashboard, clipboard,
> file transfer, wallpaper archival and telemetry together.

`grep -rn 'recover()' --include=*.go .` → **zero matches**. There are seven or
more long-lived goroutines (in-process sync services, desktop bridge, send-folder
watcher, staged-file sweeper, service refresher, telemetry sampler, wallpaper
job, internet poller). One panic ⇒ process death.

The fix is cheap and mechanical:

- `recover()` at the top of every spawned goroutine, logging the panic and the
  stack rather than propagating it
- `recover()` in the HTTP middleware, so a panic in one handler returns a 500
  instead of killing the listener
- an explicit `recover()` on the wallpaper job and the internet poller, since
  both are user-triggered and a panic there loses a long-running archive

Note the original second half of this finding — the orphaned child, because
`exec.Command("python3", "app.py")` (`internal/node/daemonmgr.go:57`) set
neither `SysProcAttr.Pdeathsig` nor `Setpgid` — is now **moot**: there is no
child process. If `EARTHQUACK_DAEMON_IMPL=python` is kept as a fallback,
restore that flag; otherwise the section above is the whole of it.

### 4.3 Five `log.Fatalf` sites orphan the Python daemon — ✅ MOOT

> The Python child no longer exists, so there is nothing to orphan. Kept as a
> record: `main.go:155, 226, 235, 341, 358` all ran **after**
> `daemonMgr.Start()`, and `log.Fatalf` calls `os.Exit(1)`, which **skips every
> `defer`**.

One residue is worth keeping in mind: if the Python fallback is retained, those
`log.Fatalf` paths still skip cleanup. And `EARTHQUACK_REPO` still defaults to
`"."` (`main.go:139`), so a systemd unit must set `WorkingDirectory=` or an
absolute `EARTHQUACK_REPO` (§4.1).

### 4.4 Restart loop has no backoff and will fill your disk — ✅ MOOT

> There is no supervisor loop for a child process any more, and the in-process
> components (`internal/daemon/daemon.go`, `spawn`) already use exponential
> backoff with a one-minute ceiling. Kept as a record of the original finding:
> `daemonmgr.go:119` was a flat 1-second ticker logging once per second,
> forever, with no rotation.

The remaining half of this finding **still applies to the node itself**: there
is no log rotation, no log level, and no structured logging. A node that logs a
line per clipboard change — as the sync services now do — will grow a log file
or journal without bound. See §6.

### 4.5 `Registry.Refresh()` holds a write lock across network I/O

`registry.go:234` takes `r.mu.Lock()`, then calls `client.GetNode`
(`registry.go:307-320`) **inside** the lock, with a 2 s client timeout
(`client.go:37`). Every `Local()`, `Nodes()`, `History()` and
`SetInternetProvider()` caller blocks behind it. `/api/nodes` calls
`Refresh()` (`registry.go:325`), so **an API request can stall every other
registry reader for up to ~2 s per peer**. This will be the first thing that
hurts under load. Snapshot under the lock, do I/O outside it.

### 4.6 Wallpaper state has no mutex — a crash, not an error

`internal/wallpaper/state.go:107-112` and `index.go:34-37` have **no mutex**;
the only mutex in the package guards the topic map. Meanwhile the docs warn in
prose never to run a dashboard job and a CLI sync against one state dir
(`main.go:305`, `docs/NODE.md:167`) — and **nothing enforces it**. Two
processes ⇒ unsynchronized map writes ⇒ `fatal error: concurrent map writes`,
which is unrecoverable.

Note the inconsistency: `internet.State` *does* have a `sync.Mutex`
(`internal/internet/state.go:34`). Fix wallpaper the same way, and add a lock
file so the warning becomes a mechanism.

### 4.7 Shutdown can hang for minutes

The Telegram backoff sleep is not cancellable: `p.sleep(delay)`
(`internal/wallpaper/telegram.go:453`) defaults to `time.Sleep`
(`telegram.go:103`), and `ctx.Done()` is only checked *before* the sleep. With
`Backoff.Max = 2min` (`retry.go:39`) a shutdown during a retry storm blocks
`job.Wait()` for minutes — while `srv.Shutdown` gets only **3 s**
(`main.go:350`) and there is no second-chance `srv.Close()`.

Related: `isRetryableNetworkErr` ends in `return true` for **any**
unclassified error (`retry.go:92`), so permanent failures — a revoked bot
token, a wrong chat id — are retried 5× with growing sleeps. Today's dead
token cost six attempts before recording the failure. Classify the known
permanent Telegram errors explicitly.

---

## 5. Phase 4 — Persistence and observability

### 5.1 Everything operational is in-memory and dies on restart

`registry.go:16` states it plainly: *"State is in-memory only — no database."*

| Lost on restart | Bounded at |
|---|---|
| Metric samples | 120 (`history.go:35`) |
| Transition events | **30** (`history.go:37`) |
| Peer map / online state | rediscovered via `tailscale status` |
| Browser sessions | 12 h TTL, in-memory |
| CSRF secret | regenerated per process |

**30 events is the headline problem.** For a machine expected to run for
months, that is minutes of audit trail during any incident — the opposite of
what you want when diagnosing "the clipboard stopped syncing at 3am".

Note also the mismatch: `/api/events` accepts `limit` up to 200
(`api.go:234`) while the ring holds 30, so the API advertises a range it
cannot serve.

Persist events and samples. Given the zero-dependency policy, the natural
choice is an **append-only JSONL log with periodic compaction** — stdlib only,
crash-safe by construction, and it preserves the project's most valuable
property. (The alternative, bbolt/Badger, would be the first dependency.)

### 5.2 No metrics, no alerting

There is no Prometheus/OTel endpoint — only human HTML and JSON. Hand-rolling
the Prometheus text format is ~50 lines and keeps zero deps.

Alerting is more interesting: **you already have a working outbound
notification path** (the wallpaper module's Telegram provider). A home server
should reuse it — "node unreachable", "clipboard service down for 5 min",
"disk > 90%", "wallpaper sync failed". That is a natural generalisation of
`ArchiveProvider` into a notifier interface.

Related: **health ignores telemetry** (`health.go:21-23`). Health is derived
only from TCP service up/down, so a node at 98% disk or thrashing reports
`healthy`. A homeserver health check should consider resource pressure.

### 5.3 Backup and disaster recovery

The wallpaper archive (`~/.local/share/earthquack/wallpaper/*.json`) is
currently the **only durable asset the node owns** — and it is what makes
553 files not re-upload. It has no backup. For a home server, "restore from
backup" must be a tested path, not a hopeful one.

Also note `writeJSONAtomic` never fsyncs the **parent directory** after
`os.Rename`, so on ext4/XFS the rename is not crash-durable — the file can be
absent after a hard power loss even though the write "succeeded". The function
is also duplicated verbatim in `internal/internet/state.go:220-256`. Fix once,
in one place, with a test that asserts the fsync.

---

## 6. Cross-cutting: process hygiene

Low effort, prevents whole classes of future incident.

- **CI.** There is no `.github/` at all. Add a workflow running
  `go build ./... && go vet ./... && go test -race ./...` on push. The README
  already lists these as manual steps; automating them is the cheapest
  quality win available. Note the suite has **never** been run under `-race`
  in an automated context — with §4.5 and §4.6 in the tree, that is worth
  doing immediately.
- **A `Makefile`** for build/test/install, so the binary in `~/.local/bin` is
  reproducible rather than whatever was last `go build`-ed.
- **Pre-commit secret scanning** (gitleaks / `detect-secrets`) plus a
  `.gitleaks.toml` allowlist. This is the control that stops §1 recurring.
- **Process-level tests.** `main()` is never invoked by any test;
  `cmd/earthquack-node` has 5 tests, all pure helpers. Nothing verifies the
  binary boots, binds, or supervises the daemon end to end.
- **Preserve zero-dependency.** It is a genuine security property for a
  home server and it is currently 100% intact. Every "just add a library"
  suggestion below should be weighed against it; all of them have stdlib
  alternatives.
- **Fix the small lies.** `normalizeOS()` returns the literal `"linux"`
  unconditionally (`registry.go:409`) despite non-Linux stubs existing — a
  macOS build would misreport. `daemon/app.py:1` has a garbled shebang above
  the real one at `:2`. `daemon/config.py:6` and `daemon/run-earthquack.sh:12`
  hardcode a machine-specific Tailscale IP.
- **That zombie.** A `<defunct>` `earthquack-node` (PID 177430) was observed
  during this work — a parent shell not reaping its child. Harmless in
  isolation; it is a symptom of the missing supervision in §4.1.

---

## 7. Decisions to settle before the homeserver role

These are forks in the road. Each one invalidates work done on the wrong
branch, so decide first.

1. **Where does TLS terminate — in the node, or at a reverse proxy?**
   This drives the cookie `Secure` flag, the security-header set, whether the
   phone talks TLS, and the entire threat model. If a reverse proxy (Caddy,
   nginx) will always front it, the node can stay plaintext-only and the
   priority is *documenting and enforcing* that. If the node may be exposed
   directly, it needs `ListenAndServeTLS` and cert handling.
2. **One shared token, or per-device identity?** (§3.1) Touches config
   schema, auth middleware, and the Android app. Cheapest to get right before
   more clients exist.
3. **Does the Python daemon survive?** Clipboard + file transfer are the
   product's actual data plane, and it is unauthenticated Python that the Go
   node must keep alive with a watchdog. Rewriting them in Go would let you
   delete §4.2, §4.3, §4.4 and §3.2 at once. Keeping Python means hardening
   it separately forever. This is the largest single decision in the document.
4. **Is earthQuack the control plane, or also the data plane?** Today it is
   control plane plus supervisor. A homeserver usually needs both — and that
   is a much larger security surface than a read-only dashboard.
5. **Zero-dep, or batteries-included?** Metrics and time-series storage both
   have credible stdlib implementations (§5.2), so this can stay "yes" — but
   decide explicitly, because it is a promise to every future contributor.

---

## 8. What "storage capability" actually means today

Worth flagging because `examples/homeserver.json` declares
`capabilities: ["storage", "docker"]` and that could mislead.

There is **no storage service**. `internal/node/storage.go:15-17` says so
outright — *"Storage is node telemetry, NOT a capability: having disks does
not mean a node declares a 'storage' capability."* It reports disk usage about
mounts, nothing more. The `storage` string appears in non-test code only in
comments and config docs. `docker` is likewise unimplemented — the README
already labels those examples "illustrative".

So the homeserver capability surface is genuinely greenfield. The good news
is that the plugin shape is already right: a capability is *declared* in
config, *registered* in the registry, and surfaced in the API and dashboard
with no changes to those layers. Adding a real capability is a new module
implementing a `NewModule`/`Status`/`Sync`-shaped interface — the same shape
`internal/wallpaper` and `internal/internet` already follow.

The wallpaper module's `ArchiveProvider` abstraction is likewise the right
seed for homeserver storage: it already separates *scan and dedup* from *where
bytes land*. Adding S3, local disk, or Nextcloud as providers is
additive, and the 553-file archive proves the dedup path works at scale.

---

## 9. Suggested order of work

Sequenced by *risk retired per hour*, not by elegance. **Revised after the Go
rewrite** — see the warning at the top of this document for why items 1 and 2
moved up and four items dropped out entirely.

| # | Work | Why here |
|---|---|---|
| **0** | **Rotate the auth token (§1)** | Still public in git history, still the live token. Nothing else matters until this is done. |
| **1** | **`recover()` in every goroutine + HTTP middleware (§4.2)** | **Promoted.** One process now serves clipboard, files, dashboard, wallpaper and telemetry; a single panic is a total outage. |
| **2** | **`http.Server` timeouts on `:8890` (§2.1)** | **Promoted.** Trivial diff; closes Slowloris and the idle-connection leak on the same server item 1 protects. |
| **3** | **CI + gitleaks + Makefile (§6)** | Stops item 0 recurring. Cheapest quality win available. |
| 4 | Security headers + parse templates once (§2.3) | Trivial, large benefit for a UI you will actually use |
| 5 | Wallpaper `State` mutex + lock file (§4.6) | Removes a real `fatal error` crash mode; the correct pattern already exists in `internal/daemon` |
| 6 | systemd `--user` unit (§4.1) | Turns "runs until I reboot" into "self-healing" |
| 7 | Log rotation / levels (§4.4 residue) | The services now log per clipboard change; unbounded growth is real |
| 8 | `Registry.Refresh()` I/O outside the lock (§4.5) | First thing to hurt under load |
| 9 | Cancellable Telegram backoff (§4.7) | A hung shutdown blocks `job.Wait()` for minutes |
| 10 | TLS decision, then TLS (§2.2, §7.1) | Only meaningful once 0-9 hold |
| 11 | Durable event log (§5.1) | You will need it the first time something breaks |
| 12 | Per-device identity (§3.1, §7.2) | Before more clients exist |

**Dropped as done or moot:** §3.2 (data-plane auth — closed by the rewrite),
§4.3 and §4.4 (no child process to orphan or supervise).

Items 0-9 are all small, independent, and each is verifiable by a test. Items
10+ are design decisions with real rework potential — which is why §7 comes
before them.

### Deliberately not in this list

Two things are *worse* now than when this document was written, and both are
covered above rather than scheduled here:

- **The auth token is still the leaked one.** Item 0 is not optional and not
  deferrable to a maintenance window.
- **Logging is now chatty by design.** The clipboard services log one line per
  clipboard change (origin and version only — never content). That is the
  right trade for debuggability and the wrong trade without rotation, which is
  why item 7 exists.

---

## Appendix — re-running this audit

Every claim above is checkable:

```sh
cd /path/to/earthQuack

# §1 public secret
curl -s https://raw.githubusercontent.com/b4ldw1nN/earthQuack/5f016f7/config.json

# §2.1 no timeouts on the node server / §2.2 no TLS
grep -rn 'ReadHeaderTimeout\|IdleTimeout\|ListenAndServeTLS\|tls\.' --include=*.go .

# §4.2 no panic recovery  ← the promoted item
grep -rn 'recover()' --include=*.go .

# §4.6 wallpaper State still unsynchronised
grep -n 'type State struct' -A6 internal/wallpaper/state.go
grep -n 'type State struct' -A6 internal/daemon/state.go   # the correct version

# §2.3 no security headers
grep -rn 'Content-Security-Policy\|X-Frame-Options\|X-Content-Type-Options' --include=*.go .

# §3.2 unauthenticated Python daemon
grep -rniE 'authorization|bearer|token|secret' daemon/server.py daemon/file_server.py

# §4.2 no panic recovery, no child cleanup
grep -rn 'recover()' --include=*.go .
grep -n 'SysProcAttr' internal/node/daemonmgr.go

# §4.6 wallpaper state has no mutex
grep -n 'type State struct' -A6 internal/wallpaper/state.go

# §5.1 in-memory only
grep -n 'in-memory only' internal/node/registry.go

# §6 no CI
ls -d .github Dockerfile Makefile 2>/dev/null || echo "none"

# baseline health
go build ./... && go vet ./... && go test -race ./...
```
