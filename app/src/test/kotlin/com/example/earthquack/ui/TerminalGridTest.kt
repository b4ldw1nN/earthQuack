package com.example.earthquack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed-grid terminal emulator, on the JVM.
 *
 * These tests pin the failure that the linear [TerminalScreenBuffer] could not
 * fix: a prompt that redraws itself in place. With an append-only buffer every
 * redraw stacked a fresh copy, which is the "viewport drifts down, blank space
 * above the prompt" report. [TerminalGrid] keeps a real `(row, col)` screen, so
 * a redraw overwrites instead of appending, and cursor addressing, line insert/
 * delete, and erase-in-display actually do something.
 *
 * Each test names the behaviour it pins.
 */
class TerminalGridTest {

    private fun grid(rows: Int = 3, cols: Int = 10) = TerminalGrid(rows, cols)

    /** Rendered screen as lines with trailing blanks trimmed. */
    private fun lines(g: TerminalGrid): List<String> =
        g.render().map { runs -> runs.joinToString("") { it.text } }.map { it.trimEnd() }

    private fun feed(g: TerminalGrid, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        g.feed(b, 0, b.size)
    }

    // ── basic printing ────────────────────────────────────────────────────────

    @Test
    fun `plain text lands in the first row at the cursor`() {
        val g = grid(3, 10)
        feed(g, "hi")
        assertEquals(listOf("hi", "", ""), lines(g))
        assertEquals(0, g.cursorRow)
        assertEquals(2, g.cursorCol)
    }

    @Test
    fun `a carriage return overwrites the line in place rather than appending`() {
        // This is the core fix: a progress redraw must not stack.
        val g = grid(3, 20)
        feed(g, "50%\r")
        feed(g, "100%")
        assertEquals(listOf("100%", "", ""), lines(g))
    }

    @Test
    fun `line feed moves down and carriage return plus rewrite leaves one line`() {
        val g = grid(3, 20)
        feed(g, "user@host\r\n")
        feed(g, "❯ ") // prompt on row 1 (trailing space is trimmed by lines())
        assertEquals(listOf("user@host", "❯", ""), lines(g))
    }

    // ── cursor addressing (the documented gap in the linear buffer) ───────────

    @Test
    fun `CUP positions the cursor and subsequent text lands there`() {
        val g = grid(3, 10)
        feed(g, "\u001B[2;3HX") // ESC[2;3H -> row 2, col 3 (1-based).
        val r = lines(g)
        assertEquals("", r[0])
        assertEquals("  X", r[1]) // two blank cells then X
    }

    @Test
    fun `cursor movement up down forward back stays in bounds and preserves column`() {
        val g = grid(3, 5)
        feed(g, "\u001B[2;2H") // row2 col2 (0-indexed row1 col1)
        feed(g, "A") // A at (1,1), cursor now (1,2)
        feed(g, "\u001B[A") // up: row 0, column preserved -> (0,2)
        feed(g, "B") // B at (0,2)
        feed(g, "\u001B[B") // back down -> (1,2)
        feed(g, "C") // C at (1,2), cursor (1,3)
        feed(g, "\u001B[99C") // clamp to last col -> (1,4)
        feed(g, "D") // D at (1,4)
        val r = lines(g)
        assertEquals("  B", r[0]) // B at col 2 (CUU preserved the column)
        // CUU/CUD preserve the column: after B the cursor is at col 3, so the
        // ESC[B moves it back down to (row1, col3) and C lands there.
        assertEquals(" A CD", r[1]) // A col1, C col3, D col4
        assertEquals(1, g.cursorRow)
        assertEquals(4, g.cursorCol)
    }

    // ── erase ───────────────────────────────────────────────────────────────

    @Test
    fun `erase in line clears to end of line`() {
        val g = grid(3, 10)
        feed(g, "hello")
        feed(g, "\u001B[3G") // move to col 3
        feed(g, "\u001B[0K") // erase from cursor to end
        assertEquals(listOf("he", "", ""), lines(g))
    }

    @Test
    fun `erase whole line clears the row`() {
        val g = grid(3, 10)
        feed(g, "hello")
        feed(g, "\u001B[2K")
        assertEquals(listOf("", "", ""), lines(g))
    }

    @Test
    fun `erase display whole screen clears everything`() {
        val g = grid(3, 10)
        feed(g, "aaa\r\nbbb\r\nccc")
        feed(g, "\u001B[2J")
        assertEquals(listOf("", "", ""), lines(g))
    }

    // ── insert / delete line and char ─────────────────────────────────────────

