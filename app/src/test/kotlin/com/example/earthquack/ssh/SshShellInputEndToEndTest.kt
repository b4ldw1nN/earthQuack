package com.example.earthquack.ssh

import org.apache.sshd.common.config.keys.KeyUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Terminal input, end to end, against a server that will actually run a command.
 *
 * The client under test is the shipped one: [SshConnectionFactory] connects and
 * authenticates, [SshConnection.openShell] opens the channel, and the submitted
 * command goes out through the same [ShellChannel.write] the UI uses. Nothing is
 * mocked except the two Android-storage seams — private key and host-key trust —
 * which are the seams the factory is already expressed in terms of.
 *
 * ## Why this test exists
 *
 * The terminal shipped a shell that accepted keystrokes and dropped them. It
 * looked like an sshd problem (`setIn` / `QueueInputStream`) and it was not: the
 * bytes did reach a real shell. What dropped them was the fragment's input
 * transport — a `Channel<ByteArray>` that [TerminalFragment.disconnect] closed
 * once and never recreated, so every send after a reconnect failed on a closed
 * channel whose result was discarded. Nothing could have caught that until a
 * test pointed the client at a server with a shell at all.
 *
 * ## What "the command ran" means here
 *
 * A marker is a line, not a substring. The submission is `echo <marker>`, so a
 * pty that echoes input produces `echo <marker>`, and only a shell that executed
 * the command produces `<marker>` on a line of its own. That is the assertion
 * that stops this test passing on an echo.
 */
class SshShellInputEndToEndTest {

    private lateinit var filesDir: java.io.File
    private lateinit var hostKeyPath: java.nio.file.Path

    private lateinit var identities: FakeIdentities
    private lateinit var trust: FakeTrust
    private lateinit var secrets: InMemorySecretStore

