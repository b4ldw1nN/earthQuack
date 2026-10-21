package com.example.earthquack.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalShellRedrawTest {

    /**
     * bash redraws the line it is editing: it moves the cursor back, writes
     * the line again and leaves the cursor *on* the last character.
     */
    @Test
    fun `the shell's line redraw leaves the cursor on the last character`() {
        val grid = TerminalGrid(24, 80)
        // The prompt: "❯ " with the cursor after it.
        val prompt = "\u001B[0;35m❯\u001B(B\u001B[m ".toByteArray(Charsets.UTF_8)
        grid.feed(prompt, 0, prompt.size)
        assertEquals(2, grid.cursorCol)

        // The echo of the first character.
        val echo = "\u001B[?25l\u001B[0;38;5;231;48;5;203m\u001B[0;38;5;231;48;5;203ml\u001B(B\u001B[m\u001B[K\u001B[?25h"
            .toByteArray(Charsets.UTF_8)
        grid.feed(echo, 0, echo.size)
        assertEquals(3, grid.cursorCol)

        // The redraw: back one, write "ls" again, erase, and back one again
        // so the cursor sits on the last character.
        val redraw = "\u001B[?25l\u001B(B\u001B[m\u001B[1D\u001B[0;36m\u001B[0;36ml" +
            "\u001B[0;38;5;238;48;5;254ms\u001B(B\u001B[m\u001B[K\u001B(B\u001B[m\u001B[1D\u001B[?25h"
        grid.feed(redraw.toByteArray(Charsets.UTF_8), 0, redraw.toByteArray(Charsets.UTF_8).size)

        // The cursor belongs on the "s", the way a real shell leaves it.
        assertEquals(3, grid.cursorCol)
        val line = grid.renderTrimmed().last().joinToString("") { it.text }
        assertEquals("❯ ls", line.trimEnd())
    }
}
