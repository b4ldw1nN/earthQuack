package com.example.earthquack.ui

import org.junit.Test

class TerminalCursorTest {
    @Test
    fun `the starship prompt leaves the cursor after the arrow`() {
        val grid = TerminalGrid(24, 80)
        val prompt = (
            "\u001B[?25l\r\u001B(B\u001B[m\r\n\r\n\u001B(B\u001B[m\u001B[1A\u001B[2L\u001B[2K" +
                "\u001B(B\u001B[m\u001B[1A\u001B[2K\r\n\u001B[1G\u001B[2K\u001B[2M\u001B[2L\u001B[2K" +
                "\u001B[1A\r\r\nzoro in \u001B[0;38;2;230;180;253m~\u001B(B\u001B[m \r\r\n" +
                "\u001B[0;35m❯\u001B(B\u001B[m \u001B[?25h"
            )
        grid.feed(prompt.toByteArray(Charsets.UTF_8), 0, prompt.toByteArray(Charsets.UTF_8).size)
        println("cursor after prompt: ${grid.cursorRow}:${grid.cursorCol}")
        val lines = grid.renderTrimmed().map { row -> row.joinToString("") { it.text } }
        lines.forEachIndexed { i, l -> println("%2d| %s".format(i, l)) }
    }
}
