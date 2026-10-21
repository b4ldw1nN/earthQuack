# Terminal Issues Report

> [!NOTE] These are **findings only** — no fixes applied. Issues are grouped by category.

---

## 1. Text Breaking on Streaming Output

### 1.1 Two Parsers Running Simultaneously (Root Cause)
`TerminalFragment` instantiates **both**:
- `private val grid = TerminalGrid(…)` — newer VT100 parser with cursor addressing
- `TerminalSpans.VT100Parser` wrapping `TerminalScreenBuffer` — older flat-log parser

Both receive shell data. The `draw()` path goes through `TerminalSpans`, making `TerminalGrid` (with its `cursorRow`/`cursorCol`) effectively dead code — but it still accumulates state. Any interaction between the two can corrupt what's displayed.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt), [`TerminalSpans.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalSpans.kt)

---

### 1.2 `setText()` on Every Frame (Performance)
`draw()` calls `binding.terminalLog.setText(spannable)` on every data event. `TextView.setText()` discards all previous state and triggers a **full layout pass from scratch** regardless of delta size. Under `yes`, `tail -f`, or `top`, this floods the UI thread — causing visible stutter and dropped frames.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt) — `draw()` method

---

### 1.3 Growing `NestedScrollView` + `fillViewport` Layout Cost
`fragment_terminal.xml` has:
```xml
<NestedScrollView android:fillViewport="true">
  <TextView android:layout_height="wrap_content" />
</NestedScrollView>
```
`fillViewport` forces the TextView to be at least viewport height. `wrap_content` forces a full re-measure of **all text** on every `setText()`. As scrollback grows toward `MAX_SCROLLBACK = 2000 lines` / `DISPLAY_CAP = 48 KB`, layout time grows **linearly** — no recycling, no partial invalidation.

**File:** [`fragment_terminal.xml`](file:///mnt/Storage/earthQuack/app/src/main/res/layout/fragment_terminal.xml), [`TerminalScreenBuffer.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalScreenBuffer.kt)

---

### 1.4 `drawPending` Coalescing Causes Visual Jumps
`onShellData` sets `drawPending = true` and posts `drawRunnable`. If I/O fires faster than 16 ms, multiple bursts all collapse into a single draw — all intermediate state is silently skipped. This causes the terminal to visually jump forward rather than scroll smoothly through output.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt) — `onShellData` / `drawRunnable`

---

### 1.5 `setTextIsSelectable(true)` Disables Hardware Acceleration
Selectable text adds an `ActionMode` overlay and disables hardware-accelerated text rendering in some layout paths. Combined with a constantly-changing `SpannableStringBuilder`, this produces rendering artifacts (flicker, partial redraws).

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt)

---

### 1.6 Window Resize Feedback Loop Risk
`wireResize()` attaches an `addOnGlobalLayoutListener`. After every `setText()` → layout pass, the listener fires, measures `"M"` width, and potentially calls `resizeShell()` (sends PTY resize to server). The server may respond with a redraw → `onShellData` → another layout pass → listener fires again. Guards (`lastRows`/`lastCols`) exist but are mutable on the main thread with no synchronization.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt) — `wireResize()`

---

## 2. Text Breaking When Typing

### 2.1 IME Batch Deletion Sends Too Many Backspaces
`TerminalInputView.afterTextChanged()` sends one `0x7F` per character deleted (`changeBefore` copies). GBoard and SwiftKey long-press backspace deletes whole words at once — 5–10 characters. The shell receives 5–10 `0x7F` bytes instantly, often deleting past what was intended.

**File:** [`TerminalInputView.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalInputView.kt) — `afterTextChanged()`

---

### 2.2 Mirror Drift — The Core Typing Bug
`TerminalInputView` maintains an invisible mirror of the shell's line buffer to count backspaces. The mirror drifts from reality because:
- The shell **echoes** characters that are never inserted into the mirror
- Shell aliases, readline hooks, or prompt expansions change the buffer invisibly
- After drift, every backspace count is wrong — wrong number of bytes sent, visible corruption

**File:** [`TerminalInputView.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalInputView.kt)

---

### 2.3 Tab (Autocomplete) Doesn't Reset Mirror
`sendControl()` sends byte `9` (Tab) but does **not** call `clearMirror()`. If the shell autocompletes `"git che"` → `"git checkout"`, the mirror still holds `"git che"` (7 chars). The next backspace deletes 1 byte from the mirror but the shell sees it applied to `"checkout"` — permanent desync from that point.

**File:** [`TerminalInputView.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalInputView.kt) — `sendControl()` / Tab case

---

### 2.4 Enter Can Fire Twice
Three paths all send Enter:
1. `afterTextChanged` sees a `\n` → `forward()` → `ENTER_BYTE (0x0D)`
2. The control key bar has an explicit **Enter** button
3. `onKeyPreIme` intercepts `KEYCODE_ENTER` from hardware keyboards

On some IMEs, pressing the Enter key triggers both path 1 and path 3 simultaneously — command is submitted twice.

**File:** [`TerminalInputView.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalInputView.kt)

---

## 3. UI Issues

### 3.1 No Cursor Indicator
`TerminalGrid.cursorRow` / `cursorCol` are computed but never rendered. There is no blinking block cursor or underline showing where the next character will appear. Users have no visual feedback when the shell is idle vs. waiting for input.

