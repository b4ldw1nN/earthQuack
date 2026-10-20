# Embedded Tailscale Roadmap
### Feature parity with tailsocks — for EarthQuack only, no system VPN

Replace the dependency on the Tailscale Android app with an in-process
`tsnet` node embedded inside EarthQuack. Everything the app already does
(SSH, clipboard sync, rclone, SFTP) routes through the embedded tailnet
automatically. No external app, no VPN permission, no `VpnService`.

Auth: **browser login only** — user taps "Log in", browser opens, done.
No auth keys, no admin console, no copy-paste.

---

## Current state (what we're replacing)

```
EarthQuack today
  ├── Requires Tailscale app to be running separately
  ├── TailscaleDiscovery — probes hardcoded 100.x.x.x IPs via OkHttp
  ├── TailnetStatus      — reads 100.64/10 addrs from ConnectivityManager
  │                        only works if Tailscale app created the tun
  ├── EarthQuackService  — clipboard SSE/POST → hardcoded host
  ├── SshConnection      — MINA SSHD → 100.x.x.x (requires Tailscale VPN)
  └── RcloneEngine       — rclone → remotes at 100.x.x.x
```

---

## Target architecture

```
EarthQuack (self-contained)
  └── TailscaleEngine  (new — owns the tsnet lifecycle)
       ├── tsnet.Server ──────────────────────────────────────────────
       │    ├── WireGuard + control plane (userspace, no tun)
       │    ├── MagicDNS resolver
       │    ├── SOCKS5 proxy → 127.0.0.1:<port>   (all outbound traffic)
       │    ├── tsnet.Listen()  → inbound tailnet listeners
       │    └── LocalClient()   → live status, peers, prefs, WhoIs
       │
       ├── Feature modules (all new):
       │    ├── ExitNodeManager       — pick / clear exit node
       │    ├── TaildropReceiver      — accept incoming file pushes
       │    ├── TaildropSender        — push files to peers
       │    ├── TaildriveMount        — browse peer-shared directories
       │    ├── ServeManager          — expose local services to tailnet
       │    ├── FunnelManager         — expose local services to internet
       │    ├── AdminConsoleClient    — device list, keys, DNS, ACL via API
       │    └── DnsOverride           — use tailnet MagicDNS for resolution
       │
       └── Wires into existing code:
            ├── EarthQuackService — OkHttp through SOCKS5
            ├── SshConnection     — MINA SSHD through SOCKS5
            └── RcloneEngine      — rclone env SOCKS5_PROXY
```

---

## Phase 0 — Go shim: merge tsnet into libgojni.so

**Files:** `rclone-android/tailscale/tailscale.go` (new package),
`rclone-android/go.mod`, `scripts/build-rclone-aar.sh`
**APK size delta:** +~8–10 MB compressed
**Risk:** Low — additive only, same build pipeline as rclone

### New Go package: `rclone-android/tailscale/tailscale.go`

Exposes everything gomobile can bind across the JNI boundary.
All complex types are JSON strings so gomobile's type restrictions
don't limit the API surface.

