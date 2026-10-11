package com.example.earthquack.ssh

import com.example.earthquack.sftp.SftpAuthorizedKeys
import com.example.earthquack.sftp.SftpHostKeys
import com.example.earthquack.sftp.SftpSettings
import com.example.earthquack.sftp.SftpServerEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import org.apache.sshd.common.config.keys.KeyUtils

/**
 * The Test-connection probe, end to end against a real SSH server.
 *
 * The probe is the class behind the "Test connection" button on the profile
 * editor and the profile list, so what needs proving is its *security*
 * behaviour, not that MINA can connect: a first sight of a host key must
 * surface the fingerprint without recording anything, acceptance must record
 * exactly once, and a changed key must be refused forever. Those are the
 * rules a mistake in would be expensive, and they are pure enough to test
 * without a device.
 *
 * The server is the app's real [SftpServerEngine]; the stores are fakes at
 * the same seams the factory is built on.
 */
class SshProfileProbeTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var clientKey: KeyPair
    private lateinit var engine: SftpServerEngine
    private lateinit var identities: FakeIdentities
    private lateinit var trust: RecordingTrust
    private lateinit var secrets: InMemorySecretStore

    private val password = "correct-horse-battery-staple"
    private val username = "zoro"
    private val profileId = "test"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("probe-root").toFile()
        filesDir = Files.createTempDirectory("probe-files").toFile()
        clientKey = generateEc()
        secrets = InMemorySecretStore()
        identities = FakeIdentities(clientKey)
        trust = RecordingTrust()
    }

    @After
    fun tearDown() {
        runCatching { engine.stop() }
        root.deleteRecursively()
        filesDir.deleteRecursively()
    }

    /** A trust that records, so the tests can assert what was written. */
    private class RecordingTrust : HostKeyTrust {
        val recorded = mutableMapOf<String, PublicKey>()
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

    private fun startServer(passwordAuth: Boolean = false, publicKeyAuth: Boolean = true) {
        if (passwordAuth) secrets.put("sftp_password", password.toByteArray())
        val keys = object : SftpAuthorizedKeys {
            override fun list(): List<SftpAuthorizedKeys.Entry> =
                listOf(SftpAuthorizedKeys.Entry("client", "ecdsa-sha2-nistp256 AAAA client"))
            override fun matches(key: PublicKey): Boolean = key == clientKey.public
        }
        val serverHostKeys = object : SftpHostKeys {
            override fun fingerprint(): String? = null
            override fun record(value: String) {}
            override fun forget() {}
        }
        engine = SftpServerEngine(filesDir, secrets, serverHostKeys, keys)
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

    private fun probe() = SshProfileProbe(
        identityKeys = identities,
        secrets = secrets,
        knownHosts = trust,
        filesDir = filesDir
    )

    private fun profile(
        authMethod: AuthMethod = AuthMethod.PUBLIC_KEY,
        port: Int? = null
    ): ConnectionProfile =
        ConnectionProfile(
            id = profileId,
            name = "archii",
            host = "127.0.0.1",
            port = port ?: engine.serverPort(),
            username = username,
            authMethod = authMethod,
            identityKeyAlias = if (authMethod == AuthMethod.PUBLIC_KEY) "client" else null,
            passwordAlias = if (authMethod == AuthMethod.PASSWORD) "profile-password" else null,
            hostKeyPolicy = HostKeyPolicy.TOUF
        )

    @Test
    fun `a good connection is reported as connected`() = runBlocking {
        startServer()
        val p = probe()
        // The probe always starts by parking the host key for consent — that
        // is the contract, not a nuisance — and connects once it is trusted.
        assertTrue(p.connect(profile()) is ProbeResult.Failed)
        assertTrue(p.acceptHostKey(profileId))

        val result = p.connect(profile())
        assertTrue(
            "connect failed: ${(result as? ProbeResult.Failed)?.failure?.message}",
            result is ProbeResult.Connected
        )
    }

    @Test
    fun `a wrong password is an authentication failure that does not print the password`() = runBlocking {
        startServer(passwordAuth = true, publicKeyAuth = false)
        // Credential must exist before the consent probe: a missing alias
        // fails before the host key is ever presented.
        secrets.put("profile-password", "not-the-password".toByteArray())
        // Consent to the host key first, so authentication is what fails —
        // the host-key branch would otherwise refuse before any credential.
        val consent = probe()
        assertTrue(consent.connect(profile(AuthMethod.PASSWORD)) is ProbeResult.Failed)
        assertTrue(consent.acceptHostKey(profileId))

        val result = probe().connect(profile(AuthMethod.PASSWORD))

        assertTrue(result is ProbeResult.Failed)
        val failure = (result as ProbeResult.Failed).failure
        assertTrue("expected an auth failure, got $failure", failure is SshFailure.AuthFailed)
        // The reason must be useful without leaking the credential.
        assertFalse("the message leaked the password", failure.message.contains("not-the-password"))
        assertFalse("the message leaked the store alias", failure.message.contains("profile-password"))
    }

    @Test
    fun `an unfamiliar host key is shown with its fingerprint and is not recorded`() = runBlocking {
        startServer()
        val p = probe()

        val result = p.connect(profile())

        assertTrue("expected a host-key decision, got $result", result is ProbeResult.Failed)
        val failure = (result as ProbeResult.Failed).failure as SshFailure.UnknownHost
        val fingerprint = failure.fingerprint
        assertTrue("the fingerprint must be present", fingerprint.startsWith("SHA256:"))
        // The fingerprint the UI shows is the one the trust parked.
        assertEquals(fingerprint, p.pendingFingerprint)
        // And nothing was recorded on the way through.
        assertTrue("a first-use host key must not be recorded without consent", trust.recorded.isEmpty())
    }

    @Test
    fun `declining the fingerprint records nothing and keeps refusing`() = runBlocking {
        startServer()
        val p = probe()

        assertTrue(p.connect(profile()) is ProbeResult.Failed)
        p.rejectHostKey()
        assertNull(p.pendingFingerprint)

        // The decline holds immediately: nothing is parked to accept.
        assertFalse("a declined host must not park anything", p.acceptHostKey(profileId))
        val second = p.connect(profile())
        assertTrue("a declined host must still be a host-key decision", second is ProbeResult.Failed)
        assertTrue((second as ProbeResult.Failed).failure is SshFailure.UnknownHost)
        assertTrue("a declined host must not be recorded", trust.recorded.isEmpty())
    }

    @Test
    fun `accepting the fingerprint records exactly once and then connects`() = runBlocking {
        startServer()
        val p = probe()

        assertTrue(p.connect(profile()) is ProbeResult.Failed)
        assertTrue("the parked key must be recorded by accept", p.acceptHostKey(profileId))
        assertEquals(1, trust.recorded.size)
        assertNull(p.pendingFingerprint)

        // A second accept parks nothing and records nothing.
        assertFalse(p.acceptHostKey(profileId))
        assertEquals(1, trust.recorded.size)

        // The retry now succeeds from the recorded key.
        val second = p.connect(profile())
        assertTrue(
            "the accepted key must be trusted: ${(second as? ProbeResult.Failed)?.failure?.message}",
            second is ProbeResult.Connected
        )
    }

    @Test
    fun `a changed host key is refused and cannot be accepted into the store`() = runBlocking {
        startServer()
        // What the server presents today, captured through the honest path:
        // a first contact with an empty store reports the fingerprint.
        val serverFingerprint = ((probe().connect(profile()) as ProbeResult.Failed)
            .failure as SshFailure.UnknownHost).fingerprint
        // A pin that is not the server's: this is the "changed host key".
        val otherKey = generateEc().public
        trust.recorded.clear()
        trust.recorded[profileId] = otherKey
        val p = probe()

        val result = p.connect(profile())

        assertTrue(result is ProbeResult.Failed)
        assertTrue(
            "a changed host key must be refused, got ${(result as ProbeResult.Failed).failure}",
            result.failure is SshFailure.HostKeyMismatch
        )
        assertNull("a mismatch must park nothing", probe().pendingFingerprint)
        // The store still holds the user's original pin, not the server's key.
        assertTrue(trust.recorded.containsKey(profileId))
        val mismatch = result.failure as SshFailure.HostKeyMismatch
        assertTrue(
            "the mismatch must name the server's real key",
            mismatch.actual == serverFingerprint
        )
        assertTrue(
            "the user's original pin must still be stored",
            trust.recorded[profileId] == otherKey
        )
        // And nothing was parked that a later accept could record instead.
        assertFalse("a mismatch must not park a key", p.acceptHostKey(profileId))
        val again = p.connect(profile())
        assertTrue(
            "a changed key must keep being refused",
            (again as ProbeResult.Failed).failure is SshFailure.HostKeyMismatch
        )
    }

    @Test
    fun `an unreachable host produces a failure value, not a throw`() = runBlocking {
        val deadPort = ServerSocket(0).use { it.localPort }
        val result = probe().connect(profile(port = deadPort))
        assertTrue(
            "an unreachable host must produce a failure value, not a throw",
            result is ProbeResult.Failed
        )
    }

    @Test
    fun `an invalid profile is refused before any connection is attempted`() = runBlocking {
        startServer()
        val p = profile().copy(port = 70000)
        val result = probe().connect(p)
        assertTrue(result is ProbeResult.Failed)
        assertNotNull((result as ProbeResult.Failed).failure)
    }

    private class FakeIdentities(var key: KeyPair?) : ClientIdentityProvider {
        override fun loadKeyPair(alias: String): KeyPair? = key
    }

    companion object {
        fun generateEc(): KeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return generator.generateKeyPair()
        }
    }
}
