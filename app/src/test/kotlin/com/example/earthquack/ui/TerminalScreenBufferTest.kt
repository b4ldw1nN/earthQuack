package com.example.earthquack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal state machine, on the JVM.
 *
 * The parser used to live inside [TerminalFragment] and built `android.text`
 * objects directly, which meant it could not be tested at all: the Android stub
 * is inert under `unitTests.isReturnDefaultValues`, so every bug in it — UTF-8
 * split across reads, ANSI bytes rendered as text, unbounded growth — was only
 * ever found by tapping on a device. Moving the state machine into
 * [TerminalScreenBuffer] and the span building into `TerminalSpans.kt` is what
 * made this file possible.
 *
 * Each test names the failure it pins, not just the behaviour.
 */
class TerminalScreenBufferTest {

    private fun parser() = TerminalScreenBuffer()

    /** The text rendered by one feed. */
    private fun textOf(result: TerminalFeedResult): String =
        result.runs.joinToString("") { it.text }

    // ── UTF-8 across reads ───────────────────────────────────────────────────

    @Test
    fun `a multi-byte character split across reads is decoded as one character`() {
        val parser = parser()
        val marker = byteArrayOf(0xE2.toByte(), 0x96.toByte(), 0xB6.toByte())

        // The first two reads are mid-sequence and must render nothing.
        assertEquals("", textOf(parser.feed(marker, 0, 1)))
        assertEquals("", textOf(parser.feed(marker, 1, 1)))

        val result = parser.feed(marker, 2, 1)

        assertEquals("\u25B6", textOf(result))
        // Nothing was retracted: this is the first thing drawn.
        assertEquals(0, result.retractChars)
        assertEquals("\u25B6", parser.snapshot())
    }

    @Test
    fun `an emoji split at every possible boundary survives`() {
        // U+1F600, four bytes. sshd hands over whatever arrived in one TCP read,
        // so any of these splits is a legitimate read boundary.
        val bytes = "\uD83D\uDE00".toByteArray(Charsets.UTF_8)
        assertEquals(4, bytes.size)

        for (split in 1 until bytes.size) {
            val parser = parser()
            parser.feed(bytes, 0, split)
            val result = parser.feed(bytes, split, bytes.size - split)
            assertEquals(
                "split after $split bytes corrupted the character",
                "\uD83D\uDE00",
                textOf(result)
            )
        }
    }

    @Test
    fun `a truncated sequence is held rather than rendered as replacement`() {
        val parser = parser()
        val bytes = "\u25B6".toByteArray(Charsets.UTF_8)

        parser.feed(bytes, 0, 1)
        parser.feed(bytes, 1, 1)

        // Nothing drawn yet, and nothing wrong drawn either.
        assertEquals("", parser.snapshot())

        parser.feed(bytes, 2, 1)
        assertEquals("\u25B6", parser.snapshot())
    }

    @Test
    fun `a byte that cannot appear where the sequence continues is not swallowed`() {
        val parser = parser()

        // 0x41 'A' is not a continuation, so it is processed on its own merits
        // after the broken sequence is flushed.
        val result = parser.feed(byteArrayOf(0xE2.toByte(), 0x41), 0, 2)

        assertEquals("\uFFFDA", textOf(result))
    }

    // ── ANSI must never reach the text ───────────────────────────────────────

    @Test
    fun `an sgr colour sequence is not rendered as text`() {
        val parser = parser()

        val result = parser.feed("\u001B[31mred\u001B[0m plain")

        assertEquals("red plain", textOf(result))
        assertEquals("red plain", parser.snapshot())
    }

    @Test
    fun `a private mode sequence is consumed`() {
        val parser = parser()

        val result = parser.feed("\u001B[?2004hready\u001B[?2004l")

        assertEquals("ready", textOf(result))
    }

    @Test
    fun `charset selection is consumed`() {
        val parser = parser()

        val result = parser.feed("zoro\u001B(B in ~\u001B(B")

        assertEquals("zoro in ~", textOf(result))
    }

