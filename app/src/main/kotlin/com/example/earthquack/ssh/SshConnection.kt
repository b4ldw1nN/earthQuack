package com.example.earthquack.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.common.SshException
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.sftp.client.SftpClientFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Timeouts, in one place, so the terminal and the file browser cannot disagree
 * about what "hung" means.
 *
 * [connectMillis] bounds TCP plus key exchange and [authMillis] bounds
 * authentication. Both are short because the target is a machine on the same
 * tailnet, where a slow answer means something is wrong rather than merely
 * slow. There is deliberately no timeout on transfers or on a shell: those are
 * meant to run for as long as the user wants.
 */
data class SshTimeouts(
    val connectMillis: Long = 15_000,
    val authMillis: Long = 20_000,
    val commandMillis: Long = 60_000
)

/**
 * Opens authenticated SSH connections for a [ConnectionProfile].
 *
 * ## Shape
 *
 * A factory, not a singleton with global state and not an Activity helper. The
 * terminal screen and the SFTP browser both call [connect]; neither knows how
 * authentication or host-key checking works, and neither can accidentally skip
 * it. That is the entire reason this class exists.
 *
 * ## Host key policy
 *
 * Enforced in [verifyServerKey], which is the only place sshd asks. Three
 * outcomes: match (continue), unknown (record under TOFU, refuse under STRICT),
 * mismatch (refuse, always). There is no branch that accepts an unverified key.
 *
 * ## Credentials
 *
 * Read from the stores at connect time and handed straight to sshd. Nothing is
 * logged — the only values that reach [Log] are host, port, username, and
 * sshd's own message.
 */
