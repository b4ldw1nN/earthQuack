package com.example.earthquack.ui

import java.nio.charset.StandardCharsets

/**
 * The terminal's character store: a bounded scrollback log plus the escape
 * state machine that fills it.
 *
 * ## Why this is a pure class
 *
 * The old parser lived inside [TerminalFragment] and built `android.text`
 * objects itself, which made it untestable on the JVM: the Android stub's
 * `SpannableStringBuilder` is inert when
 * `unitOptions.unitTests.isReturnDefaultValues` is on. Everything the fragment
 * needs to decide is now plain data here, and the Android-specific rendering is
 * one function in `TerminalSpans.kt`. That is what makes the regression tests
 * for UTF-8 splitting, ANSI sequences and erasure possible without a device.
 *
 * ## Bounded, because the old code was not
 *
 * A shell running a build log, `yes`, or `tail -f` produces megabytes. The old
 * accumulator kept every character forever and the fragment rebuilt a TextView
 * holding all of it on every feed, so a long-running command cost unbounded
 * memory and quadratic time. [maxChars] caps what is retained: the oldest
 * characters are dropped, and [TerminalFeedResult.trimmedFromFront] reports how
 * many so the fragment deletes the same amount from the front of its view.
 *
 * ## A log, not a screen — and honest about it
 *
 * A real terminal is a cursor-addressable screen. This is a scrolling log,
 * which is what a `TextView` in a `ScrollView` can honestly be, and which is
 * what the screen already was. The model therefore supports exactly the
 * operations that touch the end of the text: appending, newline, carriage
 * return to the start of the current line, backspace, and erase-line. Those
 * are what readline, progress bars and simple full-screen programs emit.
 *
 * It deliberately does not implement cursor addressing, scroll regions, the
 * alternate screen, or wide-character layout. Anything that needs those is out
 * of reach for this shape, and claiming otherwise is how a user ends up
 * believing vim should work here. `TerminalFragment`'s header says so.
 */
class TerminalScreenBuffer(private val maxChars: Int = MAX_CHARS) {

    /** Everything rendered, at most [maxChars] characters. */
    private val buffer = StringBuilder()

    /** Offset in [buffer] where the current, unterminated line begins. */
    private var lineStart = 0

    private var state = State.GROUND

    /** Numeric CSI parameters, in the order given. */
    private val params = ArrayList<Int>(8)

    /** The parameter currently being typed, as digits. */
    private var paramDigits = 0

    /** `<` `=` `>` `?` — the private marker of the sequence in progress. */
    private var privateMarker = ' '

    /** Bytes of a UTF-8 sequence split across two reads. */
    private var utf8: ByteArray? = null

    /** How many bytes the lead byte in [utf8] accounted for. */
    private var utf8Needed = 0

    /** Characters appended by the feed currently in flight. */
    private var tail = 0

    /** Characters this feed must retract from what the UI already drew. */
    private var retract = 0

    /** Set while erasing, so [TerminalFeedResult.cleared] can be reported. */
    private var cleared = false

    /** Accumulating OSC payload, kept only so a rogue OSC cannot grow unbounded. */
    private val oscBuffer = StringBuilder(64)

    /** Attributes applied to characters as they are appended. */
    private var foreground: Int? = null
    private var background: Int? = null
    private var bold = false
    private var underline = false
    private var reverse = false
    private var concealed = false

    private val lock = Any()

    // ── feed ─────────────────────────────────────────────────────────────────

