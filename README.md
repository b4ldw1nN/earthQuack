# earthQuack

earthQuack is a personal multi-device platform: bidirectional **clipboard + file + URL sync**
between a desktop (Linux/Windows) and an Android phone over **Tailscale**, unified behind a
**Go node** that turns every machine into a first-class, observable member of your tailnet —
with a multi-node dashboard, read-only system/storage/network telemetry, and strict authentication.

One command runs the whole stack on a machine:

```sh
go run ./cmd/earthquack-node
```

The Go node is the **single entry point**: it serves the Node API + dashboard on `:8890` and
supervises the Python daemon (`daemon/app.py`) that implements clipboard sync (`:8875`) and
file transfer (`:8876`).

```
                        Tailscale (WireGuard-encrypted)
┌────────────────────────────────────┐      ┌──────────────────────────┐
│ Desktop (Arch / Hyprland / Windows)│      │ Android 8+ (Kotlin app)  │
│                                    │      │                          │
│  earthquack-node (Go, :8890)       │      │  EarthQuackService       │
│  ├─ Node API + dashboard           │      │  ├─ clipboard sync (SSE) │
│  ├─ system/storage/net telemetry   │      │  ├─ file transfer        │
│  ├─ Tailscale peer discovery       │      │  └─ pairing via shared   │
│  └─ supervises ↓                   │      │     token + AES key      │
│  python daemon (app.py)            │      │                          │
│  ├─ server.py     :8875 clipboard  │◄────►│  ClipboardApi            │
│  └─ file_server.py :8876 files     │      │  FileTransferService     │
└────────────────────────────────────┘      └──────────────────────────┘
```

Every earthQuack node in your tailnet discovers the others (via `tailscale status`),
probes their `/api/node`, and renders them all on one dashboard — so a second machine
joins with **zero source changes**: build the binary, add a config file, run it.

---

## Components

| Component | Location | What it does |
|---|---|---|
| **Go node** | `cmd/earthquack-node`, `internal/node` | Node API, dashboard, auth, identity, peer discovery/probing, telemetry (system, storage, network), daemon supervision. Stdlib only, Go ≥ 1.24, no external dependencies. |
| **Wallpaper module** | `internal/wallpaper` | First capability module: provider-independent scan/sync/status/retry for wallpapers, archival state, retry/backoff, and a minimal CLI. The Telegram provider streams `sendDocument` uploads and owns forum topic state. Stdlib only. |
| **Python daemon** | `daemon/` | The sync services themselves: clipboard broker + SSE, file staging/transfer, desktop clipboard bridge, send-folder watcher, AES-256-GCM crypto, Tailscale discovery, hotkeys. Python 3 stdlib only. |
| **Android app** | `app/` | Foreground sync service, clipboard IME, file transfer/share, quick-settings tile, server config UI. Kotlin, minSdk 26 (Android 8+), target/compile SDK 34. |
| **Shell helpers** | repo root | `clip-send`, `clip-open`, `clip-shot` — send files, open URLs on the phone, screenshot-to-phone from the desktop. |
| **Examples & docs** | `examples/`, `docs/NODE.md` | Per-machine node declarations; detailed node deployment guide. |

---

## Quick start

### 1. Build & run the node (desktop)

```sh
go build -o earthquakes-node ./cmd/earthquack-node   # or: go run ./cmd/earthquack-node

export EARTHQUACK_AUTH_TOKEN="$(openssl rand -hex 32)"   # shared secret
export CLIPBOARD_AES_KEY="$(python3 -c 'import base64,os;print(base64.b64encode(os.urandom(32)).decode())')"
export EARTHQUACK_HOST="$(tailscale ip -4 | head -n1)"   # sync services bind the tailnet IP

./earthquakes-node --host "$(tailscale ip -4 | head -n1)" --port 8890
```

* `:8890` — Node API + dashboard
* `:8875` — clipboard broker (Python, supervised)
* `:8876` — file transfer (Python, supervised)

### 2. Open the dashboard

Browse to `http://<tailscale-ip>:8890/` and sign in once with the auth token.
You'll see the local node with its capabilities, services, system info and
**storage usage bars**, plus every discovered tailnet peer.

`curl` clients can skip the login page:

