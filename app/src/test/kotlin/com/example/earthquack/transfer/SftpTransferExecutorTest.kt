package com.example.earthquack.transfer

import com.example.earthquack.testutil.TestKeyUtils.generateEc

import com.example.earthquack.ssh.AuthMethod
import com.example.earthquack.ssh.ClientIdentityProvider
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.HostKeyTrust
import com.example.earthquack.ssh.HostKeyVerdict
import com.example.earthquack.ssh.InMemorySecretStore
import com.example.earthquack.ssh.SshConnectResult
import com.example.earthquack.ssh.SshConnectionFactory
import com.example.earthquack.sftp.SftpAuthorizedKeys
import com.example.earthquack.sftp.SftpHostKeys
import com.example.earthquack.sftp.SftpSettings
import com.example.earthquack.sftp.SftpServerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicReference

/**
 * The SFTP transfer executor, end to end against the app's real SSH server.
 *
 * What is proven here is not that SFTP works — that is
 * [com.example.earthquack.sftp.SftpEndToEndTest] — but the *safety rules*
 * the transfer manager promises around it: bytes land where the task says,
 * a partial never masquerades as a whole, a failed or cancelled transfer
 * leaves no destination and no half file, and resume only happens where the
 * rule allows it.
 *
 * The executor's seams (a profile resolver and a factory) are exactly what
 * the service wires, so the code under test is the shipped code.
 */
class SftpTransferExecutorTest {

    private lateinit var serverRoot: File
    private lateinit var localDir: File
    private lateinit var filesDir: File
    private lateinit var clientKey: KeyPair
    private lateinit var engine: SftpServerEngine
    private lateinit var secrets: InMemorySecretStore
    private lateinit var trust: TrustEverything

    private val profile = ConnectionProfile(
        id = "p1",
        name = "archii",
        host = "127.0.0.1",
        port = 0,
        username = "zoro",
        authMethod = AuthMethod.PUBLIC_KEY,
        identityKeyAlias = "client"
    )

    private val executor by lazy {
        SftpTransferExecutor(
            resolveProfile = { id -> if (id == profile.id) profile.copy(port = engine.serverPort()) else null },
            factory = SshConnectionFactory(
                identityKeys = object : ClientIdentityProvider {
                    override fun loadKeyPair(alias: String): KeyPair? = clientKey
                },
                secrets = secrets,
                knownHosts = trust,
                filesDir = filesDir
            )
        )
    }

