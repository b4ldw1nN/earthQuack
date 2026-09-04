# Clipboard Sync — Complete Documentation

Bidirectional clipboard + file + URL sync between **Desktop (Linux)** and **Android device (Android 8+)** over **Tailscale**. Clipboard via SSE, files via `/upload`/`/download`, URLs via `/signal`.

> Default Tailscale host `YOUR_TAILSCALE_IP` is now **configurable** everywhere — Android `ServerConfig`, Desktop env `CLIPBOARD_SERVER_HOST` / flags, shell helpers. No recompilation needed.

---

## Table of Contents

1. [Topology](#topology)
2. [High-level Architecture](#high-level-architecture)
3. [Network Protocol](#network-protocol)
4. [Desktop Side — Python Daemon](#arch-side--python-daemon)
   - [app.py](#apppy--single-process-daemon)
   - [server.py](#serverpy--clipboard-sse-broker--8765)
   - [file_server.py](#file_serverpy--file-transfer--8766)
   - [arch.py](#archpy--wayland-clipboard-bridge)
   - [watch_send_folder.py](#watch_send_folderpy--send-to-vivo-folder)
   - [clipsyncd + systemd](#clipsyncd--systemd)
   - [HotkeyManager & Windows Global Hotkey](#hotkeymanager--windows-global-hotkey)
   - [Shell helpers](#shell-helpers)
5. [Android Side — Kotlin App](#android-side--kotlin-app)
   - [Constants / ServerConfig / SyncStatus / SyncState](#constants--serverconfig--syncstatus--syncstate)
   - [ClipboardApi](#clipboardapi)
   - [ClipboardSyncService](#clipboardsyncservice--foreground-service)
   - [ClipboardImeService](#clipboardimeservice--input-method)
   - [FileTransferService + FileShareActivity](#filetransferservice--fileshareactivity)
   - [MainActivity / StopReceiver / SyncTileService](#mainactivity--stopreceiver--synctileservice)
   - [Permissions & Manifest](#permissions--manifest)
6. [Data Flows (end-to-end)](#data-flows-end-to-end)
7. [Configuration](#configuration)
8. [Battery Saver](#battery-saver)
9. [Android Clipboard Restriction](#android-clipboard-restriction)
10. [Build & Install](#build--install)
11. [Usage](#usage)
12. [File Transfer Details](#file-transfer-details)
13. [Wayland / Hyprland Notes](#wayland--hyprland-notes)
14. [Security & Stealth](#security--stealth)
15. [Testing](#testing)
16. [Project Structure](#project-structure)
17. [Foreground Service Rationale](#foreground-service-rationale)
18. [Troubleshooting](#troubleshooting)
19. [Future / Auth Hook](#future--auth-hook)

---

## Topology

```
Tailscale (YOUR_TAILSCALE_IP:8765 / :8766)
┌─────────────────────────────────────────────────────────────────────┐
│  Desktop (Linux, Hyprland/Wayland)                                    │
│                                                                     │
│  clipsyncd (ELF, prctl PR_SET_NAME)  ──execv→  app.py               │
│  └─ Threads inside single process (app.py:76):                      │
│     ├─ server.py         :8765  clipboard broker + SSE fan-out      │
│     ├─ file_server.py    :8766  file staging + /signal → server.py │
│     ├─ arch.py           wl-paste poll + SSE client ↔ wl-copy       │
│     └─ watch_send_folder.py  ~/send-to-vivo/ → clip-send.sh         │
│                                                                     │
│  Shell: clip-send.sh → file_server /upload  (arch→vivo)             │
│         clip-open.sh → server   /signal open_url  (arch→vivo)       │
│         clip-shot.sh → grim → clip-send.sh                          │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Tailscale encrypted (WireGuard)
┌──────────────────────────────┴──────────────────────────────────────┐
│  Android device (Android 15, API 34)                                     │
│  app/ (Gradle, Kotlin, OkHttp, Coroutines, Lifecycle)               │
│  ├─ ClipboardSyncService  FGS specialUse : SSE + monitor + folder   │
│  ├─ ClipboardImeService   InputMethodService background poll         │
│  ├─ FileTransferService   /upload (share sheet / folder) + /download│
│  ├─ FileShareActivity     Share sheet SEND/SEND_MULTIPLE             │
│  ├─ MainActivity          Server IP editor + battery toggles         │
│  ├─ SyncTileService       Quick Settings tile                        │
│  └─ StopReceiver          notification Pause/Resume/Stop             │
└─────────────────────────────────────────────────────────────────────┘
```

All HTTP is plaintext over Tailscale WireGuard **+ optional AES-256-GCM end-to-end** (`AES:Base64(IV||ct+tag)`). With AES enabled, server only sees encrypted blobs. Tailscale tailnet not exposed to LAN.

---

## High-level Architecture

```
                    ┌─────────────┐
  wl-paste poll 0.5s │   arch.py    │  SSE GET /events  (long-lived)
  ──────────────────►│  push_loop   │──────────────────┐
                    │  sse_loop    │◄─────────────────┤
                    └──────┬───────┘                   │
                           │ POST /clipboard           │
                    ┌──────▼───────┐   SSE fan-out     │
                    │  server.py   │──────────────────┤
                    │  :8765 GET/  │   : keepalive     │
                    │  POST /events│   every 15s       │
                    │  POST /signal│                   │
                    └──────┬───────┘  POST /signal     │
                           │           (file_ready,    │
                    ┌──────▼───────┐   open_url)       │
                    │file_server.py│──────────────────┤
                    │  :8766       │                   │
                    │  /upload     │  GET /download/<id>
                    │  /download   │                   │
                    └──────┬───────┘                   │
                           └───────────────────────────┘
                                       ▲         │
                          Android      │         ▼
                    ┌──────────────────┴─────────────────────┐
                    │   ClipboardSyncService (FGS)           │
                    │  sseLoop ──► handleSseEvent →         │
                    │              clipboardManager.setClip   │
                    │              handleFileReady →          │
                    │              FileTransferService        │
                    │  monitorLoop ──► handleLocalClipboard  │
                    │                → ClipboardApi.post     │
                    │  folderWatch ──► FileTransferService   │
                    │                startUpload              │
                    └────────────────────────────────────────┘
```

---

## Network Protocol

### Endpoints

| Host | Method | Path | Body / Query | Response | Notes |
|------|--------|------|--------------|----------|-------|
| `:8765` | `GET` | `/clipboard` | — | `{"clipboard":str,"origin":str,"version":int}` (`server.py:23`) | Current value |
| `:8765` | `POST` | `/clipboard` | `{"clipboard":str,"origin":"desktop"\|"phone"}` | `{"version":int}` | Only bumps `version` on change (`server.py:104`), fans out `event: clipboard` to all `clients` |
| `:8765` | `GET` | `/events` | `Accept: text/event-stream` | SSE stream (`server.py:35`) | `clients.append(wfile)`, `: keepalive\n\n` every 15 s, remove on `BrokenPipe` |
| `:8765` | `POST` | `/signal` | `{"type":str, ...}` | `{"ok":true}` (`server.py:65`) | Generic fan-out used by file server (`type=file_ready`, `open_url`) |
| `:8766` | `POST` | `/upload` | Raw bytes + `X-Filename`/`X-Origin` | `{"id":str,"name":str,"size":int}` (`file_server.py:128`) | `vivo`→ save to `~/Downloads/from-phone/` + `notify-send`; else stage to `/tmp/cs-files/<id>` + `signal_sse(file_ready)` |
| `:8766` | `GET` | `/download/<id>` | `Range: bytes=<n>-` optional | `application/octet-stream` + `Content-Disposition: attachment` | Supports `206 Partial Content` + `Accept-Ranges` (`file_server.py:88`), chunk 256 KB |
| `:8766` | `GET` | `/files` | — | `[{id,name,size,path,ts}]` | List staged arch→vivo pending |

### SSE Events (`text/event-stream`)

```
event: clipboard
data: {"clipboard":"…","origin":"desktop","version":7}
# with AES enabled: {"clipboard":"AES:Base64(IV||ct+tag)","origin":"desktop","version":7}

event: file_ready
data: {"type":"file_ready","id":"2e6e…","name":"shot.png","size":611989}

event: open_url
data: {"type":"open_url","url":"https://…"}

: keepalive
```

- Clipboard `clipboard` value is `AES:<Base64>` when `ServerConfig.isAesEnabled` / `CLIPBOARD_AES_KEY` set; otherwise plaintext. Server stores blob as-is; clients `CryptoUtil.unwrapIfNeeded` / `crypto_util.unwrap` decrypt to plain. Without same 32B Base64 key on both sides, encrypted blobs are not readable (Desktop shows `AES:...` raw).
- Clipboard events filtered: Android applies only `origin == ORIGIN_DESKTOP` (`ClipboardSyncService.kt:305`), Desktop applies only `origin == "phone" (phone)` (`arch.py:111`). Version dedup via `SyncState` / `last_received` (plain after decrypt, fix `67ad4aa` prevents echo loop).
- `file_ready` triggers `FileTransferService.startDownload()` (`ClipboardSyncService.kt:328`).
- `open_url` triggers `Intent.ACTION_VIEW` on Android / `xdg-open` on Desktop.

### Threading / concurrency

- `server.py`: `ThreadingHTTPServer` — each request in its own thread; `clients` list guarded by `clients_lock` + `clients.copy()` snapshot on fan-out (`server.py:17`).
- `file_server.py`: same `ThreadingHTTPServer`, `staged` dict guarded by `threading.Lock()`.
- `arch.py`: two threads — `push_loop` (poll `get_clipboard()` every 0.5 s, `crypto_util.wrap` if `CLIPBOARD_AES_KEY` set) and `sse_loop` (blocking `urlopen` with backoff 2→60 s, `crypto_util.unwrap` on `vivo` clipboard).
- `crypto_util.py`: `cryptography` `AESGCM` or `pycryptodome` `AES.MODE_GCM` fallback, 12B IV, `Base64(IV||ct+tag)`.
- Android: `lifecycleScope` coroutines — `sseJob` on `Dispatchers.IO`, `monitorJob` on `Main`, `folderWatchJob` on `IO`; `SyncState` uses `Mutex`; `CryptoUtil.kt` `AES/GCM/NoPadding` 32B key.

---

## Desktop Side — Python Daemon

### `app.py` — single-process daemon

Stealth single-process wrapper (`app.py:1`). Spawns 4 daemon threads (`app.py:76`): `run_server` (`server.main()`), `run_file_server` (`file_server.main()`), `run_arch` (`arch.main()`), `run_watch_folder` (`watch_send_folder.main()`). Main thread sleeps 10 s loop.

Process masking (`app.py:22`): `ctypes.CDLL("libc.so.6").prctl(PR_SET_NAME, "systemd-user-service")` + optional `setproctitle.setproctitle`. All threads renamed. So `ps/htop` shows `systemd-user-service`, not `python`.

Imported via `BASE = Path(__file__).parent; sys.path.insert(0, str(BASE))` (`app.py:47`).

### `server.py` — clipboard SSE broker :8765

- **Config** (`server.py:9`): `DEFAULT_HOST="YOUR_TAILSCALE_IP"`, `HOST=os.environ.get("CLIPBOARD_SERVER_HOST", DEFAULT_HOST)`, `PORT=int(os.environ.get("CLIPBOARD_SERVER_PORT","8765"))`, argparse `--host`/`--port` override. `HOST:PORT` is bind address (Tailscale IP so reachable via tailnet only).
- **State** (`server.py:13`): `clipboard=""`, `origin=""`, `version=0`, `clients=[]`.
- **GET /clipboard**: JSON snapshot.
- **GET /events**: `200` + `Content-Type: text/event-stream`, appends `wfile` to `clients`, loops `wfile.write(b": keepalive\n\n"); flush(); sleep(15)` until disconnect, then removes. No per-event buffer — `POST` pushes directly to all `clients`.
- **POST /clipboard**: dedup (`server.py:104` only if `new_clipboard != clipboard or new_origin != origin`), increments `version`, prints, fans out `event: clipboard` with full JSON to each client; `BrokenPipe` removes dead clients. Always returns `{"version": version}`.
- **POST /signal**: used by `file_server.py:48` `signal_sse()` to broadcast `file_ready`/`open_url` etc. Generic: `event_type = body.get("type","signal")`.
- `log_message` suppressed.

### `file_server.py` — file transfer :8766

- **Config** (`file_server.py:27`): `DEFAULT_HOST`, `HOST=os.environ.get("CLIPBOARD_SERVER_HOST",…)`, `PORT=int(os.environ.get("CLIPBOARD_FILE_PORT","8766"))`, `SIGNAL_URL=f"http://{HOST}:{os.environ.get('CLIPBOARD_SERVER_PORT','8765')}/signal"`; argparse `--host/--port/--signal-port` overrides and rewrites `SIGNAL_URL`. `PHONE_SAVE_DIR=~/Downloads/from-phone/`, `STAGE_DIR=/tmp/cs-files/`, `CHUNK=256*1024`.
- **signal_sse** (`file_server.py:44`): `POST SIGNAL_URL` with JSON payload, timeout 3 s.
- **GET /files**: returns `staged` values.
- **GET /download/<id>**: lookup `staged[fid]`, `410 Gone` if file gone, `206` with `Content-Range` if `Range: bytes=` present, streams file in `CHUNK` loops.
- **POST /upload** (`file_server.py:128`):
  - `vivo` origin: save to `PHONE_SAVE_DIR/name` (dedup with `_hex` suffix), `_stream_to_file()`, `notify-send` not wired? actually `desktop_notify` not called here? — print only.
  - Else (arch): `uuid`, save to `STAGE_DIR/fid`, insert `staged[fid]`, `signal_sse({"type":"file_ready","id":fid,"name":name,"size":written})`, return JSON.
  - `_stream_to_file` (`file_server.py:184`): reads `min(CHUNK, remaining)` until `remaining==0`.
- Binds to Tailscale IP, not `0.0.0.0`.

### `arch.py` — Wayland clipboard bridge

- **Config** (`arch.py:17`): `DEFAULT_HOST`, `SERVER_HOST=os.environ.get(...)`, `SERVER_PORT=os.environ.get(...)`, `SERVER_URL=f"http://{SERVER_HOST}:{SERVER_PORT}"`, derived `POST_URL`, `EVENTS_URL`, plus `crypto_util` import (`sys.path parent`). `CLIPBOARD_AES_KEY` (44-char Base64 32B) + `CLIPBOARD_AES_ENABLED=1` env. `argparse --host/--port` recomputes globals; thread prints `[arch] Using server: …`.
- **get_clipboard** (`arch.py:31`): tries `wl-paste --no-newline`, `wl-paste --primary --no-newline`, `xclip -selection clipboard -o`, `xsel -b -o` — first success with stdout.
- **push_loop** (`arch.py:47`): every `0.5 s`, `get_clipboard()`, skip if `text == last_sent or text == last_received` (lock), else `send_text = crypto_util.wrap(text) if crypto_util else text` → `POST /clipboard {"clipboard":send_text,"origin":"desktop"}` (logs plain, server stores `AES:...` if enabled), update `last_sent` plain.
- **set_clipboard** (`arch.py:74`): tries `wl-copy`, `xclip -selection clipboard`, `xsel -b -i`.
- **sse_loop** (`arch.py:86`): `urlopen EVENTS_URL (timeout=None)`, parses line protocol: `:` keepalive, `event:` / `data:` buffered, on blank line dispatches `clipboard` (only `origin=="phone"` and text) → `plain = crypto_util.unwrap(text) if crypto_util else text` → dedup plain, `text=plain`, URL auto `xdg-open`, updates `last_received` plain, `set_clipboard` plain; also `open_url` → `xdg-open`. Backoff 2→60 s on exception.

### `watch_send_folder.py` — send-to-vivo folder

- `WATCH_DIR=~/send-to-vivo` (`watch_send_folder.py:12`), `SEND_SCRIPT=clip-send.sh`.
- Polls `WATCH_DIR.iterdir()` every `0.3 s`, diffs `seen` set, for new files runs `bash clip-send.sh <file>` and `unlink()` on success. Used by daemon thread.

### `clipsyncd` + systemd

- `clipsyncd.c:6` C wrapper: `prctl(PR_SET_NAME, "clipsyncd")`, builds `app_path = "$HOME/clipboard-sync/app.py"`, `execv("/usr/bin/python3", ["/usr/bin/python3", app_path, NULL])`. Keeps `argv[0]` as python so `sys.executable` valid. Binary is PIE for stealth.
- `clipboard-sync.service:6` systemd user service: `Description=Clipboard Sync Daemon`, `After=network-online.target tailscaled.service`, `Wants=network-online.target`, `Type=simple`, `ExecStart=%h/clipboard-sync/clipsyncd`, `Restart=always`, `RestartSec=5s`, `WantedBy=default.target`. Enable: `systemctl --user enable --now clipboard-sync.service`. Logs via `journalctl --user -u clipboard-sync.service`.

### Shell helpers

- **clip-shot.sh** (`clip-shot.sh:6`): `grim` → `maim` → `import` fallback, saves `/tmp/shot_*.png`, calls `clip-send.sh`, removes temp. Full-screen capture (no selection prompt).

### HotkeyManager & Windows Global Hotkey

- **HotkeyManager** (`daemon/hotkey_manager.py`): Platform-independent global hotkey manager.
  - **Windows**: Uses native Win32 `RegisterHotKey()` and `UnregisterHotKey()` APIs with thread `GetMessageW` loop. Registering is non-blocking to keyboard input and does NOT require window focus or low-level keyboard hooks.
  - **Linux**: DE keybindings (e.g. Hyprland/i3) invoke `clip-shot.sh` directly. `HotkeyManager` operates as a clean no-op stub on Linux.
  - **Configuration**: `SCREENSHOT_HOTKEY` environment variable (default: `Ctrl+Alt+Shift+S`).
  - **Reserved Shortcuts**: `Win+Shift+S` is avoided because Windows/Snipping Tool owns it exclusively.
  - **Cross-Platform Safety**: Win32 calls are dynamically loaded (`ctypes.windll.user32`) only when `sys.platform == "win32"`. Allows cross-building and running on Arch Linux without DLL dependencies.

---

## Android Side — Kotlin App

- **Package** `com.example.clipboardsync` (`app/build.gradle.kts` `namespace`, `compileSdk=34`, `minSdk=26`, `targetSdk=34`). Deps: `core.ktx`, `appcompat`, `material`, `lifecycle.service`, `kotlinx.coroutines.android`, `okhttp`.
- **Namespace uses API 34 foreground service types**, `specialUse` for clipboard (see rationale).

### `Constants` / `ServerConfig` / `SyncStatus` / `SyncState` / `CryptoUtil`

- **Constants.kt** (`Constants.kt:7`): `SERVER_HOST="YOUR_TAILSCALE_IP"` fallback, `SERVER_PORT=8765`, deprecated `SERVER_BASE_URL`, `FILE_SERVER_PORT=8766` deprecated, `ORIGIN_PHONE="phone"`, `ORIGIN_DESKTOP="desktop"`, notification `clipboard_sync_channel` / `1001`, actions `ACTION_START_SYNC/STOP_SYNC/ACTION_STATUS_UPDATE`, extras `EXTRA_STATUS/LAST_SYNC`, timeouts `HTTP_CONNECT/WRITE 10s`, `HTTP_READ 15s`, `SSE_READ 0 (infinite)`, poll `CLIPBOARD_POLL_INTERVAL_MS=1500`, SSE backoff `2000 → 60000 ×2`, battery `ACTION_PAUSE_SYNC/RESUME_SYNC`, `SCREEN_OFF_IDLE_DELAY 30s`, `CLIPBOARD_POLL_THROTTLED 30s`.
- **ServerConfig.kt** (`ServerConfig.kt:15`): `object ServerConfig`, `PREFS_NAME="clipboard_sync_prefs"`, keys `server_host`, `battery_saver_enabled` (default `true`), `pause_on_screen_off` (default `false` since `df8e3b2` — keep active unless Pause), `aes_enabled` (default `false`), `aes_key_b64` (44-char Base64 32B), `DEFAULT_HOST`. Methods `getHost/setHost`, `getBaseUrl/getFileBaseUrl`, `isBatterySaverEnabled/set`, `isPauseOnScreenOff/set`, `isAesEnabled/set`, `getAesKey/set`, `hasValidAesKey`. Used by all network clients.
- **CryptoUtil.kt** (`CryptoUtil.kt:10`): `object CryptoUtil`, `AES/GCM/NoPadding` 12B IV, `GCM_TAG 128`, `PREFIX AES:`, `generateKeyBase64()` 32B `SecureRandom`, `isValidKeyBase64`, `encrypt/decrypt` via `SecretKeySpec` + `GCMParameterSpec`, `wrapIfNeeded/unwrapIfNeeded` (no SHA, pure AES).
- **SyncStatus.kt** (`SyncStatus.kt:6`): `enum {STOPPED, CONNECTING, RUNNING, PAUSED, ERROR}` — `PAUSED` added for battery/manual pause.
- **SyncState.kt** (`SyncState.kt:26`): `Mutex`-protected `lastLocalText`, `lastRemoteText`, `lastServerVersion=-1`. `tryClaimLocalChange(text)` returns false if blank / same as lastLocal / same as lastRemote (echo), else records. `tryClaimRemoteEvent(text, version)` returns false if `version <= last` or same as `lastRemote`, else records. With AES, callers decrypt before claiming so plain is compared (fix `67ad4aa`).

### `ClipboardApi`

- `class ClipboardApi(private val baseUrl: String)` (`ClipboardApi.kt:24`), secondary ctor `() : this(SERVER_BASE_URL)` for back-compat.
- Two clients: `httpClient` (10/15/10 s, `retryOnConnectionFailure true`) and `sseClient` (0 read timeout). `postClipboard(text)` builds `{"clipboard":text,"origin":ORIGIN_PHONE}` → `POST $baseUrl/clipboard` blocking, returns `version` or `-1`. `buildSseRequest()` → `GET $baseUrl/events` with `Accept: text/event-stream` + `Cache-Control: no-cache`. Auth hook commented `Authorization: Bearer`.

### `ClipboardSyncService` — foreground service

~710 lines, core of app. Light theme `Theme.Material3.Light.NoActionBar` (`themes.xml:3`) + compact `activity_main.xml` (12dp paddings, 8dp margins, 42dp buttons, no-scroll on the phone 6.64").

- **Fields** (`ClipboardSyncService.kt:63`): `clipboardManager`, `notificationManager`, `windowManager`, `powerManager`, `api: ClipboardApi(ServerConfig.getBaseUrl(this))`, `syncState`, 10 s wakeLock, jobs `sseJob/monitorJob/folderWatchJob`, volatile `isManuallyPaused`, `isScreenOn=powerManager.isInteractive`, `currentSseCall: Call?`.
- **screenReceiver** (`:80`): handles `ACTION_SCREEN_OFF` → `isScreenOn=false`, if battery enabled+pause → `PAUSED` notification/broadcast, `currentSseCall?.cancel()`, `sseJob?.cancel()` then relaunch `sseLoop()` (sleeps 15 s in paused check). `SCREEN_ON` → `isScreenOn=true`, `CONNECTING` broadcast, cancel+relaunch `sseLoop` for instant reconnect. Default `pause_on_screen_off=false` (since `df8e3b2`) so screen-off only throttles unless user enables pause.
- **clipChangedListener** (`:115`): checks `isSyncPaused()` early (log ignored), then launches `Dispatchers.Main` → double-check pause, `readClipboardWithOverlayFallback()`, then `handleLocalClipboard(text)` on `IO`.
- **Lifecycle** (`:138`): `onCreate` init managers, `api`, `syncState`, `isScreenOn`, `createNotificationChannel`, `registerScreenReceiver` (`IntentFilter` `SCREEN_ON/OFF` — must be dynamic). `onStartCommand` handles `STOP_SYNC→stopSelf`, `PAUSE→isManuallyPaused=true + cancel SSE + PAUSED`, `RESUME→false + CONNECTING + restart sseLoop`, `null intent` (START_STICKY restart) treated as plain start, else re-inits `api` with current host (shutdown old), refresh `isScreenOn`, logs host, `startForegroundWithNotification` (`PAUSED` if paused else `CONNECTING`), `startCoroutines`. `onDestroy` unregisters receiver, cancels jobs, removes listener, `releaseWakeLock`, `api.shutdown`, broadcasts `STOPPED`.
- **Wake lock** (`:191`): `acquireWakeLock` only if not held, 10 s timeout; `releaseWakeLock`. `isSyncPaused()` (`:203`) `if manuallyPaused return true; if !batterySaverEnabled return false; if !pauseOnScreenOff return false; return !isScreenOn && !isInteractive`.
- **Coroutines** (`:212`): `startCoroutines` cancels previous, launches `sseLoop(IO)`, `clipboardMonitorLoop(Main)`, `folderWatchLoop(IO)`.
- **sseLoop** (`:225`): `delayMs` backoff. Loop checks `isSyncPaused()` → `delay(15_000)` cancellable, continue. Else `CONNECTING` broadcast/notification, `connectSse()`, on success reset delay; on `CancellationException` break; else `ERROR` broadcast/notification. Reconnect delay is `15_000` if `!isScreenOn && batteryEnabled` else `delayMs`, with backoff only when screen on or saver disabled.
- **connectSse** (`:259`): `api.sseClient.newCall(buildSseRequest())`, `currentSseCall=call`, `invokeOnCompletion {call.cancel()}`. `call.execute().use { response -> if !isSuccessful throw }`; if `isSyncPaused()` immediately close. Else `RUNNING` broadcast/notification, `BufferedReader` over `byteStream`, parses SSE (`:` keepalive, `event:`, `data:`, blank line dispatches `clipboard/file_ready/open_url`), checks `isSyncPaused()` mid-stream to break, also `ensureActive()`. Finally clears `currentSseCall`.
- **handleSseEvent** (`:346`): decrypt first `CryptoUtil.unwrapIfNeeded(rawText, getAesKey, isAesEnabled)` → plain, `origin==ORIGIN_DESKTOP`, not blank, `tryClaimRemoteEvent(plain, version)` (plain dedup, fix `67ad4aa` prevents AES echo loop), then `Dispatchers.Main` `clipboardManager.setPrimaryClip`, broadcast `RUNNING` with `← text`.
- **handleFileReadyEvent** (`:321`): if paused, drop (log will sync on resume); else `FileTransferService.startDownload(ctx, id, name, size)`.
- **handleOpenUrlEvent** (`:335`): `ACTION_VIEW` intent `FLAG_ACTIVITY_NEW_TASK`.
- **clipboardMonitorLoop** (`:410`): `addPrimaryClipChangedListener`, loop checks `isSyncPaused()` → `delay(15_000)` continue, else `interval = if !isScreenOn && batteryEnabled then 30_000 else 1500`, `delay`, double-check pause, `readClipboardWithOverlayFallback()` (restored `5c557fb` — direct-only broke Phone, overlay every 1.5s needed; throttled 30s on screen-off). `handleLocalClipboard`.
- **Clipboard reading** (`:381`): `readClipboardWithOverlayFallback()` tries `readClipboardText()` (`:360` `primaryClip.getItemAt(0).coerceToText`), if null and `canDrawOverlays` → `suspendCancellableCoroutine` adds `WindowManager.LayoutParams(1,1, TYPE_APPLICATION_OVERLAY, FLAG_NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT, alpha 0.01, FLAG_NOT_FOCUSABLE.inv())` with `object : View` `onWindowFocusChanged` → `readClipboardText` then `removeOverlaySafe`, resume; timeout 400 ms removes overlay and resumes null; `invokeOnCancellation` cleanup. Causes brief system toast `displaying over other apps` every poll — trade for Phone background reliability; throttled when saver.
- **handleLocalClipboard** (`:523`): checks `isSyncPaused` twice, `tryClaimLocalChange(plain)`, `toSend = CryptoUtil.wrapIfNeeded(plain, getAesKey, isAesEnabled)` → `api.postClipboard(toSend)` (logs ` [AES]` if encrypted), broadcast `→ plain` on success, 10 s wake lock.
- **folderWatchLoop** (`:552`): `try { getExternalStoragePublicDirectory } catch null` → disabled if scoped storage null; `try mkdirs catch`; `seenFiles` synchronizedSet, `checkFile` ignores dotfiles, dedup by name, `FileTransferService.startUpload`. `FileObserver` (`:415` create/CLOSE_WRITE/MOVED_TO) with `onEvent` `if paused return` then `checkFile`. `startWatching`, loop polling `listFiles().forEach(checkFile)`, `delay(15_000)` if paused else `1_000` or `30_000` throttled. `finally stopWatching`.
- **Notification** (`:452`): `createNotificationChannel` `IMPORTANCE_LOW`. `buildNotification(status)` host via `ServerConfig.getHost`, intents `MainActivity` (open), `StopReceiver` `ACTION_STOP_SYNC`, `pauseIntent` toggles `ACTION_RESUME/PAUSE` based on `status==PAUSED`. `statusText` depends on `host:port` and paused manual vs screen-off. `NotificationCompat.Builder` ongoing, `onlyAlertOnce`, `ic_menu_share`, 2 actions `Stop` + `Pause/Resume` (icons `ic_media_pause/play`), `FOREGROUND_SERVICE_IMMEDIATE`. `startForegroundWithNotification` uses `SPECIAL_USE` on Q+, else normal. `updateNotification` notifies, `broadcastStatus` sends `ACTION_STATUS_UPDATE` with `EXTRA_STATUS/LAST_SYNC` package-scoped.

### `ClipboardImeService` — input method

`InputMethodService` with zero-size `onCreateInputView` and immediate `switchToPreviousInputMethod` on `onStartInputView` so no visible keyboard. Adds `ClipboardManager.OnPrimaryClipChangedListener` (`ClipboardImeService.kt:28`) and launches poll loop (`:39`) throttled when battery paused (`15 s` else `1.5 s`). `checkAndPostClipboard` (`:66`) checks pause via `PowerManager.isInteractive`, reads `primaryClip`, dedup `lastLocalText`, `serviceScope.launch { postToServer(wrapped) }`. `postToServer` (`:88`) wraps `CryptoUtil.wrapIfNeeded(text, getAesKey, isAesEnabled)` → `HttpURLConnection POST ${ServerConfig.getBaseUrl}/clipboard` with `{"clipboard":toSend,"origin":ORIGIN_PHONE}` 5 s timeout. Requires enabling IME in system settings (button in `MainActivity`).

### `FileTransferService` / `FileShareActivity`

- **FileTransferService** (`FileTransferService.kt:26`): `Service` with `CoroutineScope(SupervisorJob+IO)`, `OkHttp` unlimited read/write for large files. Companion `ACTION_UPLOAD/DOWNLOAD`, extras `FILE_URI/NAME/ID/SIZE`, `NOTIF_CHANNEL file_transfer_channel`, `nextNotifId` counter. `startDownload/startUpload` helpers `startService` with intents. `onStartCommand` dispatches to `upload`/`download` coroutines. `upload` (`:134`): open `File` if `file://` else `contentResolver`, get size, `url=${ServerConfig.getFileBaseUrl}/upload`, `streamingBody(stream,size,nid,name)` → `POST` with `X-Filename`, `X-Origin vivo`, streaming `RequestBody` updates progress `showProgress`; on `200` delete sent file if parent `ClipboardSync-send`. `download` (`:222`): `GET ${getFileBaseUrl}/download/<id>` → `saveToDownloads` via `MediaStore` `RELATIVE_PATH Download/ClipboardSync` + `IS_PENDING` dance on Q+, direct `File` on older, `copyWithProgress` 256 KB. `createChannel`/`showProgress` (indeterminate when 0)/`notifyDone`/`formatSize`/`guessMime`. `onDestroy` cancels scope and shuts dispatcher.
- **FileShareActivity**: transparent `Theme.Translucent.NoTitleBar` with `SEND` + `SEND_MULTIPLE` `*/*` filters; immediately calls `FileTransferService.startUpload` per URI and finishes — appears as `Clipboard Sync` in share sheet.

### `MainActivity` / `StopReceiver` / `SyncTileService`

- **MainActivity** (`MainActivity.kt:19`): `ActivityMainBinding` (compact Material3 Light `Theme.Material3.Light.NoActionBar` `colors.xml` + `bg_header_gradient` hero, 12dp paddings, 8dp margins, 42dp buttons, fits without scroll on 6.64"), `currentStatus`, `serviceRunning`, `notifPermissionLauncher`. `statusReceiver` on `ACTION_STATUS_UPDATE` → `updateUi`. `onCreate` inflates, sets `editServerHost` to `ServerConfig.getHost`, `textServer` to `host:port`, wires `btnSaveServer→saveServerHost`, battery `cbBatterySaver`/`cbPauseOnScreenOff` (second enabled only if first), listeners persist via `ServerConfig`, **Security** `cbAesEnabled`/`editAesKey`/`tilAesKey`/`btnGenKey`/`btnCopyKey` (`CryptoUtil.generateKeyBase64` 32B, copy to `ClipboardManager`, `hasValidAesKey` validation 44-char Base64, auto-generate on enable if empty), `btnToggle` `stopSync/startSync`, `btnPauseResume` `pauseSync/resumeSync`, overlay/IME settings. `onResume` registers receiver (`RECEIVER_NOT_EXPORTED` on TIRAMISU), refresh overlay + `textServer`; `onPause` unregisters.
  - `saveServerHost` (`:86`): validate regex `^[a-zA-Z0-9._-]+$` or IP, no-op if same as old, else `ServerConfig.setHost`, if wasRunning `stopService(ACTION_STOP_SYNC)` then `postDelayed 600 ms` `startForegroundService(ACTION_START_SYNC)` + `CONNECTING`, else toast.
  - `startSync` checks `canDrawOverlays` → toast + overlay settings; checks `POST_NOTIFICATIONS` on TIRAMISU; else `doStartService` (`startForegroundService ACTION_START_SYNC`, `CONNECTING`). `stopSync` → `ACTION_STOP_SYNC`. `pauseSync/resumeSync` → respective actions. `updateUi` maps `SyncStatus` to `Running/Stopped/Connecting/Paused/Error` via `dot_*` drawables + `text_status_sub` host, `btnToggle` icon `ic_play_24`/`ic_media_pause`, `btnPauseResume` visibility/text/icon, lastSync snippet `→ Sent to Desktop` vs `← Received`, max 80 chars, `cardLastSync` visibility.
- **StopReceiver** (`StopReceiver.kt:15`): receives `ACTION_STOP_SYNC`, `ACTION_PAUSE_SYNC`, `ACTION_RESUME_SYNC` from notification → `startService` with same action to `ClipboardSyncService`.
- **SyncTileService** (`SyncTileService.kt:16`): `TileService` `onStartListening` calls `updateTileState` (label `Clipboard Sync`, state inactive fallback, subtitle), `onClick` toggles: if `ACTIVE` → `startService ACTION_STOP_SYNC` + `INACTIVE/ Stopped`; else `startForegroundService ACTION_START_SYNC` (`O`+) + `ACTIVE/Active`, then `updateTile`.
- **CryptoUtil** (`CryptoUtil.kt:10`) see above.

### Permissions & Manifest

`AndroidManifest.xml:4`:
```xml
INTERNET, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SYSTEM_ALERT_WINDOW (1×1 overlay),
WAKE_LOCK (10 s partial), FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE,
POST_NOTIFICATIONS, ACCESS_NETWORK_STATE, READ_EXTERNAL_STORAGE (≤32),
READ_MEDIA_IMAGES/VIDEO/AUDIO, MANAGE_EXTERNAL_STORAGE
```
`networkSecurityConfig="@xml/network_security_config"` allows cleartext over Tailscale. `allowBackup false`. Activities: `MainActivity` `singleTop`, `FileShareActivity` translucent `SEND*`. Services: `ClipboardSyncService` `foregroundServiceType specialUse stopWithTask false` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation; `FileTransferService`; `ClipboardImeService` `BIND_INPUT_METHOD`; `SyncTileService` `BIND_QUICK_SETTINGS_TILE`; receiver `StopReceiver` non-exported.

---

## Data Flows (end-to-end)

### Clipboard Desktop → Phone (AES-aware)

1. User copies on Desktop → `arch.py:get_clipboard()` (wl-paste) → `push_loop` `send_text = crypto_util.wrap(plain)` (`AES:Base64` if `CLIPBOARD_AES_KEY` set else plain) → `POST /clipboard {clipboard:send_text,origin:arch}` → `server.py` bumps `version`, fans out `event: clipboard` with blob as-is.
2. Phone `ClipboardSyncService.sseLoop.connectSse` parses `event: clipboard`, `handleSseEvent` decrypts first `CryptoUtil.unwrapIfNeeded(rawText, getAesKey, isAesEnabled)` → plain, checks `origin_arch` + `SyncState.tryClaimRemoteEvent(plain, version)` (plain dedup prevents AES echo loop `67ad4aa`), `clipboardManager.setPrimaryClip` plain on `Main`.
3. Desktop `arch.py:last_received` plain prevents echo; Phone `SyncState.lastRemoteText` plain prevents re-POST. Without same 32B Base64 key on both sides, `AES:...` is stored as-is and shows as `AES:...` on plain side.

### Clipboard Phone → Desktop (AES-aware)

1. Copy on the phone → `OnPrimaryClipChangedListener` (if paused, dropped) → `readClipboardWithOverlayFallback` (direct or 1×1 overlay ≤400 ms, every 1.5s poll restored `5c557fb` for Phone reliability; flash every 1.5s, throttled 30s on screen-off) → `handleLocalClipboard` checks pause + `tryClaimLocalChange(plain)` → `toSend = CryptoUtil.wrapIfNeeded(plain)` → `ClipboardApi.postClipboard(toSend)` `POST /clipboard {clipboard:toSend,origin:vivo}` (logs `[AES]` if encrypted, 10 s wake lock).
2. Poll fallback `clipboardMonitorLoop` every 1.5 s (30 s when throttled, 15 s when paused skip) also overlay read (Phone background requires overlay).
3. `ClipboardImeService` parallel poll (1.5 s / 15 s throttled) → `CryptoUtil.wrapIfNeeded` → `HttpURLConnection POST` same endpoint.
4. `server.py` fans out to Desktop `arch.py:sse_loop` → `plain = crypto_util.unwrap(text)` → `set_clipboard` plain via `wl-copy`; URL auto `xdg-open` (`arch.py:113`). If Desktop has no valid key but receives `AES:...`, `unwrap` returns raw and clipboard shows `AES:...`.

Loop suppression both sides via `last_sent/last_received` plain + `SyncState` version check; blank text skipped.

### File Phone → Desktop

Share sheet `FileShareActivity` or drop into `Downloads/ClipboardSync-send/` → `FileTransferService.upload` streams `POST file_server:8766/upload X-Filename/X-Origin vivo` → `file_server.py` saves to `~/Downloads/from-phone/` (dedup `_hex`), prints. On success deletes `ClipboardSync-send` file. Desktop `notify-send` helper defined but not invoked in this path (only `vivo→arch`? actually `desktop_notify` defined not called — could wire).

### File Desktop → Phone

`clip-send.sh` or `~/send-to-vivo/` (`watch_send_folder.py` 0.3 s poll) → `POST file_server:8766/upload X-Filename/X-Origin arch` raw body → staged in `/tmp/cs-files/<id>` (`staged` dict + `lock`) → `signal_sse({type:file_ready,id,name,size})` → `server.py:POST /signal` fans out `event: file_ready` → Phone `handleFileReadyEvent` (dropped if paused) → `FileTransferService.startDownload` → `GET /download/<id>` with `Range` resume, `MediaStore` `Download/ClipboardSync` `IS_PENDING` protocol, `Accept-Ranges`, `Content-Range` on `206`, chunk streamed, progress notifications, `notifyDone`.

### URL open Desktop → Phone / Phone → Desktop

- **Phone → Desktop URL auto-open**: any clipboard text starting `http://`/`https://` arriving at `arch.py:sse_loop` triggers `Popen(["xdg-open", text])` (`arch.py:114`).
- **Desktop → Phone open_url**: `clip-open.sh` `POST /signal {type:open_url,url}` → `server.py` fans out `event: open_url` → `ClipboardSyncService:handleOpenUrlEvent` → `Intent ACTION_VIEW http(s)` → `startActivity`.

---

## Configuration

| Layer | Var / Key | Default | How to set |
|-------|-----------|---------|------------|
| Android host | `clipboard_sync_prefs/server_host` | `YOUR_TAILSCALE_IP` | In-app EditText → Save → auto-restart (`MainActivity.saveServerHost`) |
| Android battery | `battery_saver_enabled` / `pause_on_screen_off` | `true/false` | Switches in-app (`ServerConfig`) — `pause` default `false` (keep active unless Pause) since `df8e3b2` |
| Android AES | `aes_enabled` / `aes_key_b64` (44-char Base64 32B) | `false / ""` | **Security** card → toggle ON (auto-generates if empty) → **Generate** / **Copy** → paste same Base64 on Desktop (`ServerConfig.hasValidAesKey` validates) |
| Desktop host | `CLIPBOARD_SERVER_HOST` | `YOUR_TAILSCALE_IP` | `export` or `systemctl --user set-environment CLIPBOARD_SERVER_HOST=100.x.x.x` |
| Desktop ports | `CLIPBOARD_SERVER_PORT` / `CLIPBOARD_FILE_PORT` | `8765/8766` | same env or `--port`/`--signal-port` |
| Desktop AES | `CLIPBOARD_AES_KEY` (44-char Base64) / `CLIPBOARD_AES_ENABLED=1` | unset (plaintext) | `export CLIPBOARD_AES_KEY=<same>` + `CLIPBOARD_AES_ENABLED=1` → `systemctl --user set-environment` + `~/.config/environment.d/clipboard-aes.conf` + `restart clipboard-sync.service` (`crypto_util.py` `AESGCM` / `pycryptodome` fallback) |
| Shell | `CLIPBOARD_SERVER_HOST/_PORT/_FILE_PORT`, `CLIPBOARD_FILE_SERVER` | same defaults | export before `clip-send.sh`/`clip-open.sh` (already Tailscale-aware) |
| Fallback | `Constants.SERVER_HOST`, `ServerConfig.DEFAULT_HOST` | `YOUR_TAILSCALE_IP` | code — deprecated `SERVER_BASE_URL` |

Desktop systemd persistent: `systemctl --user set-environment CLIPBOARD_SERVER_HOST=... CLIPBOARD_AES_KEY=... CLIPBOARD_AES_ENABLED=1 && systemctl --user restart clipboard-sync.service && journalctl --user -u clipboard-sync.service --since "1 min ago"`. Also `python3 server.py --host 100.x --port 8765` etc. for ad-hoc. Without same `CLIPBOARD_AES_KEY` on both sides, `AES:...` blobs are not readable (Desktop shows `AES:...` raw).

---

## Battery Saver

- **Defaults** `battery_saver_enabled=true`, `pause_on_screen_off=false` (since `df8e3b2` — sync stays **active** unless you tap **Pause**; previous default `true/true` auto-paused and was reported as “sync stops automatically”). Two switches in `MainActivity` compact card.
- **No permanent wakelock**: `ClipboardSyncService:acquireWakeLock` 10 s only around `POST` (`191`), vs previously 24 h. `mWakeLockSummary 0x0` when idle (`dumpsys power`).
- **Screen-off actions** (`screenReceiver:83`): `isScreenOn=false` → if `pause_on_screen_off` true → `PAUSED` notification/broadcast, `currentSseCall?.cancel()` kills SSE, `sseJob` canceled and relaunched into 15 s sleep loop that checks `isSyncPaused()` each iteration. If `pause=false` (default), screen-off only throttles: `clipboardMonitorLoop` `30_000` vs `1_500`, `folderWatch` `30_000` vs `1_000`, `ClipboardImeService` `15_000` vs `1_500`. `SCREEN_ON` → `CONNECTING`, cancel+relaunch `sseLoop` for instant reconnect (verified: `Screen OFF — throttle/pause` + `Socket closed` → `Asleep` when pause enabled; otherwise `RUNNING` persists).
- **Gated components**: `clipChangedListener:116` early return if paused; `clipboardMonitorLoop:410` `delay(15_000)` when paused else `30_000` throttled vs `1_500` normal (now with overlay `5c557fb` for Phone reliability; flash every 1.5s, throttled 30s off); `folderWatchLoop:552` `FileObserver onEvent` `if paused return` + poll `15_000/30_000/1_000`; `handleLocalClipboard:523` double pause check + `CryptoUtil.wrapIfNeeded`; `handleFileReadyEvent:321` dropped when paused; `ClipboardImeService:39` poll `15_000` when paused vs `1_500`, plus `checkAndPost` pause guard via `PowerManager.isInteractive`.
- **Manual pause**: notification `Pause/Resume` (`buildNotification:470` `pauseIntent` toggles based on `PAUSED`) → `StopReceiver:PAUSE/RESUME` → `ClipboardSyncService:ACTION_PAUSE/RESUME` (cancels SSE, sets `isManuallyPaused`). In-app `Pause/Resume` button (`MainActivity:142` visibility toggles with `serviceRunning`). QS tile `SyncTileService:onClick` stops/starts.
- **Reconnect**: SSE backoff `2→60 s ×2` (`Constants:77`) but `15 s` fixed when screen-off+battery+pause, backoff only when `isScreenOn` or saver disabled.

---

## Android Clipboard Restriction

Android 10+ blocks clipboard reads from background: `primaryClip` returns null or throws. Listener fires but read fails. Workaround in `readClipboardWithOverlayFallback:301` — direct read first; if null and `SYSTEM_ALERT_WINDOW` granted, adds `1×1` `TYPE_APPLICATION_OVERLAY` `FLAG_NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN` `PixelFormat.TRANSLUCENT` `alpha 0.01` `not focusable inv()`, `object : View onWindowFocusChanged` waits for focus (≤400 ms timeout), reads, removes via `removeOverlaySafe`. Invisible, removed in <400 ms. Requires user grant *Draw over other apps* (`MainActivity.btnOverlayPermission` + `refreshOverlayButton`). IME service is alternate path — `BIND_INPUT_METHOD` clipboard access while input active, with its own poll. **Polling restored `5c557fb`**: `clipboardMonitorLoop:410` uses `readClipboardWithOverlayFallback` every 1.5s (30s throttled) — direct-only broke Phone (required return to app) — so flash `displaying over other apps` every 1.5s is the trade for reliable background without IME/accessibility. Without overlay/IME, Phone→Desktop only when app focused.

---

## Build & Install

### Prerequisites

- JDK 11+ · Android SDK (AGP downloads Build Tools) · Tailscale on both · Wayland `wl-clipboard` (`wl-copy`/`wl-paste`) on Desktop, or `xclip`/`xsel` fallback; `grim`/`maim` for shots, `xdg-open` for URLs, `notify-send` for toasts.

### Android APK

```bash
cd /home/zoro/clipboard-sync
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.clipboardsync/.MainActivity
# Wireless
adb connect <vivo-ip>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`app/build.gradle.kts`: `compileSdk 34`, `minSdk 26`, `targetSdk 34`, `viewBinding true`, JVM 1.8. Version catalog `gradle/libs.versions.toml`.

### Desktop daemon

```bash
systemctl --user enable --now clipboard-sync.service
systemctl --user status clipboard-sync.service
journalctl --user -u clipboard-sync.service -f
systemctl --user restart clipboard-sync.service   # after editing *.py / *.sh / env
# Ad-hoc (no systemd):
python3 app.py
# or per-component
python3 server.py --host YOUR_TAILSCALE_IP --port 8765
python3 file_server.py --host YOUR_TAILSCALE_IP --port 8766 --signal-port 8765
python3 arch.py --host YOUR_TAILSCALE_IP --port 8765
python3 watch_send_folder.py
```

`clipsyncd` build: `cc -o clipsyncd clipsyncd.c` already built as PIE ELF, prctl comm.

---

## Usage

1. Tailscale up on both, note Desktop IP (`tailscale ip -4`).
2. Android: open **Clipboard Sync** → if IP differs, edit **Server (Tailscale IP)** → Save (auto-restarts). Toggle battery saver as desired (leave ON).
3. Tap **Start Sync** → allow **Notifications** (Android 13+), grant **Draw over other apps** (` Granted`), optionally **Enable Clipboard IME** (`Settings.ACTION_INPUT_METHOD_SETTINGS`) and enable `Clipboard Sync Service` IME.
4. Status ` Running`, notification `Active (host:port)` with `Pause`/`Stop`. QS tile `Clipboard Sync` toggles.
5. Copy on either device → appears on other within ~0.5–1.5 s. Screen-off: auto ` Paused (screen off — battery saver)`, resume on unlock. Manual `Pause` leaves notification `Paused — tap Resume` and blocks all sync until `Resume`.
6. Stop: `Stop Sync` or notification `Stop` or QS tile (active→inactive) or `adb shell am force-stop` / `systemctl --user stop clipboard-sync.service` on Desktop.

File & URL flows described above.

---

## File Transfer Details

- **Phone→Desktop**: `FileShareActivity` (share sheet) immediately `FileTransferService.startUpload` per URI; folder method also via `ClipboardSyncService.folderWatchLoop` (`Downloads/ClipboardSync-send/` `FileObserver CREATE|CLOSE_WRITE|MOVED_TO` + `listFiles` poll 1 s / 30 s throttled, dedup `seenFiles` by name, `Uri.fromFile`). `FileTransferService.upload` handles `file://` `FileInputStream` vs `contentResolver.openInputStream`, size via `file.length()` vs `OpenableColumns.SIZE`, `streamingBody` `256 KB` + `showProgress(↑ name pct)`, `POST upload` `X-Filename/X-Origin vivo` to `ServerConfig.getFileBaseUrl`, `200` → `notifyDone ↑ sent to Desktop` and deletes watch-folder file.
- **Desktop→Phone**: `clip-send.sh ~/file` or drop into `~/send-to-vivo/` (`watch_send_folder.py` `0.3 s` `iterdir` diff sorted) → `POST upload X-Filename/X-Origin arch` raw binary → `file_server.py` writes `/tmp/cs-files/<uuid>`, `staged[id]={id,name,size,path,ts}` locked, `signal_sse(file_ready)` → `server.py/POST /signal` SSE → Phone `handleFileReadyEvent` → `FileTransferService.startDownload` → `GET /download/<id>` (`Range` supported) streams `CHUNK` 256 KB, `saveToDownloads` uses `MediaStore.Downloads` `RELATIVE_PATH Download/ClipboardSync` `IS_PENDING 1→0` on Q+ else `Environment.DIRECTORY_DOWNLOADS/ClipboardSync` file, `copyWithProgress` updates `showProgress(↓ pct)`, `notifyDone ↓ saved`.
- **Staging & lifetime**: `staged` in-memory + `/tmp/cs-files` (tmpfs, cleared on reboot). No TTL cleanup — entries remain until manual delete or reboot. `/files` lists staged.
- **Resumable**: `Range: bytes=n-` → `206 Partial Content` + `Content-Range bytes n-total/total` + `Accept-Ranges bytes`.

---

## Wayland / Hyprland Notes

- Primary: `wl-copy`/`wl-paste --no-newline` (and `--primary`) in `arch.py:31,74`. Fallbacks `xclip`/`xsel` for Xwayland/X11. Hyprland already runs `wl-paste --type text/image --watch cliphist store` (`systemctl --user status` shows) — does not conflict; daemon uses direct `wl-copy`.
- Screenshots: `clip-shot.sh` prefers `grim` (Wayland) → `maim` → `import` (`:6`), `grim "$FILE"` full-screen, then `clip-send.sh`, rm temp. No selection prompt.
- **Windows Global Hotkey**:
  - `win-shot.py` / `HotkeyManager` listens for `SCREENSHOT_HOTKEY` (default: `Ctrl+Alt+Shift+S`).
  - Native Win32 `RegisterHotKey` / `WM_HOTKEY` loop enables background capture when window is unfocused.
  - **Important Limitation**: Do NOT set shortcut to `Win+Shift+S` as Windows Snipping Tool reserves this shortcut exclusively.
  - **Customization**: Set `SCREENSHOT_HOTKEY="Ctrl+Alt+P"` in Windows environment or start script.
- Portal services `xdg-desktop-portal-hyprland` + `xdg-document-portal` in session — for file pickers, not directly used.
- `Tailscaled.service:110592` active, `NetworkManager` active.

---

## Security & Stealth

- Transport: **WireGuard (Tailscale) + optional AES-256-GCM end-to-end**. Plain HTTP over tailnet by default; with **Security** card enabled (`ServerConfig.isAesEnabled` + `CLIPBOARD_AES_KEY` 32B Base64, `CryptoUtil.kt` / `crypto_util.py` `AES/GCM/NoPadding` 12B IV `AES:Base64(IV||ct+tag)`), server only sees `AES:...` blobs, clients decrypt to plain. No SHA — pure AES (`44bd338`). Without same key on both sides, encrypted blobs show as `AES:...` raw. Generate via in-app **Generate** or `python3 -c "import crypto_util; print(crypto_util.generate_key_b64())"` / `openssl rand -base64 32`.
- No separate auth: `POST /clipboard` accepts any `origin` when AES off; with AES on, wrong key fails `GCM` tag verify and returns raw (not applied). Future could add `Authorization: Bearer` header (`ClipboardApi.kt:59` stub) but not needed — AES provides confidentiality + implicit auth.
- Permissions are minimal + justified: `specialUse` FGS with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explaining clipboard has no standard type; `SYSTEM_ALERT_WINDOW` explained with 1 px overlay; `POST_NOTIFICATIONS` for FGS notification; `MANAGE_EXTERNAL_STORAGE` + granular media for `Downloads/ClipboardSync-send` watch (FuntouchOS needs all-files for public dir `FileObserver`).
- Stealth: `clipsyncd.c:10` null `HOME` guard + `prctl(PR_SET_NAME, "clipsyncd")`, `app.py:50` guarded restart loop (`_run_guarded` 5s retry), `prctl(PR_SET_NAME, "systemd-user-service")` + `setproctitle` if available, threads named same, service `Description=Clipboard Sync Daemon` appears as `systemd-user-service` in `ps`/`CGroup` (`app.slice/clipboard-sync.service` → `systemd-user-service`), masking Python interpreter path. `server.py:17` `clients_lock` for concurrency, `clipsyncd` PIE rebuilt `16440B`.
- UI: `Theme.Material3.Light.NoActionBar` forced light (`themes.xml:3`) fixes black containers in dark mode on Phone; compact `activity_main.xml` 12dp paddings, 8dp margins, 42dp buttons, header gradient `bg_header_gradient.xml`, `dot_*` indicators, `ic_*` vectors, fits without scroll on 6.64".

---

## Testing

Default `$HOST=YOUR_TAILSCALE_IP` (replace if changed via env or in-app). `$PORT=8765` clipboard, `$FPORT=8766` files.

```bash
# Health
curl -s http://$HOST:$PORT/clipboard | python3 -m json.tool
curl -s http://$HOST:$FPORT/files | python3 -m json.tool

# Desktop → Phone (or any curl)
curl -s -X POST http://$HOST:$PORT/clipboard -H 'Content-Type: application/json' \
  -d '{"clipboard":"HELLO FROM DESKTOP","origin":"desktop"}'
# Paste on the phone should be HELLO FROM DESKTOP in ~1s

# Phone → Desktop (app must be foreground or overlay/IME granted)
# Copy on the phone → on Desktop:
watch -n1 'curl -s http://$HOST:$PORT/clipboard | python3 -m json.tool'

# Screen-off pause
adb logcat -c; adb shell input keyevent 26; sleep 3; adb logcat -d | grep -E "ClipSyncService|Screen" | tail
# Expect: Screen OFF — battery saver will throttle/pause + SSE error: Socket closed ; mWakefulness=Asleep
adb shell input keyevent 26; sleep 2; adb logcat -d | grep ClipSyncService | tail
# Expect: Screen ON — resuming sync + SSE connected ; mWakefulness=Awake
# Clipboard POST while asleep must not arrive until unlock

# Network loss
# Disable Phone WiFi → status ✕ Connection error → re-enable → reconnect 2→4→8…60 s (Constants:77)
adb shell dumpsys activity services com.example.clipboardsync | grep -E "ServiceRecord|isForeground"
adb shell dumpsys power | grep mWakefulness

# Loop prevention
curl -s -X POST http://$HOST:$PORT/clipboard -H 'Content-Type: application/json' \
  -d '{"clipboard":"loop-test-value","origin":"desktop"}'; sleep 2; curl -s http://$HOST:$PORT/clipboard | python3 -m json.tool
# version unchanged, origin still arch

# Multiline / Unicode
curl -s -X POST http://$HOST:$PORT/clipboard -H 'Content-Type: application/json' \
  -d '{"clipboard":"line one\nline two","origin":"desktop"}'
curl -s -X POST http://$HOST:$PORT/clipboard -H 'Content-Type: application/json' \
  -d '{"clipboard":"Hello 世界 🌍 مرحبا","origin":"desktop"}'

# Files
clip-send.sh /tmp/test.png           # Desktop→Phone → check Phone Downloads/ClipboardSync/test.png
# On Phone: share image → Clipboard Sync → check Desktop ~/Downloads/from-phone/
# Desktop watch folder: cp file ~/send-to-vivo/ → auto send + unlink
# Phone watch folder: drop file into Downloads/ClipboardSync-send/ → auto upload

# Range resume
curl -v http://$HOST:$FPORT/download/<id> -H "Range: bytes=1024-"

# Open URL
clip-open.sh https://example.com     # Desktop → Phone opens browser
# Phone → Desktop URL auto-open: copy https://… on Phone → Desktop xdg-open
```

---

## Project Structure

```
clipboard-sync/
├── clipsyncd / clipsyncd.c                 ELF wrapper → app.py, prctl, null HOME guard (16440B PIE)
├── app.py                                  single-process daemon (4 threads, guarded restart, stealth names)
├── server.py                               :8765 clipboard broker + SSE (: keepalive 15s, clients_lock)
├── file_server.py / file-server.py         :8766 upload/download (+ signal_sse→server.py) — file-server.py is shim
├── arch.py                                 wl-paste poll 0.5s POST (crypto_util.wrap) + SSE sse_loop→wl-copy (unwrap)
├── crypto_util.py                          AES-256-GCM (cryptography/pycryptodome, 12B IV, AES:Base64)
├── watch_send_folder.py / watch-send-folder.py  ~/send-to-vivo 0.3s → clip-send.sh — dashed is shim
├── clipboard-sync.service                  systemd user ExecStart=%h/clipboard-sync/clipsyncd Restart=always
├── clip-send.sh                            POST file_server/upload arch→vivo (env CLIPBOARD_FILE_SERVER, timeout 120s, numfmt fallback)
├── clip-open.sh                            POST server/signal open_url (HOST/PORT env)
├── clip-shot.sh                            grim/maim/import → clip-send.sh
├── watch.py                                one-shot wl-paste POST (env CLIPBOARD_SERVER_HOST)
├── gradlew / gradle/libs.versions.toml / gradle/wrapper/ / settings.gradle.kts / build.gradle.kts
└── app/
    ├── build.gradle.kts                    compileSdk 34 minSdk 26 targetSdk 34 viewBinding
    └── src/main/
        ├── AndroidManifest.xml             perms, specialUse, share sheet, IME, tile
        ├── kotlin/com/example/clipboardsync/
        │   ├── Constants.kt                ports, timeouts, poll 1500ms, backoff 2→60s×2, PAUSE actions, throttled 30s
        │   ├── ServerConfig.kt             SharedPrefs host + battery (true/false) + aes_enabled/key_b64 (CryptoUtil)
        │   ├── CryptoUtil.kt               AES/GCM/NoPadding 12B IV, PREFIX AES:, generate/validate/wrap/unwrap (pure AES, no SHA)
        │   ├── SyncStatus.kt               STOPPED/CONNECTING/RUNNING/PAUSED/ERROR
        │   ├── SyncState.kt                Mutex lastLocal/lastRemote/version loop prevention (plain dedup for AES)
        │   ├── ClipboardApi.kt             OkHttp httpClient/sseClient (0 timeout), POST /clipboard (already wrapped), GET /events
        │   ├── ClipboardSyncService.kt     ~710 lines FGS: screenReceiver, clipListener, monitor (overlay restored), sseLoop, folderWatch (scoped fallback), notif
        │   ├── ClipboardImeService.kt      InputMethod zero-view, background poll throttled, wrap before POST
        │   ├── FileTransferService.kt      upload/download streaming 256KB, MediaStore, progress notifs (file transfer not yet AES)
        │   ├── FileShareActivity.kt        translucent share sheet SEND* → startUpload
        │   ├── MainActivity.kt             compact Material3 Light: header gradient, status dot, Server+Security+Battery+Permissions cards, Generate/Copy AES, Save&Restart, Pause/Resume
        │   ├── StopReceiver.kt             notification Stop/Pause/Resume broadcasts
        │   └── SyncTileService.kt          QS tile toggle
        └── res/
            ├── layout/activity_main.xml    CoordinatorLayout→NestedScrollView compact 12dp/8dp/42dp, fits without scroll
            ├── values/colors.xml / themes.xml (Material3.Light) / strings.xml
            ├── drawable/bg_header_gradient, bg_chip_surface, dot_*, ic_sync/server/battery/shield/play, ic_launcher
            └── xml/network_security_config.xml + xml/method.xml (IME)
```

---

## Foreground Service Rationale

`foregroundServiceType="specialUse"` required on Android 14+ where each FGS must declare type. No `clipboard` type exists; `dataSync` is for user-initiated transfers, not continuous clipboard. `specialUse` is the correct fallback for valid non-standard use, with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest justification. `START_STICKY` restarts after kill (null intent treated as start). Notification `ONGOING|ONLY_ALERT_ONCE|FOREGROUND_SERVICE`, `IMPORTANCE_LOW`, `FOREGROUND_SERVICE_IMMEDIATE`, ongoing with `Pause/Stop`. Previous 24 h wake lock replaced by 10 s on POST only; screen-off pause cancels SSE and sleeps loops, so Doze is respected yet wake is honored for active copy.

---

## Troubleshooting

- **Phone→Desktop not syncing in background:** OS restriction — grant *Draw over apps* (button shows ` Granted` when ok) and enable IME (`Settings → Keyboard → Clipboard Sync Service`), enable *Show on top* / disable Phone FuntouchOS background freeze. Polling uses `readClipboardWithOverlayFallback` every 1.5s (30s throttled) — causes brief `displaying over other apps` toast every poll (trade for reliability, restored `5c557fb`). Without overlay, need return to app. Check `adb logcat | grep ClipSyncService` shows `ClipChangedListener got` vs `null (OS restriction)`.
- **Phone→Desktop needs return to app after AES fix:** Was direct-only polling (flash-free) broke Phone → fixed by restoring overlay. If still needs return, Phone blocked overlay even with permission — enable IME or allow `Display over other apps` in Phone's iManager.
- **AES shows `AES:...` on other side:** Means one side has key, other not, or keys differ. Set same 44-char Base64 on both: Android **Security** → **Copy** → Desktop `systemctl --user set-environment CLIPBOARD_AES_KEY=<same> CLIPBOARD_AES_ENABLED=1` + `~/.config/environment.d/clipboard-aes.conf` + `restart`. Generate with `CryptoUtil.generateKeyBase64` (in-app Generate) or `python3 -c "import crypto_util; print(crypto_util.generate_key_b64())"`. Disable AES on both for plaintext.
- **Battery drain:** Default `battery_saver=true, pause_on_screen_off=false` (sync stays active, throttled 30s off). If you enabled `Pause when screen off`, screen-off fully disconnects. Check `adb logcat -c; input keyevent 26;` shows `Screen OFF — throttle/pause` + `Socket closed` only if pause enabled; `dumpsys power | grep mWakefulness` = `Asleep` no `mWakeLockSummary`. If still drain, force-stop IME if not needed or lower `CLIPBOARD_POLL_INTERVAL_MS`.
- **UI black containers / needs scroll:** Fixed `themes.xml:3` to `Theme.Material3.Light.NoActionBar` (was `DayNight` → dark mode black cards) + compact layout 12dp/8dp/42dp fits Phone 6.64" without scroll. If still scrolls, font scale large — reduce in system Display.
- **IP changed / Tailscale re-key:** Phone in-app edit → Save (auto-restart connects to `host:port` shown `textServer`). Desktop `systemctl --user set-environment CLIPBOARD_SERVER_HOST=...` + `restart clipboard-sync.service`; verify `journalctl --user -u clipboard-sync.service --since "1 min ago"` shows new host. Shell helpers also respect `CLIPBOARD_SERVER_HOST/PORT`.
- **Desktop daemon after code edit not picking up:** PID from `systemctl --user status clipboard-sync.service` still old `start_time`; must `restart` (old Python process keeps old code). `daemon-reload` not needed unless service file changed. `server.py` now `clients_lock`, `app.py` guarded restart.
- **SSE not connecting / ERROR:** `curl http://$HOST:$PORT/clipboard` must `200`; check `tailscale status`, `tailscaled.service`, `NetworkManager`, `ss -tlnp | grep 8765/8766`. Phone `adb logcat | grep SSE error` shows `HTTP` code or `Socket closed` (expected when paused). Backoff `2→60 s` auto-retries.
- **File staged but Phone not downloading:** `curl http://$HOST:$FPORT/files` lists staged; `GET /download/<id>` must 200; check `server.py` `POST /signal` succeeded (`journalctl` `file_ready` → `signal_sse` error?). If Phone paused, `file_ready dropped` — will retry after resume; `xdg-open` test for SSE path.
- **Share sheet not showing Clipboard Sync:** `AndroidManifest` `SEND* */*` should list; clear `adb shell pm clear`? Reinstall via `adb install -r`.
- **Echo loop with AES:** Fixed `67ad4aa` — `handleSseEvent` decrypts before `tryClaimRemoteEvent` so plain dedup prevents `LIVE_ARCH...` echo `v6→v7`.

Example AES test: `CLIPBOARD_AES_KEY=... python3 -c "import crypto_util; print(crypto_util.wrap('hello')); print(crypto_util.unwrap(crypto_util.wrap('hello')))"` → `AES:...` → `hello`. Android `CryptoUtil.wrapIfNeeded` / `unwrapIfNeeded` same `AES:GCM` 12B IV.

---

## Future

- **History** (Room DB + `cliphist` 20 items, pin/search), **Image clipboard** (`image/png` via `MediaStore` + `file_server` blob, currently `coerceToText` only), **Folder two-way sync** (Phone `Sync` folder already `FileObserver`, Desktop `~/send-to-vivo` already 0.3s), **Waybar widget**, **Windows/macOS companion** (Go tray SSE), **Web UI** `GET /` on `:8765`.
- Auth hook already done via AES: `ClipboardApi.kt:59` stub now replaced by `CryptoUtil` + `CLIPBOARD_AES_KEY`; no SHA needed. Tailscale ACL still primary.

## Future / Auth Hook (legacy)

Add Bearer token when needed: uncomment `Authorization: Bearer $token` in `ClipboardApi.kt:59` (`POST`) and `:87` (`SSE`), add `AUTH_TOKEN` in `Constants.kt` or `ServerConfig`, send `token` in body (`ClipboardApi.kt:54` hook), verify in `server.py:98` (`if data.get("token")!=EXPECTED: send_error 401`) and `arch.py:63` same, add `Header("Authorization", "Bearer "+token)` in `file_server.py:signal_sse` and `clip-send.sh -H "Authorization: Bearer $TOKEN"`. Tailscale already ACLs via tailnet, so token is defense-in-depth for untrusted tailnet members.

