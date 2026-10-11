# EarthQuack — Feature Work Tracker (SSH terminal · Share as link · Video streaming)

**Purpose:** a single, live tracker so multiple agents do not collide.
**Rule:** read this before touching anything, and update the matching section after
every file you change. Do not edit another agent's in-progress feature unless they
are finished or blocked. Recreated 2026-10-10 19:0x by the terminal owner after the
previous copy was removed.

- **Repo:** `/mnt/Storage/earthQuack`
- **Base commit:** `02e5581` — *fix(files): byte units, stable download cache, PDF viewer, cache settings*
- **Working-tree note:** `docs/rclone-file-download.md` is tracked in HEAD; the
  streaming handover belongs there.

---

## Ownership map

| Feature | Status | Owner | Files |
|---|---|---|---|
| **Task 1 — SSH terminal reliability** | implementing (runaway-output/viewport fix) | **terminal owner (this session)** | `ui/TerminalFragment.kt`, `ui/TerminalScreenBuffer.kt` (`shouldFollowOutput` only), `ui/TerminalScreenBufferTest.kt` |
| **Task 2 — "Share as link" via rclone** | code-complete + JVM-green | other agent | `storage/RcloneShareLink.kt`, `RcloneRemoteManager.driveRemotes()`, `FileBrowserActivity`, `dialog_share_link.xml`, tests |
| **Task 3 — Video streaming** | implementing | other agent | `stream/`, `ui/VideoPlayerActivity.kt`, `rclone-android/rclone/read.go`, `storage/RcloneRangeSource.kt` |

---

## Task 1 — Terminal runaway output / viewport (ACTIVE)

### Root cause (evidence-based, NOT the remote)

Reproduced on the connected Arch laptop (Vivo V2253, arm64) via adb + logcat:

- Logcat for one connect: `onViewCreated` → `connect()` → `connected` →
  `Opening shell channel … rows=24, cols=80` → **exactly one** `resized to 36x53`.
  No repeated `onShellData`, no repeated resize, no second shell channel. **Bytes
  are not runaway; there is one reader per channel; no reconnect loop.**
- `uiautomator` dump of `terminalLog` while "Connected" showed the TextView held
  `"\n"×12 + "❯ "` — the real Arch zsh prompt preceded by its startup blank lines.
  **The content is correct; the display was broken.**

The defect was UI-side in `TerminalFragment.draw()`:

- It called `terminalScroll.fullScroll(FOCUS_DOWN)` **unconditionally on every
  frame**. With `NestedScrollView fillViewport="true"` wrapping a `wrap_content`
  TextView taller than the viewport, this (a) yanked the viewport to the bottom on
  every update, ignoring the user's scroll; (b) left the startup blank lines above
  the prompt, so the prompt sat at the bottom with empty space above; (c) made the
  viewport appear to keep sliding down as each newline forced another scroll. That
  is the "black/empty, prompt near bottom, viewport moves down, scroll-up shows
  blank" symptom — a scroll feedback loop, not shell output.

### Fix

- Added `userScrolledUp` + a `wireScroll()` `OnScrollChangeListener` that tracks
  distance-from-bottom with a `NEAR_BOTTOM_SLACK_PX` (24px) tolerance.
- `draw()` no longer force-scrolls; it calls `scrollToBottom()`, which follows only
  when the user is already at/near the bottom and preserves their position once
  they scroll up. Re-arms when they return to the bottom.
- Extracted the follow rule to a pure `shouldFollowOutput(distanceFromBottom,
  slackPx)` in `TerminalScreenBuffer.kt` so it is JVM-testable.
- Reset `userScrolledUp=false` on each `connect()` so a new session follows again.
- Diagnostics (no secrets/contents): `bytesReceived`, `shellChannelsOpened`
  ("opening shell channel #N (generation G)"), per-draw rendered-char count +
  `autoscroll=` state, and scroll-trigger reason with px-from-bottom.

### Files touched
| File | Change | Date |
|---|---|---|
| `ui/TerminalFragment.kt` | conditional auto-scroll (`scrollToBottom` + `wireScroll`), `userScrolledUp`/`bytesReceived`/`shellChannelsOpened`, diagnostics logging, reset follow on connect, per-draw `drew` diagnostic `Log.d`→`Log.i` (device filters `Log.d`) | 2026-10-10/11 |
| `ui/TerminalScreenBuffer.kt` | +`shouldFollowOutput(distanceFromBottom, slackPx)` pure helper | 2026-10-10 |
| `ui/TerminalScreenBufferTest.kt` | +3 regression tests for the follow rule | 2026-10-10 |

### Tests + build
- `./gradlew :app:testDebugUnitTest --tests TerminalScreenBufferTest` → **40 tests,
  0 failures** (was 37; +3 follow-rule tests). BUILD SUCCESSFUL.
- `./gradlew :app:assembleDebug --offline` → BUILD SUCCESSFUL.

### Architectural decision (2026-10-11): move to a FIXED-GRID terminal model

Symptom is **inherent to the linear-log model**, not the parser. Evidence:
- `TerminalScreenBuffer` is a flat append-only `StringBuilder` with `lineStart`
  only for line-relative edits — **no absolute (row,col) cursor**, so cursor
  addressing (CUP/CUU/CUD/CUF/CUB) is a documented gap.
- Render is a `wrap_content` TextView in a `fillViewport` NestedScrollView +
  `fullScroll(FOCUS_DOWN)`. With short content (8 startup blanks + prompt) the
  TextView can't "sit" in a tall viewport → "empty space above, prompt near
  bottom, viewport drifts". Scroll tweaks can't fully fix a model with no screen.