    @Before
    fun setUp() {
        serverRoot = Files.createTempDirectory("sftp-exec-root").toFile()
        localDir = Files.createTempDirectory("sftp-exec-local").toFile()
        filesDir = Files.createTempDirectory("sftp-exec-files").toFile()
        clientKey = generateEc()
        secrets = InMemorySecretStore()
        trust = TrustEverything()
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
                rootPath = serverRoot.absolutePath,
                username = "zoro",
                passwordAuth = false,
                publicKeyAuth = true,
                maxConnections = 4
            )
        )
        assertTrue("server failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    /** Accepts and pins whatever it is shown: the trust *policy* is tested by
     *  [com.example.earthquack.ssh.SshProfileProbeTest], not here. */
    private class TrustEverything : HostKeyTrust {
        val recorded = mutableMapOf<String, PublicKey>()
        override fun verify(profileId: String, publicKey: PublicKey): HostKeyVerdict {
            val known = recorded[profileId]
            if (known == publicKey) return HostKeyVerdict.Trusted
            recorded[profileId] = publicKey
            return HostKeyVerdict.Trusted
        }
        override fun record(profileId: String, publicKey: PublicKey) {
            recorded[profileId] = publicKey
        }
        override fun forget(profileId: String) { recorded.remove(profileId) }
    }

    @After
    fun tearDown() {
        runCatching { engine.stop() }
        serverRoot.deleteRecursively()
        localDir.deleteRecursively()
        filesDir.deleteRecursively()
    }

    private fun sftpBackend(remotePath: String, localFile: String) = TransferBackend.Sftp(
        profileId = profile.id,
        localFile = localFile,
        remotePath = remotePath
    )

    private fun task(
        id: String,
        type: TransferType,
        backend: TransferBackend,
        destination: String,
        total: Long = -1L,
        bytes: Long = 0L
    ) = TransferTask(
        id = id,
        type = type,
        backend = backend,
        displayName = File(destination).name,
        sourceLabel = "archii",
        destination = destination,
        totalBytes = total,
        bytesTransferred = bytes,
        createdAt = 1L
    )

    private val payload = ByteArray(512 * 1024) { (it % 253).toByte() }

    // ── downloads ────────────────────────────────────────────────────────────

    @Test
    fun `a download lands the complete bytes at its destination`() = runBlocking {
        // Seed the remote file.
        upload("/remote.bin")

        val destination = File(localDir, "remote.bin")
        val t = task("d1", TransferType.DOWNLOAD, sftpBackend("/remote.bin", destination.absolutePath), destination.absolutePath, total = payload.size.toLong())

        val outcome = executor.run(t) {}

        assertTrue("expected completion, got $outcome", outcome is TransferOutcome.Completed)
        assertEquals(payload.size.toLong(), (outcome as TransferOutcome.Completed).bytes)
        assertTrue(payload.contentEquals(destination.readBytes()))
        assertFalse("the staging .part must not survive", File(t.destination + ".part").exists())
    }

    @Test
    fun `a download that ends at the wrong size is failed and leaves no destination`() = runBlocking {
        upload("/remote.bin")

        val destination = File(localDir, "remote.bin")
        // Claimed total larger than the truth: a truncated stream must be
        // refused, not renamed into place.
        val t = task("d2", TransferType.DOWNLOAD, sftpBackend("/remote.bin", destination.absolutePath), destination.absolutePath, total = payload.size * 2L)

        val outcome = executor.run(t) {}

        assertTrue("expected failure, got $outcome", outcome is TransferOutcome.Failed)
        assertFalse("a truncated download must not appear at the destination", destination.exists())
        assertFalse(File(t.destination + ".part").exists())
    }

    @Test
    fun `a cancelled download leaves no destination and no part`() = runBlocking {
        upload("/remote.bin")
        val destination = File(localDir, "remote.bin")
        val t = task("d3", TransferType.DOWNLOAD, sftpBackend("/remote.bin", destination.absolutePath), destination.absolutePath, total = payload.size.toLong())

        // Cancel from inside the first progress callback: deterministic, and
        // exactly the interruption the manager dispatches mid-transfer.
        val thrown = AtomicReference<Throwable>()
        val outcome = runBlocking {
            try {
                coroutineScope {
                    // A real cancel of the job running the transfer: the only
                    // nondeterministic part of cancellations elsewhere is
                    // racing a timer against I/O.
                    val job = coroutineContext[Job]
                    executor.run(t) { job?.cancel() }
                }
            } catch (e: Throwable) {
                thrown.set(e)
                TransferOutcome.Failed("cancelled")
            }
        }

        assertTrue(
            "cancellation must surface as CancellationException, got ${thrown.get()?.javaClass}",
            thrown.get() is kotlinx.coroutines.CancellationException
        )
        assertFalse("a cancelled download must not leave a destination", destination.exists())
        assertFalse("a cancelled download must clean its part", File(t.destination + ".part").exists())
    }

    @Test
    fun `a resumed download appends exactly the remaining bytes`() = runBlocking {
        upload("/remote.bin")
        val destination = File(localDir, "remote.bin")
        val partFile = destination.absolutePath + ".part"
        // A partial holding the first 64 KiB of the honest stream.
        Files.write(File(partFile).toPath(), payload.copyOfRange(0, 64 * 1024))

        val t = task("d4", TransferType.DOWNLOAD, sftpBackend("/remote.bin", destination.absolutePath), destination.absolutePath,
            total = payload.size.toLong(), bytes = 64L * 1024)

        assertTrue("a matching .part must be resumable", executor.canResume(t))
        val seen = mutableListOf<Long>()
        val outcome = executor.run(t) { seen += it }

        assertTrue("expected completion, got $outcome", outcome is TransferOutcome.Completed)
        assertTrue("resumed bytes must match the whole file", payload.contentEquals(destination.readBytes()))
        assertFalse(File(partFile).exists())
        // Progress continues from the retained offset, in absolute file
        // coordinates — the first report is that offset plus the first
        // chunk, and the last is the whole file.
        val first = seen.first()
        assertTrue(
            "progress must resume past the offset, was $first",
            first > 64L * 1024 && first < payload.size
        )
        assertEquals(payload.size.toLong(), seen.last())
        assertTrue("progress must be monotonic", seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `a resume whose remote size changed fails cleanly and clears the stale part`() = runBlocking {
        upload("/remote.bin")
        val destination = File(localDir, "remote.bin")
        val partFile = destination.absolutePath + ".part"
        Files.write(File(partFile).toPath(), payload.copyOfRange(0, 64 * 1024))
        // The server's file changes: a different length than the recorded total.
        upload("/remote.bin", size = payload.size - 1000L)

        val t = task("d5", TransferType.DOWNLOAD, sftpBackend("/remote.bin", destination.absolutePath), destination.absolutePath,
            total = payload.size.toLong(), bytes = 64L * 1024)

        val outcome = executor.run(t) {}

        assertTrue("expected the changed-file failure, got $outcome", outcome is TransferOutcome.Failed)
        assertFalse(destination.exists())
        assertFalse("the stale part must be cleared", File(partFile).exists())
        // With the part gone, canResume must no longer offer a resume.
        assertFalse(executor.canResume(t))
    }

    @Test
    fun `resume is not offered for uploads or without a matching part`() {
        val destination = File(localDir, "remote.bin")
        val download = task("d6", TransferType.DOWNLOAD, sftpBackend("/remote.bin", destination.absolutePath), destination.absolutePath,
            total = payload.size.toLong(), bytes = 64L * 1024)
        val uploadTask = task("d7", TransferType.UPLOAD, sftpBackend("/remote.bin", destination.absolutePath), "/remote.bin",
            total = payload.size.toLong(), bytes = 64L * 1024)

        File(destination.absolutePath + ".part").writeBytes(payload.copyOfRange(0, 100))
        assertFalse("a .part shorter than the recorded progress is not a resume point", executor.canResume(download))
        assertFalse("uploads never resume", executor.canResume(uploadTask))
    }

    // ── uploads ──────────────────────────────────────────────────────────────

    @Test
    fun `an upload stages to part, renames, and leaves the local source intact`() = runBlocking {
        val source = File(localDir, "local.bin").also { it.writeBytes(payload) }
        val t = task("u1", TransferType.UPLOAD, sftpBackend("/local.bin", source.absolutePath), "/local.bin",
            total = payload.size.toLong())

        val outcome = executor.run(t) {}

        assertTrue("expected completion, got $outcome", outcome is TransferOutcome.Completed)
        assertTrue("local source must survive an upload", source.isFile && source.length() == payload.size.toLong())
        val landed = File(serverRoot, "local.bin")
        assertTrue("the remote file must exist at the destination", landed.isFile)
        assertTrue(payload.contentEquals(landed.readBytes()))
        assertFalse("the staging part must not survive", File(serverRoot, "local.bin.part").exists())
    }

    @Test
    fun `a failing upload leaves the previous destination intact`() = runBlocking {
        // The destination already holds the old file the user cares about.
        val old = ByteArray(32) { 7 }
        val destination = File(serverRoot, "old.bin").also { it.writeBytes(old) }
        // The upload's local file is gone: the pre-destination reality the UI
        // can produce by deleting a staged file out of the cache.
        val missingSource = File(localDir, "not-there.bin")
        val t = task("u2", TransferType.UPLOAD, sftpBackend("/old.bin", missingSource.absolutePath), "/old.bin",
            total = payload.size.toLong())

        val outcome = executor.run(t) {}

        assertTrue("expected failure, got $outcome", outcome is TransferOutcome.Failed)
        assertTrue("the pre-existing destination must survive", destination.isFile)
        assertTrue(old.contentEquals(destination.readBytes()))
    }

    @Test
    fun `an upload whose source is missing fails with a reason`() = runBlocking {
        val t = task("u3", TransferType.UPLOAD, sftpBackend("/x.bin", File(localDir, "gone.bin").absolutePath), "/x.bin")
        val outcome = executor.run(t) {}
        assertTrue("expected failure, got $outcome", outcome is TransferOutcome.Failed)
        assertNotNull((outcome as TransferOutcome.Failed).error)
    }

    @Test
    fun `a transfer whose profile no longer exists fails with a reason`() = runBlocking {
        val orphan = TransferBackend.Sftp(profileId = "gone", localFile = "/x", remotePath = "/y")
        val t = task("u4", TransferType.DOWNLOAD, orphan, "/x")
        val outcome = executor.run(t) {}
        assertTrue("expected failure, got $outcome", outcome is TransferOutcome.Failed)
        assertTrue((outcome as TransferOutcome.Failed).error.contains("profile", ignoreCase = true))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Puts [payload] (or [size] zero-padded bytes of it) on the server. */
    private suspend fun upload(path: String, size: Long = payload.size.toLong()) {
        val seed = if (size == payload.size.toLong()) payload else payload.copyOf(size.toInt())
        val result = executor.run(
            task("seed-$path", TransferType.UPLOAD, sftpBackend(path, File(localDir, "seed.tmp").also { it.writeBytes(seed) }.absolutePath), path, total = seed.size.toLong())
        ) {}
        assertTrue("seeding $path failed: $result", result is TransferOutcome.Completed)
    }
}