    /**
     * Feeds raw bytes from the remote shell.
     *
     * Called on an sshd I/O thread. The only locking requirement is that the
     * buffer is never half-updated when the main thread reads [snapshot].
     */
    fun feed(data: ByteArray, offset: Int, len: Int): TerminalFeedResult {
        val runs = ArrayList<TerminalRun>(16)
        return synchronized(lock) {
            cleared = false
            tail = 0
            retract = 0

            var i = offset
            val end = offset + len
            while (i < end) {
                val b = data[i].toInt() and 0xFF
                i++

                // A pending UTF-8 sequence always wins. The lead byte's value
                // range is the continuation range's neighbour, so the byte has
                // to be resolved as a continuation before anything else looks
                // at it, or a marker split across reads is corrupted.
                if (utf8 != null) {
                    if (isContinuation(b)) {
                        val bytes = utf8!! + byteArrayOf(b.toByte())
                        if (bytes.size >= utf8Needed) {
                            utf8 = null
                            append(runs, decode(bytes))
                            continue
                        }
                        utf8 = bytes
                        continue
                    }
                    // Not a continuation, so the pending sequence is broken.
                    // Flush it, then let *this* byte be judged on its own
                    // rather than swallowing it.
                    utf8 = null
                    append(runs, REPLACEMENT)
                    // fall through and handle b below
                }

                // ESC restarts a sequence from wherever it appears. This is the
                // terminal-standard reading, and it is what keeps a sequence
                // the write boundary split in half from eating the *next*
                // sequence's opening bytes as literal text: the only way to
                // know a CSI never got its final byte is to see the ESC of the
                // sequence that follows it.
                if (b == 0x1B) {
                    state = State.ESC
                    continue
                }

                when (state) {
                    State.GROUND -> ground(b, runs)
                    State.ESC -> if (!escape(b)) continue
                    State.CSI -> csi(b, runs)
                    State.OSC -> osc(b, runs)
                    State.OSC_STRING -> oscString(b)
                    State.CHARSET -> state = State.GROUND
                }
            }

            // A sequence truncated at the end of this read stays pending: the
            // next read may complete it, and rendering a replacement character
            // now would be a lie about what arrived.
            TerminalFeedResult(runs, cleared, retract, trim())
        }
    }

    /** Feeds text; the convenience signature the tests use. */
    fun feed(text: String): TerminalFeedResult =
        feed(text.toByteArray(StandardCharsets.UTF_8), 0, text.length)

    /** The whole retained text. */
    fun snapshot(): String = synchronized(lock) { buffer.toString() }

    /** Drops everything and the parser state. Safe from any thread. */
    fun reset() = synchronized(lock) {
        buffer.setLength(0)
        lineStart = 0
        state = State.GROUND
        params.clear()
        paramDigits = 0
        privateMarker = ' '
        utf8 = null
        utf8Needed = 0
        tail = 0
        retract = 0
        cleared = false
        oscBuffer.setLength(0)
        foreground = null
        background = null
        bold = false
        underline = false
        concealed = false
        Unit
    }

    // ── ground ───────────────────────────────────────────────────────────────

    /** Handles a byte that is not inside an escape sequence. */
    private fun ground(b: Int, runs: MutableList<TerminalRun>) {
        // ESC is handled by the caller, before the state dispatch, so that a
        // sequence interrupted mid-way still restarts from ESC.
        when (b) {
            0x07 -> Unit // BEL: nothing to draw
            0x08 -> eraseTail(runs, 1)
            0x09 -> {
                // TAB to the next 8-column stop from the start of this line.
                val column = buffer.length - lineStart
                append(runs, " ".repeat(8 - (column % 8)))
            }
            0x0A, 0x0B, 0x0C -> {
                // LF, VT and FF all end a line. Some tools emit one of the
                // other two meaning "newline", and treating VT as a printable
                // character put a glyph in the middle of the text.
                append(runs, "\n")
                lineStart = buffer.length
            }
            0x0D -> {
                // CR returns to the start of the line, which is what a progress
                // bar relies on to overwrite itself. Ignoring it (the old
                // behaviour) stacked every progress update onto one line.
                eraseTail(runs, Int.MAX_VALUE)
            }
            in 0x00..0x1F, 0x7F -> Unit // other control bytes: nothing to draw
            0x9B -> {
                state = State.CSI
                beginCsi()
            }
            0x9D -> {
                state = State.OSC
                oscBuffer.setLength(0)
            }
            in 0x20..0x7E -> append(runs, b.toChar().toString())
            else -> if (isLead(b)) {
                utf8 = byteArrayOf(b.toByte())
                utf8Needed = lengthOf(b)
            } else {
                append(runs, REPLACEMENT)
            }
        }
    }