```sh
curl -H "Authorization: Bearer $EARTHQUACK_AUTH_TOKEN" http://127.0.0.1:8890/api/node
```

## Configuration

### Clipboard key in the node config

The top-level `clipboard_aes_key` string in the node JSON config accepts the
same Base64-encoded 32-byte key as `CLIPBOARD_AES_KEY`. Fill in the key matching
the Android app, then restart the node to apply changes. A non-empty config key
takes precedence over the environment. Leaving both empty disables clipboard encryption.
From the project folder, run `./earthquack-node`: it loads `config.json` by default
(unless `EARTHQUACK_NODE_CONFIG` or `--config` selects another file), without needing
an `env -u CLIPBOARD_AES_KEY` prefix.
Keep real keys private: do not commit a populated config, and restrict file access
(e.g. mode `600`). The node passes the resolved key to its managed Python daemon;
it does not change an independently managed clipboard service.

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `EARTHQUACK_AUTH_TOKEN` | *(none)* | Shared bearer token. **Required** — without it the node fails closed (see Auth). Wins over `auth.token` in the config file. |
| `CLIPBOARD_AES_KEY` | *(none)* | Base64 32-byte AES-256-GCM key for clipboard payloads. Must match the Android app. Used only when `clipboard_aes_key` in the node config is empty. If both are empty, clipboard travels unencrypted. |
| `EARTHQUACK_HOST` | `127.0.0.1` | Bind host for the Python sync services. |
| `EARTHQUACK_PORT` / `EARTHQUACK_FILE_PORT` | `8875` / `8876` | Ports for clipboard / file transfer. |
| `EARTHQUACK_NODE_HOST` / `EARTHQUACK_NODE_PORT` | `0.0.0.0` / `8890` | Defaults for the node's `--host` / `--port` flags. |
| `EARTHQUACK_NODE_CONFIG` | *(none)* | Default `--config` path. |
| `EARTHQUACK_REPO` | `.` | Repo root, so the node can find `daemon/app.py`. |
| `EARTHQUACK_SECURE_COOKIE` | *(off)* | `1` adds the `Secure` flag to session cookies (enable once the dashboard is behind TLS). |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | *(none)* | Wallpaper→Telegram credentials. **Never commit or log.** They register the `wallpaper` capability/service automatically when present. |
| `EARTHQUACK_WALLPAPER_SOURCE` | `~/Pictures/Wallpapers` | Wallpaper source directory override. |
| `EARTHQUACK_WALLPAPER_STATE` | `~/.local/share/earthquack/wallpaper` | Wallpaper state directory (`uploads.json`, `topics.json`, `failed.json`, `files.json`, `sync.json`). Point it at an existing archive to adopt it. |
| `EARTHQUACK_WALLPAPER_PROVIDER` | `telegram` | Archive provider selector (only `telegram` is implemented). |

### Node declaration file (optional)

The same binary runs everywhere; a node differs only in its optional JSON
declarations file (`--config`, see `examples/`):

```json
{
  "capabilities": ["clipboard", "file-transfer"],
  "services": [
    {"capability": "clipboard",     "name": "clipboard",     "port": 8875, "version": "0.1.0"},
    {"capability": "file-transfer", "name": "file-transfer", "port": 8876, "version": "0.1.0"}
  ]
}
```

A node with **no config file is valid**: it falls back to the built-in
declarations (clipboard + file-transfer on 8875/8876). `examples/arch.json`
matches those defaults; `examples/homeserver.json` and `examples/vps.json`
illustrate other declaration sets (storage, docker, reverse-proxy);
`examples/arch-wallpaper.json` additionally declares the `wallpaper`
module as an enabled node capability:

```json
{
  "wallpaper": {
    "enabled": true,
    "source": "~/Pictures/Wallpapers",
    "provider": "telegram"
  }
}
```

Credentials (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) **never belong in the
file** — they come from the environment. A node with the module declared
reports capability `wallpaper` and a `running` in-process service of the
same name in `/api/node`; having zero pending wallpapers never marks the
node unhealthy.

**The config boundary is strict** — configuration is *declarations only*:

```text
CONFIG (declarations only)        RUNTIME (never configurable, rejected at load)
  capabilities                      identity (machine-id)
  services (name/port/version)      hostname, OS
  auth.token                        online/offline, service status
                                    network addresses, peers
```

