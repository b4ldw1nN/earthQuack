package com.example.earthquack.sftp

import android.content.Context
import android.util.Log
import com.example.earthquack.ssh.SecretStore
import com.example.earthquack.ssh.SshdEnvironment
import com.example.earthquack.sftp.fs.AndroidFileSystemFactory
import com.example.earthquack.sftp.fs.requireUsableRoot
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
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
     * Settings that require a server restart when changed.
     * These are the settings that affect how the server binds and authenticates.
     */
    private data class ServerSettings(
        val port: Int,
        val rootPath: String,
        val username: String,
        val passwordAuth: Boolean,
        val publicKeyAuth: Boolean,
        val maxConnections: Int
    )

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

    /** Current server settings, used to detect when a restart is needed. */
    @Volatile
    private var currentSettings: ServerSettings? = null

    private val activeConnections = AtomicInteger(0)

    /**
     * Serialises every lifecycle transition.
     *
     * `start` is called from the foreground service's main thread, `stop` from
     * a notification action and from `onDestroy`, and the UI polls `isRunning`
     * meanwhile. Without a single lock around start/stop/restart, two overlapping
     * starts could each build a server and each assign `server`, so one of them
     * would be listening with nobody holding the reference — an orphaned listener
     * holding the port and invisible to `stop()`.
     */
    private val lifecycle = Any()

    /**
     * Starts the server, or returns why it could not start.
     *
     * Never throws. A port already in use, a root that became unreadable and a
     * corrupt host-key file are all conditions the user can fix, and the
     * screen needs to be able to say which one it was.
     *
     * If the server is already running with the same settings, returns the
     * running instance without touching it. If the settings differ, the
     * replacement is validated *before* the running server is stopped, so a
     * rejected configuration leaves the working server alone.
     *
     * On success the returned instance reports the port sshd actually bound,
     * which for an ephemeral (`port = 0`) request is not the requested port.
     */
    fun start(settings: SftpSettings): Result<SftpServerInstance> = synchronized(lifecycle) {
        val root = File(settings.rootPath)

        // Validate first. Everything below this point is allowed to stop a
        // working server; nothing above it is. That ordering is the difference
        // between "your new port is in use" and "your new port was in use and
        // now nothing is running either".
        validate(settings, root)?.let { problem ->
            Log.w(TAG, "refusing to start: ${problem.message}")
            return@synchronized Result.failure(problem)
        }

        val newSettings = ServerSettings(
            port = settings.port,
            rootPath = settings.rootPath,
            username = settings.username,
            passwordAuth = settings.passwordAuth,
            publicKeyAuth = settings.publicKeyAuth,
            maxConnections = settings.maxConnections
        )

        server?.takeIf { it.isStarted }?.let { existingServer ->
            if (settingsMatch(currentSettings, existingServer.port, settings)) {
                Log.i(TAG, "already running with the requested settings on ${existingServer.port}")
                return@synchronized Result.success(
                    SftpServerInstance.of(existingServer, activeConnections, hostKeyStore)
                )
            }
            Log.i(TAG, "settings changed, restarting")
            stopLocked()
        }

        val hostKeyFile = File(filesDir, HOST_KEY_FILE)
        val ssh = buildServer(settings, root, hostKeyFile.toPath())
        return@synchronized try {
            ssh.start()
            server = ssh
            currentSettings = newSettings
            // sshd rewrites `port` with the port it actually bound, which is the
            // only number the UI may show. Requesting 0 and reading back the
            // saved preference would report a port nothing is listening on.
            recordHostKey(ssh)
            Log.i(TAG, "listening on ${ssh.port}, serving ${settings.rootPath}")
            Result.success(SftpServerInstance.of(ssh, activeConnections, hostKeyStore))
        } catch (e: IOException) {
            // The listener may already be bound even though start() threw (a
            // session accepted during the bind, a failure in a post-bind
            // initialiser). Leaving it running would hold the port with no
            // reference to stop it, so the half-built server is torn down here.
            runCatching { ssh.stop(true) }
            server = null
            currentSettings = null
            Log.e(TAG, "start failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Everything that can be checked without binding a port, as a problem.
     *
     * Returns null when the settings are usable. Split out from [start] so the
     * checks provably run before any state is destroyed.
     */
    private fun validate(settings: SftpSettings, root: File): IOException? {
        settings.problems().firstOrNull()?.let { return IOException(it) }
        try {
            requireUsableRoot(root)
        } catch (e: IOException) {
            return e
        }
        if (!settings.passwordAuth && !settings.publicKeyAuth) {
            return IOException(
                "No authentication method is enabled, so no client could connect. " +
                    "Turn password or public-key authentication on first."
            )
        }
        if (settings.publicKeyAuth && authorizedKeys.list().isEmpty()) {
            return IOException(
                "Public-key authentication is on, but no key is installed. " +
                    "Add one in the server screen first."
            )
        }
        if (settings.passwordAuth && secrets.get(PASSWORD_ALIAS) == null) {
            return IOException("Password authentication is on, but no password is set.")
        }
        return null
    }

    /**
     * Publishes the running host key's fingerprint so the security screen has
     * something to show and the user can verify it out of band.
     *
     * Recorded only after a successful start, and re-recorded identically on
     * every restart, because the key file is stable: an unchanged fingerprint
     * across restarts is the property that makes TOFU usable.
     */
    private fun recordHostKey(ssh: SshServer) {
        val key = runCatching { ssh.keyPairProvider?.loadKeys(null) }.getOrNull()
            ?.firstOrNull()
            ?: return
        val fingerprint = runCatching { KeyUtils.getFingerPrint(key.public) }.getOrNull() ?: return
        if (fingerprint != hostKeyStore.fingerprint()) {
            hostKeyStore.record(fingerprint)
            Log.i(TAG, "host key fingerprint: $fingerprint")
        }
    }

    /**
     * Whether a restart is needed.
     *
     * [boundPort] is what sshd actually listens on. A request for port 0 binds
     * an ephemeral port, so comparing the requested `0` against the recorded `0`
     * would call two unrelated servers "the same settings" — hence the separate
     * port argument. An explicit port must match exactly; an ephemeral request
     * matches whatever the existing listener is bound to, because there is no
     * port for the user to have meant.
     */
    private fun settingsMatch(current: ServerSettings?, boundPort: Int, next: SftpSettings): Boolean {
        if (current == null) return false
        if (next.port != 0 && next.port != boundPort) return false
        return current.rootPath == next.rootPath &&
            current.username == next.username &&
            current.passwordAuth == next.passwordAuth &&
            current.publicKeyAuth == next.publicKeyAuth &&
            current.maxConnections == next.maxConnections
    }

    /** Stops the server, if it is running. Returns false when it was not. */
    fun stop(): Boolean = synchronized(lifecycle) { stopLocked() }

    /**
     * The body of [stop], for callers already holding [lifecycle].
     *
     * `stopSelf()` and `onDestroy()` on the service reach this through [stop];
     * a restart reaches it from inside [start] while holding the same lock.
     */
    private fun stopLocked(): Boolean {
        val ssh = server ?: return false
        server = null
        currentSettings = null
        return try {
            ssh.stop(true)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "stop threw: ${e.message}", e)
            false
        }
    }

    /**
     * Whether a server is currently listening.
     *
     * True only when this engine started *this* server and sshd still reports
     * it as started. It deliberately does not consult [serverPort]: a caller
     * that wants "running with these settings" must ask [matches], because
     * "running" alone is exactly the answer that lets a restart be mistaken for
     * success on the previous configuration.
     */
    fun isRunning(): Boolean = server?.isStarted == true

    /**
     * Whether the running server was started with exactly [settings].
     *
     * The distinction that makes a restart observable: a server bound to the
     * old port is running, so `isRunning()` alone cannot tell a caller that the
     * requested configuration is not the one in effect.
     */
    fun matches(settings: SftpSettings): Boolean {
        val current = currentSettings ?: return false
        val running = server?.takeIf { it.isStarted } ?: return false
        return settingsMatch(current, running.port, settings)
    }

    /** The port the server is bound to, or 0 when it is not running. */
    fun serverPort(): Int = server?.takeIf { it.isStarted }?.port ?: 0

    /** Number of clients currently connected. */
    fun connectedClients(): Int = activeConnections.get()

    /** The recorded host-key fingerprint, for the UI. */
    fun hostKeyFingerprint(): String? = hostKeyStore.fingerprint()

    private fun buildServer(settings: SftpSettings, root: File, hostKeyFile: Path): SshServer {
        // sshd's user-home resolver is process-global and is installed once, by
        // SshdEnvironment. This class deliberately does not set it: the served
        // root is user-configurable and meant to be reachable, which makes it
        // the wrong value for the slot that decides where sshd looks for
        // client-side key material. A client's start directory comes from
        // AndroidFileSystemFactory.getUserHomeDir instead, per session.
        SshdEnvironment.ensureUserHome(filesDir)

        val ssh = SshServer.setUpDefaultServer()

        // SFTP only — see the class comment.
        val subsystemFactory = SftpSubsystemFactory()
        subsystemFactory.setFileSystemAccessor(AndroidFileSystemAccessor())
        sftpEventListener?.let { subsystemFactory.addSftpEventListener(it) }
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

    /**
     * The port sshd actually bound.
     *
     * Read from the live server rather than from the requested setting: for an
     * ephemeral request the two differ, and the bound port is the only one a
     * client can connect to.
     */
    val port: Int get() = server.port
    val isRunning: Boolean get() = server.isStarted
    val connectedClients: Int get() = connections.get()
    val hostKeyFingerprint: String? get() = hostKeyStore.fingerprint()

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
 * Parses one `authorized_keys` line into the form this store keeps.
 *
 * Pure and top-level so it can be unit tested without a Context. An
 * authorized_keys line is public data, so no passphrase is involved and nothing
 * secret is handled here.
 *
 * @param text a whole file's worth of text. Real `cat ~/.ssh/id_ed25519.pub`
 *   output, which may include comments and blank lines.
 * @return the entry, or null with a reason when nothing usable is present.
 */
fun parseAuthorizedKeyLine(text: String): Result<AuthorKeyLine> {
    val line = text.lineSequence().map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return Result.failure(IOException("the file has no key line in it"))

    val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.size < 2) {
        return Result.failure(IOException("an authorized_keys line needs a key type and a key"))
    }
    // The key-type tokens ssh uses, plus the certificate forms ssh keygen emits.
    val knownTypes = setOf("ssh-rsa", "ssh-dss", "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521")
    val isCertificate = parts[0].endsWith("-cert-v01@openssh.com") ||
        parts[0].startsWith("ssh-") && parts[0].contains("@")
    if (parts[0] !in knownTypes && !isCertificate && !parts[0].startsWith("ecdsa-")) {
        return Result.failure(IOException("this does not look like an OpenSSH public key: ${parts[0].take(24)}"))
    }
    // The blob must be base64 of a plausible length for a key; a short or empty
    // one is a truncation, and installing it would produce a client that can
    // never authenticate and a server that reports success.
    if (parts[1].length < 20) {
        return Result.failure(IOException("the key itself is too short to be a whole key"))
    }
    val label = parts.drop(2).joinToString(" ").ifBlank { parts[0] }
    return Result.success(AuthorKeyLine(parts[0], parts[1], label))
}

/** One parsed authorized_keys entry. */
data class AuthorKeyLine(
    /** e.g. `ssh-ed25519`, `ecdsa-sha2-nistp256`. */
    val type: String,
    /** The base64 key blob. */
    val key: String,
    /** The trailing comment, or the type when the client wrote none. */
    val label: String
) {
    /** The line as `authorized_keys` wants it. */
    fun toAuthorizedKeyText(): String = listOf(type, key, label).joinToString(" ")
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
/**
 * Base64, using the JDK.
 *
 * The previous implementation hand-rolled the alphabet and indexed
 * `ALPHABET[0x40]` to emit padding — an index that does not exist, so every
 * digest whose length was not a multiple of three threw
 * `StringIndexOutOfBoundsException`. A SHA-256 digest is 32 bytes, so *every*
 * fingerprint hit it, and `AuthorizedKeysStore.matches()` threw for every key a
 * client presented. Public-key authentication on the embedded server therefore
 * never succeeded against an installed key.
 *
 * `java.util.Base64` exists on Android API 26, which is this app's minSdk, so
 * there is no reason to carry a hand-written codec at all. The old comment
 * avoided `android.util.Base64` (which is unusable on the JVM) — a different
 * class that does not have that problem.
 */
internal object Base64Codec {

    private val encoder = java.util.Base64.getEncoder()
    private val decoder = java.util.Base64.getDecoder()

    /** Decoded bytes, or null when [input] is not valid base64. */
    fun decode(input: String): ByteArray? = runCatching { decoder.decode(input) }.getOrNull()

    /** Standard base64, padded — the form OpenSSH uses in `authorized_keys`. */
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Unpadded base64url, the form sshd uses in a `SHA256:` fingerprint. */
    fun encodeUrlNoPadding(bytes: ByteArray): String =
        encoder.encodeToString(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
}
