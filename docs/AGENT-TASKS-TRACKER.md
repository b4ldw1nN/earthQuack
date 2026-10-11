# EarthQuack — Active Work Tracker

> Previous multi-agent tasks (SSH terminal grid emulator, Share-as-link, video
> streaming) are **DONE and pushed** (commit `02915b1`). This tracker now tracks
> only the current terminal-input work.

## Current focus: Terminal input UX + back navigation

Owner: this session. Do not touch rclone / SFTP / Share-as-link / video code.

### Open issues
- [ ] **I4 — Verify back-nav fix on device.** Code fixed + APK built; device
  dropped off ADB before live test. Reconnect phone, then: open Connections →
  press ← → must return to Services **and** reset the app bar (title + hide
  Back arrow). Repeat from SFTP/Clipboard and from Terminal.
- [x] **I1 — Double-tap to type.** Fixed by removing the separate EditText
  (see I3); terminal taps focus the inline sink once.
- [x] **I2 — `ls` produced no on-screen response.** `submitInput()` never sent
  Enter, so the cooked-mode shell waited forever. `commandWithEnter()` now
  appends CR (0x0D). Regression: `TerminalCommandSubmitTest` 5/5.
- [x] **I3 — Termux-style inline input.** Implemented (TerminalInputView).

### Findings / decisions
- **I3 (Termux-style input, DONE):** Replaced the bottom EditText + Send button
  with a full-width, transparent `TerminalInputView` sink whose soft-keyboard
  keystrokes are forwarded straight to the shell (PTY echoes them). Two bugs
  fixed to make it reliable:
  - **IME desync (only one letter):** the sink consumed `commitText` but never
    inserted into the editable, so Gboard's cursor diverged from the (empty)
    field and stopped appending. Fix: let the IME insert normally, siphon each
    change to the shell via a TextWatcher, then clear the field immediately.
  - **Focus-steal loop (only one char):** `scrollToBottom()` used
    `fullScroll(FOCUS_DOWN)`, which *steals focus* to the last focusable view
    (`terminalLog`) after every echo → next keystrokes went nowhere. Fix: scroll
    with `scrollTo(0, bottom)` (no focus change).
  Layout width fixed too (input no longer squeezed next to Send button).
- **I5 — Back navigation (DONE, needs device confirm).** Root cause: the
  toolbar Back arrow called `onBackPressedDispatcher.onBackPressed()`, but the
  app-bar reset lived in the **deprecated `onBackPressed()` override**, which
  the dispatcher does NOT reliably invoke. So the pushed content popped while
  the app bar stayed stale ("Connections" + Back arrow), and from a restored
  state the gesture exited the app. Fix in `MainActivity`: registered a single
  `OnBackPressedCallback` and extracted shared `popSubScreen()` (pop + restore
  app bar + transparent content bg). Toolbar arrow, system gesture, and tab
  reselect all funnel through it, so content and app bar always move together.
  `compileDebugKotlin` BUILD SUCCESSFUL; APK 52 MB built.

### Verifications this session
- `TerminalCommandSubmitTest` 5/5 · `TerminalGridTest` 20/20 ·
  `TerminalScreenBufferTest` 40/40 · `compileDebugKotlin` green.
- Device: V2253 (arm64). **Blocked on I4** — phone fell off ADB
  (`30fa:0400` gone from `lsusb`); needs physical USB reconnect.

