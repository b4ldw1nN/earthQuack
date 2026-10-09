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
    /** Key material. See [ClientIdentityProvider]. */
    private val identityKeys: ClientIdentityProvider,
    private val secrets: SecretStore,
    /** Host-key trust. See [HostKeyTrust]. */
    private val knownHosts: HostKeyTrust,
    private val filesDir: java.io.File,
    private val timeouts: SshTimeouts = SshTimeouts()
) {

    private companion object {
        const val TAG = "EarthQuackSsh"

        /**
         * How far to walk a cause chain.
         *
         * Bounded because a cycle in `cause` would otherwise hang the connect
         * path; sshd's real chains are three or four deep.
         */
        const val MAX_CAUSE_DEPTH = 12
    }

    init {
        // sshd needs a user-home resolver on Android, which has no $HOME. It is
        // process-global, so it is installed once, centrally — see
        // [SshdEnvironment] for why neither this class nor the SFTP server may
        // set it for itself.
        SshdEnvironment.ensureUserHome(filesDir)
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
            // A host-key verdict is delivered through the auth future, because
            // sshd asks the ServerKeyVerifier during key exchange, which auth
            // awaits. Wrapping it here as "authentication failed" lost that
            // distinction entirely: a changed host key was reported as bad
            // credentials, which is precisely the wrong place to send someone.
            // The structured failure is rethrown so the host-key error survives.
            val structured = rootCause<SshFailureException>(e)
            if (structured != null) {
                throw structured
            }
            throw SshFailureException(
                SshFailure.AuthFailed(
                    profile.authMethod,
                    e.message ?: "the server rejected the credentials"
                )
            )
        }
        // Nothing else here, and deliberately so. See the comment in connect().
    }

    /** The first [T] in [e]'s cause chain, or null. */
    private inline fun <reified T : Throwable> rootCause(e: Throwable): T? {
        var current: Throwable? = e
        repeat(MAX_CAUSE_DEPTH) {
            when (current) {
                is T -> return current
                null -> return null
                else -> current = current.cause
            }
        }
        return null
    }

    /**
     * Maps exceptions onto the failure type the UI can act on.
     *
     * Ordering matters: a timeout and a refused connection are both IO
     * exceptions and the difference between them is the entire message.
     *
     * The whole cause chain is walked, because sshd buries the interesting
     * exception: a rejected host key arrives as an `SshException` wrapping a
     * `UserAuthException` wrapping the real cause, and classifying only the
     * outer type turns "the host key changed" into "unexpected error". Every
     * chain is also logged in full so a report from a device shows what
     * actually happened rather than the outermost wrapper.
     */
    private fun classify(e: Throwable): SshFailure {
        val chain = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        Log.w(TAG, "connect failed: ${chain.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }}")

        // Innermost first: the root cause describes the failure better than the
        // wrapper, so `timeout` must be recognised under `SshException` too.
        val leafFirst = chain.asReversed()
        for (candidate in leafFirst) {
            // A structured failure already decided the reason; it must win over
            // any wrapper the SSH layer added on the way out.
            if (candidate is SshFailureException) return candidate.failure
        }
        for (candidate in leafFirst) {
            when (candidate) {
                is SocketTimeoutException -> return SshFailure.Timeout
                is java.util.concurrent.TimeoutException -> return SshFailure.Timeout
                is UnknownHostException -> return SshFailure.Network("no such host")
                is ConnectException -> return SshFailure.Network(
                    "connection refused — is the host up and sshd listening?"
                )
                is java.net.PortUnreachableException -> return SshFailure.Network(
                    "no route to that port"
                )
                is SshException -> return classifySsh(candidate, chain)
                is SocketException -> return SshFailure.Network(candidate.message ?: "socket error")
            }
        }
        // An IOException whose message says timeout: sshd has no dedicated
        // timeout exception in this line, so a failed verify() surfaces as one.
        chain.firstOrNull { it.message?.contains("imeout", ignoreCase = true) == true }
            ?.let { return SshFailure.Timeout }

        val root = leafFirst.firstOrNull()
        return SshFailure.Unexpected(
            buildString {
                append(root?.message ?: root?.javaClass?.simpleName ?: "unknown")
                // The outer wrappers add nothing for a user but everything for a
                // bug report, so they are appended rather than discarded.
                chain.drop(1).forEach { append(" (caused by ${it.javaClass.simpleName}: ${it.message})") }
            }
        )
    }

    /** Distinguishes the sshd exceptions the UI can say something useful about. */
    private fun classifySsh(e: SshException, chain: List<Throwable>): SshFailure {
        val text = chain.joinToString(" ") { it.message ?: "" }
        return when {
            // Host-key rejection arrives as a plain SshException from
            // ServerKeyVerifier; the marker is the verifier's own message.
            text.contains("key", ignoreCase = true) &&
                (text.contains("host", ignoreCase = true) || text.contains("verify", ignoreCase = true)) ->
                SshFailure.Unexpected("the server's host key was rejected: ${e.message}")
            text.contains("auth", ignoreCase = true) ->
                SshFailure.Unexpected("the server refused the session: ${e.message}")
            text.contains("imeout", ignoreCase = true) -> SshFailure.Timeout
            else -> SshFailure.Unexpected(e.message ?: "ssh error")
        }
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
     *
     * @throws IOException when the server declines the channel. The session is
     *   still authenticated at that point; only this channel is unavailable.
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
            val future = channel.open()
            future.verify(CHANNEL_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            // Close the half-open channel before reporting. Leaving it to the
            // finaliser keeps a window descriptor (a PTY on most servers) alive
            // for an unknown time after the screen is gone.
            runCatching { channel.close() }
            runCatching { stdin.close() }
            throw IOException(shellUnavailableReason(e), e)
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

        // The future is verified, not discarded.
        //
        // A discarded `channel.open()` is where a refused exec used to vanish: the
        // server answers with a channel-open failure, delivered through that
        // future, and with it dropped `waitFor` returns normally and `exitStatus`
        // is null — so "the server would not run this" was reported as a command
        // that ran and produced no output. Verifying it makes that a failure.
        channel.open().verify(CHANNEL_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // The open has already succeeded, so this is only the wait for the
        // command to finish.
        channel.waitFor(WAIT_FOR_CHANNEL_EVENTS, timeoutMillis)
        val status = channel.exitStatus
            ?: throw IOException(
                "the command did not report an exit status within ${timeoutMillis}ms; " +
                    "it may still be running"
            )

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

        /**
         * Explains a failed shell channel in terms the user can act on.
         *
         * A denied shell is not an authentication failure and not a broken
         * connection: the session is authenticated and open, the server simply
         * declines this one channel type. Saying so is the whole point — the
         * same session may still serve SFTP perfectly.
         *
         * There is deliberately no exec fallback here. Running `bash -l` when
         * the shell request was refused ignores the account's configured shell,
         * ignores `ForceCommand`, and for an `internal-sftp` account it either
         * hangs or hands the user a shell the administrator withheld.
         */
        fun shellUnavailableReason(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.take(8)
                .joinToString(" ") { it.message ?: "" }
            return when {
                chain.contains("admin", ignoreCase = true) ||
                    chain.contains("prohibited", ignoreCase = true) ||
                    chain.contains("closed", ignoreCase = true) ->
                    "The server refused to open a shell for this account. " +
                        "That is normal for an SFTP-only account (ForceCommand " +
                        "internal-sftp, or a shell of nologin); use the file browser " +
                        "instead of the terminal."
                chain.contains("pty", ignoreCase = true) ||
                    chain.contains("pseudo-terminal", ignoreCase = true) ->
                    "The server refused the pseudo-terminal, so it cannot host an " +
                        "interactive session. Check PermitTTY in sshd_config."
                chain.contains("timeout", ignoreCase = true) ||
                    e is java.util.concurrent.TimeoutException ->
                    "The server did not answer the shell request within " +
                        "${CHANNEL_OPEN_TIMEOUT_MS / 1000}s."
                else -> "Could not open a shell on this session: " +
                    (chain.ifBlank { e.javaClass.simpleName })
            }
        }
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
    private val knownHosts: HostKeyTrust
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