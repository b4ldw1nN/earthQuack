# earthQuack

earthQuack is a personal multi-device platform: bidirectional **clipboard + file + URL sync**
between a desktop (Linux/Windows) and an Android phone over **Tailscale**, unified behind a
**Go node** that turns every machine into a first-class, observable member of your tailnet —
with a multi-node dashboard, read-only system/storage telemetry, and strict authentication.

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
│  earthquakes-node (Go, :8890)      │      │  EarthQuackService       │
│  ├─ Node API + dashboard           │      │  ├─ clipboard sync (SSE) │
│  ├─ system/storage telemetry       │      │  ├─ file transfer        │
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
| **Go node** | `cmd/earthquack-node`, `internal/node` | Node API, dashboard, auth, identity, peer discovery/probing, telemetry, daemon supervision. Stdlib only, Go ≥ 1.24, no external dependencies. |
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

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `EARTHQUACK_AUTH_TOKEN` | *(none)* | Shared bearer token. **Required** — without it the node fails closed (see Auth). Wins over `auth.token` in the config file. |
| `CLIPBOARD_AES_KEY` | *(none)* | Base64 32-byte AES-256-GCM key for clipboard payloads. Must match the Android app. Unset ⇒ clipboard travels unencrypted. |
| `EARTHQUACK_HOST` | `127.0.0.1` | Bind host for the Python sync services. |
| `EARTHQUACK_PORT` / `EARTHQUACK_FILE_PORT` | `8875` / `8876` | Ports for clipboard / file transfer. |
| `EARTHQUACK_NODE_HOST` / `EARTHQUACK_NODE_PORT` | `0.0.0.0` / `8890` | Defaults for the node's `--host` / `--port` flags. |
| `EARTHQUACK_NODE_CONFIG` | *(none)* | Default `--config` path. |
| `EARTHQUACK_REPO` | `.` | Repo root, so the node can find `daemon/app.py`. |
| `EARTHQUACK_SECURE_COOKIE` | *(off)* | `1` adds the `Secure` flag to session cookies (enable once the dashboard is behind TLS). |

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
illustrate other declaration sets (storage, docker, reverse-proxy).

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

All endpoints are read-only.

| Endpoint | Auth | Returns |
|---|---|---|
| `GET /api/health` | public | `{"status":"ok","service":"earthQuack-node","version":"0.1.0"}` |
| `GET /api/node` | Bearer | This node: identity, hostname, OS, capabilities, services (+status), network, `system`, `storage` |
| `GET /api/nodes` | Bearer | Local node + all discovered peers |
| `GET /` | session or Bearer | Human dashboard |
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

The dashboard renders both under each node card: system facts as a fact
list, storage as `  ██████░░░░  210.2 / 369.5 GB · 62%` usage bars in a
**Storage** group. Discovered peers show presence only until they report
their own telemetry.

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
                        (system*.go, storage*.go) + tests
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

* Fail-closed auth everywhere: no token ⇒ 503s, no login page, no dashboard.
* Constant-time token comparison; tokens never logged, echoed, or put in URLs.
* Sessions are in-memory, 12 h; the cookie carries no secret (`SameSite=Strict`).
* Config files cannot fabricate identity, network state, or runtime status.
* The dashboard is read-only; there are no state-changing endpoints.
* Clipboard payloads are AES-256-GCM encrypted end-to-end when a key is set.
* Bind the node to the tailnet IP (`--host "$(tailscale ip -4)"`) so nothing
  is exposed on your LAN — binding is transport hygiene, the token is the gate.