Unknown fields (typos, or attempts to inject runtime state like a hostname)
fail config loading outright — a config file can never fabricate identity
or runtime state.

## Authentication

earthQuack has one shared secret and three ways to present it:

1. **API — Bearer token.** `/api/node` and `/api/nodes` require
   `Authorization: Bearer <token>` (constant-time comparison, fail-closed:
   with no token configured, protected endpoints return
   `503 authentication not configured` instead of ever serving
   unauthenticated). `/api/health` and the stylesheet are public.
2. **Browser — session cookie.** A plain browser can't send auth headers, so
   the dashboard has its own layer derived from the same single token (no
   second secret): `GET/POST /login`, `POST /logout`. The session cookie
   (`eq_session`) holds only a random 256-bit session id — never the token —
   and is `HttpOnly`, `SameSite=Strict`, `Path=/`, 12 h TTL, in-memory
   (restart signs everyone out). The cookie authorizes the dashboard only;
   API endpoints never accept it. Conversely, a Bearer header works on `/` too.
3. **Android — the same token.** The app stores it in `ServerConfig` and sends
   `Authorization: Bearer` on HTTP, SSE, and file transfers.

Clipboard payloads are additionally encrypted end-to-end with **AES-256-GCM**
(`CLIPBOARD_AES_KEY`, wire format `AES:<base64(IV‖ciphertext+tag)>`), shared
between desktop and app but invisible to any middlebox.

The token is never logged, never returned by any endpoint, and never placed in URLs.

---

## Node API

The `/api/*` endpoints are read-only. The browser dashboard also provides
session-authenticated, CSRF-protected local service controls on the Nodes page.

| Endpoint | Auth | Returns |
|---|---|---|
| `GET /api/health` | public | `{"status":"ok","service":"earthQuack-node","version":"0.1.0"}` |
| `GET /api/node` | Bearer | This node: identity, hostname, OS, capabilities, services (+status), network, `system`, `storage`, `network_stats` |
| `GET /api/nodes` | Bearer | Local node + all discovered peers |
| `GET /` | session or Bearer | Human dashboard |
| `POST /services/control` | — | Local managed-service Start/Stop (browser session + CSRF) |
| `GET /login`, `POST /login`, `POST /logout` | — | Browser session management |

### Telemetry

Telemetry snapshots are measured **at read time** and attached only to the
local node's own entry — a node reports *itself*, never guesses at peers.

* **`system`** — CPU count, memory, uptime, load average, plus human distro
  name and arch (from `/proc` and `/etc/os-release` on Linux).
* **`storage`** — mounted filesystems from `/proc/self/mounts` + `statfs(2)`:
  per mount `total`/`used`/`available` bytes and a df-style `usage_percent`
  (`(total−available)/total`). Pseudo-filesystems (`proc`, `tmpfs`, `cgroup2`,
  `overlay`, …) are filtered **by filesystem type**, never by mount path;
  statfs failures and zero-capacity mounts are skipped rather than
  fabricated; entries are sorted by mount point. Linux-only — other
  platforms omit the field (`omitempty`).
* **`network_stats`** — network interfaces from the standard library's
  `net.Interfaces` merged with per-device traffic counters from
  `/proc/net/dev`: name, MTU, hardware address, current IP addresses,
  and since-boot `rx/tx` byte/packet/error/drop counters (the same
  numbers `ip -s link` shows — lifetime totals, not rates). Only **up**
  interfaces are reported; an up interface with no counter row is
  skipped rather than filled with invented zeros; unreadable sources
  yield an empty snapshot. Linux-only (`omitempty` elsewhere).

  This is deliberately separate from the node's `network` field:
  `network` is **discovery** (how earthQuack reaches the node —
  transport + Tailscale addresses), while `network_stats` is **interface
  telemetry** (what NICs the machine has and what they carry). Tailscale
  discovery is never mixed into interface telemetry.

The dashboard renders system facts as a fact list, storage as
`  ██████░░░░  210.2 / 369.5 GB · 62%` usage bars in a **Storage**
group, and interfaces as one line each in a **Network** group —
`name  addresses  ·  rx 4.1 GB · tx 271.5 MB`, with nonzero
error/drop counters shown when present. Discovered peers show presence
only until they report their own telemetry.