    @Test
    fun `an osc title sequence is swallowed whole`() {
        val parser = parser()

        val result = parser.feed("\u001B]0;zoro@archii:~ \u0007prompt$ ")

        assertEquals("prompt$ ", textOf(result))
    }

    @Test
    fun `an osc terminated by st rather than bel is swallowed`() {
        val parser = parser()

        // ESC \ is the standard string terminator, and an OSC using it is the
        // common second form.
        val result = parser.feed("\u001B]2;title\u001B\\done")

        assertEquals("done", textOf(result))
    }

    @Test
    fun `a dcs sequence does not wedge the parser in charset state`() {
        val parser = parser()

        // ESC P starts DCS. 0x50 is 'P', which the old parser treated as a
        // charset-selection introducer, so the parser parked in CHARSET and
        // the characters that followed were dropped one at a time.
        val result = parser.feed("\u001BP+some payload\u001B\\tail")

        assertEquals("tail", textOf(result))
        // And what follows still parses.
        val after = parser.feed("more")
        assertEquals("more", textOf(after))
    }

    @Test
    fun `cursor addressing and erase sequences are consumed not displayed`() {
        val parser = parser()

        // A full-screen program's opening burst. None of it may reach the text,
        // which is what corrupted the old display.
        val result = parser.feed("\u001B[2J\u001B[Hhello\u001B[1;1H")

        assertEquals("hello", textOf(result))
    }

    @Test
    fun `erase-line after the text is on screen erases it`() {
        val parser = parser()

        // ESC[K erases from the cursor to the end of the line, and the cursor
        // is at the end of what was just written, so the whole line goes.
        val result = parser.feed("\u001B[2J\u001B[Hhello\u001B[1;1H\u001B[K")

        assertEquals("", textOf(result))
        assertEquals("", parser.snapshot())
    }

    @Test
    fun `an unterminated sequence does not swallow the one that follows`() {
        val parser = parser()

        // The write boundary split a sequence in half, and the next sequence
        // starts with its own ESC. Without the ESC-restart rule that ESC merely
        // abandons the CSI state, and the following '[' '0' 'm' are then drawn
        // as literal text — so a colour change appears on screen as "[0m".
        val first = parser.feed("\u001B[31")
        assertEquals("", textOf(first))

        val after = parser.feed("\u001B[0mtext")

        assertEquals("text", textOf(after))
    }

    @Test
    fun `a csi sequence is not terminated until its final byte`() {
        val parser = parser()

        // 'x' is in the CSI final-byte range, so it completes ESC[31x rather
        // than being printed. That is why a host must not split a sequence
        // across writes — and why the buffer has to hold one until it is whole.
        val result = parser.feed("\u001B[31x")

        assertEquals("", textOf(result))
    }

    @Test
    fun `a malformed csi does not consume the characters after it`() {
        val parser = parser()

        // 0x07 BEL cannot appear in a parameter position: the sequence is
        // abandoned and the rest of the text is drawn.
        val result = parser.feed("\u001B\u0007after")

        assertEquals("after", textOf(result))
    }

    // ── what the shell actually emits, and what must survive ─────────────────

    @Test
    fun `a carriage return rewrites the line instead of stacking`() {
        val parser = parser()

        parser.feed("10%")
        val result = parser.feed("\r50%")

        // The caller is told to take the previous characters back.
        assertEquals(3, result.retractChars)
        assertEquals("50%", textOf(result))
        assertEquals("50%", parser.snapshot())
    }

    @Test
    fun `a carriage return inside one read is not reported twice`() {
        val parser = parser()

        // Within a single feed the characters being erased were never drawn,
        // so the caller must not be asked to retract them.
        val result = parser.feed("10%\r50%")

        assertEquals(0, result.retractChars)
        assertEquals("50%", textOf(result))
    }

    @Test
    fun `backspace erases the previous character`() {
        val parser = parser()

        parser.feed("ab")
        val result = parser.feed("\bc")

        assertEquals(1, result.retractChars)
        assertEquals("c", textOf(result))
        assertEquals("ac", parser.snapshot())
    }

