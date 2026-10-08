package com.example.earthquack.sftp

import com.example.earthquack.ssh.InMemorySecretStore
import com.example.earthquack.ssh.SftpSession
import com.example.earthquack.ssh.TransferProgress
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.client.session.ClientSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/**
 * An end-to-end SFTP test with no device and no network.
 *
 * A real [SftpServerEngine] is started on an ephemeral port serving a temporary
 * directory, and a real [com.example.earthquack.ssh.SftpSession] (which is a
 * thin wrapper over MINA's own `SftpClient`) connects over loopback, lists,
 * uploads, downloads, renames, deletes and is refused a traversal.
 *
 * This is the test that would catch "the protocol works on paper": the client
 * is the same library the app ships, and the server is the same class the
 * service runs, so anything that passes here is a claim about the shipped
 * code rather than about a mock.
 */
class SftpEndToEndTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var hostKeyStore: FakeHostKeys
    private lateinit var authorizedKeys: FakeAuthorizedKeys
    private lateinit var secrets: InMemorySecretStore
    private lateinit var engine: SftpServerEngine
    private lateinit var client: SshClient
    private lateinit var session: ClientSession

    private val serverKeyPair: KeyPair = generateEc()
    private val clientKeyPair: KeyPair = generateEc()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sftp-e2e-root").toFile()
        filesDir = Files.createTempDirectory("sftp-e2e-files").toFile()
        secrets = InMemorySecretStore()
        hostKeyStore = FakeHostKeys()
        authorizedKeys = FakeAuthorizedKeys(listOf(clientKeyPair.public))

        engine = SftpServerEngine(
            filesDir = filesDir,
            secrets = secrets,
            hostKeyStore = hostKeyStore,
            authorizedKeys = authorizedKeys
        )
    }

    @After
    fun tearDown() {
        runCatching { session.close() }
        runCatching { client.stop() }
        runCatching { engine.stop() }
        runCatching { root.deleteRecursively() }
        runCatching { filesDir.deleteRecursively() }
    }

    // ── server lifecycle ─────────────────────────────────────────────────────

    @Test
    fun `the server starts and reports the port it bound`() {
        val result = engine.start(settings(passwordAuth = false, publicKeyAuth = true))
        assertTrue("start failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue(engine.isRunning())
        assertTrue(engine.serverPort() > 0)
        engine.stop()
        assertFalse(engine.isRunning())
    }

    @Test
    fun `a second start while running is idempotent`() {
        val first = engine.start(settings(passwordAuth = false, publicKeyAuth = true))
        assertTrue(first.isSuccess)
        val second = engine.start(settings(passwordAuth = false, publicKeyAuth = true))
        assertTrue(second.isSuccess)
        assertEquals(first.getOrNull()!!.port, second.getOrNull()!!.port)
    }

    @Test
    fun `an unusable root is refused with a reason`() {
        val missing = File(root, "does-not-exist")
        val result = engine.start(settings(root = missing.absolutePath))
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    fun `public key auth with no installed key is refused`() {
        val empty = FakeAuthorizedKeys(emptyList())
        val localEngine = SftpServerEngine(filesDir, secrets, hostKeyStore, empty)
        val result = localEngine.start(
            settings(publicKeyAuth = true, passwordAuth = false)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("key", true) == true)
    }

    // ── client round trips ───────────────────────────────────────────────────

    @Test
    fun `list upload download rename and delete all work`() = runBlocking {
        startServerAndConnect()

        val sftp = sftpClient()

        // list on an empty root
        val initialList = sftp.list("/")
        println("[TEST] initial list: ${initialList.map { it.name }}")
        val filtered = initialList.filter { it.name != "." && it.name != ".." }
        assertTrue("expected empty directory, got ${filtered.map { it.name }}", filtered.isEmpty())

        // upload
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val uploadedBytes = sftp.upload(ByteArrayInputStream(payload), "/big.bin", size = payload.size.toLong())
        println("[TEST] upload returned: $uploadedBytes bytes")
        val uploaded = File(root, "big.bin")
        println("[TEST] uploaded.exists() = ${uploaded.exists()}, uploaded.length() = ${uploaded.length()}")
        assertTrue("upload returned $uploadedBytes but file not found", uploaded.exists())
        println("[TEST] file length check")
        assertEquals(payload.size.toLong(), uploaded.length())
        println("[TEST] sha256 check")
        assertEquals(sha256(payload), sha256(uploaded.readBytes()))
        println("[TEST] about to call stat")
        // metadata
        val stat = sftp.stat("/big.bin")
        println("[TEST] stat result: $stat")
        assertNotNull(stat)
        assertEquals(payload.size.toLong(), stat!!.size)
        assertFalse(stat.isDirectory)

        // list sees it
        val entries = sftp.list("/").filter { it.name != "." && it.name != ".." }
        assertEquals(1, entries.size)
        assertEquals("big.bin", entries[0].name)

        // download
        val out = ByteArrayOutputStream()
        val downloaded = sftp.download("/big.bin", out)
        assertEquals(payload.size.toLong(), downloaded)
        assertEquals(sha256(payload), sha256(out.toByteArray()))

        // rename
        sftp.rename("/big.bin", "/renamed.bin")
        assertFalse(File(root, "big.bin").exists())
        assertTrue(File(root, "renamed.bin").exists())

        // delete
        sftp.delete("/renamed.bin")
        val remaining = sftp.list("/").filter { it.name != "." && it.name != ".." }
        assertTrue("expected empty directory, got ${remaining.map { it.name }}", remaining.isEmpty())

        sftp.close()
    }

    @Test
    fun `a traversal is refused`() = runBlocking {
        startServerAndConnect()
        val sftp = sftpClient()

        val outside = Files.createTempDirectory("sftp-e2e-outside").toFile()
        File(outside, "secret.txt").writeText("secret")

        val attempts = listOf(
            "/../secret.txt",
            "/../../etc/passwd",
            "/./../secret.txt"
        )
        for (path in attempts) {
            val caught = runCatching { sftp.stat(path) }
            assertTrue("expected $path to fail or return null", caught.isFailure || caught.getOrNull() == null)
        }

        // And nothing was written into the root by the attempt.
        val rootEntries = sftp.list("/").filter { it.name != "." && it.name != ".." }
        assertTrue(rootEntries.isEmpty())
        sftp.close()
    }

    @Test
    fun `a symlink pointing outside the root is not followed`() = runBlocking {
        startServerAndConnect()
        val sftp = sftpClient()

        val outside = Files.createTempDirectory("sftp-e2e-outside2").toFile()
        File(outside, "target.txt").writeText("x")
        Files.createSymbolicLink(File(root, "link").toPath(), File(outside, "target.txt").toPath())

        // The link is listed as a link...
        val entries = sftp.list("/").filter { it.name != "." && it.name != ".." }
        assertEquals(1, entries.size)
        assertTrue(entries[0].isSymbolicLink)

        // ...but reading through it must not succeed.
        val caught = runCatching {
            val out = ByteArrayOutputStream()
            sftp.download("/link", out)
        }
        assertTrue("expected the symlink to be refused", caught.isFailure)
        sftp.close()
    }

    @Test
    fun `mkdir creates a directory that can be listed and entered`() = runBlocking {
        startServerAndConnect()
        val sftp = sftpClient()

        sftp.mkdir("/photos")
        assertTrue(sftp.stat("/photos")?.isDirectory == true)

        sftp.upload(ByteArrayInputStream("hello".toByteArray()), "/photos/a.txt")
        val children = sftp.list("/photos").filter { it.name != "." && it.name != ".." }
        assertEquals(1, children.size)
        assertEquals("a.txt", children[0].name)

        println("[TEST] deleting /photos/a.txt")
        sftp.delete("/photos/a.txt")
        println("[TEST] stat /photos after file delete: ${sftp.stat("/photos")}")
        // The server must report the directory as empty before rmdir;
        // leftover entries here would explain a DIR_NOT_EMPTY (status 18).
        println("[TEST] listing /photos before rmdir: ${sftp.list("/photos")}")
        println("[TEST] deleting /photos")
        sftp.delete("/photos")
        // After rmdir, the directory no longer exists
        val statAfterDelete = sftp.stat("/photos")
        assertNull("directory should not exist after rmdir", statAfterDelete)
        sftp.close()
    }

    @Test
    fun `a wrong password is rejected by the server`() = runBlocking {
        startServer(password = "correct-horse", passwordAuth = true, publicKeyAuth = false)
        // Connect with a different password and expect authentication to fail.
        client = SshClient.setUpDefaultClient()
        client.start()
        val future = client.connect("zoro", "127.0.0.1", engine.serverPort())
        future.verify(10, TimeUnit.SECONDS)
        val session = future.clientSession!!
        session.addPasswordIdentity("wrong-password")
        val caught = runCatching { session.auth().verify(10, TimeUnit.SECONDS) }
        assertTrue("expected authentication to fail", caught.isFailure)
        session.close()
    }

    @Test
    fun `two clients can be connected at once`() = runBlocking {
        startServer(passwordAuth = false, publicKeyAuth = true)
        val first = openSession()
        val second = openSession()

        val sftp1 = sftpClientFor(first)
        val sftp2 = sftpClientFor(second)
        sftp1.mkdir("/from-first")
        sftp2.mkdir("/from-second")

        assertTrue(sftp1.list("/").any { it.name == "from-second" })
        assertTrue(sftp2.list("/").any { it.name == "from-first" })

        sftp1.close()
        sftp2.close()
        first.close()
        second.close()
    }

    @Test
    fun `progress is reported during a large upload`() = runBlocking {
        startServerAndConnect()
        val sftp = sftpClient()

        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        var lastReported = -1L
        val observations = mutableListOf<TransferProgress>()
        sftp.upload(
            input = ByteArrayInputStream(payload),
            remotePath = "/progress.bin",
            size = payload.size.toLong()
        ) { progress ->
            observations += progress
            lastReported = progress.bytesTransferred
        }
        assertTrue("expected progress observations", observations.isNotEmpty())
        assertEquals(payload.size.toLong(), lastReported)
        sftp.close()
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    /** A host-key store that remembers one fingerprint. */
    private class FakeHostKeys : SftpHostKeys {
        private var fingerprint: String? = null
        override fun fingerprint(): String? = fingerprint
        override fun record(value: String) { fingerprint = value }
        override fun forget() { fingerprint = null }
    }

    /**
     * An authorized-keys store that accepts exactly the keys it was given.
     *
     * Compares fingerprints rather than re-parsing stored lines: the fingerprint
     * is defined as the SHA-256 of the encoded key blob, so hashing the blob
     * directly is the same comparison the real store makes.
     */
    private class FakeAuthorizedKeys(keys: List<PublicKey>) : SftpAuthorizedKeys {
        private val keyList = keys
        private val accepted: List<String> = keyList.map { KeyUtils.getFingerPrint(it) }
        override fun list(): List<SftpAuthorizedKeys.Entry> = keyList.map { key ->
            SftpAuthorizedKeys.Entry(label = key.algorithm, publicKey = encode(key))
        }
        override fun matches(key: PublicKey): Boolean =
            accepted.contains(KeyUtils.getFingerPrint(key))

        private fun encode(key: PublicKey): String {
            val sb = StringBuilder()
            PublicKeyEntry.appendPublicKeyEntry(sb, key)
            return sb.toString()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun settings(
        root: String = this.root.absolutePath,
        port: Int = 0,
        username: String = "zoro",
        passwordAuth: Boolean = true,
        publicKeyAuth: Boolean = true,
        maxConnections: Int = 4
    ) = SftpSettings(
        port = port,
        rootPath = root,
        username = username,
        passwordAuth = passwordAuth,
        publicKeyAuth = publicKeyAuth,
        maxConnections = maxConnections
    )

    private fun startServer(password: String = "s3cret", passwordAuth: Boolean = false, publicKeyAuth: Boolean = true) {
        attachExceptionLogger()
        if (passwordAuth) {
            secrets.put("sftp_password", password.toByteArray())
        }
        val result = engine.start(settings(passwordAuth = passwordAuth, publicKeyAuth = publicKeyAuth))
        assertTrue("server failed to start: ${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    private fun attachExceptionLogger() {
        engine.sftpEventListener = object : org.apache.sshd.sftp.server.AbstractSftpEventListenerAdapter() {
            override fun openFailed(
                session: org.apache.sshd.server.session.ServerSession,
                handle: String,
                path: java.nio.file.Path,
                isDirectory: Boolean,
                t: Throwable
            ) {
                println("[server-side] openFailed $path: ${t.javaClass.name}: ${t.message}")
                t.printStackTrace(System.out)
            }
        }
    }



    private fun startServerAndConnect() {
        startServer(passwordAuth = false, publicKeyAuth = true)
        session = openSession()
    }

    private fun openSession(): ClientSession {
        client = SshClient.setUpDefaultClient()
        client.start()
        val future = client.connect("zoro", "127.0.0.1", engine.serverPort())
        future.verify(10, TimeUnit.SECONDS)
        val session = future.clientSession!!
        session.addPublicKeyIdentity(clientKeyPair)
        session.auth().verify(10, TimeUnit.SECONDS)
        return session
    }

    /**
     * The SFTP client for [session].
     *
     * [SftpSession] is the app's wrapper over MINA's `SftpClient`; `openSftp()`
     * lives on [com.example.earthquack.ssh.SshConnection] because that is where
     * the session's lifetime is managed.
     */
    private fun sftpClient(): SftpSession = sftpClientFor(session)

    private fun sftpClientFor(session: ClientSession): SftpSession {
        val connection = com.example.earthquack.ssh.SshConnection(
            client, session, testProfile()
        )
        return connection.openSftp()
    }

    private fun testProfile() = com.example.earthquack.ssh.ConnectionProfile(
        id = "test", name = "test", host = "127.0.0.1",
        port = engine.serverPort(), username = "zoro"
    )

    private fun generateEc(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
