# EarthQuack — Work Tracker (Tailscale embedded)

> Multi-agent coordination file. **Edit only your own section.** Each section
> names its owner. Push when your part is done.

Last updated: 2026-10-21 · branch: feature/tailscale-embedded (owner: tailscale-embedded agent)

---

## Section TS — Embedded Tailscale (owner: tailscale-embedded agent)

### Roadmap
`docs/tailscale-embedded-roadmap.md` — phases 0–11. This agent owns Phase 0 first,
then proceeds sequentially unless another agent claims a later phase on another branch.

### Status: Phase 0 IN PROGRESS — Go shim + merged AAR

**Done this session:**
- Committed prior working-tree changes (build.gradle.kts, deleted binaries, roadmap).
- Created branch `feature/tailscale-embedded`.
- Cleared this tracker; started Phase 0 tracking.
- Added `rclone-android/tailscale/tailscale.go` — full Go shim over tsnet:
  lifecycle (Start/Stop/Status), peers/this-device, exit node, taildrop,
  taildrive, serve, funnel, admin API, resolve, whois. SOCKS5 loopback proxy.
- Updated `rclone-android/go.mod` to add tailscale.com v1.104.1 + go 1.27.1.
- Updated `scripts/build-rclone-aar.sh` to build/bind `./tailscale`.

**Next:**
- Verify `go build ./rclone/ ./tailscale/` and `gomobile bind` produce merged AAR.
- Phase 1: TailscaleAuthManager + TailscaleStateStore (Kotlin).
- Phase 2: TailscaleEngine + SOCKS5 wiring.

### Phase status table

| Phase | Status |
|---|---|
| 0 Go shim + AAR | in progress |
| 1 Browser login + state store | not started |
| 2 TailscaleEngine + SOCKS5 | not started |
| 3 Exit nodes | not started |
| 4 Taildrop | not started |
| 5 Taildrive | not started |
| 6 Serve & Funnel | not started |
| 7 Admin console | not started |
| 8 MagicDNS peer discovery | not started |
| 9 DPI bypass | not started |
| 10 Headscale | not started |
| 11 Service lifecycle | not started |

### Files this agent owns
- `rclone-android/tailscale/**`
- `rclone-android/go.mod` (additive)
- `scripts/build-rclone-aar.sh`
- `docs/tailscale-embedded-roadmap.md` (read-only reference)
- Kotlin: `tailscale/**` (phases 1+)

### Coordination
- Other agents may work on other phases on other branches. Rebase/merge carefully.
- Do not edit Section A–E legacy content (removed on clear); refer to git history.