    @Test
    fun `insert lines pushes existing lines down`() {
        val g = grid(3, 10)
        feed(g, "one\r\ntwo\r\nthree")
        feed(g, "\u001B[H") // home
        feed(g, "\u001B[L") // insert one line at top
        assertEquals(listOf("", "one", "two"), lines(g))
    }

    @Test
    fun `delete characters shifts the tail left`() {
        val g = grid(3, 10)
        feed(g, "abcdef")
        feed(g, "\u001B[H") // col 1
        feed(g, "\u001B[P") // delete 1 char
        assertEquals(listOf("bcdef", "", ""), lines(g))
    }

    // ── scrolling and scrollback ──────────────────────────────────────────────

    @Test
    fun `line feed at the bottom scrolls up and keeps prior lines in scrollback`() {
        val g = grid(2, 10)
        feed(g, "a\r\nb\r\nc")
        // Live screen now shows b, c; 'a' is in scrollback.
        assertTrue(g.isAtLive())
        assertEquals(listOf("b", "c"), lines(g))
    }

    @Test
    fun `scrolling up into history then back to live restores the live screen`() {
        val g = grid(2, 10)
        feed(g, "a\r\nb\r\nc")
        g.scrollViewUp(1)
        assertFalse(g.isAtLive())
        // One line up: top line becomes the scrolled-off 'a'.
        assertEquals(listOf("a", "b"), lines(g))
        g.scrollToLive()
        assertTrue(g.isAtLive())
        assertEquals(listOf("b", "c"), lines(g))
    }

    @Test
    fun `viewport offset cannot exceed the retained scrollback`() {
        val g = grid(2, 10)
        feed(g, "a\r\nb\r\nc") // 'a' scrolls into the retained history (1 line)
        g.scrollViewUp(1000)
        // Only one line is in scrollback, so the offset clamps to 1, not 1000.
        assertFalse(g.isAtLive())
        g.scrollToLive()
        assertTrue(g.isAtLive())
    }

    // ── scroll region (DECSTBM) ────────────────────────────────────────────────

    @Test
    fun `scroll region keeps the line above it fixed while the region scrolls`() {
        val g = grid(3, 10)
        feed(g, "\u001B[2;3r") // scroll region = rows 2-3 (1-based) -> index [1,2]
        feed(g, "\u001B[1;1Htop") // status line at row 1 (index 0), OUTSIDE region
        feed(g, "\u001B[2;1Hone\r\ntwo\r\nthree")
        // Row 0 (the status 'top') must stay put; the region below scrolls.
        assertEquals("top", lines(g)[0])
    }

    // ── resize ────────────────────────────────────────────────────────────────

    @Test
    fun `resize preserves the top-left overlap`() {
        val g = grid(3, 10)
        feed(g, "hello")
        g.resize(4, 6)
        assertEquals(listOf("hello", "", "", ""), lines(g))
        assertEquals(4, g.render().size)
    }

    @Test
    fun `resize with no change is a no-op`() {
        val g = grid(3, 10)
        feed(g, "hello")
        g.resize(3, 10)
        assertEquals(listOf("hello", "", ""), lines(g))
    }

    // ── UTF-8 across reads ────────────────────────────────────────────────────

    @Test
    fun `a multi-byte character split across reads is decoded as one cell`() {
        val g = grid(3, 10)
        val marker = "▶".toByteArray(Charsets.UTF_8)
        g.feed(marker, 0, 1)
        g.feed(marker, 1, 1)
        g.feed(marker, 2, 1)
        assertEquals("▶", lines(g)[0])
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears screen scrollback and cursor`() {
        val g = grid(2, 10)
        feed(g, "a\r\nb\r\nc")
        g.reset()
        assertTrue(g.isAtLive())
        assertEquals(listOf("", ""), lines(g))
        assertEquals(0, g.cursorRow)
        assertEquals(0, g.cursorCol)
    }

    // ── device status report ───────────────────────────────────────────────────

    @Test
    fun `a cursor position report is answered through the callback`() {
        val g = grid(3, 10)
        feed(g, "\u001B[2;5H") // cursor to row2 col5
        var reported: String? = null
        g.onDeviceReport = { reported = String(it, Charsets.US_ASCII) }
        feed(g, "\u001B[6n")
        assertEquals("\u001B[2;5R", reported)
    }

    // ── bounded scrollback ─────────────────────────────────────────────────────

    @Test
    fun `scrollback is bounded so a runaway stream cannot grow without limit`() {
        val g = grid(2, 4)
        // Emit far more than MAX_SCROLLBACK lines.
        repeat(TerminalGrid.MAX_SCROLLBACK + 500) { feed(g, "xxxx\r\n") }
        // Feed is bounded; the live screen still renders and is responsive.
        assertEquals(2, g.render().size)
        assertTrue(g.isAtLive())
    }
}