## The Python daemon

`daemon/app.py` is a small supervisor: it runs every component in guarded
threads (restart-on-crash after 5 s), initializes optional hotkeys, and
shuts down cleanly. The Go node starts, restarts, and stops it automatically —
you normally never launch it by hand (but `daemon/run-earthquack.sh` runs it
standalone).

| Module | Role |
|---|---|
| `server.py` (`:8875`) | Clipboard broker: `GET/POST /clipboard`, `GET /events` (SSE fan-out), `POST /signal` (open a URL on the phone), `GET /health` |
| `file_server.py` (`:8876`) | `POST /upload` (raw body, `X-Filename`/`X-Origin`), `GET /download/<id>` (Range/206 resumable), `GET /files`. Phone→desktop lands in `~/Downloads/from-phone/`; desktop→phone is staged in `/tmp/cs-files/` and announced over SSE |
| `desktop.py` | Desktop clipboard bridge: polls `wl-paste` (Wayland) / `xclip`/`xsel` (X11) / tkinter (Windows) every 0.5 s and pushes changes; receives the phone's clipboard via SSE |
| `watch_send_folder.py` | Watches a send-folder and pushes new files to the phone |
| `crypto_util.py` | AES-256-GCM (`CLIPBOARD_AES_KEY`) — the `AES:`-prefixed wire format shared with the Android `CryptoUtil` |
| `tailscale_discovery.py` | Finds online tailnet peers running the clipboard server (cached `tailscale status --json`) |
| `hotkey_manager.py` | Global screenshot hotkey (Win32 `Ctrl+Alt+Shift+S`, configurable via `SCREENSHOT_HOTKEY`; stub on Linux where the DE binds `clip-shot.sh` directly) |

`daemon/file-server.py` and `daemon/watch-send-folder.py` are legacy
kebab-case shims delegating to the canonical underscore modules.

### Desktop shell helpers

```sh
clip-send.sh report.pdf            # file(s) → phone's Downloads/ClipboardSync/
clip-open.sh https://example.com   # open a URL on the phone
clip-shot.sh                       # screenshot (grim) → phone gallery
```

They resolve the server address via `tailscale_discovery.py`, falling back
to `tailscale ip -4`. Windows: `win-start.bat` launches the daemon headlessly
and `win-shot.py` is the screenshot path behind the hotkey manager.

`clipsyncd.c`, `clipsyncd-wrapper.sh`, and `clipboard-sync.service` are the
legacy systemd glue from before the Go node became the entry point — kept
for reference, not part of the current flow.

## The wallpaper module

The repository's first capability module lives in `internal/wallpaper` —
an archive layer over the existing wallpaper collection. It scans a
source directory, discovers supported images, resolves each file's
SHA-256 digest (from its local digest cache when unchanged, from an
existing archive record when the path and size already match, and only
otherwise by reading the file), skips anything already archived, groups
files by folder (loose root files → `Unsorted`), and archives the rest
via a provider interface.

```sh
earthquack-node wallpaper status                                   # source/provider/counts/topics/cache/last-sync
earthquack-node wallpaper scan                                     # list discovered files + categories
earthquack-node wallpaper sync                                     # archive new/changed wallpapers
earthquack-node wallpaper sync --dry-run                           # preview; never uploads, topics, or state
earthquack-node wallpaper retry-failed                             # re-archive only files in failed.json
```

Useful overrides: `-source`, `-state`, `-provider` (or
`$EARTHQUACK_WALLPAPER_SOURCE`, `$EARTHQUACK_WALLPAPER_STATE`,
`$EARTHQUACK_WALLPAPER_PROVIDER`).

### Provider abstraction

Only one interface exists — the one this feature needs:

```go
type ArchiveProvider interface {
    Name() string
    Upload(ctx context.Context, file ArchiveFile) error
}
```

`ArchiveFile` carries provider-agnostic terms: source path, filename,
category, digest, size. Telegram-specific concepts such as
`message_thread_id` live inside the Telegram provider only
(`internal/wallpaper`), which owns `topics.json` and all forum-topic
creation/reuse. The wallpaper module never touches them.

