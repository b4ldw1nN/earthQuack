package com.example.earthquack.sftp

import android.content.Context
import android.util.Log
import com.example.earthquack.ssh.SecretStore
import com.example.earthquack.sftp.fs.AndroidFileSystemFactory
import com.example.earthquack.sftp.fs.requireUsableRoot
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server's host-key record.
 *
 * An interface so the engine can be tested without a Context: the Android
 * implementation is a SharedPreferences wrapper, and the rules it wraps are
 * trivial. What must be testable is the *engine's* use of them.
 */
interface SftpHostKeys {

    /** The SHA-256 fingerprint of the server's host key, or null when it has not been generated. */
    fun fingerprint(): String?

    fun record(fingerprint: String)

    fun forget()
}

/**
 * The public keys accepted by the server.
 *
 * Separate from [SftpHostKeys] because the two have different lifetimes: a host
 * key is generated once per install, while a user adds and removes client keys
 * freely.
 */
interface SftpAuthorizedKeys {

    /** One installed key, in `authorized_keys` form. */
    data class Entry(
        val label: String,
        /** OpenSSH authorized_keys line, e.g. `ssh-ed25519 AAAA… zoro@arch`. */
        val publicKey: String
    )

    fun list(): List<Entry>

    /** Whether [key] is in the set. */
    fun matches(key: PublicKey): Boolean
}

/**
 * Routes file opens through [AndroidFileSystem]'s own channel.
 *
 * sshd's default accessor opens the [Path] with `FileChannel.open`, which
 * bypasses a custom `FileSystemProvider` that only implements
 * `newByteChannel` (ours delegates `newFileChannel` to the default
 * implementation, which throws `UnsupportedOperationException` — surfaced to
 * the client as `SSH_FX_OP_UNSUPPORTED`). Delegating to the provider's
 * `newByteChannel` keeps every open on the tested backend, where
 * `PathEscapeException` confinement already lives.
 */
internal class AndroidFileSystemAccessor : SftpFileSystemAccessor {
    override fun openFile(
        instance: SftpSubsystemProxy,
        fileHandle: FileHandle,
        file: Path,
        handle: String,
        options: Set<java.nio.file.OpenOption>,
        attrs: Array<out java.nio.file.attribute.FileAttribute<*>>
    ): java.nio.channels.SeekableByteChannel =
        file.fileSystem.provider().newByteChannel(file, options, *attrs)
}

/**
 * The embedded SFTP server.
 *
 * ## Scope
 *
 * SFTP only. No shell, no exec, no agent forwarding, no TCP forwarding — every
 * one of those is a way for a client to execute code on the phone, and the
 * point of replacing Termux is to stop handing that out by default. The
 * subsystem list is one line; if it ever needs to grow, that is a decision for
 * the security screen, not a side effect of touching this file.
 *
 * ## What is exposed
 *
 * One directory, the configured root. The NIO provider in `fs` refuses every
 * path that resolves outside it — including through a symlink — so a client
 * asking for `/../` gets `SSH_FX_PERMISSION_DENIED` rather than a file. That is
 * the whole sandbox: the server cannot name a path above its root, because the
 * only way to reach storage is a method on the backend, and the backend
 * refuses.
 *
 * ## Host key
 *
 * Generated on first start and kept in app-private storage, so a client that
 * connected yesterday is not asked to trust a new key tomorrow. The
 * fingerprint is shown in the UI so it can be verified out of band.
 *
 * ## Why a class and not a service method
 *
 * [SftpServerService] owns Android lifecycle and notifications; this class owns
 * the protocol. Splitting them is what makes it possible to start the server
 * in a JVM test with a temporary root and no Context at all — which is where
 * the traversal and compatibility tests come from.
 */
