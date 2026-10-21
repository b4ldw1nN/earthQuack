package com.example.earthquack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalGridResizeTest {

    @Test
    fun `a shorter screen keeps the newest rows and the cursor on its cell`() {
        val grid = TerminalGrid(6, 40)
        // Three lines of text, the cursor at the end of the last one.
        listOf("first", "second", "third").forEach {
            grid.feed((it + "\r\n").toByteArray(Charsets.UTF_8), 0, it.length + 2)
        }
        grid.feed("prompt".toByteArray(Charsets.UTF_8), 0, 6)
        val rowBefore = grid.cursorRow
        val colBefore = grid.cursorCol

        // The keyboard opens: the screen becomes shorter.
        grid.resize(4, 40)

        // The cursor kept its cell.
        assertEquals(colBefore, grid.cursorCol)
        // The content is anchored at the bottom, so the cursor moved up with
        // the rows that were dropped.
        assertEquals(rowBefore - 2, grid.cursorRow)
        assertTrue(grid.cursorRow >= 0)
    }

    @Test
    fun `a taller screen adds empty rows below and keeps the content`() {
        val grid = TerminalGrid(6, 40)
        grid.feed("hello".toByteArray(Charsets.UTF_8), 0, 5)
        val rowBefore = grid.cursorRow

        grid.resize(10, 40)

        assertEquals(rowBefore, grid.cursorRow)
        val rendered = grid.renderTrimmed().map { row -> row.joinToString("") { it.text } }
        assertEquals("hello", rendered.first().trimEnd())
    }

    @Test
    fun `the text is not re-wrapped when the width changes`() {
        val grid = TerminalGrid(6, 40)
        val line = "x".repeat(30)
        grid.feed(line.toByteArray(Charsets.UTF_8), 0, line.length)

        // Narrower: a real terminal keeps the line and clips it; it does not
        // re-flow it onto two rows.
        grid.resize(6, 20)

        val rendered = grid.renderTrimmed().map { row -> row.joinToString("") { it.text } }
        assertEquals(1, rendered.count { it.isNotBlank() })
        assertEquals(20, rendered.first().trimEnd().length)
    }
}
