package com.example.earthquack.ui

/**
 * A fixed `rows×cols` terminal screen with a real addressable cursor.
 *
 * ## Why this replaces [TerminalScreenBuffer]
 *
 * The old buffer was a flat append-only `StringBuilder`. It could not represent
 * a screen, so every cursor-addressing sequence a real shell prompt relies on —
 * `CUU`/`CUD`/`CHA`/`CUP`, `IL`/`DL` (insert/delete line), `ED`/`EL` — was a
 * documented no-op. A prompt that redraws itself in place (fish, powerlevel10k,
 * a multi-line bash `PS1`) therefore **appended a fresh copy on every redraw**
 * instead of overwriting, which is exactly the "viewport drifts down, blank
 * space above the prompt" report. See the feed dumps in the tracker.
 *
 * This grid keeps a real screen: characters land in `(row, col)` cells, the
 * cursor moves with the standard VT ops, and a line feed at the bottom of the
 * scroll region scrolls the region up (pushing the top line into a bounded
 * scrollback ring) rather than emitting a literal `\n`. Blank cells are blank,
 * so there is never phantom text above the prompt.
 *
 * ## Scope
 *
 * This is still a **subset** of VT100/xterm, not a full emulator: there is no
 * alt-screen buffer, no tabs, no mouse, no image protocol, no wide-character
 * (CJK) double-width handling, and no true-colour cells (RGB is snapped to the
 * 256 palette, as in the old renderer). Full-screen programs (vim, htop) remain
 * out of scope. What it *does* fix is in-place prompt redraw and scrolling.
 *
 * ## Testability
 *
 * Pure Kotlin — no `android.text`. The whole state machine is JVM-unit-testable,
 * which is what let the linear buffer's bugs be pinned in the first place. The
 * Android span rendering lives in `TerminalSpans.kt`.
 */
