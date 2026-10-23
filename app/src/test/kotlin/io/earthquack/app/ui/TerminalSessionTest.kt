package io.earthquack.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionTest {

    @Test
    fun termSessionInitializesEmulator() {
        val session = TerminalSession("test-profile")
        assertNotNull(session.termSession)

        // Initialize size
        session.termSession.updateSize(80, 24)
        val emulator = session.termSession.emulator
        assertNotNull(emulator)
        assertEquals(80, emulator.mColumns)
        assertEquals(24, emulator.mRows)
    }

    @Test
    fun remoteDataAppendsToEmulator() {
        val session = TerminalSession("test-profile")
        session.termSession.updateSize(80, 24)

        val output = "Hello from remote SSH\r\n".toByteArray(Charsets.UTF_8)
        session.termSession.appendRemoteData(output, 0, output.size)

        // Give the handler a moment or inspect emulator screen directly
        val screen = session.termSession.emulator.screen
        assertNotNull(screen)
    }
}
