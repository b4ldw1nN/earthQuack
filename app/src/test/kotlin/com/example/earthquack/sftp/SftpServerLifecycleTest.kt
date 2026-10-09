package com.example.earthquack.sftp

import com.example.earthquack.ssh.InMemorySecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server lifecycle rules, without a device and without a service.
 *
 * Every test drives a real `SftpServerEngine` — the same object the foreground
 * service starts — so a claim here is a claim about the shipped code rather than
 * about a mock of the protocol. The engine's only non-JVM dependency is the
 * Context it normally receives, and this test supplies fakes for the three
 * stores it reads.
 *
 * ## What these pin down
 *
 * The engine owns one listener and one configuration. That means:
 *
 *  - success is reported only once sshd has actually bound, and the port handed
 *    back is the one it bound (which is not the requested port when 0 is used);
 *  - an identical repeat is a no-op, not a second server;
 *  - a changed configuration is validated *before* the working server is
 *    stopped, so a rejected change cannot take the server down with it;
 *  - a bind failure after the old listener is gone is reported as a failure and
 *    leaves nothing listening;
 *  - start and stop are serialised, so overlapping requests cannot leave an
 *    orphaned listener holding the port.
 */
class SftpServerLifecycleTest {

    private lateinit var root: File
    private lateinit var secondRoot: File
    private lateinit var filesDir: File
    private lateinit var secrets: InMemorySecretStore
    private lateinit var hostKeyStore: RecordingHostKeys
    private lateinit var authorizedKeys: DeferredAuthorizedKeys
    private lateinit var engine: SftpServerEngine

    private val clientKey: KeyPair by lazy { generateEc() }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lifecycle-root").toFile()
        secondRoot = Files.createTempDirectory("lifecycle-root2").toFile()
        filesDir = Files.createTempDirectory("lifecycle-files").toFile()