Images upload via Telegram `sendDocument` as the original bytes —
streamed in bounded chunks, never resized, recompressed, converted, or
fully loaded into memory. Files larger than the 50 MB Bot API limit fail
validation without an upload attempt.

### Retry/backoff

Transient failures (network/TLS resets, HTTP 408/429, Telegram 5xx) retry
with exponential backoff (`Base 2s × Factor 2`, up to 5 retries) and
**honour Telegram's `Retry-After`**. Permanent failures (other HTTP 4xx)
fail fast. The policies are pure functions (`Backoff.Delay`, retry
classifiers), so tests cover them without sleeping.

If Telegram reports that a cached forum topic no longer exists
(`message thread not found` / `thread not found` / `topic not found`),
the provider drops the stale mapping, recreates the topic, and re-sends
the file once — the same recovery the Python script performs. If recovery
is impossible the original upload error is what surfaces (and therefore
what `failed.json` records).

### Failed state

A failed upload is recorded in `failed.json` (path → category, thread id,
digest, size, error, time) and **never** added to `uploads.json`.

`wallpaper retry-failed` re-verifies each entry before retrying, exactly
like the Python script: the file must still exist, be a regular image
file, and be within the provider's size limit; its content is re-hashed;
and an entry whose digest is already archived is cleared as resolved
(counted as `Skipped`) instead of being re-uploaded. A successful retry
updates `uploads.json` and removes the failed entry. Entries that cannot
be resolved are preserved and counted as still failing, so state is never
silently lost and the command's exit code reflects outstanding work. All
JSON state writes are atomic (temp + fsync + rename).

### Existing archives

The module reads the existing `uploads.json` verbatim and models **both**
record shapes the Python script has produced:

```json
{"file": "...", "filename": "...", "topic": "Unsorted", "thread_id": 8, "size": 321177}
{"path": "...", "filename": "...", "topic": "Wallhaven", "size": 690476,
 "mtime_ns": 1789552342111352324, "message_id": 538, "uploaded_at": 1789553881}
```

Keys are SHA-256 digests, so de-duplication is always content-addressed.
Records written by the module use the current `path`-based shape; legacy
records are written back with every field they carried, so neither tool
loses data written by the other. Point `state_dir`/env at an existing
directory to adopt it: the first sync recognises all archived files and
uploads nothing.

Deliberate differences from the Python script:

- New records carry `path`, `filename`, `topic`, `size`, `mtime_ns` and
  `uploaded_at`. `thread_id` is not written per file (the provider keeps
  category → thread-id in `topics.json`) and `message_id` is not produced
  at all — neither is ever used for de-duplication, and existing values
  are preserved.
- `sync --dry-run` writes nothing, including `files.json` (the Python
  script refreshes its index even on a dry run).
- `retry-failed` counts unresolvable entries (deleted file, unsupported
  type, over the size limit) as still failing, matching the Python
  script's non-zero exit; their `failed.json` entries are preserved.

### Digest cache (`files.json`)

The updated Python script keeps a local file index in `files.json`
(`resolved path → {size, mtime_ns, sha256}`) so unchanged files are never
re-read. The module reads and writes that same file, and each scan
resolves digests in order of increasing cost:

1. the local index (path + size + mtime match) — no read at all;
2. an existing archive record for the same path and size — no read
   (this is what lets an adopted archive avoid re-hashing itself);
3. otherwise SHA-256 the file once, streaming.

Consequences worth knowing:

- `status` and `sync --dry-run` **only read** the index; they never write
  it. `sync` refreshes it after scanning, and `retry-failed` refreshes the
  entries it resolves.
- Pruning is scoped to the scanned source root, so a shared state
  directory (this archive caches two source trees) never loses the other
  tree's cached digests.
- The index is advisory: deleting it costs CPU, never correctness —
  archived/pending decisions always come from `uploads.json`.
- `status` reports `Digest cache hits` / `Files hashed` so the cost of a
  run is visible. On this archive (438 files) a status run is fully
  cache-served: 438 hits, 0 files hashed.

### State locations (defaults shown)

```text
~/Pictures/Wallpapers                            source (config: source)
~/.local/share/earthquack/wallpaper              state  (config: state_dir)
  uploads.json                                   already archived, keyed by SHA-256
  failed.json                                    files to retry, keyed by source path
  topics.json                                    provider state: category → thread id
  files.json                                     local digest cache (shared with the Python script)
  sync.json                                      last sync timestamp
```