**File:** [`TerminalGrid.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalGrid.kt), [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt)

---

### 3.2 Control Key Bar Overflows on Small Screens
`wireButtons()` inflates `MaterialButton` instances with no compact style. They get default Material padding (~12 dp horizontal). On phones ≤360 dp wide, the bar overflows the `HorizontalScrollView` with no scroll indicator — buttons like `ESC`, `F1–F4`, and arrow keys are invisible/unreachable.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt) — `wireButtons()`

---

### 3.3 Status Text Pushes Buttons Off Screen
`statusText` (`13sp`, monospace) has no `maxLines` or `ellipsize`. Long error strings like `"Error: javax.net.ssl.SSLHandshakeException: …"` wrap to multiple lines and push the **Connect / Disconnect** buttons below the visible fold.

**File:** [`fragment_terminal.xml`](file:///mnt/Storage/earthQuack/app/src/main/res/layout/fragment_terminal.xml)

---

### 3.4 Invisible Input Field Wastes 44 dp
The bottom `inputField` (`minHeight="44dp"`) is kept invisible but permanently reserves screen space. A real terminal puts this sink at 0 dp height or removes it from the layout hierarchy entirely.

**File:** [`fragment_terminal.xml`](file:///mnt/Storage/earthQuack/app/src/main/res/layout/fragment_terminal.xml)

---

### 3.5 No "Scroll to Bottom" Button
`userScrolledUp` is tracked in code but there is no FAB or affordance to jump back to live output. Users must manually drag back to the bottom after scrolling up — unusable during fast-streaming commands.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt)

---

### 3.6 Font Size Hardcoded at 13sp — No Zoom
Font size is set in both XML (`android:textSize="13sp"`) and in `onViewCreated` (`binding.terminalLog.textSize = 13f`). No pinch-to-zoom. On high-density displays (440+ dpi) 13sp renders extremely small.

**File:** [`fragment_terminal.xml`](file:///mnt/Storage/earthQuack/app/src/main/res/layout/fragment_terminal.xml), [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/terminal/TerminalFragment.kt)

---

## 4. Width Problem

### 4.1 Column Math Uses a Single Glyph Width
`wireResize()` computes `cols = viewportW / log.paint.measureText("M")`. With `lineSpacingMultiplier="1.1"` and the NestedScrollView/TextView padding, this can be off by one or more columns, so the PTY and the grid disagree on line width — causing premature wrapping or a clipped last column.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/ui/TerminalFragment.kt) — `wireResize()`

### 4.2 Resize Does Not Reflow
`TerminalGrid.resize()` only copies the overlapping top-left region; text written at the old width stays truncated/misaligned after a width change.

**File:** [`TerminalGrid.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/ui/TerminalGrid.kt) — `resize()`

### 4.3 Trailing Spaces Are Not Trimmed
`runsFrom` turns untouched cells (`0`) into spaces, and `renderTrimmed()` only trims blank *rows*, not trailing spaces, so long lines can force horizontal scrolling and interact badly with `fillViewport`.

**File:** [`TerminalGrid.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/ui/TerminalGrid.kt) — `runsFrom()`, `renderTrimmed()`

### 4.4 Resize Feedback Loop Risk (Width)
`addOnGlobalLayoutListener` recomputes geometry after every `setText()` → layout; guards exist but are unsynchronized, so width changes can repeatedly trigger PTY resizes/redraws.

**File:** [`TerminalFragment.kt`](file:///mnt/Storage/earthQuack/app/src/main/kotlin/com/example/earthquack/ui/TerminalFragment.kt) — `wireResize()`

### 4.5 Control Bar Width Overflow
`HorizontalScrollView fillViewport="true"` with `wrap_content` buttons can overflow on narrow screens, hiding keys.

**File:** [`fragment_terminal.xml`](file:///mnt/Storage/earthQuack/app/src/main/res/layout/fragment_terminal.xml)

---

## Summary Table

| # | Category | Issue | Severity |
|---|----------|-------|----------|
| 1.1 | Streaming | Two parsers (`TerminalGrid` + `VT100Parser`) both active, one is dead | 🔴 High |
| 1.2 | Streaming | `setText()` on every frame — full layout pass each time | 🔴 High |
| 1.3 | Streaming | `NestedScrollView fillViewport` + `wrap_content` = O(n) measure per draw | 🔴 High |
| 1.4 | Streaming | `drawPending` coalescing causes visual jumps on fast output | 🟡 Medium |
| 1.5 | Streaming | `setTextIsSelectable` disables HW acceleration | 🟡 Medium |
| 1.6 | Streaming | Resize listener may create a redraw feedback loop | 🟡 Medium |
| 2.1 | Typing | IME batch backspace sends too many `0x7F` | 🔴 High |
| 2.2 | Typing | Mirror drift causes wrong backspace counts | 🔴 High |
| 2.3 | Typing | Tab autocomplete doesn't reset mirror → permanent desync | 🔴 High |
| 2.4 | Typing | Enter can fire twice (IME + hardware keyboard + control bar) | 🟡 Medium |
| 3.1 | UI | No cursor indicator | 🟡 Medium |
| 3.2 | UI | Control bar overflows on small screens | 🟡 Medium |
| 3.3 | UI | Status text can push Connect/Disconnect off screen | 🟡 Medium |
| 3.4 | UI | Invisible input field wastes 44 dp | 🟢 Low |
| 3.5 | UI | No scroll-to-bottom button | 🟡 Medium |
| 3.6 | UI | Font size hardcoded, no zoom | 🟢 Low |
| 4.1 | Width | Column math off by one or more columns | 🔴 High |
| 4.2 | Width | Resize does not reflow existing text | 🔴 High |
| 4.3 | Width | Trailing spaces not trimmed | 🟡 Medium |
| 4.4 | Width | Resize feedback loop risk | 🟡 Medium |
| 4.5 | Width | Control bar overflow on narrow screens | 🟡 Medium |
