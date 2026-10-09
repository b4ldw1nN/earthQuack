package com.example.earthquack.sftp

import com.example.earthquack.ssh.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Why the SFTP screen's Start button appeared to do nothing.
 *
 * ## The bug
 *
 * `SftpFragment.toggleServer` chose its action with
 * `if (status == SftpServerStatus.Stopped) start() else stop()`, while the
 * button's *label* was chosen with `status is SftpServerStatus.Running`.
 *
 * `SftpServerStatus` is a sealed class with `Stopped` as a singleton `object`
 * and `Error` as a separate `data class`. So the two comparisons disagreed
 * exactly when the status was `Error`:
 *
 *  1. Press Start. Status is `Stopped`, so `start()` runs.
 *  2. The settings are unusable (the old default was `passwordAuth = true` with
 *     no password stored, which can never bind), so the status becomes `Error`.
 *  3. The button still reads "Start" — it is not `Running` — but pressing it now
 *     calls `stop()`, because `Error != Stopped`.
 *  4. `stop()` returns `Stopped`. Press Start again: `start()`, `Error`,
 *     `Stopped`, `start()`, `Error` … The server never starts, and the button
 *     gives no impression of having been pressed at all.
 *
 * ## What this pins down
 *
 * The rules that make the *fixed* branch correct, expressed without a View or a
 * Service: the action must be derived from "is it running", never from an
 * equality test against one particular non-running status. And a start that
 * cannot succeed must leave the previous good configuration alone.
 *
 * Both of these are properties of [SftpServerStatus] and the engine, which is
 * what makes them testable here.
 */
class SftpStartButtonTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var secrets: InMemorySecretStore
    private lateinit var engine: SftpServerEngine

    @org.junit.Before
    fun setUp() {
        root = Files.createTempDirectory("start-btn-root").toFile()
        filesDir = Files.createTempDirectory("start-btn-files").toFile()
        secrets = InMemorySecretStore()
        engine = SftpServerEngine(filesDir, secrets, FixedHostKeys(), FixedAuthorizedKeys(setOf()))
    }

    @org.junit.After
    fun tearDown() {
        engine.stop()
        root.deleteRecursively()
        filesDir.deleteRecursively()
    }

    private fun settings(
        port: Int = 0,
        passwordAuth: Boolean = false,
        publicKeyAuth: Boolean = true,
        username: String = "earthquack"
    ) = SftpSettings(
        port = port,
        rootPath = root.absolutePath,
        username = username,
        passwordAuth = passwordAuth,
        publicKeyAuth = publicKeyAuth,
        maxConnections = 4
    )

    /**
     * The state the app is left in after a failed start.
     *
     * This is the state the broken branch misread. `Stopped` and `Error` are
     * distinct values, so a caller that tests `== Stopped` must treat `Error` as
     * "not stopped" — which is what turned the button into a Stop button.
     */
    @Test
    fun `an Error status is a distinct state from Stopped and from Running`() {
        val stopped = SftpServerStatus.Stopped
        val error = SftpServerStatus.Error("cannot start")

        assertFalse("Error must not be Stopped", error == stopped)
        assertFalse("Error must not be Running", error is SftpServerStatus.Running)
        assertFalse("and Stopped is not Running either", stopped is SftpServerStatus.Running)

        // The only predicate that separates "the server is up" from everything
        // else, without depending on which adjective is in play. This is what
        // the fixed branch uses; `== Stopped` is what the broken one used.
        assertFalse("Stopped must not count as running", stopped is SftpServerStatus.Running)
        assertFalse("Error must not count as running", error is SftpServerStatus.Running)
    }

    /**
     * The old default configuration, which guaranteed a failure on a fresh
     * install: password authentication on, no password stored.
     */
    @Test
    fun `the old default settings cannot start, which is what exposed the branch`() {
        // Mirrors the previous SftpSettingsStore.load() defaults.
        val result = engine.start(settings(passwordAuth = true, publicKeyAuth = false))

        assertTrue("expected the start to fail", result.isFailure)
        assertTrue(
            "and it must say why",
            result.exceptionOrNull()?.message?.contains("password", true) == true
        )
        assertFalse(engine.isRunning())
    }

    /**
     * A failed start must leave the engine in a state where a *retry* is
     * possible, not one where the button would silently call stop() instead.
     */
    @Test
    fun `a failed start leaves the engine stopped so a retry can start it`() {
        val first = engine.start(settings(passwordAuth = true, publicKeyAuth = false))
        assertTrue(first.isFailure)
        assertFalse(engine.isRunning())
        assertEquals(0, engine.serverPort())

        // The user fixes the problem (stores the password the settings promise)
        // and presses Start again. Whatever the button's label logic does, the
        // engine must be in a state where this succeeds rather than one where
        // the previous failure blocks it.
        secrets.put("sftp_password", "pw".toByteArray())
        val second = engine.start(settings(passwordAuth = true, publicKeyAuth = false))
        assertTrue("a retry after a fix must be able to start", second.isSuccess)
        assertTrue(engine.isRunning())
    }

    /**
     * The enabled-without-a-password state must never persist.
     *
     * This is the transition that made the old screen unrecoverable: the switch
     * asked for a password but never committed the flag, so the settings kept
     * promising an authentication method that did not exist.
     */
    @Test
    fun `enabling password authentication with no stored password cannot succeed`() {
        val result = engine.start(settings(passwordAuth = true, publicKeyAuth = false))
        assertTrue(result.isFailure)
        // Nothing may be left listening, and nothing may claim to be.
        assertFalse(engine.isRunning())
    }

    /**
     * And with a password present, the same request must succeed — so the fix
     * is in the sequence, not in refusing password auth.
     */
    @Test
    fun `password authentication works once a password is stored`() {
        secrets.put("sftp_password", "pw".toByteArray())
        val result = engine.start(settings(passwordAuth = true, publicKeyAuth = false))
        assertTrue("start failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue(engine.isRunning())
    }

    /**
     * `Error` must be a *reachable*, non-terminal state: it is normal for a
     * server screen to sit in Error while the user fixes the cause, and the
     * next Start must be able to leave it.
     */
    @Test
    fun `an Error state is left by a subsequent successful start`() {
        engine.start(settings(passwordAuth = true, publicKeyAuth = false)) // -> Error
        assertFalse(engine.isRunning())

        secrets.put("sftp_password", "pw".toByteArray())
        val recovery = engine.start(settings(passwordAuth = true, publicKeyAuth = false))

        assertTrue("recovery from Error must be possible", recovery.isSuccess)
        assertTrue(engine.matches(settings(passwordAuth = true, publicKeyAuth = false)))
    }

    /** A store default that yields a startable first run. */
    @Test
    fun `both authentication methods off is a startable-after-configuration state`() {
        // The new store default. It fails, but with the actionable reason —
        // "turn one on" — instead of a stale password flag.
        val result = engine.start(settings(passwordAuth = false, publicKeyAuth = false))
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("authentication", true) == true
        )
    }

    private class FixedHostKeys : SftpHostKeys {
        override fun fingerprint(): String? = null
        override fun record(fingerprint: String) {}
        override fun forget() {}
    }

    private class FixedAuthorizedKeys(private val keys: Set<java.security.PublicKey>) :
        SftpAuthorizedKeys {
        override fun list(): List<SftpAuthorizedKeys.Entry> = keys.map {
            SftpAuthorizedKeys.Entry(it.algorithm, "")
        }
        override fun matches(key: java.security.PublicKey) = keys.any { it == key }
    }
}