    /**
     * Escape-sequence dispatch.
     *
     * @return false only to stop the whole read, which happens for RIS.
     */
    private fun escape(b: Int): Boolean = when (b) {
        0x5B -> {
            state = State.CSI
            beginCsi()
            true
        }
        // ], P, X, ^, _ all start a string that runs to ST or BEL. OSC is the
        // common one (a window title); DCS/PM/APC appear from the likes of
        // shell prompt frameworks. The old parser hung in CHARSET on `\x1bP`
        // because 0x50 was in its charset-selection set.
        0x5D, 0x50, 0x58, 0x5E, 0x5F -> {
            state = State.OSC
            oscBuffer.setLength(0)
            true
        }
        0x28, 0x29, 0x2A, 0x2B, 0x2D, 0x2E, 0x2F -> {
            state = State.CHARSET
            true
        }
        0x37, 0x38 -> {
            state = State.GROUND // DECSC, DECRC
            true
        }
        0x63 -> {
            reset()
            false // RIS: whatever follows starts a fresh screen
        }
        else -> {
            state = State.GROUND
            true
        }
    }

    private fun beginCsi() {
        params.clear()
        paramDigits = 0
        privateMarker = ' '
    }

    private fun csi(b: Int, runs: MutableList<TerminalRun>) {
        when (b) {
            in 0x30..0x39 -> paramDigits = paramDigits * 10 + (b - 0x30)
            0x3A, 0x3B -> {
                params.add(paramDigits)
                paramDigits = 0
            }
            in 0x3C..0x3F -> if (privateMarker == ' ') privateMarker = b.toChar()
            0x20 -> Unit // intermediate
            in 0x40..0x7E -> {
                params.add(paramDigits)
                state = State.GROUND
                dispatchCsi(b, privateMarker, params, runs)
            }
            else -> {
                // Not a valid CSI byte — a hyperlink payload, a stray control
                // character. Abandon the sequence instead of parking the parser
                // in a state nothing will ever complete.
                state = State.GROUND
            }
        }
    }

    /**
     * Acts on one finished CSI sequence.
     *
     * The cursor-movement and cursor-position cases are consumed rather than
     * acted on: a log has no addressable screen, and emitting the sequence
     * into the displayed text was what corrupted output from `top` and `less`.
     */
    private fun dispatchCsi(
        final: Int,
        marker: Char,
        p: List<Int>,
        runs: MutableList<TerminalRun>
    ) {
        when (final.toChar()) {
            'm' -> if (marker != '?') sgr(p)
            'H', 'f' -> Unit // CUP / HVP: no addressable screen
            'A', 'B', 'C', 'D' -> Unit // CUU / CUD / CUF / CUB: same
            'E', 'F' -> {
                // CNL / CPL: move down or up a line. Down is a real newline;
                // up is not addressable, so it is consumed.
                append(runs, "\n")
                lineStart = buffer.length
            }
            'G', '`' -> {
                // CHA / HPA: to a column on the current line. Only movement
                // backwards from the tail is meaningful in a log — which is
                // what a spinner or progress bar needs.
                val length = buffer.length - lineStart
                val wanted = (p.firstOrNull() ?: 1).coerceAtLeast(1) - 1
                when {
                    wanted < length -> eraseTail(runs, length - wanted)
                    wanted > length -> append(runs, " ".repeat(wanted - length))
                    else -> Unit
                }
            }
            'J' -> when (p.firstOrNull() ?: 0) {
                0 -> eraseLine(runs)
                2, 3 -> eraseAll(runs)
                else -> Unit // 1: erase up to the cursor — needs addressing
            }
            'K' -> eraseLine(runs)
            'S', 'T' -> Unit // scroll up / down: a log scrolls by itself
            's', 'u' -> Unit // save / restore cursor: no cursor to save
            else -> Unit
        }
    }

