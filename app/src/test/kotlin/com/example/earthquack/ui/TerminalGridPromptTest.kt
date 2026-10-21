package com.example.earthquack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid against real prompts.
 *
 * The bytes below are what bash+starship actually sends for its two-line
 * prompt: a status line, then the "❯" line, redrawn with a cursor-up /
 * erase / delete-lines dance every time the duration module ticks.
 */
class TerminalGridPromptTest {

    private fun screen(grid: TerminalGrid): List<String> =
        grid.render().map { row -> row.joinToString("") { it.text } }

    @Test
    fun `the starship prompt lands on two lines with the cursor after the arrow`() {
        val grid = TerminalGrid(24, 80)
        val prompt = (
            "\u001B[?25l\r\u001B(B\u001B[m\r\n\r\n\u001B(B\u001B[m\u001B[1A\u001B[2L\u001B[2K" +
                "\u001B(B\u001B[m\u001B[1A\u001B[2K\r\n\u001B[1G\u001B[2K\u001B[2M\u001B[2L\u001B[2K" +
                "\u001B[1A\r\r\n\u001B[0;1;38;2;170;204;216mzoro\u001B(B\u001B[m in " +
                "\u001B[0;1;38;2;117;211;240m🌐 archii\u001B(B\u001B[m in " +
                "\u001B[0;38;2;230;180;253m~\u001B(B\u001B[m \r\r\n" +
                "\u001B[0;35m❯\u001B(B\u001B[m \u001B[?25h"
            )
        grid.feed(prompt.toByteArray(Charsets.UTF_8), 0, prompt.toByteArray(Charsets.UTF_8).size)

        val lines = screen(grid)
        val status = lines.indexOfFirst { it.contains("zoro") }
        assertTrue("the status line should be on screen: $lines", status >= 0)
        assertEquals("❯", lines[status + 1].trim())
        // The cursor belongs right after the arrow, on the same line.
        assertEquals(status + 1, grid.cursorRow)
    }

    @Test
    fun `a redraw of the prompt does not move it`() {
        val grid = TerminalGrid(24, 80)
        val first = (
            "\u001B[?25l\r\u001B(B\u001B[m\r\n\r\n\u001B(B\u001B[m\u001B[1A\u001B[2L\u001B[2K" +
                "\u001B(B\u001B[m\u001B[1A\u001B[2K\r\n\u001B[1G\u001B[2K\u001B[2M\u001B[2L\u001B[2K" +
                "\u001B[1A\r\r\nzoro in \u001B[0;38;2;230;180;253m~\u001B(B\u001B[m \r\r\n" +
                "\u001B[0;35m❯\u001B(B\u001B[m \u001B[?25h"
            )
        grid.feed(first.toByteArray(Charsets.UTF_8), 0, first.toByteArray(Charsets.UTF_8).size)
        val before = screen(grid)
        val statusBefore = before.indexOfFirst { it.contains("zoro") }

        // The one-second redraw: up two, erase, delete two, insert two, up one.
        val redraw = (
            "\u001B[?25l\u001B(B\u001B[m\u001B[2A\r\u001B[2K\r\n\u001B[1G\u001B[2K\u001B[2M" +
                "\u001B[2L\u001B[2K\u001B[1A\r\r\nzoro in \u001B[0;38;2;230;180;253m~\u001B(B\u001B[m \r\r\n" +
                "\u001B[0;35m❯\u001B(B\u001B[m \u001B[?25h"
            )
        grid.feed(redraw.toByteArray(Charsets.UTF_8), 0, redraw.toByteArray(Charsets.UTF_8).size)

        val after = screen(grid)
        val statusAfter = after.indexOfFirst { it.contains("zoro") }
        assertEquals("the prompt must not move on a redraw", statusBefore, statusAfter)
        assertEquals("❯", after[statusAfter + 1].trim())
        assertEquals(statusAfter + 1, grid.cursorRow)
    }

    @Test
    fun `typed text is echoed on the prompt line`() {
        val grid = TerminalGrid(24, 80)
        val prompt = (
            "\u001B[?25l\r\u001B(B\u001B[m\r\n\r\n\u001B(B\u001B[m\u001B[1A\u001B[2L\u001B[2K" +
                "\u001B(B\u001B[m\u001B[1A\u001B[2K\r\n\u001B[1G\u001B[2K\u001B[2M\u001B[2L\u001B[2K" +
                "\u001B[1A\r\r\nzoro in ~\r\r\n\u001B[0;35m❯\u001B(B\u001B[m \u001B[?25h"
            )
        grid.feed(prompt.toByteArray(Charsets.UTF_8), 0, prompt.toByteArray(Charsets.UTF_8).size)
        val echo = "whoami"
        grid.feed(echo.toByteArray(Charsets.UTF_8), 0, echo.toByteArray(Charsets.UTF_8).size)

        val lines = screen(grid)
        val status = lines.indexOfFirst { it.contains("zoro") }
        assertEquals(
            "the echoed text belongs on the arrow line",
            "❯ whoami",
            lines[status + 1].trim(),
        )
    }
}
