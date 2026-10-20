# EarthQuack — Work Tracker (Tailscale embedded)

> Multi-agent coordination file. **Edit only your own section.** Each section
> names its owner. Push when your part is done.

Last updated: 2026-10-21 · branch: `feature/tailscale-embedded` (owner: tailscale-embedded agent)

---

## Section TS — Embedded Tailscale (owner: tailscale-embedded agent)

### Roadmap
`docs/tailscale-embedded-roadmap.md` — phases 0–11. This agent works the
foundation phases sequentially; later phases may be claimed by other agents
on other branches.

### Status: Phase 1 DONE — browser login + state store ✅

**Phase 0 — DONE** (commit `0f5bdf0`)
- `rclone-android/tailscale/tailscale.go` — full tsnet gomobile shim.
- `rclone-android/go.mod` — tailscale.com v1.104.1, go 1.27.1.
- `scripts/build-rclone-aar.sh` — binds `./rclone` + `./tailscale`.
- Verified: `go build -mod=mod ./rclone/ ./tailscale/` OK.
- Verified: `./scripts/build-rclone-aar.sh` → `app/libs/rclone.aar` (19 MB)
  contains `com/example/earthquack/tailscale/Tailscale.class` with all bound
  methods (checked with javap).

**Phase 1 — DONE this session**
- `app/src/main/kotlin/com/example/earthquack/tailscale/TailscaleStateStore.kt`
  — hostname + control-URL persistence, enrolment check (stateDir non-empty),
  hostname sanitisation (`Pixel 8` → `pixel-8`, JVM-tested).
- `app/src/main/kotlin/com/example/earthquack/tailscale/TailscaleAuthManager.kt`
  — Start → NeedsLogin → Custom Tab → poll Status() every 2s → Running
  state machine, exposed as `StateFlow<TailscaleLoginState>`. Login URL may
  arrive a beat after Start(); the loop surfaces it when it appears. 5-minute
  login timeout. Opens the URL in a Chrome Custom Tab with VIEW-intent
  fallback. gomobile calls sit behind `TailscaleNode`/`TailscaleNodeConfig`
  interfaces so the whole machine is JVM-testable.
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — added
  `androidx.browser:browser:1.8.0` for Custom Tabs.
- `app/src/test/kotlin/com/example/earthquack/tailscale/TailscaleAuthManagerTest.kt`
  — 15/15 JVM tests (parsing, first-run login flow, late auth URL, timeout,
  error paths).
- Verified: `:app:testDebugUnitTest` (all suites) + `:app:assembleDebug`
  green.

**Next: Phase 2** — `TailscaleEngine.kt` (lifecycle + StateFlow) + SOCKS5
wiring into OkHttp / MINA SSHD / rclone.

### Phase status table

| Phase | Status |
|---|---|
| 0 Go shim + AAR | **done** (verified: go build + merged AAR) |
| 1 Browser login + state store | **done** (verified: 15 JVM tests, assembleDebug) |
| 2 TailscaleEngine + SOCKS5 | next |
| 3 Exit nodes | not started |
| 4 Taildrop | not started |
| 5 Taildrive | not started |
| 6 Serve & Funnel | not started |
| 7 Admin console | not started |
| 8 MagicDNS peer discovery | not started |
| 9 DPI bypass | not started |
| 10 Headscale | not started |
| 11 Service lifecycle | not started |

### On-device verification needed (next ADB session)
- Phase 1 flow: fresh install → tap login → Custom Tab opens → log in →
  app state reaches Running (tab dismisses). Then force-stop + relaunch:
  must join silently with no UI.

### Files this agent owns
- `rclone-android/tailscale/**`
- `rclone-android/go.mod` (additive)
- `scripts/build-rclone-aar.sh`
- `app/libs/rclone.aar` (git-ignored build output)
- `docs/tailscale-embedded-roadmap.md` (read-only reference)
- Kotlin: `app/src/main/kotlin/com/example/earthquack/tailscale/**` (phases 1+)
- Tests: `app/src/test/kotlin/com/example/earthquack/tailscale/**`

### Coordination
- Other agents may work on other phases on other branches. Rebase/merge carefully.
- Do not edit other agents' sections (A–E legacy content lives in git history).
