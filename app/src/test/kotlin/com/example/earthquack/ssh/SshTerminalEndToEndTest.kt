package com.example.earthquack.ssh

import com.example.earthquack.sftp.SftpAuthorizedKeys
import com.example.earthquack.sftp.SftpHostKeys
import com.example.earthquack.sftp.SftpSettings
import com.example.earthquack.sftp.SftpServerEngine
import org.apache.sshd.common.config.keys.KeyUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The SSH client, end to end against a real server.
 *
 * The client under test is [SshConnectionFactory] — the real class the terminal
 * screen uses — and the server is the real [SftpServerEngine]. Neither is
 * mocked. That matters because the failures in this area were all about the two
 * halves disagreeing: a diagnostic the client insisted on running after
 * authentication, a fallback that ran a shell the account had chosen not to
 * have, and a client that could report a disconnected session as connected.
 *
 * The stores are fakes, but only the ones that exist to read Android storage:
 * [ClientIdentityProvider] and [HostKeyTrust] are exactly the seams the factory
 * is already expressed in terms of, so what is exercised is the shipped
 * connection, authentication and channel code rather than a stand-in.
 *
 * ## What is deliberately not asserted
 *
 * That a shell opens. The shipped server is SFTP-only by design — see
 * `SftpServerEngine`'s class comment — so it refuses shell channels, and this
 * test asserts instead that such a refusal is reported honestly and does not
 * derail the authenticated session. An interactive terminal against OpenSSH is
 * a separate, device-or-host test; see the final report.
 */
class SshTerminalEndToEndTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var clientKey: KeyPair
    private lateinit var engine: SftpServerEngine

    private lateinit var identities: FakeIdentities
    private lateinit var trust: FakeTrust
    private lateinit var secrets: InMemorySecretStore

    private val password = "correct-horse-battery-staple"
    private val username = "zoro"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ssh-e2e-root").toFile()
        filesDir = Files.createTempDirectory("ssh-e2e-files").toFile()
        clientKey = generateEc()
        secrets = InMemorySecretStore()
        identities = FakeIdentities(clientKey)
        trust = FakeTrust()
    }

    @After
    fun tearDown() {
        runCatching { engine.stop() }
        root.deleteRecursively()
        filesDir.deleteRecursively()
    }

    private fun startServer(
        passwordAuth: Boolean = false,
        publicKeyAuth: Boolean = true
    ) {
        if (passwordAuth) secrets.put("sftp_password", password.toByteArray())
        val keys = object : SftpAuthorizedKeys {
            override fun list(): List<SftpAuthorizedKeys.Entry> = listOf(
                SftpAuthorizedKeys.Entry("client", "ecdsa-sha2-nistp256 AAAA client")
            )
            override fun matches(key: PublicKey): Boolean =
                key == clientKey.public
        }
        engine = SftpServerEngine(filesDir, secrets, NullHostKeys(), keys)
        val result = engine.start(
            SftpSettings(
                port = 0,
                rootPath = root.absolutePath,
                username = username,
                passwordAuth = passwordAuth,
                publicKeyAuth = publicKeyAuth,
                maxConnections = 4
            )
        )
        assertTrue("server failed to start: ${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    private fun factory() = SshConnectionFactory(
        identityKeys = identities,
        secrets = secrets,
        knownHosts = trust,
        filesDir = filesDir
    )

    private fun profile(authMethod: AuthMethod = AuthMethod.PUBLIC_KEY, port: Int = engine.serverPort()) =
        ConnectionProfile(
            id = "test",
            name = "test",
            host = "127.0.0.1",
            port = port,
            username = username,
            authMethod = authMethod,
            identityKeyAlias = if (authMethod == AuthMethod.PUBLIC_KEY) "client" else null,
            passwordAlias = if (authMethod == AuthMethod.PASSWORD) "profile-password" else null,
            hostKeyPolicy = HostKeyPolicy.TOUF
        )

    // ── the connection flow, in the order it is separated ────────────────────

    @Test
    fun `TCP and the handshake alone are reported as connected`() = runBlocking {
        startServer()
        val result = factory().connect(profile())
        assertTrue(
            "connect failed: ${(result as? SshConnectResult.Failed)?.failure?.message}",
            result is SshConnectResult.Connected
        )
        (result as SshConnectResult.Connected).connection.close()
    }

    @Test
    fun `an authenticated session is open and close is idempotent`() = runBlocking {
        startServer()
        val connection = (factory().connect(profile()) as SshConnectResult.Connected).connection

        // The claim the old post-auth `echo 'test'` probe existed to check. It is
        // a property of the session, not of any one channel.
        assertTrue("an authenticated session must be open", connection.isOpen)
        connection.close()
        connection.close()
        assertFalse("close must be idempotent and effective", connection.isOpen)
    }

    @Test
    fun `a successful connection does not depend on the server allowing exec`() = runBlocking {
        // The shipped server has no exec subsystem at all. A liveness probe that
        // ran `echo 'test'` here could only ever fail or be swallowed — and
        // swallowing it is what hid the real state of the session.
        startServer()
        val result = factory().connect(profile())

        assertTrue(
            "an authenticated session must not depend on exec: " +
                (result as? SshConnectResult.Failed)?.failure?.message,
            result is SshConnectResult.Connected
        )
    }

    @Test
    fun `the SFTP subsystem opens on the same authenticated session`() = runBlocking {
        startServer()
        val connection = (factory().connect(profile()) as SshConnectResult.Connected).connection

        val sftp = connection.openSftp()
        // A real listing through the real client proves the subsystem request was
        // accepted and answered.
        val entries = sftp.list("/")
        assertNotNull(entries)
        sftp.close()
        connection.close()
    }

    @Test
    fun `a refused shell is reported honestly and does not derail the session`() = runBlocking {
        startServer()
        val connection = (factory().connect(profile()) as SshConnectResult.Connected).connection

        // The old code retried four shell commands through exec on any failure.
        // Here, a refusal must be one clear message, and the session must still
        // be usable for what the server does allow.
        val caught = runCatching { connection.openShell({ _, _, _ -> }, rows = 24, cols = 80) }
        if (caught.isFailure) {
            val message = caught.exceptionOrNull()?.message ?: ""
            assertTrue("a refused shell must say so: $message", message.contains("shell", true))
        } else {
            caught.getOrNull()?.close()
        }
        assertTrue("the authenticated session must survive a refused shell", connection.isOpen)
        assertNotNull(connection.openSftp().list("/"))
        connection.close()
    }

    // ── classification: the four kinds must stay distinguishable ─────────────

    @Test
    fun `a wrong password is an authentication failure`() = runBlocking {
        startServer(passwordAuth = true, publicKeyAuth = false)
        secrets.put("profile-password", "not-the-password".toByteArray())

        val result = factory().connect(profile(AuthMethod.PASSWORD))

        assertTrue(result is SshConnectResult.Failed)
        assertTrue(
            "expected an authentication failure, got ${(result as SshConnectResult.Failed).failure.message}",
            result.failure is SshFailure.AuthFailed
        )
    }

    @Test
    fun `password authentication works when the password is right`() = runBlocking {
        startServer(passwordAuth = true, publicKeyAuth = false)
        secrets.put("profile-password", password.toByteArray())

        val result = factory().connect(profile(AuthMethod.PASSWORD))

        assertTrue(
            "password auth failed: ${(result as? SshConnectResult.Failed)?.failure?.message}",
            result is SshConnectResult.Connected
        )
        (result as SshConnectResult.Connected).connection.close()
    }

    @Test
    fun `public-key authentication works`() = runBlocking {
        startServer()
        val result = factory().connect(profile(AuthMethod.PUBLIC_KEY))
        assertTrue(
            "pubkey auth failed: ${(result as? SshConnectResult.Failed)?.failure?.message}",
            result is SshConnectResult.Connected
        )
        (result as SshConnectResult.Connected).connection.close()
    }

    @Test
    fun `an unknown host key is refused under the strict policy`() = runBlocking {
        startServer()
        val result = factory().connect(profile().copy(hostKeyPolicy = HostKeyPolicy.STRICT))

        assertTrue(result is SshConnectResult.Failed)
        assertTrue(
            "expected a host-key refusal, got ${(result as SshConnectResult.Failed).failure.message}",
            result.failure is SshFailure.UnknownHost
        )
    }

    @Test
    fun `a changed host key is refused even under TOFU`() = runBlocking {
        startServer()
        // Record a key that is not the server's: TOFU must refuse a key that
        // differs from what it recorded, not accept the new one.
        trust.seed("test", generateEc().public)

        val result = factory().connect(profile())

        assertTrue(result is SshConnectResult.Failed)
        assertTrue(
            "a changed host key must be refused, got ${(result as SshConnectResult.Failed).failure.message}",
            result.failure is SshFailure.HostKeyMismatch
        )
    }

    @Test
    fun `the known host key is recorded on first use under TOFU`() = runBlocking {
        startServer()
        val result = factory().connect(profile())

        assertTrue(result is SshConnectResult.Connected)
        assertTrue("the first-seen key must be recorded", trust.recorded.containsKey("test"))
        (result as SshConnectResult.Connected).connection.close()
    }

    @Test
    fun `a second connection to the same host succeeds from the recorded key`() = runBlocking {
        startServer()
        val first = factory().connect(profile())
        assertTrue(first is SshConnectResult.Connected)
        (first as SshConnectResult.Connected).connection.close()

        val second = factory().connect(profile())
        assertTrue(
            "the recorded key must still be trusted: ${(second as? SshConnectResult.Failed)?.failure?.message}",
            second is SshConnectResult.Connected
        )
        (second as SshConnectResult.Connected).connection.close()
    }

    @Test
    fun `a missing credential is reported as missing, not as an auth failure`() = runBlocking {
        startServer()
        identities.key = null

        val result = factory().connect(profile(AuthMethod.PUBLIC_KEY))

        assertTrue(result is SshConnectResult.Failed)
        assertTrue(
            "a missing key must be its own error, got ${(result as SshConnectResult.Failed).failure.message}",
            result.failure is SshFailure.CredentialMissing
        )
    }

    @Test
    fun `an unreachable host produces a failure value, not a throw`() = runBlocking {
        val deadPort = freePort()
        val result = factory().connect(profile(port = deadPort))

        assertTrue(
            "an unreachable host must produce a failure value, not a throw",
            result is SshConnectResult.Failed
        )
    }

    @Test
    fun `exec is offered explicitly rather than being a side effect of auth`() = runBlocking {
        startServer()
        val connection = (factory().connect(profile()) as SshConnectResult.Connected).connection

        // The server has no exec subsystem, so this cannot succeed. What matters is
        // that the refusal is raised as a failure — a discarded `channel.open()`
        // used to make this return a null exit status instead — and that the
        // authenticated session survives the attempt.
        val failure = runCatching { connection.exec("echo hello", timeoutMillis = 5_000) }
        assertTrue("a refused exec must be raised, not returned as a null status", failure.isFailure)
        assertTrue("the session must survive a refused exec", connection.isOpen)
        connection.close()
    }

    private class NullHostKeys : SftpHostKeys {
        override fun fingerprint(): String? = null
        override fun record(fingerprint: String) {}
        override fun forget() {}
    }

    private class FakeIdentities(var key: KeyPair?) : ClientIdentityProvider {
        override fun loadKeyPair(alias: String): KeyPair? = key
    }

    /**
     * Host-key trust with a seedable state, so the mismatch and unknown-key
     * paths can both be reached deterministically.
     */
    private class FakeTrust : HostKeyTrust {
        val recorded = mutableMapOf<String, PublicKey>()
        fun seed(id: String, key: PublicKey) { recorded[id] = key }

        override fun verify(profileId: String, publicKey: PublicKey): HostKeyVerdict {
            val known = recorded[profileId]
                ?: return HostKeyVerdict.Unknown(
                    KeyUtils.getFingerPrint(publicKey), publicKey.algorithm
                )
            if (known == publicKey) return HostKeyVerdict.Trusted
            return HostKeyVerdict.Mismatch(
                KeyUtils.getFingerPrint(known), KeyUtils.getFingerPrint(publicKey)
            )
        }

        override fun record(profileId: String, publicKey: PublicKey) {
            recorded[profileId] = publicKey
        }

        override fun forget(profileId: String) { recorded.remove(profileId) }
    }

    companion object {
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
    }
}