```go
package tailscale

// --- Lifecycle ---

// Start initialises the tsnet node.
// stateDir: app-private path (context.filesDir + "/tailscale")
// hostname: how this device appears in the tailnet admin console
// Returns a JSON object: {"socks_port":N, "login_url":"...", "node_ip":"..."}
// login_url is non-empty when browser login is needed.
func Start(stateDir, hostname string) string

// Stop shuts the node down cleanly.
func Stop()

// Status returns a JSON snapshot:
// {"state":"Running|NeedsLogin|Stopped", "node_ip":"100.x", "login_url":"..."}
func Status() string

// --- Peers & network ---

// Peers returns a JSON array of tailnet peers:
// [{"id":"...", "name":"...", "ip":"...", "os":"...", "online":true}]
func Peers() string

// ThisDevice returns a JSON object for this node.
func ThisDevice() string

// --- Exit node ---

// SetExitNode sets the exit node by peer ID. Pass "" to clear.
func SetExitNode(peerID string) error

// ExitNode returns the current exit node peer ID, or "".
func ExitNode() string

// --- Taildrop ---

// TaildropSend sends a file to a peer.
// peerID: from Peers()
// filePath: local absolute path
func TaildropSend(peerID, filePath string) error

// TaildropPending returns a JSON array of incoming file offers waiting to be
// accepted: [{"id":"...", "name":"...", "size":N, "peer":"..."}]
func TaildropPending() string

// TaildropAccept accepts a pending file offer and saves it to destDir.
func TaildropAccept(offerID, destDir string) error

// TaildropDecline declines a pending file offer.
func TaildropDecline(offerID string) error

// --- Taildrive ---

// TaildriveShares returns a JSON array of shares visible on the tailnet:
// [{"peer":"...", "name":"...", "path":"..."}]
func TaildriveShares() string

// TaildriveMount exposes a local directory to the tailnet via Taildrive.
// name: label peers see, localPath: local dir to share
func TaildriveMount(name, localPath string) error

// TaildriveUnmount removes a previously mounted share by name.
func TaildriveUnmount(name string) error

// --- Serve (expose services to tailnet) ---

// ServeHTTP serves localPort on the tailnet at the given tailnet port.
// E.g. ServeHTTP(8875, 80) makes :8875 reachable as http://hostname:80
func ServeHTTP(localPort, tailnetPort int) error

// ServeTLS serves localPort on the tailnet with automatic TLS (HTTPS).
func ServeTLS(localPort, tailnetPort int) error

// ServeStop stops the serve on tailnetPort.
func ServeStop(tailnetPort int) error

// --- Funnel (expose services to internet) ---

// FunnelEnable exposes tailnetPort publicly to the internet via Funnel.
// Requires Funnel to be enabled in the tailnet admin console.
func FunnelEnable(tailnetPort int) error

// FunnelDisable removes Funnel for tailnetPort.
func FunnelDisable(tailnetPort int) error

// --- Admin API ---

// AdminDevices returns all devices in the tailnet as JSON (Tailscale API v2).
func AdminDevices(apiKey string) string

// AdminExpireKey expires a device's node key by device ID.
func AdminExpireKey(apiKey, deviceID string) error

// AdminDNS returns the tailnet DNS configuration as JSON.
func AdminDNS(apiKey string) string

// AdminACL returns the tailnet ACL policy as JSON.
func AdminACL(apiKey string) string

// --- MagicDNS / resolution ---

// Resolve resolves a MagicDNS hostname to an IP using the tailnet resolver.
// E.g. Resolve("archii") → "100.92.160.31"
func Resolve(hostname string) string

// --- WhoIs ---

// WhoIs identifies a tailnet IP — returns JSON:
// {"login":"user@example.com", "display_name":"...", "device":"..."}
func WhoIs(ip string) string
```

`gomobile bind` is updated to include `./tailscale` alongside `./rclone`:

```bash
gomobile bind \
    -target="android/arm64" \
    -androidapi=26 \
    -javapkg="com.example.earthquack" \
    -ldflags="-w -s" \
    -o "$OUT_AAR" \
    ./rclone \
    ./tailscale
```

---

## Phase 1 — Auth: browser login

**New files:** `tailscale/TailscaleAuthManager.kt`, `tailscale/TailscaleStateStore.kt`
**No auth keys.** Browser only.

### How it works

```
First run:
  TailscaleEngine.start()
    → Tailscale.start(stateDir, hostname)
    → returns {"state":"NeedsLogin", "login_url":"https://login.tailscale.com/..."}
  App opens login_url in Chrome Custom Tab
  User logs in to Tailscale in browser
  tsnet polls control plane every 2s
  state transitions to "Running"
  Custom Tab is dismissed

All subsequent runs:
  tsnet reads persisted node key from stateDir
  Joins tailnet without any user action
  login_url is "" — no UI shown
```

### TailscaleStateStore

Persists nothing sensitive — tsnet owns its own state in `filesDir/tailscale/`.
This store only tracks:

