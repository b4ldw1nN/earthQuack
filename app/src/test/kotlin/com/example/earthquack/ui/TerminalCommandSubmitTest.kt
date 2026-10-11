package com.example.earthquack.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the command-submission byte layout.
 *
 * The bug these pin: [commandWithEnter] used to return the command text alone,
 * with no Enter key. A PTY shell is line-buffered by the tty, so it sat waiting
 * on its input line and never ran the command — the "I typed `ls` and nothing
 * happened" report. The fix appends a carriage return (0x0D), which the tty line
 * discipline turns into the newline the shell acts on.
 */
class TerminalCommandSubmitTest {

    @Test
    fun `appends a carriage return so the shell runs the line`() {
        val bytes = commandWithEnter("ls")
        // "ls" then CR (0x0D)
        assertArrayEquals(byteArrayOf('l'.code.toByte(), 's'.code.toByte(), 0x0D), bytes)
    }

    @Test
    fun `empty command still terminates with a carriage return`() {
        assertArrayEquals(byteArrayOf(0x0D), commandWithEnter(""))
    }

    @Test
    fun `preserves utf-8 command text before the carriage return`() {
        // A command with non-ASCII must keep its UTF-8 bytes and still end in CR.
        val bytes = commandWithEnter("echo café")
        val expected = "echo café".toByteArray(Charsets.UTF_8) + byteArrayOf(0x0D)
        assertArrayEquals(expected, bytes)
        // The trailing byte is the Enter key.
        assertEquals(0x0D, bytes.last().toInt())
    }

    @Test
    fun `spaces and special characters are passed through verbatim`() {
        val cmd = "echo 'a b  c' && ls -la"
        val bytes = commandWithEnter(cmd)
        assertEquals(cmd.toByteArray(Charsets.UTF_8).size + 1, bytes.size)
        assertEquals(0x0D, bytes.last().toInt())
        // Round-trips to the original text minus the terminator.
        assertEquals(
            cmd,
            String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
        )
    }

    @Test
    fun `enter byte is carriage return`() {
        assertEquals(0x0D, ENTER_BYTE.toInt())
    }
}