class TerminalGrid(

    /** Visible rows. The remote PTY is sized to match the rendered viewport. */
    var rows: Int,

    /** Visible columns. */
    var cols: Int
) {

    /** One retained (scrolled-off) line, as fixed-width cell arrays. */
    class Line(
        val codes: IntArray,
        val fg: IntArray,
        val bg: IntArray,
        val flags: IntArray
    )

    // ── screen cells ────────────────────────────────────────────────────────
    // Parallel arrays indexed by `row * cols + col`. Codepoint 0 marks a cell
    // never written (distinct from an explicit space) so a fresh screen is all
    // background with no text. A colour of [NO_COLOR] means "terminal default",
    // which must be distinct from palette index 0 (black).
    private var codes = IntArray(rows * cols)
    private var fg = IntArray(rows * cols) { NO_COLOR }
    private var bg = IntArray(rows * cols) { NO_COLOR }
    private var flags = IntArray(rows * cols)

    // ── cursor ──────────────────────────────────────────────────────────────
    private var cursorX = 0
    private var cursorY = 0

    /** Set after writing into the last column; the next printable wraps. */
    private var wrapPending = false

    // ── scroll region (inclusive, 0-based) ───────────────────────────────────
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // ── saved cursor (DECSC/DECRC) ────────────────────────────────────────────
    private var savedX = 0
    private var savedY = 0

    // ── scrollback ring ──────────────────────────────────────────────────────
    private val scrollback = ArrayDeque<Line>()

    /**
     * How many lines the view is scrolled up into [scrollback].
     *
     * 0 means the live screen is shown. The fragment surfaces a "back to live"
     * affordance whenever this is > 0, mirroring the reference implementation.
     */
    var viewportOffset = 0
        private set

    /** Live cursor position for the renderer to draw a block, when live. */
    val cursorRow get() = cursorY
    val cursorCol get() = cursorX

    /**
     * Invoked when the remote asked for a device status report (`\e[6n`, DSR).
     * The fragment answers by writing the bytes back to the shell. Prompts such
     * as fish use these to measure glyph width and will fall back after a
     * timeout if unanswered, so answering keeps startup snappy and stops
     * redundant width probing.
     */
    var onDeviceReport: ((ByteArray) -> Unit)? = null

    private enum class State { GROUND, ESC, CSI, OSC, OSC_STRING, CHARSET }

    private var state = State.GROUND
    private val params = ArrayList<Int>(8)
    private var paramDigits = 0
    private var privateMarker = ' '
    private val oscBuffer = StringBuilder(64)

    // UTF-8 sequence split across reads.
    private var utf8: ByteArray? = null
    private var utf8Needed = 0

    // Current SGR attributes applied to newly written cells.
    private var curFg = NO_COLOR
    private var curBg = NO_COLOR
    private var curBold = false
    private var curUnderline = false
    private var curReverse = false
    private var curConcealed = false

    private val lock = Any()

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Feeds raw bytes from the remote shell.
     *
     * Called on an sshd I/O thread; the [lock] keeps a half-updated screen from
     * being read by the main thread mid-render. This is the same contract the
     * old buffer had, so the reader coroutine is unchanged.
     */
    fun feed(data: ByteArray, offset: Int, len: Int) {
        synchronized(lock) {
            var i = offset
            val end = offset + len
            while (i < end) {
                val b = data[i].toInt() and 0xFF
                i++

                // A pending UTF-8 sequence always wins: the lead byte's range
                // neighbours the continuation range, so the byte has to be
                // resolved as a continuation before anything else reads it, or
                // a marker split across two reads is corrupted.
                if (utf8 != null) {
                    if (isContinuation(b)) {
                        val bytes = utf8!! + byteArrayOf(b.toByte())
                        if (bytes.size >= utf8Needed) {
                            utf8 = null
                            putChar(decodeCodepoint(bytes))
                            continue
                        }
                        utf8 = bytes
                        continue
                    }
                    // Not a continuation: the pending sequence is broken. Emit a
                    // replacement, then let this byte be judged on its own
                    // rather than swallowing it.
                    utf8 = null
                    putChar(REPLACEMENT)
                }

                // ESC restarts a sequence from wherever it appears — the
                // terminal-standard reading. It keeps a CSI split across a write
                // boundary from eating the *next* sequence's opening bytes.
                if (b == 0x1B) {
                    state = State.ESC
                    continue
                }

                when (state) {
                    State.GROUND -> ground(b)
                    State.ESC -> escape(b)
                    State.CSI -> csi(b)
                    State.OSC -> osc(b)
                    State.OSC_STRING -> oscString(b)
                    State.CHARSET -> state = State.GROUND // consume the designator byte
                }
            }
        }
    }

    /** Drops all screen content, scrollback, and parser state. Any thread. */
    fun reset() = synchronized(lock) {
        codes.fill(0)
        fg.fill(NO_COLOR)
        bg.fill(NO_COLOR)
        flags.fill(0)
        cursorX = 0
        cursorY = 0
        wrapPending = false
        scrollTop = 0
        scrollBottom = rows - 1
        savedX = 0
        savedY = 0
        scrollback.clear()
        viewportOffset = 0
        state = State.GROUND
        params.clear()
        paramDigits = 0
        privateMarker = ' '
        oscBuffer.setLength(0)
        utf8 = null
        utf8Needed = 0
        curFg = NO_COLOR
        curBg = NO_COLOR
        curBold = false
        curUnderline = false
        curReverse = false
        curConcealed = false
        Unit
    }

    /**
     * Resizes the screen, preserving the overlapping top-left region.
     *
     * The remote PTY is resized to match, so the shell redraws its prompt at
     * the new size. Scrollback lines carry their own width and are padded or
     * trimmed on render, so they survive a resize unchanged.
     */
    fun resize(newRows: Int, newCols: Int) = synchronized(lock) {
        if (newRows == rows && newCols == cols) return@synchronized
        val nr = newRows.coerceAtLeast(1)
        val nc = newCols.coerceAtLeast(1)
        val oldRows = rows
        val oldCols = cols

        // Capture the current screen as cell rows.
        val oldScreen = ArrayList<ArrayList<Cell>>(oldRows)
        for (r in 0 until oldRows) {
            val row = ArrayList<Cell>(oldCols)
            val base = r * oldCols
            for (c in 0 until oldCols) {
                row.add(Cell(codes[base + c], fg[base + c], bg[base + c], flags[base + c]))
            }
            oldScreen.add(row)
        }

        // A terminal keeps its text when the window changes size: it does not
        // re-wrap what is already on the screen.
        //
        // The old code re-flowed every row into the new width and kept the
        // *top* rows when the screen got shorter. Both were wrong here: the
        // re-flow moved the cursor off its cell, and dropping the bottom
        // rows cut the prompt off when the keyboard opened — the shell then
        // redrew it, and the user saw extra blank lines appear.
        //
        // So: the content is anchored at the bottom, the rows that no longer
        // fit go to the scrollback, and the cursor keeps its cell.
        val keep = minOf(oldRows, nr)
        val firstKept = oldRows - keep

        // Allocate new arrays.
        codes = IntArray(nr * nc)
        fg = IntArray(nr * nc) { NO_COLOR }
        bg = IntArray(nr * nc) { NO_COLOR }
        flags = IntArray(nr * nc)

        // The rows that no longer fit become scrollback, oldest first.
        for (r in 0 until firstKept) {
            val text = rowText(oldScreen[r])
            if (text.isNotEmpty()) pushScrollback(lineOf(text))
        }

        for (r in 0 until keep) {
            val row = oldScreen[firstKept + r]
            for (c in 0 until nc) {
                val to = r * nc + c
                if (c < row.size) {
                    val cell = row[c]
                    codes[to] = cell.code
                    fg[to] = cell.fg
                    bg[to] = cell.bg
                    flags[to] = cell.flags
                }
            }
        }

        rows = nr
        cols = nc
        // The cursor stays on its cell; when the screen shrank, it moves up
        // with the content it belongs to.
        cursorY = (cursorY - firstKept).coerceIn(0, rows - 1)
        cursorX = cursorX.coerceIn(0, cols - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        if (viewportOffset > scrollback.size) viewportOffset = scrollback.size
        Unit
    }

    /** The visible text of a row, trimmed of the trailing blanks. */
    private fun rowText(row: List<Cell>): String {
        val sb = StringBuilder(row.size)
        for (cell in row) {
            val code = cell.code
            sb.append(if (code == 0) ' ' else code.toChar())
        }
        return sb.toString().trimEnd()
    }

    /** A scrollback line holding [text]. */
    private fun lineOf(text: String): Line {
        val n = text.length
        val lineCodes = IntArray(n)
        val lineFg = IntArray(n) { NO_COLOR }
        val lineBg = IntArray(n) { NO_COLOR }
        val lineFlags = IntArray(n)
        for (i in 0 until n) {
            lineCodes[i] = text[i].code
        }
        return Line(lineCodes, lineFg, lineBg, lineFlags)
    }

    private data class Cell(val code: Int, val fg: Int, val bg: Int, val flags: Int)

    private fun blankRow(nc: Int): ArrayList<Cell> {
        val r = ArrayList<Cell>(nc)
        repeat(nc) { r.add(Cell(0, NO_COLOR, NO_COLOR, 0)) }
        return r
    }

    // ── ground state ────────────────────────────────────────────────────────

    /** Handles a byte that is not inside an escape sequence. */
    private fun ground(b: Int) {
        when (b) {
            0x07 -> Unit // BEL
            0x08 -> { // BS
                wrapPending = false
                if (cursorX > 0) cursorX-- else cursorX = cols - 1
            }
            0x09 -> { // TAB to next 8-column stop
                val next = ((cursorX / 8) + 1) * 8
                cursorX = minOf(next, cols - 1)
                wrapPending = false
            }
            0x0A, 0x0B, 0x0C -> { // LF / VT / FF: line feed
                lineFeed()
            }
            0x0D -> { // CR: column 0
                wrapPending = false
                cursorX = 0
            }
            in 0x00..0x1F, 0x7F -> Unit // other control bytes: nothing to draw
            0x9B -> { state = State.CSI; beginCsi() }
            0x9D -> { state = State.OSC; oscBuffer.setLength(0) }
            in 0x20..0x7E -> putChar(b)
            else -> if (isLead(b)) {
                utf8 = byteArrayOf(b.toByte())
                utf8Needed = lengthOf(b)
            } else {
                putChar(REPLACEMENT)
            }
        }
    }

    /** Writes one codepoint at the cursor, wrapping and scrolling as needed. */
    private fun putChar(cp: Int) {
        if (wrapPending) {
            cursorX = 0
            lineFeed()
            wrapPending = false
        }
        if (cursorY < 0) cursorY = 0
        if (cursorY >= rows) cursorY = rows - 1
        if (cursorX < 0) cursorX = 0
        if (cursorX >= cols) cursorX = cols - 1

        val idx = cursorY * cols + cursorX
        // SGR 8 (conceal) stores the codepoint but flags it so the renderer can
        // skip it — the same treatment the linear buffer gave password prompts.
        codes[idx] = cp
        fg[idx] = curFg
        bg[idx] = curBg
        flags[idx] = cellFlags()

        if (cursorX == cols - 1) {
            wrapPending = true
        } else {
            cursorX++
        }
    }

    /** Moves the cursor down one row, scrolling the region if at the bottom. */
    private fun lineFeed() {
        if (cursorY == scrollBottom) {
            scrollUp(1)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    // ── scroll ──────────────────────────────────────────────────────────────

    /** Scrolls the scroll region up by [n], pushing top lines to scrollback. */
    private fun scrollUp(n: Int) {
        if (scrollTop == 0 && scrollBottom == rows - 1) {
            // Full-screen scroll: retired lines become scrollback.
            repeat(n) { pushScrollback(captureRow(0)) }
        }
        for (step in 0 until n) {
            for (r in scrollTop until scrollBottom) copyRow(r + 1, r)
            clearRow(scrollBottom)
        }
        // Live output scrolled: drop any stale viewport offset so the cursor
        // stays reachable, exactly as the live buffer stayed followed.
        wrapPending = false
    }

    /** Scrolls the scroll region down by [n]; dropped bottom lines are lost. */
    private fun scrollDown(n: Int) {
        for (step in 0 until n) {
            for (r in scrollBottom downTo scrollTop + 1) copyRow(r - 1, r)
            clearRow(scrollTop)
        }
    }

    private fun pushScrollback(line: Line) {
        scrollback.addLast(line)
        while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
    }

    private fun captureRow(r: Int): Line {
        val base = r * cols
        return Line(
            codes.copyOfRange(base, base + cols),
            fg.copyOfRange(base, base + cols),
            bg.copyOfRange(base, base + cols),
            flags.copyOfRange(base, base + cols)
        )
    }

    private fun copyRow(from: Int, to: Int) {
        if (from == to) return
        val fs = from * cols
        val ts = to * cols
        for (c in 0 until cols) {
            codes[ts + c] = codes[fs + c]
            fg[ts + c] = fg[fs + c]
            bg[ts + c] = bg[fs + c]
            flags[ts + c] = flags[fs + c]
        }
    }

    private fun clearRow(r: Int) {
        val base = r * cols
        for (c in 0 until cols) {
            codes[base + c] = 0
            fg[base + c] = NO_COLOR
            bg[base + c] = NO_COLOR
            flags[base + c] = 0
        }
    }

    private fun cellFlags(): Int {
        var f = 0
        if (curBold) f = f or FLAG_BOLD
        if (curUnderline) f = f or FLAG_UNDERLINE
        if (curReverse) f = f or FLAG_REVERSE
        if (curConcealed) f = f or FLAG_CONCEAL
        return f
    }

    // ── escape / CSI / OSC ──────────────────────────────────────────────────

    /** Escape-sequence dispatch (state == ESC). */
    private fun escape(b: Int) {
        when (b) {
            0x5B -> { state = State.CSI; beginCsi() } // CSI: ESC [
            0x5D, 0x50, 0x58, 0x5E, 0x5F -> { state = State.OSC; oscBuffer.setLength(0) }
            0x28, 0x29, 0x2A, 0x2B, 0x2D, 0x2E, 0x2F -> state = State.CHARSET
            0x37 -> { savedX = cursorX; savedY = cursorY } // DECSC
            0x38 -> { cursorX = savedX.coerceIn(0, cols - 1); cursorY = savedY.coerceIn(0, rows - 1); wrapPending = false } // DECRC
            0x44 -> lineFeed() // IND: index, cursor down
            0x45 -> { cursorX = 0; lineFeed() } // NEL: next line
            0x4D -> if (cursorY == scrollTop) scrollDown(1) else if (cursorY > 0) cursorY-- // RI
            0x63 -> reset() // RIS
            0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            else -> state = State.GROUND // unknown single-byte escape
        }
    }

    private fun beginCsi() {
        params.clear()
        paramDigits = 0
        privateMarker = ' '
    }

    /** CSI parameter/intermediate accumulation. */
    private fun csi(b: Int) {
        when (b) {
            in 0x30..0x39 -> {
                paramDigits = paramDigits * 10 + (b - 0x30)
                if (paramDigits > 0xFFFF) paramDigits = 0xFFFF
            }
            0x3B -> { params.add(paramDigits); paramDigits = 0 } // ;
            0x3A -> { params.add(paramDigits); paramDigits = 0 } // : (sub-params; treated as separators)
            in 0x3C..0x3F -> privateMarker = b.toChar() // < = > ? private markers
            in 0x40..0x7E -> {
                if (paramDigits > 0 || params.isEmpty()) params.add(paramDigits)
                paramDigits = 0
                dispatchCsi(b.toChar())
                state = State.GROUND
            }
            else -> state = State.GROUND // control byte inside CSI: abort
        }
    }

    /** Executes a completed CSI sequence. */
    private fun dispatchCsi(final: Char) {
        // Private-marker sequences (cursor visibility ?25h, mouse, etc.) are not
        // screen-affecting; consume them without touching the grid.
        if (privateMarker == '?' || privateMarker == '<' ||
            privateMarker == '=' || privateMarker == '>'
        ) {
            return
        }
        when (final) {
            'A' -> cursorUp(arg(0, 1))
            'B' -> cursorDown(arg(0, 1))
            'C' -> cursorForward(arg(0, 1))
            'D' -> cursorBack(arg(0, 1))
            'E' -> { cursorDown(arg(0, 1)); cursorX = 0; wrapPending = false } // CNL
            'F' -> { cursorUp(arg(0, 1)); cursorX = 0; wrapPending = false } // CPL
            'G', '`' -> { cursorX = (arg(0, 1) - 1).coerceIn(0, cols - 1); wrapPending = false } // CHA/HPA
            'd' -> { cursorY = (arg(0, 1) - 1).coerceIn(0, rows - 1); wrapPending = false } // VPA
            'H', 'f' -> { // CUP / HVP
                cursorY = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                cursorX = (arg(1, 1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'I' -> { // CHT: cursor forward tab stops
                repeat(arg(0, 1)) { cursorX = minOf(((cursorX / 8) + 1) * 8, cols - 1) }
                wrapPending = false
            }
            'J' -> eraseDisplay(arg(0, 0))
            'K' -> eraseLine(arg(0, 0))
            'L' -> insertLines(arg(0, 1)) // IL
            'M' -> deleteLines(arg(0, 1)) // DL
            'P' -> deleteChars(arg(0, 1)) // DCH
            '@' -> insertChars(arg(0, 1)) // ICH
            'X' -> eraseChars(arg(0, 1)) // ECH
            'S' -> scrollUp(arg(0, 1)) // SU
            'T' -> scrollDown(arg(0, 1)) // SD
            'r' -> { // DECSTBM: set scroll region
                val top = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (arg(1, rows) - 1).coerceIn(0, rows - 1)
                scrollTop = top
                scrollBottom = if (bottom > top) bottom else top
                cursorX = 0; cursorY = scrollTop; wrapPending = false
            }
            'm' -> sgr(params)
            'n' -> { // DSR: device status report
                if (arg(0, 0) == 6) {
                    val report = "\u001B[${cursorY + 1};${cursorX + 1}R"
                    onDeviceReport?.invoke(report.toByteArray(Charsets.US_ASCII))
                }
            }
            's' -> { savedX = cursorX; savedY = cursorY } // SCOSC
            'u' -> { cursorX = savedX.coerceIn(0, cols - 1); cursorY = savedY.coerceIn(0, rows - 1); wrapPending = false }
            else -> Unit
        }
    }

    private fun arg(i: Int, dflt: Int): Int = params.getOrNull(i)?.takeIf { it != 0 } ?: dflt

    private fun cursorUp(n: Int) {
        cursorY = (cursorY - n).coerceAtLeast(scrollTop)
        wrapPending = false
    }

    private fun cursorDown(n: Int) {
        cursorY = (cursorY + n).coerceAtMost(scrollBottom)
        wrapPending = false
    }

    private fun cursorForward(n: Int) {
        cursorX = (cursorX + n).coerceAtMost(cols - 1)
        wrapPending = false
    }

    private fun cursorBack(n: Int) {
        cursorX = (cursorX - n).coerceAtLeast(0)
        wrapPending = false
    }

    // ── erase / insert / delete ─────────────────────────────────────────────

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // cursor → end of screen
                eraseLineCells(cursorY, cursorX, cols)
                for (r in cursorY + 1 until rows) clearRow(r)
            }
            1 -> { // start → cursor
                for (r in 0 until cursorY) clearRow(r)
                eraseLineCells(cursorY, 0, cursorX + 1)
            }
            else -> for (r in 0 until rows) clearRow(r) // 2 / 3: whole screen
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseLineCells(cursorY, cursorX, cols)
            1 -> eraseLineCells(cursorY, 0, cursorX + 1)
            else -> eraseLineCells(cursorY, 0, cols) // 2: whole line
        }
    }

    /** Blanks cells in row [r] over [from, until). */
    private fun eraseLineCells(r: Int, from: Int, until: Int) {
        if (r < 0 || r >= rows) return
        val base = r * cols
        val a = from.coerceIn(0, cols)
        val b = until.coerceIn(0, cols)
        for (c in a until b) {
            codes[base + c] = 0
            fg[base + c] = NO_COLOR
            bg[base + c] = NO_COLOR
            flags[base + c] = 0
        }
    }

    private fun insertLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        for (step in 0 until n) {
            for (r in scrollBottom downTo cursorY + 1) copyRow(r - 1, r)
            clearRow(cursorY)
        }
        cursorX = 0
        wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        for (step in 0 until n) {
            for (r in cursorY until scrollBottom) copyRow(r + 1, r)
            clearRow(scrollBottom)
        }
        cursorX = 0
        wrapPending = false
    }

    private fun deleteChars(n: Int) {
        val base = cursorY * cols
        for (c in cursorX until cols) {
            val src = c + n
            codes[base + c] = if (src < cols) codes[base + src] else 0
            fg[base + c] = if (src < cols) fg[base + src] else NO_COLOR
            bg[base + c] = if (src < cols) bg[base + src] else NO_COLOR
            flags[base + c] = if (src < cols) flags[base + src] else 0
        }
        wrapPending = false
    }

    private fun insertChars(n: Int) {
        val base = cursorY * cols
        for (c in cols - 1 downTo cursorX) {
            val src = c - n
            codes[base + c] = if (src >= cursorX) codes[base + src] else 0
            fg[base + c] = if (src >= cursorX) fg[base + src] else NO_COLOR
            bg[base + c] = if (src >= cursorX) bg[base + src] else NO_COLOR
            flags[base + c] = if (src >= cursorX) flags[base + src] else 0
        }
        wrapPending = false
    }

    private fun eraseChars(n: Int) {
        eraseLineCells(cursorY, cursorX, cursorX + n)
        wrapPending = false
    }

    // ── SGR ─────────────────────────────────────────────────────────────────

    private fun sgr(p: List<Int>) {
        if (p.isEmpty()) { resetAttributes(); return }
        var i = 0
        while (i < p.size) {
            val v = p[i]
            when {
                v == 0 -> resetAttributes()
                v == 1 -> curBold = true
                v == 4 -> curUnderline = true
                v == 7 -> curReverse = true
                v == 8 -> curConcealed = true
                v == 22 -> curBold = false
                v == 24 -> curUnderline = false
                v == 27 -> curReverse = false
                v == 28 -> curConcealed = false
                v == 39 -> curFg = NO_COLOR
                v == 49 -> curBg = NO_COLOR
                v in 30..37 -> curFg = v - 30
                v in 40..47 -> curBg = v - 40
                v in 90..97 -> curFg = v - 90 + 8
                v in 100..107 -> curBg = v - 100 + 8
                v == 38 || v == 48 -> when (p.getOrNull(i + 1)) {
                    5 -> {
                        val index = p.getOrNull(i + 2)
                        if (index != null) { if (v == 38) curFg = index else curBg = index }
                        i += 2
                    }
                    2 -> {
                        val r = p.getOrNull(i + 2)
                        val g = p.getOrNull(i + 3)
                        val b = p.getOrNull(i + 4)
                        if (r != null && g != null && b != null) {
                            val index = nearest256(r, g, b)
                            if (v == 38) curFg = index else curBg = index
                        }
                        i += 4
                    }
                    else -> i = p.size
                }
                else -> Unit
            }
            i++
        }
    }

    private fun resetAttributes() {
        curFg = NO_COLOR
        curBg = NO_COLOR
        curBold = false
        curUnderline = false
        curReverse = false
        curConcealed = false
    }

    // ── OSC ─────────────────────────────────────────────────────────────────

    private fun osc(b: Int) {
        when (b) {
            0x07 -> state = State.GROUND // BEL terminates
            0x1B -> state = State.OSC_STRING // possible ST (ESC \)
            0x0A -> state = State.GROUND // unterminated OSC: recover at EOL
            in 0x08..0x0C, in 0x0E..0x1F -> Unit
            else -> if (oscBuffer.length < OSC_LIMIT) oscBuffer.append(b.toChar())
        }
    }

    private fun oscString(b: Int) {
        // We arrive here after an ESC seen inside an OSC. ESC \ (0x5C) is ST and
        // terminates the string. Any other byte means that ESC actually began a
        // new sequence, so reprocess this byte through the escape dispatcher
        // rather than dropping it.
        if (b == 0x5C) state = State.GROUND else escape(b)
    }

    // ── viewport / rendering ────────────────────────────────────────────────

    /**
     * Scrolls the view up into scrollback by [n] lines. The fragment calls this
     * when the user scrolls up; it never moves on its own from live output.
     */
    fun scrollViewUp(n: Int = 3) = synchronized(lock) {
        viewportOffset = (viewportOffset + n).coerceIn(0, scrollback.size)
        Unit
    }

    /** Scrolls the view down toward the live screen by [n] lines. */
    fun scrollViewDown(n: Int = 3) = synchronized(lock) {
        viewportOffset = (viewportOffset - n).coerceAtLeast(0)
        Unit
    }

    /** Returns the view to the live screen (bottom). */
    fun scrollToLive() = synchronized(lock) {
        viewportOffset = 0
        Unit
    }

    /** True when the live screen is shown (not scrolled into history). */
    fun isAtLive(): Boolean = synchronized(lock) { viewportOffset == 0 }

    /**
     * The visible rows, top to bottom, as styled runs for [TerminalSpans].
     *
     * When [viewportOffset] is 0 this is the live screen; otherwise it is the
     * window of scrollback lines that sits [viewportOffset] above the live
     * screen, so the live screen's last row still appears at the bottom. Each
     * row is a list of [TerminalRun] exactly as the linear buffer produced, so
     * the existing span renderer consumes it unchanged.
     */
    fun render(): List<List<TerminalRun>> = synchronized(lock) {
        val out = ArrayList<List<TerminalRun>>(rows)
        // The bottom `rows` visible lines: (scrollback size + rows) - offset.
        val totalLines = scrollback.size + rows
        val bottomExclusive = totalLines - viewportOffset
        val topInclusive = bottomExclusive - rows
        for (screenRow in 0 until rows) {
            val lineIndex = topInclusive + screenRow
            when {
                lineIndex < 0 -> out.add(emptyList())
                lineIndex < scrollback.size -> out.add(renderLine(scrollback[lineIndex]))
                else -> {
                    val r = lineIndex - scrollback.size
                    out.add(renderScreenRow(r))
                }
            }
        }
        out
    }

    /** Renders a live screen row as runs. */
    private fun renderScreenRow(r: Int): List<TerminalRun> {
        val base = r * cols
        return runsFrom(codes, fg, bg, flags, base, cols)
    }

    /** Renders a retained scrollback line as runs. */
    private fun renderLine(line: Line): List<TerminalRun> {
        return runsFrom(line.codes, line.fg, line.bg, line.flags, 0, line.codes.size)
    }

    /**
     * The whole logical screen, top to bottom: every retained scrollback line
     * followed by all [rows] live rows.
     *
     * Unlike [render], which returns only the visible window for a viewport
     * scrolled by [viewportOffset], this is the full scrollable log. The
     * fragment lays it out in a scrolling TextView and reuses its existing
     * conditional auto-follow, so the only behavioural change from the old
     * linear buffer is *correctness*: the prompt sits at its real cursor cell,
     * carriage returns overwrite instead of stacking, cursor addressing works,
     * and memory is bounded by [MAX_SCROLLBACK].
     */
    fun renderAll(): List<List<TerminalRun>> = synchronized(lock) {
        val out = ArrayList<List<TerminalRun>>(scrollback.size + rows)
        for (i in 0 until scrollback.size) out.add(renderLine(scrollback[i]))
        for (r in 0 until rows) out.add(renderScreenRow(r))
        out
    }

    /**
     * The scrollable content for the fragment's TextView, with the trailing
     * blank live rows trimmed away.
     *
     * A fixed screen is [rows] tall whether or not the shell has filled it, so
     * [renderAll] always ends in up to [rows] blank lines. Rendered into a
     * `fillViewport` TextView that leaves real, scrollable blank space below the
     * prompt — the same drift the linear buffer had, just moved under the
     * prompt instead of above it. Trimming to the last row that has content
     * (never below the cursor row) makes the prompt the last line, so the
     * fragment's auto-follow pins it to the bottom of the viewport and the
     * screen is stable when idle.
     */
    fun renderTrimmed(): List<List<TerminalRun>> = synchronized(lock) {
        var lastContentRow = cursorY
        for (r in rows - 1 downTo cursorY + 1) {
            if (rowHasContent(r)) {
                lastContentRow = r
                break
            }
        }
        // Only the newest lines are rendered.
        //
        // The TextView re-lays out its whole content on every frame, so a
        // full 2000-line scrollback made each frame take seconds. Worse, the
        // render holds the grid's lock, and the shell's reader thread needs
        // that same lock to feed the output in: the render starved the
        // reader, the reader stalled the shell, and a command like `ls` took
        // the better part of a minute to appear.
        val wanted = RENDER_MAX_LINES - lastContentRow
        val from = if (wanted <= 0) scrollback.size else maxOf(0, scrollback.size - wanted)
        val out = ArrayList<List<TerminalRun>>(scrollback.size - from + lastContentRow + 1)
        for (i in from until scrollback.size) out.add(renderLine(scrollback[i]))
        for (r in 0..lastContentRow) {
            val runs = renderScreenRow(r)
            // The cursor is drawn as a reversed cell: without it there is
            // nothing on screen showing where the next character will land.
            out.add(if (r == cursorY) withCursor(runs, cursorX) else runs)
        }
        out
    }

    /**
     * The runs of one row with the cursor cell reversed.
     *
     * A real terminal shows a block or an underline on the cell the cursor
     * is on; reversing that one cell is the same idea, and it works with the
     * span-based rendering.
     */
    private fun withCursor(runs: List<TerminalRun>, col: Int): List<TerminalRun> {
        if (col < 0) return runs
        var offset = 0
        for ((index, run) in runs.withIndex()) {
            val length = run.text.length
            if (col < offset + length) {
                val at = col - offset
                val style = run.style
                val cursorStyle = style.copy(reverse = !style.reverse)
                val out = ArrayList<TerminalRun>(runs.size + 2)
                if (at > 0) out.add(TerminalRun(run.text.substring(0, at), style))
                out.add(TerminalRun(run.text.substring(at, at + 1), cursorStyle))
                if (at + 1 < length) out.add(TerminalRun(run.text.substring(at + 1), style))
                for (i in index + 1 until runs.size) out.add(runs[i])
                return out
            }
            offset += length
        }
        // The cursor is past the end of the text: show it as a reversed space.
        val out = ArrayList<TerminalRun>(runs.size + 1)
        out.addAll(runs)
        out.add(TerminalRun(" ", runs.lastOrNull()?.style?.copy(reverse = true) ?: TerminalStyle(null, null, false, false, true)))
        return out
    }

    /** True when row [r] holds anything other than blank cells. */
    private fun rowHasContent(r: Int): Boolean {
        val base = r * cols
        for (c in 0 until cols) {
            val code = codes[base + c]
            // 0 is an untouched cell; a literal written space is 0x20. Either
            // renders as blank, so neither counts as content.
            if (code != 0 && code != ' '.code) return true
        }
        return false
    }


    private fun runsFrom(
        codes: IntArray,
        fg: IntArray,
        bg: IntArray,
        flags: IntArray,
        base: Int,
        width: Int
    ): List<TerminalRun> {
        val runs = ArrayList<TerminalRun>(8)
        val sb = StringBuilder(width)
        var curStyle: TerminalStyle? = null
        for (c in 0 until width) {
            val idx = base + c
            val cp = codes[idx]
            val fl = flags[idx]
            // Concealed cells draw nothing (password prompts), as before.
            if ((fl and FLAG_CONCEAL) != 0) continue
            val fgIdx = if (fg[idx] == NO_COLOR) null else fg[idx]
            val bgIdx = if (bg[idx] == NO_COLOR) null else bg[idx]
            val style = TerminalStyle(
                foreground = fgIdx,
                background = bgIdx,
                bold = (fl and FLAG_BOLD) != 0,
                underline = (fl and FLAG_UNDERLINE) != 0,
                reverse = (fl and FLAG_REVERSE) != 0
            )
            val ch = if (cp == 0) ' ' else cp.toChar()
            if (curStyle != style) {
                if (sb.isNotEmpty()) {
                    runs.add(TerminalRun(sb.toString(), curStyle!!))
                    sb.setLength(0)
                }
                curStyle = style
            }
            sb.append(ch)
        }
        if (sb.isNotEmpty() && curStyle != null) {
            runs.add(TerminalRun(sb.toString(), curStyle))
        }
        // Trim trailing spaces so a fixed-width row does not force horizontal
        // scrolling or leave phantom trailing whitespace in the rendered line.
        while (runs.isNotEmpty()) {
            val last = runs.last()
            val trimmed = last.text.trimEnd(' ')
            if (trimmed.isEmpty()) {
                runs.removeAt(runs.lastIndex)
            } else if (trimmed.length != last.text.length) {
                runs[runs.lastIndex] = TerminalRun(trimmed, last.style)
                break
            } else {
                break
            }
        }
        return runs
    }

    companion object {
        /** Marks a cell with no explicit colour (terminal default). */
        const val NO_COLOR = -1

        private const val FLAG_BOLD = 1
        private const val FLAG_UNDERLINE = 2
        private const val FLAG_REVERSE = 4
        private const val FLAG_CONCEAL = 8

        private const val REPLACEMENT = 0xFFFD

        /** Caps an unterminated OSC payload so a rogue host cannot grow it. */
        private const val OSC_LIMIT = 4096

        /** Scrollback lines retained above the live screen. */
        const val MAX_SCROLLBACK = 2000

        /**
         * How many lines the view renders at once.
         *
         * The TextView re-lays out its whole content on every frame, and the
         * render holds the grid's lock, which the shell's reader thread also
         * needs. Rendering everything made a command like `ls` take the best
         * part of a minute to appear.
         */
        const val RENDER_MAX_LINES = 400

        private fun isLead(b: Int) = b in 0xC2..0xF4

        private fun isContinuation(b: Int) = b in 0x80..0xBF

        private fun lengthOf(lead: Int): Int = when (lead) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> 1
        }

        private fun decodeCodepoint(bytes: ByteArray): Int {
            if (bytes.size == 1) return bytes[0].toInt() and 0xFF
            return String(bytes, Charsets.UTF_8).codePointAt(0)
        }

        /**
         * Nearest xterm-256 index for an RGB triple, reused from the old
         * renderer so true-colour still snaps the same way.
         */
        fun nearest256(r: Int, g: Int, b: Int): Int {
            val rr = r.coerceIn(0, 255)
            val gg = g.coerceIn(0, 255)
            val bb = b.coerceIn(0, 255)
            if (rr == gg && gg == bb) {
                return when {
                    rr < 8 -> 16
                    rr > 248 -> 231
                    else -> 232 + (rr - 8) * 23 / 247
                }
            }
            fun cube(v: Int): Int = ((v - 55) / 40).coerceIn(0, 5)
            return 16 + 36 * cube(rr) + 6 * cube(gg) + cube(bb)
        }
    }
}