```kotlin
class TailscaleStateStore(context: Context) {
    // Whether the node has ever been enrolled (stateDir exists + non-empty)
    fun isEnrolled(): Boolean
    // Hostname override (defaults to Build.MODEL sanitised)
    fun hostname(): String
    fun setHostname(name: String)
    // Headscale control URL (empty = use tailscale.com)
    fun controlUrl(): String
    fun setControlUrl(url: String)
}
```

### Login sequence diagram

```
Kotlin                    Go (tsnet)              Browser
  │                          │                       │
  │── Tailscale.start() ────►│                       │
  │◄─ {login_url: "..."}  ──│                       │
  │                          │                       │
  │── open CustomTab ───────────────────────────────►│
  │                          │         user logs in  │
  │                          │◄─ control plane OK ───│
  │◄─ Tailscale.Status()    │                       │
  │   {state:"Running"} ────│                       │
  │── dismiss CustomTab      │                       │
```

---

## Phase 2 — TailscaleEngine: lifecycle + SOCKS5 wiring

**New file:** `tailscale/TailscaleEngine.kt`

```kotlin
object TailscaleEngine {

    val state: StateFlow<TailscaleState>

    /** Start the node. Called from EarthQuackService.onCreate(). */
    suspend fun start(context: Context)

    /** Stop the node. Called from EarthQuackService.onDestroy(). */
    fun stop()

    /** 127.0.0.1:N for outbound SOCKS5 proxy, or null if not running. */
    fun socksProxy(): InetSocketAddress?

    /** This device's tailnet IP. */
    fun nodeIp(): String?

    /** Live peers, parsed from Tailscale.peers() JSON. */
    fun peers(): List<TailnetPeer>

    /** Resolve a MagicDNS hostname. */
    suspend fun resolve(hostname: String): String?
}

sealed class TailscaleState {
    object Stopped    : TailscaleState()
    object Starting   : TailscaleState()
    data class NeedsLogin(val loginUrl: String) : TailscaleState()
    data class Running(val nodeIp: String, val socksPort: Int) : TailscaleState()
    data class Error(val message: String) : TailscaleState()
}
```

### Wire SOCKS5 into OkHttp (clipboard + API)

```kotlin
fun buildHttpClient(proxy: InetSocketAddress?): OkHttpClient =
    OkHttpClient.Builder()
        .apply { if (proxy != null) proxy(Proxy(Proxy.Type.SOCKS, proxy)) }
        .build()
```

### Wire SOCKS5 into Apache MINA SSHD

```kotlin
client.proxyData = Socks5ProxyData(
    InetAddress.getByName("127.0.0.1"),
    socksPort,
    null, null
)
```

### Wire SOCKS5 into rclone

```kotlin
// After TailscaleEngine is Running:
RcloneEngine.rpc("core/command",
    """{"command":"--proxy","args":["socks5://127.0.0.1:$socksPort"]}""")
// Or via environment before rclone init:
System.setProperty("SOCKS5_PROXY", "socks5://127.0.0.1:$socksPort")
```

---

## Phase 3 — Exit Nodes

**New file:** `tailscale/ExitNodeManager.kt`
**UI:** dropdown in `ConnectionsFragment` / new `TailscaleFragment`

```kotlin
class ExitNodeManager {
    // List peers that advertise as exit nodes
    fun availableExitNodes(): List<TailnetPeer>
    // Set exit node — all tsnet outbound traffic routes through it
    suspend fun setExitNode(peer: TailnetPeer?)
    fun currentExitNode(): TailnetPeer?
}
```

UI: a "Route internet through" picker. Selecting a peer calls
`Tailscale.setExitNode(peer.id)`. Selecting "None" clears it.

---

## Phase 4 — Taildrop (file sharing between devices)

**New files:** `tailscale/TaildropManager.kt`,
`ui/TaildropFragment.kt`, notification channel for incoming files

### Send

```
File browser → long-press → "Send via Tailscale"
→ peer picker (from Peers())
→ TaildropManager.send(peer, uri)
→ Tailscale.taildropSend(peer.id, localPath)
→ progress notification
```

### Receive