    @Test
    fun `backspace at the start of a line is harmless`() {
        val parser = parser()

        parser.feed("ab\n")

        // Nothing on the new line to erase, and the line above is untouched.
        val result = parser.feed("\bc")
        assertEquals("c", textOf(result))
        assertEquals("ab\nc", parser.snapshot())
    }

    @Test
    fun `erase-line clears the current line only`() {
        val parser = parser()

        parser.feed("previous\nprefix")
        val result = parser.feed("stuff\u001B[K")

        // The cursor is at the end of the line, so erase-to-end-of-line takes
        // the whole line — the blank one and the text on it.
        assertEquals("previous\n", parser.snapshot())
        // Six characters the UI already drew, and none of this feed's, because
        // all five of "stuff" were picked up by the same erasure.
        assertEquals(6, result.retractChars)
        assertEquals("", textOf(result))
    }

    @Test
    fun `erase-display clears the screen and says so`() {
        val parser = parser()

        parser.feed("a screen of output")
        val result = parser.feed("\u001B[2J")

        assertTrue("the caller must know to drop what it is holding", result.cleared)
        assertEquals("", parser.snapshot())
        assertEquals("", textOf(result))
    }

    @Test
    fun `cursor forward position pads within the current line`() {
        val parser = parser()

        val result = parser.feed("ab\u001B[10Gc")

        assertEquals("ab       c", textOf(result))
    }

    @Test
    fun `a newline keeps the previous line intact`() {
        val parser = parser()

        val result = parser.feed("one\ntwo\nthree")

        assertEquals("one\ntwo\nthree", textOf(result))
        assertEquals("one\ntwo\nthree", parser.snapshot())
    }

    @Test
    fun `vertical tab and form feed end a line`() {
        val parser = parser()

        // Neither is a tab stop, and rendering either as a glyph put an
        // unprintable character in the middle of the text.
        val result = parser.feed("a\u000Bb\u000Cc")

        assertEquals("a\nb\nc", textOf(result))
    }

    @Test
    fun `tab advances to the next eight column stop`() {
        val parser = parser()

        val result = parser.feed("a\tb")

        // 'a' is column 0, the next stop is 8, so seven spaces follow it.
        assertEquals("a       b", textOf(result))
    }

    @Test
    fun `concealed text is not rendered`() {
        val parser = parser()

        // SGR 8 is what a password prompt uses. Rendering it would put the
        // password in the scrollback, which is the log.
        val result = parser.feed("\u001B[8m[hidden payload]\u001B[0mshown")

        assertEquals("shown", textOf(result))
        assertEquals("shown", parser.snapshot())
    }

    // ── attributes ───────────────────────────────────────────────────────────

    @Test
    fun `attributes are carried on the run they were set for`() {
        val parser = parser()

        val result = parser.feed("\u001B[1;4mBold underline\u001B[0m plain")

        assertEquals(2, result.runs.size)
        val styled = result.runs[0]
        assertEquals("Bold underline", styled.text)
        assertTrue(styled.style.bold)
        assertTrue(styled.style.underline)

        val plain = result.runs[1]
        assertEquals(" plain", plain.text)
        assertFalse(plain.style.bold)
        assertFalse(plain.style.underline)
    }

    @Test
    fun `foreground and background colours are recorded as palette indices`() {
        val parser = parser()

        val result = parser.feed("\u001B[31mred")

        assertEquals(1, result.runs[0].style.foreground)

        val bg = parser.feed("\u001B[41mred")
        assertEquals(1, bg.runs[0].style.background)
    }

    @Test
    fun `a 256-colour sequence gives that palette index`() {
        val parser = parser()

        val result = parser.feed("\u001B[38;5;196mred")

        assertEquals(196, result.runs[0].style.foreground)
    }

    @Test
    fun `a true-colour sequence maps to a palette colour`() {
        val parser = parser()

        val result = parser.feed("\u001B[38;2;255;0;0mred")

        assertEquals(196, result.runs[0].style.foreground)
    }

    @Test
    fun `reverse video is flagged rather than approximated`() {
        val parser = parser()

        val result = parser.feed("\u001B[7mrev")

        assertTrue(result.runs[0].style.reverse)
    }