    private fun sgr(p: List<Int>) {
        var i = 0
        while (i < p.size) {
            val v = p[i]
            when {
                v == 0 -> resetAttributes()
                v == 1 -> bold = true
                v == 2 -> Unit // faint: no second weight to apply
                v == 3 -> Unit // italic: no span for it in this renderer
                v == 4 -> underline = true
                v == 7 -> reverse = true
                v == 8 -> concealed = true
                v == 22 -> bold = false
                v == 24 -> underline = false
                v == 27 -> reverse = false
                v == 28 -> concealed = false
                v == 39 -> foreground = null
                v == 49 -> background = null
                v in 30..37 -> foreground = v - 30
                v in 40..47 -> background = v - 40
                v in 90..97 -> foreground = v - 90 + 8
                v in 100..107 -> background = v - 100 + 8
                v == 38 || v == 48 -> when (p.getOrNull(i + 1)) {
                    5 -> {
                        val index = p.getOrNull(i + 2)
                        if (index != null) {
                            if (v == 38) foreground = index else background = index
                        }
                        i += 2
                    }
                    2 -> {
                        val r = p.getOrNull(i + 2)
                        val g = p.getOrNull(i + 3)
                        val b = p.getOrNull(i + 4)
                        if (r != null && g != null && b != null) {
                            val index = nearest256(r, g, b)
                            if (v == 38) foreground = index else background = index
                        }
                        i += 4
                    }
                    else -> i = p.size // malformed: drop the rest
                }
                else -> Unit
            }
            i++
        }
    }

    private fun resetAttributes() {
        foreground = null
        background = null
        bold = false
        underline = false
        reverse = false
        concealed = false
    }

    // ── OSC ─────────────────────────────────────────────────────────────────
    private fun osc(b: Int, runs: MutableList<TerminalRun>) {
        when (b) {
            // ESC is handled by the caller, so ESC \ terminates the OSC as a
            // side effect of the restart rather than by a special case here.
            0x07 -> state = State.GROUND
            0x0A -> {
                // An OSC that never terminated. End of line is the only
                // recovery: the payload is garbage and the shell is still
                // waiting on stderr.
                append(runs, "\n")
                lineStart = buffer.length
                state = State.GROUND
            }
            in 0x08..0x0C, in 0x0E..0x1F -> Unit
            else -> if (oscBuffer.length < OSC_LIMIT) oscBuffer.append(b.toChar())
        }
    }

    private fun oscString(b: Int) {
        when (b) {
            0x5C, 0x07 -> state = State.GROUND
            else -> state = State.OSC
        }
    }

    /**
     * Removes [n] characters from the end of the current line.
     *
     * [retract] is incremented by however many of those the UI has already
     * drawn; characters this very feed produced are removed from [runs]
     * instead, so they are never counted twice.
     *
     * The buffer always shrinks by the whole [n]: the "produced here" split
     * only decides *where the caller hears about it* — a retract it must apply
     * to text drawn by an earlier feed, or a run in this feed that never gets
     * built at all.
     */
    private fun eraseTail(runs: MutableList<TerminalRun>, n: Int) {
        val available = buffer.length - lineStart
        if (available <= 0 || concealed) return
        val count = minOf(n, available)

        val producedHere = minOf(count, tail)

        buffer.setLength(buffer.length - count)
        eraseFromRuns(runs, count)
        retract += count - producedHere
        tail -= producedHere
    }

    /** Erases the current line. In a log that is the tail. */
    private fun eraseLine(runs: MutableList<TerminalRun>) = eraseTail(runs, Int.MAX_VALUE)

    /** Erases everything rendered so far. */
    private fun eraseAll(runs: MutableList<TerminalRun>) {
        buffer.setLength(0)
        lineStart = 0
        runs.clear()
        tail = 0
        cleared = true
    }

    // ── appending ────────────────────────────────────────────────────────────