tsnet surfaces incoming file offers. Poll `Tailscale.taildropPending()`
every few seconds (or hook a background worker). When offers arrive,
show a persistent notification:

```
"archii wants to send 'report.pdf' (2.3 MB)"
[Accept → Downloads]  [Decline]
```

Accept calls `Tailscale.taildropAccept(offerId, downloadsDir)`.

---

## Phase 5 — Taildrive (shared directories across devices)

**New files:** `tailscale/TaildriveManager.kt`, `ui/TaildriveFragment.kt`

### Browse peer shares

```kotlin
class TaildriveManager {
    // All shares visible on tailnet from Tailscale.taildriveShares()
    fun remoteShares(): List<TaildriveShare>

    // Expose a local directory to the tailnet
    suspend fun mountLocal(name: String, localPath: String)
    suspend fun unmountLocal(name: String)

    // Open a remote share via the rclone webdav backend
    // (rclone already runs in-process; Taildrive is WebDAV under the hood)
    suspend fun openRemoteShare(share: TaildriveShare): RcloneRemote
}
```

> [!NOTE]
> Taildrive is WebDAV over the tailnet. rclone already supports WebDAV
> as a backend. `openRemoteShare()` registers a one-shot rclone remote
> for the share URL and hands it to the existing file browser — no new
> file-browsing code needed.

---

## Phase 6 — Serve & Funnel

**New file:** `tailscale/ServeManager.kt`
**UI:** toggle rows in a new "Expose services" section

### Serve (tailnet only)

Expose EarthQuack services to tailnet peers without them knowing ports:

| Local service | Expose as |
|---|---|
| Clipboard API (`:8875`) | `http://earthquack-phone:80` |
| SFTP server (`:2222`) | tailnet-visible SSH/SFTP on `:22` |
| RangeHttpServer (`:8080`) | `http://earthquack-phone:8080` |

```kotlin
class ServeManager {
    suspend fun exposeClipboardApi()    // ServeHTTP(8875, 80)
    suspend fun exposeSftp()            // ServeHTTP(2222, 22)
    suspend fun stopAll()
    fun activeServes(): List<ServeEntry>
}
```

### Funnel (internet-accessible)

Wraps `Tailscale.funnelEnable(port)`. Shown in the UI only when Funnel
is enabled on the tailnet (detected via `AdminACL`). Useful for
temporarily sharing a file or clipboard link publicly.

---

## Phase 7 — Admin console in-app

**New file:** `tailscale/AdminConsoleClient.kt`, `ui/AdminFragment.kt`

A lightweight wrapper over the Tailscale HTTP API v2. Secured behind
Android biometric prompt before the API key is read from
`EncryptedSharedPreferences`.

### Sections

| Section | Tailscale API | What it shows |
|---|---|---|
| **Devices** | `GET /api/v2/tailnet/-/devices` | All nodes, OS, last seen, IP |
| **Keys** | `GET /api/v2/tailnet/-/keys` | Auth keys, expiry, tags |
| **DNS** | `GET /api/v2/tailnet/-/dns/nameservers` | MagicDNS config |
| **ACL** | `GET /api/v2/tailnet/-/acl` | Policy as JSON, read-only |

Device actions: expire key, delete device.
Admin API key entered once, stored in `EncryptedSharedPreferences`.

---

## Phase 8 — MagicDNS + smart peer discovery

**Changed files:** `TailscaleDiscovery.kt` → deleted,
`TailnetStatus.kt` → deleted, replaced by `TailscaleEngine`

### What replaces the hardcoded IP scanner

```kotlin
// Before:
TailscaleDiscovery.discoverServerIp()  // probes 100.92.160.31...

// After:
TailscaleEngine.resolve("archii")      // → "100.92.160.31" via MagicDNS
TailscaleEngine.peers()               // → live list, no scanning needed
```

`ConnectionsFragment` becomes a live peer list sourced from `tsnet`.
Tapping a peer sets it as the sync target — same "Use selected" flow,
but now driven by real data instead of a port scanner.

---

## Phase 9 — DPI bypass (optional, for restricted networks)