class SftpServerEngine(
    private val filesDir: File,
    private val secrets: SecretStore,
    private val hostKeyStore: SftpHostKeys,
    private val authorizedKeys: SftpAuthorizedKeys
) {

    private companion object {
        const val TAG = "EarthQuackSftpServer"
        const val HOST_KEY_FILE = "sftp_host_key"
        const val PASSWORD_ALIAS = "sftp_password"

        /**
         * sshd's session property keys.
         *
         * Declared in `SessionHelper` as package-private constants in this
         * sshd line, so the names are spelled out here. They are the documented
         * names and are stable across 2.x.
         */
        const val PROP_IDLE_TIMEOUT = "IDLE_TIMEOUT"
        const val PROP_NIO2_READ_TIMEOUT = "NIO2_READ_TIMEOUT"
        const val PROP_CLOSE_TIMEOUT = "CLOSE_TIMEOUT"
        const val PROP_WINDOW_SIZE = "WINDOW_SIZE"
        const val PROP_MAX_PACKET_SIZE = "MAX_PACKET_SIZE"
        const val PROP_MAX_AUTH_REQUESTS = "MAX_AUTH_REQUESTS"
        const val PROP_AUTH_TIMEOUT = "AUTH_TIMEOUT"

        // A client that is transferring is not idle; one that has not sent
        // anything for ten minutes is gone.
        const val IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
        const val NIO2_READ_TIMEOUT_MILLIS = 5 * 60 * 1000L
        const val CLOSE_TIMEOUT_MILLIS = 5 * 1000L
        const val AUTH_TIMEOUT_MILLIS = 30_000L

        /** Window and packet cap, matching OpenSSH's defaults. */
        const val WINDOW_SIZE = 2 * 1024 * 1024
        const val MAX_PACKET_BYTES = 256 * 1024
        const val MAX_AUTH_REQUESTS = 6
    }

    /**
     * Optional listener for SFTP-level events, for diagnostics.
     *
     * sshd reports failures to the client as a status code and swallows the
     * exception, which makes a server-side problem look like a client problem.
     * This is the hook that makes the difference visible in a log.
     */
    @Volatile
    var sftpEventListener: org.apache.sshd.sftp.server.SftpEventListener? = null

    @Volatile
    private var server: SshServer? = null

    private val activeConnections = AtomicInteger(0)

    /**
     * Starts the server, or returns why it could not start.
     *
     * Never throws. A port already in use, a root that became unreadable and a
     * corrupt host-key file are all conditions the user can fix, and the
     * screen needs to be able to say which one it was.
     */
    fun start(settings: SftpSettings): Result<SftpServerInstance> {
        val root = File(settings.rootPath)
        try {
            requireUsableRoot(root)
        } catch (e: IOException) {
            Log.w(TAG, "refusing to start: ${e.message}")
            return Result.failure(e)
        }

        server?.takeIf { it.isStarted }?.let {
            return Result.success(SftpServerInstance.of(it, activeConnections, hostKeyStore))
        }

        if (settings.publicKeyAuth && authorizedKeys.list().isEmpty()) {
            return Result.failure(
                IOException(
                    "Public-key authentication is on, but no key is installed. " +
                        "Add one in the server screen first."
                )
            )
        }
        if (settings.passwordAuth && secrets.get(PASSWORD_ALIAS) == null) {
            return Result.failure(
                IOException("Password authentication is on, but no password is set.")
            )
        }

        val hostKeyFile = File(filesDir, HOST_KEY_FILE)
        val ssh = buildServer(settings, root, hostKeyFile.toPath())
        return try {
            ssh.start()
            server = ssh
            Log.i(TAG, "listening on ${settings.port}, serving ${settings.rootPath}")
            Result.success(SftpServerInstance.of(ssh, activeConnections, hostKeyStore))
        } catch (e: IOException) {
            Log.e(TAG, "start failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Stops the server, if it is running. Returns false when it was not. */
    fun stop(): Boolean {
        val ssh = server ?: return false
        server = null
        return try {
            ssh.stop(true)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "stop threw: ${e.message}")
            false
        }
    }

    /** Whether a server is currently listening. */
    fun isRunning(): Boolean = server?.isStarted == true

    /** The port the server is bound to, or 0 when it is not running. */
    fun serverPort(): Int = server?.port ?: 0

    /** Number of clients currently connected. */
    fun connectedClients(): Int = activeConnections.get()

    /** The recorded host-key fingerprint, for the UI. */
    fun hostKeyFingerprint(): String? = hostKeyStore.fingerprint()

    private fun buildServer(settings: SftpSettings, root: File, hostKeyFile: Path): SshServer {
        val ssh = SshServer.setUpDefaultServer()

        // SFTP only — see the class comment.
        val subsystemFactory = SftpSubsystemFactory()
        subsystemFactory.setFileSystemAccessor(AndroidFileSystemAccessor())
        println("[ENGINE] sftpEventListener is ${if (sftpEventListener != null) "SET" else "NULL"}")
        sftpEventListener?.let { 
            println("[ENGINE] adding listener")
            subsystemFactory.addSftpEventListener(it) 
        }
        ssh.subsystemFactories = listOf(subsystemFactory)

        // Confinement lives in the tested backend + NIO provider, not in sshd's
        // virtual filesystem: LocalAndroidFileSystem refuses lexical `..`
        // escapes and canonical symlink escapes with PathEscapeException
        // (mapped to SSH_FX_PERMISSION_DENIED), and AndroidFileSystemProvider
        // is the only way the protocol layer reaches storage. A client asking
        // for `/../` gets permission-denied rather than a file.
        val backend = LocalAndroidFileSystem(root)
        ssh.fileSystemFactory = AndroidFileSystemFactory(backend)

        // Persistent host key: the same key on every start, so the first
        // connection's trust does not silently become a new key next week.
        ssh.keyPairProvider = hostKeyProvider(hostKeyFile)

        ssh.port = settings.port

        ssh.passwordAuthenticator = passwordAuthenticator(secrets, settings)
        ssh.publickeyAuthenticator = publickeyAuthenticator(authorizedKeys, settings)

        ssh.properties[PROP_IDLE_TIMEOUT] = IDLE_TIMEOUT_MILLIS
        ssh.properties[PROP_NIO2_READ_TIMEOUT] = NIO2_READ_TIMEOUT_MILLIS
        ssh.properties[PROP_CLOSE_TIMEOUT] = CLOSE_TIMEOUT_MILLIS
        ssh.properties[PROP_WINDOW_SIZE] = WINDOW_SIZE
        ssh.properties[PROP_MAX_PACKET_SIZE] = MAX_PACKET_BYTES
        ssh.properties[PROP_MAX_AUTH_REQUESTS] = MAX_AUTH_REQUESTS
        ssh.properties[PROP_AUTH_TIMEOUT] = AUTH_TIMEOUT_MILLIS

        // Count clients so the UI can say how many are connected, and enforce
        // the limit rather than merely document it.
        ssh.addSessionListener(object : SessionListener {
            override fun sessionCreated(session: Session) {
                if (activeConnections.incrementAndGet() > settings.maxConnections) {
                    Log.w(TAG, "rejecting connection: at the limit of ${settings.maxConnections}")
                    runCatching { session.close(true) }
                    return
                }
            }

            override fun sessionClosed(session: Session) {
                activeConnections.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        })
        return ssh
    }

    private fun hostKeyProvider(hostKeyFile: Path): KeyPairProvider {
        val provider = SimpleGeneratorHostKeyProvider(hostKeyFile)
        provider.algorithm = "EC"
        provider.keySize = 256
        return provider
    }

    private fun passwordAuthenticator(
        secrets: SecretStore,
        settings: SftpSettings
    ): org.apache.sshd.server.auth.password.PasswordAuthenticator? {
        if (!settings.passwordAuth) return null
        return org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, _ ->
            if (username != settings.username) return@PasswordAuthenticator false
            val stored = secrets.get(PASSWORD_ALIAS)?.toString(Charsets.UTF_8)
                ?: return@PasswordAuthenticator false
            // Constant-time comparison: the first difference should not be
            // observable in how long the check takes.
            MessageDigest.isEqual(password.toByteArray(), stored.toByteArray())
        }
    }

    private fun publickeyAuthenticator(
        authorizedKeys: SftpAuthorizedKeys,
        settings: SftpSettings
    ): org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator? {
        if (!settings.publicKeyAuth) return null
        return org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { username, key, _ ->
            if (username != settings.username) return@PublickeyAuthenticator false
            authorizedKeys.matches(key)
        }
    }
}

/**
 * A running server, as the rest of the app sees it.
 *
 * [SftpServerEngine] keeps the only live reference; this is a snapshot wrapper
 * so the UI can observe a running server without being handed an [SshServer].
 */
class SftpServerInstance(
    val server: SshServer,
    private val connections: AtomicInteger,
    private val hostKeyStore: SftpHostKeys
) {

    val port: Int get() = server.port
    val isRunning: Boolean get() = server.isStarted
    val connectedClients: Int get() = connections.get()
    val hostKeyFingerprint: String? get() = hostKeyStore.fingerprint()

    fun shutdown() {
        runCatching { server.stop(true) }
    }

    companion object {
        fun of(
            server: SshServer,
            connections: AtomicInteger,
            hostKeyStore: SftpHostKeys
        ): SftpServerInstance = SftpServerInstance(server, connections, hostKeyStore)
    }
}

/**
 * The server's own view of its host key.
 *
 * Deliberately separate from [SftpSettings]: the host key is a fact about the
 * server, while `SftpSettingsStore` holds the configuration the user typed.
 * Mixing them would mean regenerating a key means editing settings.
 */
class HostKeyStore(context: Context) : SftpHostKeys {

    private companion object {
        const val PREFS = "earthquack_sftp_hostkey"
        const val KEY_FINGERPRINT = "fingerprint"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun fingerprint(): String? = prefs.getString(KEY_FINGERPRINT, null)

    override fun record(fingerprint: String) {
        prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()
    }

    override fun forget() = prefs.edit().remove(KEY_FINGERPRINT).apply()
}

/**
 * Public keys accepted by the SFTP server.
 *
 * Each entry is the client's public key in `authorized_keys` form — what
 * `ssh-add -L` prints and what an app can compare against without a server.
 * Nothing here is private: a public key grants nothing on its own.
 */
class AuthorizedKeysStore(context: Context) : SftpAuthorizedKeys {

    private companion object {
        const val PREFS = "earthquack_sftp_authorized_keys"
        const val KEY_KEYS = "keys"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun list(): List<SftpAuthorizedKeys.Entry> {
        val raw = prefs.getString(KEY_KEYS, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size < 2 || parts[1].isBlank()) return@mapNotNull null
            val label = parts.drop(2).joinToString(" ").ifBlank { parts[0] }
            SftpAuthorizedKeys.Entry(label = label, publicKey = line)
        }
    }

    fun add(label: String, publicKey: String) {
        val updated = list().filterNot { it.publicKey == publicKey } +
            SftpAuthorizedKeys.Entry(label = label, publicKey = publicKey)
        write(updated)
    }

    fun remove(publicKey: String) {
        write(list().filterNot { it.publicKey == publicKey })
    }

    /**
     * Whether [key] is in the set.
     *
     * Compares fingerprints of the raw key blobs rather than parsing each
     * stored line back into a key object: the fingerprint is defined as the
     * SHA-256 of exactly that blob, so hashing it directly is both simpler and
     * the same comparison sshd itself makes.
     */
    override fun matches(key: PublicKey): Boolean {
        val wanted = encode(key)?.let { fingerprintOf(decode(it)) } ?: return false
        return list().any { entry ->
            fingerprintOf(decode(entry.publicKey)) == wanted
        }
    }

    /**
     * The wire encoding of [key].
     *
     * This is the exact blob whose SHA-256 is the key fingerprint, so comparing
     * these is the same comparison sshd makes against the key it negotiated.
     */
    private fun encode(key: PublicKey): String? = runCatching {
        val sb = StringBuilder()
        PublicKeyEntry.appendPublicKeyEntry(sb, key)
        sb.toString()
    }.getOrNull()

    private fun decode(line: String): ByteArray? {
        val parts = line.split(' ')
        if (parts.size < 2) return null
        return Base64Codec.decode(parts[1])
    }

    private fun fingerprintOf(blob: ByteArray?): String? {
        if (blob == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64Codec.encode(digest)
    }

    private fun write(keys: List<SftpAuthorizedKeys.Entry>) {
        prefs.edit().putString(KEY_KEYS, keys.joinToString("\n") { it.publicKey }).apply()
    }
}

/**
 * Base64, locally.
 *
 * `android.util.Base64` cannot be used here: this class is unit tested on the
 * JVM, where the Android runtime returns null for it. The alphabet and padding
 * are the standard ones, so a key written here is the same key sshd reads.
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(input: String): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input) {
            if (c == '=') break
            val index = ALPHABET.indexOf(c)
            if (index < 0) throw IllegalArgumentException("not base64: '$c'")
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        out.toByteArray()
    }.getOrNull()

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index++].toInt() and 0xFF
            val b1 = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
            val b2 = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
            sb.append(ALPHABET[(b0 shr 2) and 0x3F])
            sb.append(ALPHABET[((b0 shl 4) or (if (b1 >= 0) b1 shr 4 else 0)) and 0x3F])
            if (b1 >= 0) {
                sb.append(ALPHABET[((b1 shl 2) or (if (b2 >= 0) b2 shr 6 else 0)) and 0x3F])
                sb.append(ALPHABET[if (b2 >= 0) b2 and 0x3F else 0x40])
            } else {
                sb.append("==")
            }
        }
        return sb.toString()
    }
}