    private fun append(runs: MutableList<TerminalRun>, text: String) {
        // SGR 8 (conceal) is honoured by rendering nothing: what it hides is a
        // password prompt, and drawing it would put it in the scrollback,
        // which is the log.
        if (concealed || text.isEmpty()) return
        buffer.append(text)
        tail += text.length

        val style = TerminalStyle(foreground, background, bold, underline, reverse)
        val last = runs.lastOrNull()
        if (last != null && last.style == style) {
            runs[runs.size - 1] = last.copy(text = last.text + text)
        } else {
            runs += TerminalRun(text, style)
        }
    }

    /** Strips [n] characters off the end of [runs]. */
    private fun eraseFromRuns(runs: MutableList<TerminalRun>, n: Int) {
        var left = n
        while (left > 0 && runs.isNotEmpty()) {
            val last = runs.removeAt(runs.size - 1)
            if (last.text.length <= left) {
                left -= last.text.length
            } else {
                runs += last.copy(text = last.text.dropLast(left))
                left = 0
            }
        }
    }

    // ── bounding ─────────────────────────────────────────────────────────────

    /** @return how many characters were dropped from the front. */
    private fun trim(): Int {
        val excess = buffer.length - maxChars
        if (excess <= 0) return 0
        buffer.delete(0, excess)
        lineStart = maxOf(0, lineStart - excess)
        tail = maxOf(0, tail - excess)
        return excess
    }

    private enum class State { GROUND, ESC, CSI, OSC, OSC_STRING, CHARSET }

    companion object {
        const val MAX_CHARS = 64 * 1024

        private val REPLACEMENT = "\uFFFD"

        /** Caps an unterminated OSC payload so a malicious host cannot grow it. */
        private const val OSC_LIMIT = 4096

        internal fun isLead(b: Int) = b in 0xC2..0xF4

        internal fun isContinuation(b: Int) = b in 0x80..0xBF

        private fun lengthOf(lead: Int): Int = when (lead) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> 1
        }

        private fun decode(bytes: ByteArray): String =
            String(bytes, java.nio.charset.StandardCharsets.UTF_8)

        /**
         * Nearest xterm-256 index for an RGB triple.
         *
         * True colour is mapped onto the 256-colour palette this renderer
         * actually supports. Grey is special-cased because the 24 grey ramp
         * matches a neutral colour far better than the 6×6×6 cube, which
         * cannot represent grey at all — it only produces tints.
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

/** Persistent SGR attributes for a run of characters. */
data class TerminalStyle(
    val foreground: Int?,
    val background: Int?,
    val bold: Boolean,
    val underline: Boolean,
    val reverse: Boolean
)

/** A styled piece of text produced by one [TerminalScreenBuffer.feed]. */
data class TerminalRun(val text: String, val style: TerminalStyle)

/**
 * What one feed did.
 *
 * The fragment applies it as: if [cleared], throw away what it is holding;
 * otherwise delete [retractChars] characters **from the end** and
 * [trimmedFromFront] **from the front** of what it is holding, then append
 * [runs].
 */
class TerminalFeedResult(
    val runs: List<TerminalRun>,
    val cleared: Boolean,
    val retractChars: Int,
    val trimmedFromFront: Int
)

/**
 * Whether a terminal viewport should keep following new output.
 *
 * The rule behind [TerminalFragment]'s auto-scroll, lifted out so it can be
 * tested on the JVM. The defect it guards against: scrolling to the bottom on
 * *every* frame fought the user — a scroll up to read a line was undone by the
 * next newline, and against the remote's startup blank lines the viewport looked
 * like it was sliding down on its own. Following is therefore conditional on the
 * user already being at (or within [slackPx] of) the bottom.
 *
 * @param distanceFromBottom how many pixels of content lie below the viewport's
 *   current bottom edge: `childHeight - (scrollY + viewportHeight)`. Zero at the
 *   bottom, growing as the user scrolls up.
 * @param slackPx tolerance for a NestedScrollView that settles a fraction short
 *   of the true bottom, so resting at the bottom is not read as "scrolled up".
 * @return true when the viewport is at/near the bottom and new output should
 *   pull it down; false once the user has scrolled up, which must be preserved.
 */
fun shouldFollowOutput(distanceFromBottom: Int, slackPx: Int): Boolean =
    distanceFromBottom <= slackPx