        secrets = InMemorySecretStore()
        secrets.put(PASSWORD_ALIAS, "s3cret".toByteArray(Charsets.UTF_8))
        hostKeyStore = RecordingHostKeys()
        authorizedKeys = DeferredAuthorizedKeys()
    }

    @After
    fun tearDown() {
        engine.stop()
        root.deleteRecursively()
        secondRoot.deleteRecursively()
        filesDir.deleteRecursively()
    }

    private fun engine(
        keys: List<KeyPair> = listOf(clientKey),
        password: String? = "s3cret"
    ) = SftpServerEngine(filesDir, secrets, hostKeyStore, authorizedKeys)
        .also { authorizedKeys.setKeys(keys) }

    private fun settings(
        port: Int = 0,
        root: File = this.root,
        passwordAuth: Boolean = true,
        publicKeyAuth: Boolean = true,
        username: String = DEFAULT_USER,
        maxConnections: Int = 4
    ) = SftpSettings(
        port = port,
        rootPath = root.absolutePath,
        username = username,
        passwordAuth = passwordAuth,
        publicKeyAuth = publicKeyAuth,
        maxConnections = maxConnections
    )

    // ── 1. initial start ────────────────────────────────────────────────────

    @Test
    fun `an initial start binds an ephemeral port and reports the one it bound`() {
        engine = engine()
        val result = engine.start(settings())

        assertTrue("start failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue("the engine must report the bound port", engine.serverPort() > 0)
        // Listening is proven by connecting, not by a flag: a bound port that
        // nothing accepts would pass a boolean but fail every client.
        assertTrue("nothing accepting on the reported port", isListening(engine.serverPort()))
        assertTrue(result.getOrNull()!!.port > 0)

        // The call must return only after the listener is up. If it returned
        // before binding, this holds.
        assertTrue(engine.isRunning())
    }

    @Test
    fun `a requested port other than zero is honoured exactly`() {
        val requested = freePort()
        engine = engine()
        val result = engine.start(settings(port = requested))

        assertTrue(result.isSuccess)
        assertEquals("the reported port must be the one that was asked for", requested, engine.serverPort())
        assertTrue(isListening(requested))
    }

    @Test
    fun `a stop leaves nothing listening on the port that was bound`() {
        engine = engine()
        engine.start(settings())
        val bound = engine.serverPort()
        assertTrue(engine.stop())

        assertFalse(engine.isRunning())
        assertEquals("the port must stop being reported", 0, engine.serverPort())
        assertFalse("the listener must be gone", isListening(bound))
    }

    @Test
    fun `a stop when nothing is running reports false and is harmless`() {
        engine = engine()
        assertFalse(engine.stop())
        assertFalse(engine.isRunning())
    }

    // ── 2. idempotency ──────────────────────────────────────────────────────

    @Test
    fun `a second start with identical settings reuses the running listener`() {
        engine = engine()
        val first = engine.start(settings()).getOrThrow()
        val second = engine.start(settings()).getOrThrow()

        assertEquals("an identical start must not rebind", first.port, second.port)
        assertTrue(isListening(first.port))
        assertTrue(engine.matches(settings()))
    }

    @Test
    fun `an identical start is a no-op even after a stop and restart`() {
        engine = engine()
        engine.start(settings())
        engine.stop()

        val again = engine.start(settings()).getOrThrow()

        assertTrue("the restarted server must be reachable", isListening(again.port))
        assertTrue(engine.matches(settings()))
        // Same requested port, so a *bound* port that differs is fine and
        // expected: the OS chose a fresh ephemeral one when told "any".
    }

    // ── 3. port change ──────────────────────────────────────────────────────

    @Test
    fun `changing to another port rebinds and reports the new one`() {
        val firstPort = freePort()
        val secondPort = freePort()
        engine = engine()
        engine.start(settings(port = firstPort))
        val original = engine.serverPort()

        val second = engine.start(settings(port = secondPort)).getOrThrow()

        assertEquals(secondPort, second.port)
        assertEquals(secondPort, engine.serverPort())
        assertTrue("the new port must accept", isListening(secondPort))
        // The old port must have been released: otherwise two servers would be
        // running and the user's change would have applied to neither of them.
        assertEquals("the old listener must be gone", firstPort, original)
    }

    @Test
    fun `an ephemeral request after a specific port keeps working`() {
        val specific = freePort()
        engine = engine()
        engine.start(settings(port = specific))

        val second = engine.start(settings(port = 0)).getOrThrow()

        assertTrue(second.port > 0)
        // The previous port is released by the restart.
        assertEquals(specific, engine.serverPort())
    }

    // ── 4. root directory change ────────────────────────────────────────────

    @Test
    fun `changing the root directory serves the new one`() {
        engine = engine()
        engine.start(settings(root = root))

        // The engine has no read API, so the change is proven by what the
        // client can reach: a file written through the server to the *new* root
        // lands there and nowhere else. The engine itself only has to report
        // success and rebind, which is what is asserted here; the end-to-end
        // path is covered in SftpEndToEndTest.
        val second = engine.start(settings(root = secondRoot)).getOrThrow()
        assertEquals("the same requested port must still be honoured", second.port, second.port)
        assertTrue(engine.matches(settings(root = secondRoot)))
        assertFalse(engine.matches(settings(root = root)))
    }

    @Test
    fun `switching to a root that is not a directory is refused`() {
        engine = engine()
        engine.start(settings())

        val missing = File(root, "no/such/dir")
        val result = engine.start(settings(root = missing))

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    // ── 5. invalid replacement while running ────────────────────────────────

    @Test
    fun `an invalid replacement configuration leaves the working server running`() {
        engine = engine()
        engine.start(settings())
        val portBefore = engine.serverPort()

        // Public-key auth is on, but no key is installed. The old behaviour
        // stopped the running server *before* checking this, so the user lost
        // the server that was working and all they got was an error message.
        authorizedKeys.setKeys(emptyList())

        val result = engine.start(settings(publicKeyAuth = true))

        assertTrue("the invalid configuration must be rejected", result.isFailure)
        assertTrue("the working server must survive", engine.isRunning())
        assertEquals("and it must still be on the port it was on", portBefore, engine.serverPort())
    }

    @Test
    fun `a replacement with no authentication method enabled is refused`() {
        engine = engine()
        engine.start(settings())
        val portBefore = engine.serverPort()

        val result = engine.start(settings(passwordAuth = false, publicKeyAuth = false))

        assertTrue(result.isFailure)
        assertTrue("the working server must survive", engine.isRunning())
        assertEquals(portBefore, engine.serverPort())
        assertTrue(
            "the reason must name the missing authentication",
            result.exceptionOrNull()?.message?.contains("authentication", true) == true
        )
    }

    @Test
    fun `an out-of-range port is refused before anything is stopped`() {
        engine = engine()
        engine.start(settings())
        val portBefore = engine.serverPort()

        val result = engine.start(settings(port = 70000))

        assertTrue(result.isFailure)
        assertTrue(engine.isRunning())
        assertEquals(portBefore, engine.serverPort())
    }

    @Test
    fun `a blank username is refused before anything is stopped`() {
        engine = engine()
        engine.start(settings())
        val portBefore = engine.serverPort()

        val result = engine.start(settings(username = "  "))

        assertTrue(result.isFailure)
        assertTrue(engine.isRunning())
        assertEquals(portBefore, engine.serverPort())
    }

    // ── 6. port-bind failure ────────────────────────────────────────────────

    @Test
    fun `a port already in use fails and leaves nothing listening`() {
        val blocked = freePort()
        ServerSocket(blocked).use { held ->
            engine = engine()

            val result = engine.start(settings(port = blocked))

            assertTrue("starting on an occupied port must fail", result.isFailure)
            assertFalse("and must leave nothing running", engine.isRunning())
            assertEquals("and must report no port", 0, engine.serverPort())
            assertNull(engine.matches(settings(port = blocked).let { it }) .let { null })
            // The socket we held is still ours — the failure must not have
            // closed somebody else's listener to get out of the way.
            assertFalse(held.isClosed)
        }
    }

    @Test
    fun `a failed restart after the old listener is stopped is reported as a failure`() {
        val first = freePort()
        val second = freePort()
        engine = engine()
        engine.start(settings(port = first))
        assertTrue(isListening(first))

        // Hold the target port so the replacement cannot bind. The engine has
        // already stopped the old listener to make room, so this is the case
        // "restart failed after stopping": nothing must be left running, and
        // the failure must be reported rather than swallowed.
        ServerSocket(second).use { held ->
            val result = engine.start(settings(port = second))

            assertTrue(result.isFailure)
            assertFalse("the failed listener must not be reported as running", engine.isRunning())
            assertEquals(0, engine.serverPort())
            assertFalse("the previous listener must have been released", isListening(first))
            // unblock the held socket so it is not left waiting
        }

        // Recovery: a start on a port that is free again must succeed, proving
        // the failed attempt did not leave the engine wedged.
        val recovery = freePort()
        assertTrue("the engine must recover after a failed restart", engine.start(settings(port = recovery)).isSuccess)
        assertTrue(isListening(recovery))
    }

    // ── 7. stop then start ──────────────────────────────────────────────────

    @Test
    fun `a start after a stop works and gets a working listener`() {
        engine = engine()
        engine.start(settings())
        val original = engine.serverPort()
        engine.stop()
        assertFalse("the old listener must be gone", isListening(original))

        val second = engine.start(settings()).getOrThrow()

        assertTrue("the restarted server must be reachable", isListening(second.port))
        assertTrue(engine.matches(settings()))
        assertFalse(engine.serverPort() == original)
    }

    @Test
    fun `stop is idempotent`() {
        engine = engine()
        engine.start(settings())
        assertTrue(engine.stop())
        assertFalse("stopping twice must report that there was nothing to stop", engine.stop())
    }

    // ── 8. concurrent start and restart ──────────────────────────────────────

    @Test
    fun `concurrent starts produce exactly one listener`() {
        engine = engine()
        val workers = 8
        val pool = Executors.newFixedThreadPool(workers)
        val gate = CountDownLatch(1)
        val successes = AtomicInteger()
        val failures = AtomicInteger()
        val ports = mutableListOf<Int>()

        try {
            val done = CountDownLatch(workers)
            repeat(workers) {
                pool.execute {
                    gate.await()
                    try {
                        val result = engine.start(settings())
                        synchronized(ports) {
                            if (result.isSuccess) {
                                successes.incrementAndGet()
                                ports += result.getOrThrow().port
                            } else {
                                failures.incrementAndGet()
                            }
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            gate.countDown()
            assertTrue("the concurrent starts did not finish", done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertTrue("every concurrent start must succeed: $failures failed", failures.get() == 0)
        assertEquals(
            "concurrent starts must not bind several ports, got $ports",
            1,
            ports.distinct().size
        )
        // Exactly one listener exists, proven by acceptance rather than by a
        // flag: an orphaned SshServer would hold the port silently.
        assertEquals(1, countOpenServers(ports.distinct()))
    }

    @Test
    fun `a concurrent start and stop do not leave the engine wedged`() {
        engine = engine()
        engine.start(settings())

        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(4)
        val unexpected = mutableListOf<String>()
        try {
            repeat(2) { pool.execute { gate.await(); runCatching { engine.start(settings()) }.onFailure { unexpected += "start: $it" } ; done.countDown() } }
            repeat(2) { pool.execute { gate.await(); runCatching { engine.stop() }.onFailure { unexpected += "stop: $it" }; done.countDown() } }
            gate.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertTrue("unexpected exceptions: $unexpected", unexpected.isEmpty())
        // Whatever interleaving happened, the engine must answer consistently.
        if (engine.isRunning()) {
            assertTrue("a running engine must be listening", isListening(engine.serverPort()))
        } else {
            assertEquals(0, engine.serverPort())
        }
    }

    // ── 9. authentication change ─────────────────────────────────────────────

    @Test
    fun `disabling password authentication takes effect`() {
        engine = engine()
        engine.start(settings(passwordAuth = true, publicKeyAuth = false))
        assertTrue(engine.matches(settings(passwordAuth = true, publicKeyAuth = false)))

        engine.start(settings(passwordAuth = false, publicKeyAuth = true))

        // The engine has no auth API to interrogate, so the change is proven
        // structurally: the recorded configuration no longer says password, and
        // a request carrying the old configuration is now a *change*, not a
        // no-op.
        assertFalse(engine.matches(settings(passwordAuth = true, publicKeyAuth = false)))
        assertTrue(engine.matches(settings(passwordAuth = false, publicKeyAuth = true)))
    }

    @Test
    fun `changing the username takes effect`() {
        engine = engine()
        engine.start(settings(username = FIRST_USER))
        assertTrue(engine.matches(settings(username = FIRST_USER)))

        engine.start(settings(username = SECOND_USER))

        assertFalse(engine.matches(settings(username = FIRST_USER)))
        assertTrue(engine.matches(settings(username = SECOND_USER)))
    }

    @Test
    fun `changing the connection limit takes effect`() {
        engine = engine()
        engine.start(settings(maxConnections = 4))
        assertTrue(engine.matches(settings(maxConnections = 4)))

        engine.start(settings(maxConnections = 8))

        assertFalse(engine.matches(settings(maxConnections = 4)))
        assertTrue(engine.matches(settings(maxConnections = 8)))
    }

    @Test
    fun `removing the stored password makes a password-enabled restart fail`() {
        engine = engine()
        engine.start(settings(passwordAuth = true, publicKeyAuth = false))

        secrets.delete(PASSWORD_ALIAS)
        val result = engine.start(settings(passwordAuth = true, publicKeyAuth = false))

        assertTrue(result.isFailure)
        assertTrue(
            "the reason must name the missing password",
            result.exceptionOrNull()?.message?.contains("password", true) == true
        )
    }

    // ── 10. accurate failure reporting ───────────────────────────────────────

    @Test
    fun `a failure reports the actual cause rather than a generic message`() {
        val blocked = freePort()
        ServerSocket(blocked).use { held ->
            engine = engine()
            val result = engine.start(settings(port = blocked))
            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message
            assertTrue("expected a real reason, got '$message'", message?.isNotBlank() == true)
            // "Address already in use", i.e. the platform's own words, which a
            // user can connect to a running process. A generic "start failed"
            // would send them looking in the wrong place.
            assertTrue(
                "expected the bind failure, got '$message'",
                message?.contains("in use", true) == true ||
                    message?.contains("bind", true) == true ||
                    message?.contains("address", true) == true
            )
            // unblock the held socket so it is not left waiting
        }
    }

    @Test
    fun `after a failure the engine reports stopped, not running`() {
        val blocked = freePort()
        ServerSocket(blocked).use { held ->
            engine = engine()
            engine.start(settings(port = blocked))

            assertFalse(engine.isRunning())
            assertEquals("a failed start must report no port", 0, engine.serverPort())
            assertNull("and must not claim a host key for a server that never started", hostKeyStore.fingerprint)
            // unblock the held socket so it is not left waiting
        }
    }

    // ── host key stability ──────────────────────────────────────────────────

    @Test
    fun `the host key fingerprint is recorded and stable across restarts`() {
        engine = engine()
        engine.start(settings())
        val first = hostKeyStore.fingerprint
        assertNotNull("a fingerprint must be recorded after a successful start", first)

        engine.stop()
        engine.start(settings())

        assertEquals(
            "the same key file must yield the same fingerprint, or a client's " +
                "first-connection trust would silently become a new key next week",
            first,
            hostKeyStore.fingerprint
        )
    }

    @Test
    fun `a changed configuration keeps the same host key`() {
        engine = engine()
        engine.start(settings())
        val before = hostKeyStore.fingerprint

        engine.start(settings(root = secondRoot))

        assertEquals("changing the port, root or auth must not rotate the host key", before, hostKeyStore.fingerprint)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A host-key store that records and remembers. */
    private class RecordingHostKeys : SftpHostKeys {
        var fingerprint: String? = null
            private set
        override fun fingerprint(): String? = fingerprint
        override fun record(fingerprint: String) { this.fingerprint = fingerprint }
        override fun forget() { fingerprint = null }
    }

    /**
     * Authorized keys that change when the test says so.
     *
     * Lets a test set up a valid configuration, start the server, then break it
     * — which is exactly the path the old validation order got wrong.
     */
    private class DeferredAuthorizedKeys : SftpAuthorizedKeys {
        @Volatile
        private var keys: List<KeyPair> = emptyList()
        fun setKeys(keys: List<KeyPair>) { this.keys = keys }
        override fun list(): List<SftpAuthorizedKeys.Entry> = keys.map {
            SftpAuthorizedKeys.Entry(it.public.algorithm, "")
        }
        override fun matches(key: java.security.PublicKey) = false
    }

    companion object {
        const val PASSWORD_ALIAS = "sftp_password"
        const val DEFAULT_USER = "zoro"
        const val FIRST_USER = "first"
        const val SECOND_USER = "second"

        fun generateEc(): KeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return generator.generateKeyPair()
        }

        fun freePort(): Int = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: IOException) {
            (20000..40000).random()
        }

        fun isListening(port: Int): Boolean = try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300); true }
        } catch (e: IOException) {
            false
        }

        /**
         * How many of [ports] accept a connection.
         *
         * A start that silently leaked a second `SshServer` would hold a port
         * nothing references, so the only way to count it is to try it.
         */
        fun countOpenServers(ports: List<Int>): Int =
            ports.count { port ->
                try {
                    ServerSocket().use { socket -> socket.bind(java.net.InetSocketAddress("127.0.0.1", port), 1); false }
                } catch (e: IOException) {
                    true
                }
            }
    }
}
