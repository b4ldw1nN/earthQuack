package com.example.earthquack.ssh

import org.apache.sshd.common.util.io.PathUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one place sshd's process-global state is configured.
 *
 * ## Why this cannot live in either caller
 *
 * `org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver` writes a
 * `static` field. It is not per-`SshClient` and not per-`SshServer`; there is
 * exactly one in the process, and the last writer wins for every subsequent
 * sshd call.
 *
 * Both of this app's sshd entry points used to write it:
 *
 *  - `SshConnectionFactory`'s companion `init` set it to
 *    `Paths.get(System.getProperty("user.dir"))`, which on Android is `/`.
 *  - `SftpServerEngine.buildServer` set it to the served SFTP root, on every
 *    (re)start.
 *
 * So the value depended on which ran last. Start the SFTP server, then open a
 * terminal, and the client's home directory became the served root; restart the
 * server and it became the root again. Nothing failed loudly — sshd reads this
 * lazily, deep inside key and default-path resolution — which is what made it
 * worth centralising rather than just picking one of the two.
 *
 * ## The rule
 *
 * Set exactly once, to a stable app-private directory, and never overwrite it.
 * A file server's root directory is *not* a sensible process-wide home: it is
 * user-configurable and it is meant to be reachable, whereas this slot decides
 * where sshd looks for client-side key material.
 *
 * The server does not depend on this at all: [com.example.earthquack.sftp.fs.AndroidFileSystemFactory.getUserHomeDir]
 * returns the served root for every SFTP session, which is what actually sets a
 * client's start directory.
 */
object SshdEnvironment {

    private val initialised = AtomicBoolean(false)

    /**
     * Installs the resolver, once.
     *
     * @param appHomeDir an app-private directory used as the fallback home. Any
     *   directory works; callers should pass their `filesDir` so the value is
     *   stable for the life of the install.
     */
    fun ensureUserHome(appHomeDir: File) {
        if (!initialised.compareAndSet(false, true)) return
        val home = runCatching { appHomeDir.canonicalFile }.getOrDefault(appHomeDir)
        PathUtils.setUserHomeFolderResolver { home.toPath() }
    }

    /**
     * Whether [ensureUserHome] has run.
     *
     * Exposed so a test can assert the invariant rather than infer it from
     * whether a connection happened to work.
     */
    val isInitialised: Boolean get() = initialised.get()

    /**
     * Resets the guard. Test-only.
     *
     * Does not restore `PathUtils` — that field has no public setter for its
     * previous value — so a test that calls this must be the only thing in the
     * JVM that cares about the home directory.
     */
    internal fun resetForTesting() {
        initialised.set(false)
    }
}