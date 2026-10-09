package com.example.earthquack.ssh

import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.apache.sshd.server.Environment
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.server.shell.ShellFactory

/**
 * A shell-capable sshd server, for the one test that needs a real remote shell.
 *
 * ## Why this is not [SftpServerEngine]
 *
 * The shipped server is SFTP-only on purpose — a shell channel is a way to
 * execute code on the device — so it never answers a `shell` request with a
 * running shell. The terminal cannot be exercised against it, and that is
 * precisely why the bug it shipped with survived: the client's input path was
 * never once driven against a server that would accept a keystroke.
 *
 * This is a test fixture, not production code. It lives in the test source set,
 * it binds an ephemeral port, and it runs one command: a real POSIX shell
 * reading commands from the channel.
 *
 * ## Portability
 *
 * `/bin/sh` is assumed present, which every CI image and every Android build
 * host satisfies. A JVM on a box without `/bin/sh` cannot host a real shell
 * process at all, and the alternative — a command handler that pretends to echo
 * what it was sent — would not prove that input executes, which is the entire
 * point of the test.
 */
internal object ShellTestServer {

    /** PTY mode bit sshd uses on the wire, as the client sends it. */
    private val ECHO = org.apache.sshd.common.channel.PtyMode.ECHO

    /**
     * What the server saw, so the test can assert the client actually asked for
     * a PTY rather than the server having quietly ignored the request.
     */
    class Requests {
        @Volatile var ptyTerm: String? = null
        @Volatile var ptyColumns: Int = 0
        @Volatile var ptyRows: Int = 0
        @Volatile var echoModeEnabled: Boolean = false
        val shellCount = AtomicInteger()

        fun record(channel: ChannelSession) {
            shellCount.incrementAndGet()
            val env: Environment = channel.environment
            ptyTerm = env.env[Environment.ENV_TERM]
            env.env[Environment.ENV_COLUMNS]?.toIntOrNull()?.let { ptyColumns = it }
            env.env[Environment.ENV_LINES]?.toIntOrNull()?.let { ptyRows = it }
            echoModeEnabled = env.ptyModes[ECHO] == 1
        }
    }

    /**
     * Wraps the real [ProcessShellFactory] so the PTY request can be observed
     * before the shell process is started.
     */
    private class RecordingShellFactory(
        private val delegate: ShellFactory,
        private val requests: Requests
    ) : ShellFactory {
        override fun createShell(channel: ChannelSession): Command {
            requests.record(channel)
            return delegate.createShell(channel)
        }
    }

    class Started(
        private val server: SshServer,
        val requests: Requests,
        val hostKey: PublicKey
    ) {
        val port: Int get() = server.port
        fun stop() = runCatching { server.stop(true) }
    }

    fun start(hostKeyPath: Path, acceptedPublicKey: PublicKey): Started {
        val requests = Requests()

        // sshd needs a user-home resolver on the JVM and the slot is
        // process-global, so both halves go through SshdEnvironment rather than
        // each setting it for itself.
        SshdEnvironment.ensureUserHome(hostKeyPath.parent.toFile())

        val ssh: SshServer = SshServer.setUpDefaultServer()
        ssh.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyPath).apply {
            algorithm = "EC"
            keySize = 256
        }
        ssh.port = 0
        ssh.publickeyAuthenticator = PublickeyAuthenticator { _, key, _ -> key == acceptedPublicKey }

        // sshd runs `command` as `/bin/sh -c <command>`, so the effective argv is
        // `/bin/sh -c /bin/sh`: a shell reading each line from the channel's
        // stdin and executing it. That is what makes an output marker evidence of
        // execution rather than of a transport that happens to round-trip bytes.
        ssh.shellFactory = RecordingShellFactory(
            ProcessShellFactory("/bin/sh", "-c", "/bin/sh"),
            requests
        )

        ssh.start()
        return Started(ssh, requests, ssh.keyPairProvider.loadKeys(null).first().public)
    }

    /** A fresh client key, so each connection authenticates with its own. */
    fun clientKeyPair(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }
}

/**
 * A deterministic sink for the bytes a shell session produces.
 *
 * Blocks the producer exactly as the real UI callback does — it just records
 * instead of parsing — and lets a test wait for a line rather than sleep.
 */
internal class ShellOutputSink : (ByteArray, Int, Int) -> Unit {

    private val lock = Object()
    private val text = StringBuilder()

    /** How many times the callback ran. */
    val chunks = AtomicInteger()

    override fun invoke(data: ByteArray, offset: Int, len: Int) {
        chunks.incrementAndGet()
        val piece = String(data, offset, len, Charsets.UTF_8)
        synchronized(lock) {
            text.append(piece)
            lock.notifyAll()
        }
    }

    fun snapshot(): String = synchronized(lock) { text.toString() }

    /** Waits until [predicate] holds for everything received, or the timeout. */
    fun await(timeoutMillis: Long, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            while (!predicate(text.toString())) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                runCatching { TimeUnit.NANOSECONDS.timedWait(lock, remaining) }
            }
            return text.toString()
        }
    }

    /** Blocks until [line] arrives as a line of its own. */
    fun awaitLine(line: String, timeoutMillis: Long): String =
        await(timeoutMillis) { hasExactLine(it, line) }
}

/**
 * Whether [line] appears as a whole line.
 *
 * This is the assertion that separates "the remote ran the command" from "the
 * PTY echoed what was typed". A pty echoes the submission verbatim, so it emits
 * `echo <marker>` and cannot produce a line that is only `<marker>`.
 */
internal fun hasExactLine(text: String, line: String): Boolean =
    text.split('\n').any { it.trimEnd('\r') == line }

/**
 * The thread names sshd uses for its per-channel stdin pump.
 *
 * The shell no longer uses one — it writes to the channel directly — so the test
 * asserts none exist, which is what "no worker left blocked on input" means for
 * this code.
 */
internal fun inputPumpThreads(): List<String> =
    Thread.getAllStackTraces().keys
        .map { it.name }
        .filter { it.startsWith("ClientInputStreamPump") }