    @Test
    fun `sgr 0 resets every attribute`() {
        val parser = parser()

        val result = parser.feed("\u001B[1;31;44mx\u001B[0my")

        assertEquals("xy", textOf(result))
        assertEquals(2, result.runs.size)
        assertFalse(result.runs[1].style.bold)
        assertEquals(null, result.runs[1].style.foreground)
        assertEquals(null, result.runs[1].style.background)
    }

    // ── bounding: the whole reason this file exists ──────────────────────────

    @Test
    fun `retained text is bounded and the caller is told how much was dropped`() {
        val parser = TerminalScreenBuffer(maxChars = 32)

        // A long-running command, in eight reads like a real stream.
        for (i in 0 until 8) {
            parser.feed("0123456789AB") // 12 chars each, 96 in total
        }

        val snapshot = parser.snapshot()
        assertEquals("retained text must be capped", 32, snapshot.length)
        // It is the tail of the stream that survives: the newest text is the
        // text that explains what the shell is doing now.
        assertEquals("456789AB0123456789AB0123456789AB", snapshot)
    }

    @Test
    fun `overshooting the cap reports the trim on the feed that did it`() {
        val parser = TerminalScreenBuffer(maxChars = 4)

        val result = parser.feed("abcdefgh")

        assertEquals("efgh", parser.snapshot())
        assertEquals(4, result.trimmedFromFront)
    }

    @Test
    fun `a single line longer than the cap does not corrupt the line start`() {
        val parser = TerminalScreenBuffer(maxChars = 8)

        // No newline at all: the whole buffer is one line, and a negative
        // lineStart would throw on the next erase (delete with from > to).
        parser.feed("averyverylongsingleline")

        // Backspace must still work after the front was trimmed off.
        val result = parser.feed("\bx")
        assertEquals(1, result.retractChars)
        assertTrue("the cap must still hold", parser.snapshot().length <= 8)
        assertTrue("the edit must land at the tail", parser.snapshot().endsWith("x"))
    }

    @Test
    fun `text within the cap is never trimmed`() {
        val parser = TerminalScreenBuffer(maxChars = 1024)

        val result = parser.feed("small")

        assertEquals(0, result.trimmedFromFront)
    }

    // ── reset ────────────────────────────────────────────────────────────────

    @Test
    fun `reset drops the text and the attributes`() {
        val parser = parser()

        parser.feed("\u001B[31mred\u001B[0m")
        parser.reset()

        assertEquals("", parser.snapshot())
        val result = parser.feed("plain")
        assertEquals(null, result.runs[0].style.foreground)
    }

    // ── Auto-scroll follow rule ────────────────────────────────────────────────

    /**
     * The viewport follows only while the user is at the bottom.
     *
     * The runaway-terminal defect was `fullScroll(FOCUS_DOWN)` on every frame:
     * it fought a scroll up and, against the remote's startup blank lines, made
     * the view look like it was sliding down by itself. This pins the rule that
     * replaces it — follow when at/near the bottom, hold position otherwise.
     */
    @Test
    fun `output is followed when the viewport is exactly at the bottom`() {
        assertTrue(shouldFollowOutput(distanceFromBottom = 0, slackPx = 24))
    }

    @Test
    fun `output is followed within the settling slack`() {
        // A NestedScrollView rests a fraction short of the true bottom; that is
        // still "following", not a deliberate scroll up.
        assertTrue(shouldFollowOutput(distanceFromBottom = 10, slackPx = 24))
        assertTrue(shouldFollowOutput(distanceFromBottom = 24, slackPx = 24))
    }

    @Test
    fun `a deliberate scroll up stops the viewport from following`() {
        // Past the slack means the user moved up on purpose; their position must
        // be preserved, not yanked back to the bottom by the next newline.
        assertFalse(shouldFollowOutput(distanceFromBottom = 25, slackPx = 24))
        assertFalse(shouldFollowOutput(distanceFromBottom = 2000, slackPx = 24))
    }
}