class SshConnectionFactory(
    private val identityKeys: IdentityKeyStore,
    private val secrets: SecretStore,
    private val knownHosts: KnownHostsStore,
    private val filesDir: java.io.File,
    private val timeouts: SshTimeouts = SshTimeouts()
) {

    private companion object {
        const val TAG = "EarthQuackSsh"

        init {
            // On Android, sshd needs a user home folder resolver since there's no
            // traditional $HOME. Must be set before any sshd classes are loaded.
            PathUtils.setUserHomeFolderResolver { Paths.get(System.getProperty("user.dir")) }
        }
    }

    /**
     * Connects and authenticates.
     *
     * Blocking work happens on [Dispatchers.IO]; safe to call from the main
     * thread. Returns the failure as data, never as an exception, so callers
     * cannot forget it.
     */
    suspend fun connect(profile: ConnectionProfile): SshConnectResult {
        Log.i(TAG, "SshConnectionFactory.connect() called for ${profile.name}@${profile.endpoint}")
        val problems = profile.problems()
        if (problems.isNotEmpty()) {
            return SshConnectResult.Failed(SshFailure.InvalidProfile(problems))
        }

        // Credentials are resolved before connecting so a missing key is
        // reported as a missing key, not as an authentication failure from the
        // server two seconds later.
        val keyPair: java.security.KeyPair?
        val password: String?
        when (profile.authMethod) {
            AuthMethod.PUBLIC_KEY -> {
                keyPair = identityKeys.loadKeyPair(profile.identityKeyAlias!!)
                if (keyPair == null) {
                    return SshConnectResult.Failed(
                        SshFailure.CredentialMissing(
                            "The key for this connection is not on the device any more. " +
                                "Import it again."
                        )
                    )
                }
                password = null
            }
            AuthMethod.PASSWORD -> {
                keyPair = null
                password = secrets.get(profile.passwordAlias!!)?.toString(StandardCharsets.UTF_8)
                if (password == null) {
                    return SshConnectResult.Failed(
                        SshFailure.CredentialMissing("The stored password could not be decrypted.")
                    )
                }
            }
        }

        return withContext(Dispatchers.IO) {
            var client: SshClient? = null
            try {
                client = SshClient.setUpDefaultClient().apply {
                    serverKeyVerifier = HostKeyVerifier(profile, knownHosts)
                }
                client.start()
                val session = connectAndVerify(client, profile)
                try {
                    authenticate(session, profile, keyPair, password)
                } catch (e: Throwable) {
                    runCatching { session.close() }
                    throw e
                }
                Log.i(TAG, "connected to ${profile.username}@${profile.endpoint}")
                SshConnectResult.Connected(SshConnection(client, session, profile))
            } catch (e: SshFailureException) {
                runCatching { client?.stop() }
                SshConnectResult.Failed(e.failure)
            } catch (e: Throwable) {
                runCatching { client?.stop() }
                SshConnectResult.Failed(classify(e))
            }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun connectAndVerify(client: SshClient, profile: ConnectionProfile): ClientSession {
        // verify() returns void in this sshd line; the session is read back off
        // the future afterwards.
        val future = client.connect(profile.username, profile.host, profile.port)
        future.verify(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        return future.clientSession
            ?: throw SshFailureException(SshFailure.Timeout)
    }

    private fun authenticate(
        session: ClientSession,
        profile: ConnectionProfile,
        keyPair: java.security.KeyPair?,
        password: String?
    ) {
        when (profile.authMethod) {
            AuthMethod.PUBLIC_KEY -> session.addPublicKeyIdentity(keyPair!!)
            AuthMethod.PASSWORD -> session.addPasswordIdentity(password!!)
        }
        try {
            session.auth().verify(timeouts.authMillis, TimeUnit.MILLISECONDS)
        } catch (e: SshException) {
            throw SshFailureException(
                SshFailure.AuthFailed(
                    profile.authMethod,
                    e.message ?: "the server rejected the credentials"
                )
            )
        }

        // Test if session stays alive by executing a simple command
        // This helps diagnose if the session is being closed by the server immediately after auth
        try {
            val testChannel: ChannelExec = session.createExecChannel("echo 'test'")
            testChannel.setOut(CallbackOutputStream { _, _, _ -> })
            testChannel.setErr(CallbackOutputStream { _, _, _ -> })
            testChannel.open().verify(5000, TimeUnit.MILLISECONDS)
            Log.i("EarthQuackSsh", "Test exec command succeeded, session is alive")
            testChannel.close()
        } catch (e: Throwable) {
            Log.w("EarthQuackSsh", "Test exec command failed: ${e.message}", e)
        }
    }

    /**
     * Maps exceptions onto the failure type the UI can act on.
     *
     * Ordering matters: a timeout and a refused connection are both IO
     * exceptions and the difference between them is the entire message.
     */
    private fun classify(e: Throwable): SshFailure = when (e) {
        is SshFailureException -> e.failure
        is SocketTimeoutException -> SshFailure.Timeout
        // sshd has no dedicated timeout exception in this line; a failed
        // verify() surfaces as a plain IOException whose message says so.
        is IOException -> if (e.message?.contains("imeout", ignoreCase = true) == true) {
            SshFailure.Timeout
        } else {
            SshFailure.Unexpected(e.message ?: e.javaClass.simpleName)
        }
        is java.util.concurrent.TimeoutException -> SshFailure.Timeout
        is UnknownHostException -> SshFailure.Network("no such host")
        is ConnectException ->
            SshFailure.Network("connection refused — is the host up and sshd listening?")
        is SocketException -> SshFailure.Network(e.message ?: "socket error")
        is SshException -> SshFailure.Unexpected(e.message ?: "ssh error")
        else -> SshFailure.Unexpected(e.message ?: e.javaClass.simpleName)
    }
}

/** Carries a structured [SshFailure] up through sshd's exception-based API. */
internal class SshFailureException(val failure: SshFailure) : IOException(failure.message)

/**
 * An authenticated SSH session, and the three things one can do with it.
 *
 * ## Lifetime
 *
 * Owns the [SshClient] and the [ClientSession]; [close] shuts both down and is
 * idempotent. Callers hold this rather than the client, so no code path can
 * leak a session by keeping a reference to something narrower.
 *
 * ## Long-running work
 *
 * [openShell] hands back streams, because a terminal is bidirectional and does
 * not fit a suspending function. [exec] is the opposite: inherently "run and
 * collect", so it is a suspending call with a deadline.
 */
class SshConnection(
    private val client: SshClient,
    val session: ClientSession,
    val profile: ConnectionProfile
) : AutoCloseable {

    @Volatile
    private var closed = false

    val isOpen: Boolean get() = !closed && session.isOpen

    /**
     * Opens an interactive shell with a PTY.
     *
     * [rows] and [cols] are the geometry the remote side should assume; they
     * must be kept current with [ShellChannel.resize] or full-screen programs
     * (vim, less, htop) will draw for the wrong size.
     *
     * @param onData receives everything the server writes — stdout and stderr
     *   together. A terminal has one pane, and hiding errors in a second
     *   channel that is never displayed is worse than interleaving them.
     */
    /**
     * Opens an interactive shell with a PTY.
     *
     * [rows] and [cols] are the geometry the remote side should assume; they
     * must be kept current with [ShellChannel.resize] or full-screen programs
     * (vim, less, htop) will draw for the wrong size.
     *
     * @param onData receives everything the server writes — stdout and stderr
     *   together. A terminal has one pane, and hiding errors in a second
     *   channel that is never displayed is worse than interleaving them.
     */
    fun openShell(
        onData: (ByteArray, Int, Int) -> Unit,
        term: String = "xterm-256color",
        rows: Int = 24,
        cols: Int = 80
    ): ShellChannel {
        check(isOpen) { "session is closed" }

        Log.i("EarthQuackSsh", "Opening shell channel with term=$term, rows=$rows, cols=$cols")

        val channel: ChannelShell = session.createShellChannel()
        setupPtyChannel(channel, term, rows, cols)

        val stdin = QueueInputStream()
        channel.setIn(stdin)
        channel.setOut(CallbackOutputStream(onData))
        channel.setErr(CallbackOutputStream(onData))
        try {
            Log.i("EarthQuackSsh", "Opening shell channel...")
            val future = channel.open()
            Log.i("EarthQuackSsh", "Channel open future created, verifying...")
            future.verify(CHANNEL_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.i("EarthQuackSsh", "Shell channel opened successfully")
        } catch (e: Throwable) {
            Log.e("EarthQuackSsh", "Failed to open shell channel, trying exec fallback", e)
            // Fallback: try exec channel with explicit shell command
            return openShellFallback(onData, term, rows, cols, e)
        }

        return ShellChannel(channel, stdin)
    }

    private fun setupPtyChannel(channel: ChannelShell, term: String, rows: Int, cols: Int) {
        channel.setPtyType(term)
        channel.setPtyHeight(rows)
        channel.setPtyWidth(cols)
        channel.setUsePty(true)

        // Set basic PTY modes that most servers expect
        val ptyModes = mutableMapOf<org.apache.sshd.common.channel.PtyMode, Int>()
        ptyModes[org.apache.sshd.common.channel.PtyMode.ECHO] = 1
        ptyModes[org.apache.sshd.common.channel.PtyMode.ECHOCTL] = 1
        ptyModes[org.apache.sshd.common.channel.PtyMode.ICRNL] = 1
        ptyModes[org.apache.sshd.common.channel.PtyMode.ONLCR] = 1
        ptyModes[org.apache.sshd.common.channel.PtyMode.ISIG] = 1
        ptyModes[org.apache.sshd.common.channel.PtyMode.ICANON] = 1
        channel.setPtyModes(ptyModes)
    }

    /**
     * Fallback: opens an exec channel with an explicit shell command.
     * Used when the shell channel fails (e.g., server closes shell channel).
     */
    private fun openShellFallback(
        onData: (ByteArray, Int, Int) -> Unit,
        term: String,
        rows: Int,
        cols: Int,
        originalError: Throwable
    ): ShellChannel {
        Log.w("EarthQuackSsh", "Attempting fallback: exec channel with explicit shell", originalError)

        // Try common shells in order of preference
        val shells = listOf("bash -l", "sh -l", "bash", "sh")
        var lastError: Throwable? = originalError

        for (shellCmd in shells) {
            try {
                Log.i("EarthQuackSsh", "Trying fallback shell: $shellCmd")
                val execChannel: ChannelExec = session.createExecChannel(shellCmd)
                execChannel.setPtyType(term)
                execChannel.setPtyHeight(rows)
                execChannel.setPtyWidth(cols)
                execChannel.setUsePty(true)

                val ptyModes = mutableMapOf<org.apache.sshd.common.channel.PtyMode, Int>()
                ptyModes[org.apache.sshd.common.channel.PtyMode.ECHO] = 1
                ptyModes[org.apache.sshd.common.channel.PtyMode.ECHOCTL] = 1
                ptyModes[org.apache.sshd.common.channel.PtyMode.ICRNL] = 1
                ptyModes[org.apache.sshd.common.channel.PtyMode.ONLCR] = 1
                ptyModes[org.apache.sshd.common.channel.PtyMode.ISIG] = 1
                ptyModes[org.apache.sshd.common.channel.PtyMode.ICANON] = 1
                execChannel.setPtyModes(ptyModes)

                val stdin = QueueInputStream()
                execChannel.setIn(stdin)
                execChannel.setOut(CallbackOutputStream(onData))
                execChannel.setErr(CallbackOutputStream(onData))
                execChannel.open().verify(CHANNEL_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                Log.i("EarthQuackSsh", "Fallback exec channel opened successfully with: $shellCmd")
                return ShellChannel(execChannel, stdin)
            } catch (e: Throwable) {
                Log.w("EarthQuackSsh", "Fallback shell '$shellCmd' failed", e)
                lastError = e
            }
        }

        // All fallbacks failed
        val msg = when {
            lastError?.message?.contains("Closed", ignoreCase = true) == true ->
                "All shell attempts failed. The SSH server may be configured to deny shell access (e.g., ForceCommand internal-sftp, PermitTTY no, or user shell set to nologin). Check server's sshd_config and user shell."
            lastError?.message?.contains("timeout", ignoreCase = true) == true ->
                "Connection timed out opening shell channel"
            lastError?.message?.contains("auth", ignoreCase = true) == true ->
                "Authentication failed"
            else ->
                "Terminal error: ${lastError?.message ?: "Unknown error"}"
        }
        Log.e("EarthQuackSsh", "All shell attempts failed: $msg", lastError)
        throw IOException("Failed to open shell channel: $msg", lastError!!)
    }

    /** Opens the SFTP subsystem on this session. The caller closes it. */
    fun openSftp(): SftpSession = SftpSession(SftpClientFactory.instance().createSftpClient(session))

    /**
     * Runs a command and collects its output.
     *
     * Output is streamed to [onOutput] as it arrives *and* retained up to
     * [retainLimit] bytes, so a command producing megabytes can be displayed
     * without being held in memory in full. The retained tail is what comes
     * back in [SshCommandResult].
     */
    suspend fun exec(
        command: String,
        timeoutMillis: Long = 60_000,
        retainLimit: Int = DEFAULT_RETAIN_LIMIT,
        onOutput: ((ByteArray, Int, Int) -> Unit)? = null
    ): SshCommandResult = withContext(Dispatchers.IO) {
        check(isOpen) { "session is closed" }

        val stdout = RetainingOutputStream(retainLimit, onOutput)
        val stderr = RetainingOutputStream(retainLimit, onOutput)
        val channel: ChannelExec = session.createExecChannel(command)
        channel.setIn(QueueInputStream())
        channel.setOut(stdout)
        channel.setErr(stderr)
        channel.open()

        channel.waitFor(WAIT_FOR_CHANNEL_EVENTS, timeoutMillis)
        val status = channel.exitStatus

        SshCommandResult(
            exitStatus = status,
            stdout = stdout.text(),
            stderr = stderr.text()
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
        runCatching { client.stop() }
    }

    /**
     * A live interactive shell.
     *
     * [stdin] is where keystrokes go; [close] tears the channel down. Both are
     * safe to call from any thread.
     */
    /**
 * A live interactive shell.
 */
class ShellChannel internal constructor(
    private val channel: org.apache.sshd.client.channel.ClientChannel,
    val stdin: QueueInputStream
) {
    /** Sends a terminal resize to the remote side. */
    fun resize(rows: Int, cols: Int) {
        if (channel is ChannelShell) {
            runCatching { (channel as ChannelShell).sendWindowChange(rows, cols, 0, 0) }
        }
    }

    val isOpen: Boolean get() = channel.isOpen

    fun close() {
        runCatching { stdin.close() }
        runCatching { channel.close() }
    }
}

    private companion object {
        const val CHANNEL_OPEN_TIMEOUT_MS = 20_000L
        const val DEFAULT_RETAIN_LIMIT = 1024 * 1024

        val WAIT_FOR_CHANNEL_EVENTS: Set<ClientChannelEvent> = setOf(
            ClientChannelEvent.CLOSED,
            ClientChannelEvent.EXIT_STATUS,
            ClientChannelEvent.EOF
        )
    }
}

/**
 * A blocking [InputStream] fed by [offer], for the server→client direction of
 * keystrokes.
 *
 * sshd reads a client channel's input on its own thread, so the UI thread
 * writes into a queue and that thread drains it. A pipe would do, but its
 * 1 KiB buffer means a paste larger than that blocks the writer until the
 * reader catches up — which, for an interactive terminal, is a visible stall
 * rather than buffering.
 *
 * [fail] exists so a reader blocked on an empty queue when the connection dies
 * wakes up and throws instead of hanging forever; that is the difference
 * between a terminal that reports a dropped connection and one that just
 * stops responding.
 */
class QueueInputStream : InputStream() {

    private val lock = Object()
    private val queue = ArrayDeque<Byte>()
    private var failure: Throwable? = null
    private var closed = false

    /** Bytes waiting to be sent. Non-blocking. */
    val pendingBytes: Int get() = synchronized(lock) { queue.size }

    /** Queues [data] for delivery to the server. */
    fun offer(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            for (i in offset until offset + length) queue.addLast(data[i])
            lock.notifyAll()
        }
    }

    /** Makes blocked and future reads fail with [error]. */
    fun fail(error: Throwable) {
        synchronized(lock) {
            if (failure == null) failure = error
            lock.notifyAll()
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            while (queue.isEmpty()) {
                failure?.let { throw IOException("shell channel failed: ${it.message}", it) }
                if (closed) return -1
                try {
                    lock.wait(READ_WAIT_MILLIS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted while reading shell input", e)
                }
            }
            var i = 0
            while (i < len && queue.isNotEmpty()) {
                b[off + i] = queue.removeFirst()
                i++
            }
            return i
        }
    }

    override fun available(): Int = synchronized(lock) { queue.size }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    private companion object {
        const val READ_WAIT_MILLIS = 500L
    }
}

/** Hands every byte written to it straight to [sink]. */
private class CallbackOutputStream(
    private val sink: (ByteArray, Int, Int) -> Unit
) : OutputStream() {
    private val one = ByteArray(1)

    override fun write(b: Int) {
        one[0] = b.toByte()
        sink(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0) sink(b, off, len)
    }

    override fun flush() = Unit
}

/**
 * Keeps the first [limit] bytes and forwards everything to [sink].
 *
 * Truncation is by dropping the tail rather than the head, because the start
 * of a command's output is the part that explains what happened; a truncated
 * error at the top of a long log is worth more than the last line of it.
 */
private class RetainingOutputStream(
    private val limit: Int,
    private val sink: ((ByteArray, Int, Int) -> Unit)?
) : OutputStream() {

    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
        if (buffer.size() < limit) buffer.write(b)
        sink?.invoke(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = limit - buffer.size()
        if (room > 0) buffer.write(b, off, minOf(room, len))
        sink?.invoke(b, off, len)
    }

    fun text(): String = buffer.toString(StandardCharsets.UTF_8)
}

/**
 * sshd's single host-key decision point, wired to [KnownHostsStore].
 *
 * An explicit object rather than a lambda because the policy is the security
 * boundary of the whole client: being able to read it in one screenful, and to
 * test it without a live socket, is worth the two extra lines over SAM
 * conversion. [verifyServerKey] has no branch that returns true for a key that
 * was not either recorded already or recorded now under TOFU.
 */
internal class HostKeyVerifier(
    private val profile: ConnectionProfile,
    private val knownHosts: KnownHostsStore
) : ServerKeyVerifier {

    override fun verifyServerKey(
        session: ClientSession,
        remoteAddress: java.net.SocketAddress,
        serverKey: java.security.PublicKey
    ): Boolean = when (val verdict = knownHosts.verify(profile.id, serverKey)) {
        is HostKeyVerdict.Trusted -> true
        is HostKeyVerdict.Mismatch ->
            throw SshFailureException(SshFailure.HostKeyMismatch(verdict.expected, verdict.actual))
        is HostKeyVerdict.Unknown -> when (profile.hostKeyPolicy) {
            HostKeyPolicy.STRICT ->
                throw SshFailureException(SshFailure.UnknownHost(verdict.fingerprint))
            HostKeyPolicy.TOUF -> {
                knownHosts.record(profile.id, serverKey)
                Log.i(
                    TAG,
                    "first use of ${profile.endpoint}: recorded ${verdict.keyType} ${verdict.fingerprint}"
                )
                true
            }
        }
    }

    private companion object {
        const val TAG = "EarthQuackSsh"
    }
}