`sync.json` is this module's own additive file; the Python script ignores
it. The other four are shared with `wallpaper-backup.py`.

## The Android app

Kotlin, `app/` (package `com.example.earthquack`, Gradle project
`ClipboardSync`, minSdk 26 / target & compile SDK 34):

* **`EarthQuackService`** — foreground service running the sync loop:
  clipboard push/poll + SSE receive with exponential-backoff reconnect,
  file-ready handling, and battery saver (auto-pause on screen-off, throttled
  polling, manual pause/resume, resume on unlock).
* **`ClipboardImeService`** — optional input method that syncs the clipboard
  while you type. Android 10+ blocks background clipboard reads, so the IME
  plus a 1.5 s foreground poll cover the gaps.
* **`FileTransferService` / `FileShareActivity`** — uploads via the share
  sheet (they land on the desktop in `~/Downloads/from-phone/`) and streaming
  downloads (256 KB chunks, MediaStore, progress notifications).
* **`ServerConfig`** — SharedPreferences for host, port, and auth token.
* **`SyncTileService` / `MainActivity` / `StopReceiver`** — quick-settings
  toggle, status UI, and stop controls.
* **`CryptoUtil` / `TailscaleDiscovery`** — mirror the Python AES wire format
  and peer discovery.

Permissions: notifications (Android 13+), clipboard access via IME/focus,
file saving to `Downloads/ClipboardSync`.

## Project layout

```text
cmd/earthquack-node/    Go entry point (flags, env, wiring, lifecycle)
internal/node/          Node model, registry, Tailscale provider, peer client,
                        auth (bearer + browser sessions), API, dashboard,
                        service refresher, daemon supervisor, telemetry
                        (system*.go, storage*.go, netstats*.go) + tests
web/                    Embedded dashboard template + stylesheet
daemon/                 Python sync services (see table above)
app/                    Android app (Kotlin)
examples/               arch.json / homeserver.json / vps.json declarations
docs/NODE.md            Node deployment guide (build → config → token → run)
clip-*.sh, win-*        Desktop helper scripts
```

## Testing

```sh
go test ./...            # node unit + integration tests (API, auth, registry,
go test -race ./...      # telemetry, daemon supervision, deploy checks)
go vet ./...

python3 daemon/test_tailscale_discovery.py
python3 daemon/test_hotkey_manager.py

./gradlew :app:test      # Android unit tests
```

## Security notes

* **Token hygiene for wallpaper → Telegram:** the bot token lives only in
  `TELEGRAM_BOT_TOKEN` (or config-free environments), and `chat_id` in
  `TELEGRAM_CHAT_ID`. Neither may ever appear in source code, tests,
  `config.json`, README, logs, dashboard HTML, or repo history. Capitals:
  never print the bot token. `TelegramError` builds transport and
  Telegram errors that describe the failure without embedding the token.
  Logs and CLI output describe outcomes (`✓`, `✗ FAILED`, counts) — not
  secrets.
* Fail-closed auth everywhere: no token ⇒ 503s, no login page, no dashboard.
* Constant-time token comparison; tokens never logged, echoed, or put in URLs.
* Sessions are in-memory, 12 h; the cookie carries no secret (`SameSite=Strict`).
* Config files cannot fabricate identity, network state, or runtime status.
* **Nodes → Managed services** provides Start/Stop for the local shared
  clipboard/file-transfer daemon and Start sync/Stop for configured wallpaper
  jobs. The dashboard stays online. Remote and monitor-only services remain
  read-only; changes are runtime-only. Wallpaper Stop cancels only its dashboard
  job, not an external CLI run. Do not run both against the same archive state.
* `POST /services/control` requires a browser session and session-bound CSRF
  token; bearer-only clients cannot mutate service state. See
  [managed-service behavior](docs/NODE.md#managed-services-in-the-frontend).
* Clipboard payloads are AES-256-GCM encrypted end-to-end when a key is set.
* Bind the node to the tailnet IP (`--host "$(tailscale ip -4)"`) so nothing
  is exposed on your LAN — binding is transport hygiene, the token is the gate.