- Reference: cloned `github.com/jossephus/chuchu` (MIT). It renders a **fixed
  rows×cols grid** (`TerminalSnapshot`: parallel `codepoints/fgArgb/bgArgb/flags`
  + `cursorX/cursorY` + `viewportScrollY`) from a native emulator; scrollback is
  an explicit ring (`scrollToActive()`). Blank cells are bg-coloured → never
  "empty space above the prompt".

Decision: **keep MINA SSHD + PTY + single-reader coroutine** (working). Replace
**only** the buffer+render layer with a pure-Kotlin fixed grid (NO native dep,
NO Ghostty): `TerminalGrid` (rows×cols cells + absolute cursor + scroll-up on LF
at bottom row + bounded scrollback ring), real VT cursor ops (CUP/CUU/CUD/CUF/
CUB/ED/EL/SGR/scroll region), render as exactly `rows` fixed-height monospace
lines. Fully JVM-testable (no `android.text`) → keeps the "all tests pass" gate.

Note: the remote shell is **bash** (user-confirmed), not zsh — so the `❯ `
glyph is the user's custom bash PS1, and the 8 blank lines are bash/PS1 startup
output, not a zsh artifact.

### DEFINITIVE feed-dump evidence (2026-10-11, installed build w/ `Log.i` diags)
Fresh Connect captured raw bytes (feed #1..#5). Bytes are NOT runaway (they
stop), but the **prompt is redrawn in place with cursor addressing the linear
model cannot honour** — proving the grid rewrite is required:
- feed #1 (324B): fish/zsh startup — `\e[6n` cursor-position-report queries +
  glyph-width probing (`\r\e[K[char]`). Consumed correctly (drew 0 chars).
- feed #4 (172B): the prompt, drawn with **absolute addressing**:
  `\r\n\r\n`, `\e[1A` (CUU), `\e[2L` (IL), `\e[2K` (EL), `\e[2M` (DL), `\e[1G`
  (CHA), then `zoro in 🌐 archii in ~` … `❯`. → drew 7 chars.
- feed #5 (144B): **same prompt redrawn** (`\e[2A …\e[2M\e[2L\e[1G`). → drew 10.
The linear parser treats `\e[A/\e[G/\e[L/\e[M` as **no-ops**, so each in-place
prompt redraw **appends a fresh copy** instead of overwriting → the drifting
viewport + growing blank space. This is the root cause; grid rewrite is the fix.

### RESOLVED (2026-10-11, V2253 / Vivo, `archii` profile) — fixed-grid shipped
Replaced the linear-log render with a fixed-grid emulator and wired it in:
- New pure-Kotlin `ui/TerminalGrid.kt` (rows×cols cell grid + absolute cursor
  `(row,col)` + scroll-up on LF at bottom row + bounded scrollback ring). Implements
  real VT cursor ops the linear buffer treated as no-ops: CUP `\e[r;cH`, CUU/CUD/
  CUF/CUB (`\e[nA/B/C/D`), CHA `\e[nG`, IL `\e[nL`, DL `\e[nM`, EL `\e[nK`, ED `\e[nJ`,
  SGR (incl. 256/true-colour), scroll region. Fully JVM-testable (no `android.text`).
- `TerminalSpans.kt` now bridges `TerminalGrid` → Android: `List<List<TerminalRun>>
  .toScreenSpan()` renders logical lines (scrollback + live) for the TextView.
- `TerminalFragment` render path now: `grid.feed(...)` (no per-frame correction) →
  coalesced `requestDraw()` → `draw()` calls `grid.renderTrimmed()` (trailing blank
  rows trimmed to `cursorY`, so the prompt is the last line) → auto-follow only when
  `!userScrolledUp`. `grid.resize(rows,cols)` kept in step in `resizeShell`; DSR
  `\e[6n` replies wired via `grid.onDeviceReport { send(it){} }`; `resetTerminal()`
  resets the grid. Per-draw `drew N chars …` and byte-flow `feed #N (len=…)` are `Log.i`.
- Root cause confirmed by byte-dump: Arch redraws the prompt **in place** with cursor
  addressing (`\e[1A \e[2L \e[2K \e[2M \e[1G …`); the old linear model appended a fresh
  copy each redraw → drifting viewport + growing blank space. The grid overwrites cells.
- On-device: `terminalLog` now shows a stable `rows×cols` screen with the prompt at the
  cursor row; idle terminal no longer drifts, scroll-up is respected, commands follow.
- Tests: `TerminalGridTest` **20/20 pass**, `TerminalScreenBufferTest` 40/40 (legacy
  coverage kept). `compileDebugKotlin` + `testDebugUnitTest` BUILD SUCCESSFUL.
  Debug APK (52.4 MB) rebuilt and reinstalled on V2253.

---

## Shared-file warning
`res/values/strings.xml` and `res/layout/fragment_terminal.xml` are shared. This
task only adds Kotlin in `ui/`; it does not touch strings or the terminal layout
beyond what already exists.

---

## Remaining issues
| Issue | Feature | Next action |
|---|---|---|
| ~~On-device confirmation of the terminal fix~~ **RESOLVED 2026-10-11** | Task 1 | fixed-grid shipped, APK reinstalled on V2253, idle stable + prompt at cursor row confirmed |
| Task 2 device verification (Drive upload + public link) | Task 2 | needs a Drive remote on device |
| Task 3 device verification (ExoPlayer playback/seek) | Task 3 | needs rebuilt AAR on device |