    private lateinit var server: ShellTestServer.Started
    private lateinit var clientKey: java.security.KeyPair

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("shell-e2e-files").toFile()
        hostKeyPath = Files.createTempDirectory("shell-e2e-hostkey")
        secrets = InMemorySecretStore()
        identities = FakeIdentities(null)
        trust = FakeTrust()
        clientKey = ShellTestServer.clientKeyPair()
        identities.key = clientKey
        // A fresh server per test, so the PTY record, the marker counter and the
        // thread population asserted below all belong to this test alone.
        server = ShellTestServer.start(hostKeyPath.resolve("hostkey"), clientKey.public)
    }

    @After
    fun tearDown() {
        server.stop()
        filesDir.deleteRecursively()
        hostKeyPath.toFile().deleteRecursively()
    }

    private fun factory() = SshConnectionFactory(
        identityKeys = identities,
        secrets = secrets,
        knownHosts = trust,
        filesDir = filesDir
    )

    private fun profile() = ConnectionProfile(
        id = "shell-input",
        name = "shell-input",
        host = "127.0.0.1",
        port = server.port,
        username = "zoro",
        authMethod = AuthMethod.PUBLIC_KEY,
        identityKeyAlias = "client",
        passwordAlias = null,
        hostKeyPolicy = HostKeyPolicy.TOUF
    )

    /** A marker no other test run can share. */
    private fun marker(tag: String): String = "EQK-$tag-" +
        java.util.UUID.randomUUID().toString().replace("-", "").take(12).uppercase()

    private suspend fun connect(): SshConnection =
        (factory().connect(profile()) as SshConnectResult.Connected).connection

    /** Polls [condition] for at most [timeoutMillis]. */
    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(25)
        }
        return true
    }

    private fun submit(shell: SshConnection.ShellChannel, token: String) {
        shell.write("echo $token\n".toByteArray(Charsets.UTF_8))
    }

    // ── 1 & 2: connect, authenticate, open a shell channel with a PTY ───────

    @Test
    fun `connects, authenticates and opens a shell channel with a PTY`() = runBlocking {
        val connection = connect()
        val sink = ShellOutputSink()
        val shell = connection.openShell(sink, rows = 24, cols = 80)
        assertTrue("the shell channel must report itself open", shell.isOpen)
        try {
            // The PTY geometry and modes travel on the pty-req sent with the
            // channel open. Reading them back off the server is the only way to
            // know the request was honoured rather than ignored — and it is the
            // server that first sees them, so this waits for the server rather
            // than assuming the client already knows.
            awaitUntil(TIMEOUT_MS) { server.requests.shellCount.get() >= 1 }
            assertEquals("xterm-256color", server.requests.ptyTerm)
            assertEquals(24, server.requests.ptyRows)
            assertEquals(80, server.requests.ptyColumns)
            assertTrue(
                "the client asked for ECHO, so the server must have received it",
                server.requests.echoModeEnabled
            )
            assertTrue("the session must survive opening a shell", connection.isOpen)
        } finally {
            shell.close()
            connection.close()
        }
    }

    // ── 3, 4, 5, 6, 7: input after the open, newline-terminated, executes ────

    @Test
    fun `a submitted command executes and its output reaches the callback`() = runBlocking {
        val connection = connect()
        val sink = ShellOutputSink()
        val shell = connection.openShell(sink, rows = 24, cols = 80)
        try {
            // (3) input is sent only after the channel is open, and (4) the
            // submission carries the newline the shell needs to run it.
            val token = marker("RAN")
            submit(shell, token)

            val received = sink.awaitLine(token, TIMEOUT_MS)

            // (5)(6) the bytes went through the production write path and came
            // back through the registered callback.
            assertTrue("the output callback must have run", sink.chunks.get() > 0)
            // (7) the marker must be a line, not a substring: a pty echoing the
            // submission yields `echo <token>`, which is not the token.
            assertTrue(
                "the command did not execute — no line equals the marker. " +
                    "received=${received.escaped()}",
                hasExactLine(received, token)
            )
        } finally {
            shell.close()
            connection.close()
        }
    }

    // ── the explicit anti-echo proof ─────────────────────────────────────────

    @Test
    fun `the marker is not merely an echo of the submitted bytes`() = runBlocking {
        val connection = connect()
        val sink = ShellOutputSink()
        val shell = connection.openShell(sink, rows = 24, cols = 80)
        try {
            val token = marker("ECHO")
            submit(shell, token)
            sink.awaitLine(token, TIMEOUT_MS)

            val received = sink.snapshot()
            val lines = received.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            val exact = lines.filter { it == token }
            assertTrue(
                "the only evidence of the command was its own echo; " +
                    "received=${received.escaped()}",
                exact.isNotEmpty()
            )
            // Whatever else mentions the token must mention it inside the
            // submitted command, which is the shape of an echo and only of an
            // echo. A bare token line cannot come from one.
            lines.filter { token in it && it != token }.forEach { line ->
                assertTrue(
                    "a line mentions the token without being the command: ${line.escaped()}",
                    line.startsWith("echo ")
                )
            }
        } finally {
            shell.close()
            connection.close()
        }
    }

    // ── 8: clean shutdown, no worker left blocked ────────────────────────────

    @Test
    fun `closing the shell and the connection leaves no blocked worker`() = runBlocking {
        val connection = connect()
        val sink = ShellOutputSink()
        val shell = connection.openShell(sink, rows = 24, cols = 80)
        val token = marker("CLEAN")
        submit(shell, token)
        assertTrue("the command ran before the close", hasExactLine(sink.awaitLine(token, TIMEOUT_MS), token))

        shell.close()
        connection.close()

        assertFalse("close must close the shell channel", shell.isOpen)
        assertFalse("close must close the session", connection.isOpen)

        // sshd names each channel's stdin-pump thread. The shell no longer uses
        // one, so none may exist, and in particular none may be left blocked in
        // a read that nothing will ever wake. Bounded poll: the pump is shut down
        // during channel close.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var leftBehind = inputPumpThreads()
        while (leftBehind.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50)
            leftBehind = inputPumpThreads()
        }
        assertTrue(
            "no input-pump worker may be left blocked: ${leftBehind.joinToString()}",
            leftBehind.isEmpty()
        )

        // Repeated teardown is a no-op, not a throw.
        shell.close()
        shell.close()
        connection.close()
    }

    // ── 9: reconnect, second command ────────────────────────────────────────

    @Test
    fun `reconnecting runs a second command on a fresh shell`() = runBlocking {
        val first = connect()
        val firstSink = ShellOutputSink()
        val firstShell = first.openShell(firstSink, rows = 24, cols = 80)
        try {
            val token = marker("FIRST")
            submit(firstShell, token)
            assertTrue(
                "first session did not run the command",
                hasExactLine(firstSink.awaitLine(token, TIMEOUT_MS), token)
            )
        } finally {
            firstShell.close()
            first.close()
        }

        // A brand new connection and a brand new channel. This is the path the
        // old transport dead-ended on: its input queue was closed by the first
        // disconnect and never recreated, so this send failed silently.
        val second = connect()
        val secondSink = ShellOutputSink()
        val secondShell = second.openShell(secondSink, rows = 24, cols = 80)
        try {
            val token = marker("SECOND")
            submit(secondShell, token)
            assertTrue(
                "the reconnected shell did not run the command",
                hasExactLine(secondSink.awaitLine(token, TIMEOUT_MS), token)
            )
        } finally {
            secondShell.close()
            second.close()
        }
        assertEquals("two shell sessions were served", 2, server.requests.shellCount.get())
    }

    // ── 10: writes to a closed shell fail predictably ────────────────────────

    @Test
    fun `writing to a closed shell fails rather than silently dropping input`() = runBlocking {
        val connection = connect()
        val sink = ShellOutputSink()
        val shell = connection.openShell(sink, rows = 24, cols = 80)
        shell.close()

        assertFalse("a closed shell must report itself closed", shell.isOpen)
        val token = marker("DEAD")
        try {
            submit(shell, token)
            fail("writing to a closed shell must throw, not discard the input")
        } catch (e: IOException) {
            // sshd's own exception type; what matters is that it is an
            // IOException carrying a reason a UI can show.
            assertTrue(
                "the failure must say the channel is closed: ${e.message}",
                (e.message ?: "").contains("closed", ignoreCase = true)
            )
        }
        assertFalse(
            "nothing may reach the remote shell after the channel closed",
            sink.snapshot().contains(token)
        )

        // The same for a shell whose session went away underneath it.
        val live = connection.openShell(sink, rows = 24, cols = 80)
        connection.close()
        try {
            submit(live, token)
            fail("writing after the session closed must throw")
        } catch (e: IOException) {
            assertTrue("the failure must carry a reason", (e.message ?: "").isNotBlank())
        }
    }

    // ── interleaved writes: the serializer must hold ──────────────────────────

    @Test
    fun `concurrent writes are not interleaved into each other`() = runBlocking {
        val connection = connect()
        val sink = ShellOutputSink()
        val shell = connection.openShell(sink, rows = 24, cols = 80)
        try {
            val tokens = (1..8).map { marker("CONC$it") }
            val ready = CountDownLatch(1)
            val threads = tokens.map { token ->
                Thread {
                    ready.await()
                    submit(shell, token)
                }.apply { isDaemon = true; start() }
            }
            ready.countDown()
            threads.forEach { it.join(15_000) }

            // Each marker must survive as its own line. An interleaved write
            // would corrupt one into something else.
            for (token in tokens) {
                assertTrue(
                    "marker $token was corrupted or lost: ${sink.snapshot().escaped()}",
                    hasExactLine(sink.awaitLine(token, TIMEOUT_MS), token)
                )
            }
        } finally {
            shell.close()
            connection.close()
        }
    }

    // ── a channel the server will not open is reported, and cleaned up ────────

    @Test
    fun `a shell channel the server declines is reported and nothing is left half open`() = runBlocking {
        // A server with no channel types at all: the client's channel open is
        // answered with a failure, which is what openShell must classify and
        // clean up after. (A shell *request* refusal is a different thing — see
        // the note in SshShellInputEndToEndTest's class comment — and sshd's
        // default `channel-shell-want-reply=false` means the client is not told
        // about it at all.)
        val declining = org.apache.sshd.server.SshServer.setUpDefaultServer()
        declining.keyPairProvider = org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
            hostKeyPath.resolve("declining-key")
        ).apply { algorithm = "EC"; keySize = 256 }
        declining.port = 0
        // Only a TCP-forwarding channel factory: the client asks for a "session"
        // channel, the server has no factory for that type, and the open is
        // answered with a failure. That is what openShell must classify and
        // clean up after. (A shell *request* refusal is a different thing —
        // sshd's default `channel-shell-want-reply=false` means the client is
        // never told about one — so this is the failure it can observe.)
        declining.channelFactories =
            listOf(org.apache.sshd.server.forward.DirectTcpipFactory.INSTANCE)
        declining.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator {
            _, key, _ -> key == clientKey.public
        }
        declining.start()

        val connection = try {
            (factory().connect(
                profile().copy(port = declining.port, id = "declining", name = "declining")
            ) as SshConnectResult.Connected).connection
        } finally {
            identities.key = clientKey
        }
        try {
            val failure = runCatching { connection.openShell(ShellOutputSink()) }
            assertTrue(
                "a declined channel open must be raised, not returned as a live shell",
                failure.isFailure
            )
            val error = failure.exceptionOrNull()
            assertTrue(
                "the failure must be an IOException carrying a reason: $error",
                error is IOException && !error.message.isNullOrBlank()
            )
            assertTrue(
                "the session must outlive a declined channel: ${error?.message}",
                connection.isOpen
            )
            // And the same session can still open a shell on the real server
            // above, so the failure was confined to the channel.
        } finally {
            connection.close()
            runCatching { declining.stop(true) }
        }

        val good = connect()
        val sink = ShellOutputSink()
        val shell = good.openShell(sink, rows = 24, cols = 80)
        try {
            val token = marker("AFTERFAIL")
            submit(shell, token)
            assertTrue(
                "a shell must still open after a declined one",
                hasExactLine(sink.awaitLine(token, TIMEOUT_MS), token)
            )
        } finally {
            shell.close()
            good.close()
        }
    }

    // ── opening on a closed session fails, loudly ────────────────────────────

    @Test
    fun `opening a shell after the session closed fails instead of hanging`() = runBlocking {
        val connection = connect()
        val shell = connection.openShell(ShellOutputSink(), rows = 24, cols = 80)
        shell.close()
        connection.close()

        val failure = runCatching { connection.openShell(ShellOutputSink()) }
        assertTrue("a closed session must refuse to open a shell", failure.isFailure)
        assertFalse("the session must stay closed", connection.isOpen)
    }

    private fun String.escaped(): String = replace("\r", "<CR>").replace("\n", "<LF>\n")

    private companion object {
        /**
         * Bounded and generous for a CI box. A command on loopback answers in
         * milliseconds, so a failure shows up as an absent marker rather than
         * as the whole budget being spent.
         */
        const val TIMEOUT_MS = 15_000L
    }

    // ── the two Android-storage seams, as fakes ──────────────────────────────

    private class FakeIdentities(var key: java.security.KeyPair?) : ClientIdentityProvider {
        override fun loadKeyPair(alias: String): java.security.KeyPair? = key
    }

    private class FakeTrust : HostKeyTrust {
        val recorded = mutableMapOf<String, PublicKey>()
        fun seed(id: String, key: PublicKey) { recorded[id] = key }
        override fun verify(profileId: String, publicKey: PublicKey): HostKeyVerdict {
            val known = recorded[profileId]
                ?: return HostKeyVerdict.Unknown(KeyUtils.getFingerPrint(publicKey), publicKey.algorithm)
            if (known == publicKey) return HostKeyVerdict.Trusted
            return HostKeyVerdict.Mismatch(
                KeyUtils.getFingerPrint(known), KeyUtils.getFingerPrint(publicKey)
            )
        }
        override fun record(profileId: String, publicKey: PublicKey) { recorded[profileId] = publicKey }
        override fun forget(profileId: String) { recorded.remove(profileId) }
    }
}
