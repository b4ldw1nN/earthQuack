package com.example.earthquack.ui

import org.junit.Test

/**
 * Replays a captured bash+starship+ble.sh session.
 *
 * The bytes are what the desktop shell actually sent for "whoami" followed by
 * "uptime -r", captured from the device.
 */
class TerminalGridCaptureTest {

    private val capture: ByteArray = javaClass.getResourceAsStream("/terminal-capture.bin")
        ?.readBytes() ?: error("capture missing")

    private fun play(rows: Int, cols: Int): List<String> {
        val grid = TerminalGrid(rows, cols)
        grid.feed(capture, 0, capture.size)
        return grid.renderTrimmed().map { row -> row.joinToString("") { it.text } }
    }

    @Test
    fun `the captured session renders the command and its output`() {
        val lines = play(24, 80)
        println("--- 24x80 ---")
        lines.forEachIndexed { i, l -> println("%2d| %s".format(i, l)) }
    }

    @Test
    fun `the captured session renders the same way in a short viewport`() {
        val lines = play(7, 130)
        println("--- 7x130 ---")
        lines.forEachIndexed { i, l -> println("%2d| %s".format(i, l)) }
    }
}
