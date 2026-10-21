# Embedded-Tailscale follow-up work

Items: 2 (remote health), 3 (node state visibility), 4 (desktop capability
handshake), 5 (Serve/Admin as real UI + serve persistence), 6 (SSH profile peer
picker), 7 (exit node honesty), 8 (diagnostics screen), 10 (app-hosted
clipboard API served to the desktop).

## What landed

**2 — Remote health.** `RcloneHealthProbe` asks rclone whether a remote
answers (`operations/list` on the remote root, `fs` = `name:`) and classifies
the answer: reachable, needs sign-in, unreachable, error, or not in this build.
The Storage list shows it next to the type (`drive · reachable`,
`sftp · via Tailscale · reachable`) and the rows fill in as the probes come
back, each bounded by a 12 s timeout.

**3 — Node state visibility.** `TailscaleNodeMonitor` owns the node and
publishes its state; `AppStatus` carries one app-wide message; Home and
Services show it in a banner; the service posts a "Tailscale needs you to
sign in again" notification. The background button now reflects the node's
real state.

**4 — Capability handshake.** The desktop daemon answers `GET /capabilities`
(service, version, whether AES is on, a fingerprint of the key). The app
queries it before the first clipboard exchange and says in words when the two
sides disagree about encryption — the failure used to show up as unreadable
ciphertext in the clipboard.

**5 — Serve/Admin.** Both screens are XML layouts in the app's own style.
Serves are persisted (`ServeStore`) and re-published when the node comes back,
so a restart no longer silently unpublishes the ports.

**6 — SSH profile peer picker.** The profile editor offers the tailnet peers
the node can see, so a 100.x address does not have to be typed.

**7 — Exit node.** The scope is stated in the UI: it moves this app's traffic,
not the rest of the phone's internet.

**8 — Diagnostics.** One screen with the node state and address, the SOCKS
port, the exit node, the peers, the SFTP server, the published ports, the
desktop's build and the health of every rclone remote — copyable as a report.

**10 — App-hosted clipboard API.** The phone serves its own clipboard over
HTTP (`/health`, `/capabilities`, `/clipboard`, `/events`) on port 8875,
published to the tailnet by the node. The desktop bridge can point at the
phone instead of running a server of its own:

```
CLIPBOARD_SERVER_HOST=100.121.203.68 CLIPBOARD_SERVER_PORT=8875 python3 daemon/desktop.py
```

## Notes

- The bundled rclone has no `about` method, and the fs must carry its colon
  (`archii:`), or rclone reads the name as a local path.
- `protondrive` and `koofr` remotes in the config are not part of the bundled
  rclone; the health probe now says so instead of "error".