**New file:** `tailscale/DpiBypass.kt`
Mirrors tailsocks's `byedpi` integration — a lightweight SOCKS5 tunnel
that disguises Tailscale's WireGuard handshake as HTTPS traffic (port 443).

Exposed as a toggle in Settings: "DPI bypass (restricted networks)".
Off by default. When on, tsnet's UDP/WireGuard is wrapped via a local
HTTPS CONNECT proxy before leaving the device.

Implementation: ship a prebuilt `libbyedpi.so` (C, ~200 KB, static,
BSD-3-Clause) alongside `libgojni.so`. Start/stop via JNI.

---

## Phase 10 — Headscale support

**Changed file:** `TailscaleStateStore.kt`
**UI:** advanced setting in auth screen

`tsnet.Server.ControlURL` accepts any URL. When the user sets a custom
control URL (e.g. `https://headscale.example.com`), it is passed to
`Tailscale.start()`. No other code changes needed — all the feature APIs
work identically against Headscale.

Shown in Settings → Tailscale → Advanced → "Control server URL".

---

## Phase 11 — Service lifecycle & final wiring

`TailscaleEngine.start()` is called in `EarthQuackService.onCreate()`,
before any network operation. `TailscaleEngine.stop()` in `onDestroy()`.

The node is up for exactly as long as the foreground service is up.

State machine that EarthQuackService now follows:

```
onCreate()
  └─ TailscaleEngine.start()
       ├─ state=Starting
       ├─ if NeedsLogin → post notification → user taps → MainActivity opens login
       └─ state=Running → inject socksPort → start SSE/clipboard loops

onDestroy()
  └─ TailscaleEngine.stop()
```

---

## What stays exactly the same

| Feature | Change |
|---|---|
| rclone integration | None — same RPC, just routes through SOCKS5 |
| SSH terminal | None — MINA SSHD adds one proxy config line |
| SFTP server | None — serves LAN; tailnet peers reach it via their own path |
| Clipboard sync protocol | None — SSE + POST, different route |
| SSH key auth, TOFU, CredentialVault | None |
| Android permissions | **No new permissions.** No `BIND_VPN_SERVICE`. |

---

## Milestone summary

| Phase | Deliverable | Effort |
|---|---|---|
| **0** | tsnet Go shim + merged AAR | 2 days |
| **1** | Browser login + TailscaleStateStore | 2 days |
| **2** | TailscaleEngine + SOCKS5 wired into OkHttp / MINA / rclone | 2–3 days |
| **3** | Exit nodes UI | 1 day |
| **4** | Taildrop send + receive | 2–3 days |
| **5** | Taildrive browse + rclone bridge | 2 days |
| **6** | Serve + Funnel | 1–2 days |
| **7** | Admin console (biometric-gated) | 2–3 days |
| **8** | Delete TailscaleDiscovery + TailnetStatus, MagicDNS peer picker | 1 day |
| **9** | DPI bypass toggle | 1 day |
| **10** | Headscale control URL setting | 0.5 day |
| **11** | Service lifecycle wiring + end-to-end tests | 2 days |
| | **Total** | **~19–24 days** |

> [!IMPORTANT]
> Phases 0–2 are the hard foundation. Everything else is additive on top.
> Phases 3–11 can be shipped incrementally — each is independently useful.

> [!NOTE]
> Phase 0 unblocks Phase 1 and 2. Phase 2 must land before Phases 3–11
> (they all need the running tsnet node). Everything after Phase 2 is
> parallel and independently shippable.

---

## Open questions

1. **Hostname** — default to `Build.MODEL` sanitised (`Pixel 8` → `pixel-8`)?
   Make it editable in Settings → Tailscale.

2. **Admin API key** — entered manually once and stored in
   `EncryptedSharedPreferences`. Biometric gate before display.
   Rotation: user re-enters it. Out of scope to generate it automatically
   (would require OAuth on the Tailscale account, which is a different flow).

3. **Taildrop receive directory** — default to
   `Environment.DIRECTORY_DOWNLOADS`. Make configurable.

4. **DPI bypass** — Phase 9 is low priority unless you're in a restricted
   network. Can be skipped initially.
