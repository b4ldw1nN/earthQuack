package com.example.earthquack.sftp

import android.content.Context
import com.example.earthquack.ssh.KeystoreSecretStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Process-wide access to the one [SftpServerEngine].
 *
 * The engine is a plain object with no Android lifecycle of its own, so it can
 * be started by the service and read by the UI without either holding the
 * other. This holder is the only place the reference lives, which is what makes
 * "is the server running?" a question with one answer rather than two.
 *
 * Not a dependency-injection framework: there is exactly one server, one
 * settings store and one set of keys per process, and a singleton is the honest
 * model of that.
 */
object SftpServerHolder {

    @Volatile
    private var cached: SftpServerEngine? = null

    /** The engine, building it on first use. */
    fun engine(context: Context): SftpServerEngine {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }
    }

    /**
     * Drops the reference, so the next [engine] call builds a new one.
     *
     * Only safe when nothing is listening. It is deliberately *not* called on
     * the stop path: `SftpServerService.onDestroy` resolves the engine through
     * this holder, so clearing it there would make a late `onDestroy` build and
     * stop a brand-new engine while the server it was actually meant to tear
     * down stayed bound. There is one engine per process for the life of the
     * process, and that is the honest model.
     */
    @Deprecated("Dropping the engine orphans whatever it is listening on; stop it first.")
    fun clear() {
        cached = null
    }

    private fun build(context: Context): SftpServerEngine = SftpServerEngine(
        filesDir = context.filesDir,
        secrets = KeystoreSecretStore(context.filesDir),
        hostKeyStore = HostKeyStore(context),
        authorizedKeys = AuthorizedKeysStore(context)
    )
}

/**
 * The [SftpServerController] the SFTP screen is already wired to.
 *
 * Implements the seam that [UnavailableSftpController] currently fills, so the
 * screen's code does not change: it calls [status], [start] and [stop] and
 * renders whatever comes back. The difference is that these now talk to a real
 * server.
 *
 * ## Threading
 *
 * [status] is synchronous and safe from the UI thread. [start] and [stop] are
 * suspending because they cross into the service; the fragment already calls
 * them from `lifecycleScope`.
 */
class SftpServerControllerImpl(
    private val context: Context
) : SftpServerController {

    private val appContext: Context = context.applicationContext

    override fun status(): SftpServerStatus {
        val engine = runCatching { SftpServerHolder.engine(appContext) }.getOrNull()
            ?: return SftpServerStatus.Stopped
        return if (engine.isRunning()) {
            SftpServerStatus.Running(
                port = engine.serverPort(),
                connectedClients = engine.connectedClients()
            )
        } else {
            SftpServerStatus.Stopped
        }
    }

    override suspend fun start(settings: SftpSettings): SftpServerStatus {
        val problems = settings.problems()
        if (problems.isNotEmpty()) {
            return SftpServerStatus.Error(problems.first())
        }

        // Start the service first: it owns the foreground notification, and a
        // server that is listening but not foregrounded would be killed within
        // seconds on Android 14.
        //
        // Then wait for the outcome the service actually reached, rather than
        // polling `isRunning()`. Polling cannot tell a port change from a
        // no-op: the old server is still running when the new request is
        // queued, so the first poll answers "running" and returns the old port,
        // which is the bug that made a saved port change appear to do nothing.
        val future = SftpServerService.requestStart(appContext, settings)
        return try {
            withTimeout(START_TIMEOUT_MILLIS) { future.await() }
        } catch (e: TimeoutCancellationException) {
            // The service never reported. Ask the engine what it actually
            // thinks rather than assuming either way.
            val engine = SftpServerHolder.engine(appContext)
            if (engine.matches(settings)) {
                SftpServerStatus.Running(engine.serverPort(), engine.connectedClients())
            } else {
                SftpServerStatus.Error(
                    "The server did not report as listening within " +
                        "${START_TIMEOUT_MILLIS / 1000}s. " +
                        "Check that port ${settings.port} is free."
                )
            }
        }
    }

    override suspend fun stop(): SftpServerStatus {
        // Stop the listener here rather than only asking the service to, so the
        // status returned to the caller is one that has already happened. The
        // service's own ACTION_STOP handler is then idempotent.
        val engine = runCatching { SftpServerHolder.engine(appContext) }.getOrNull()
        engine?.stop()
        SftpServerService.stop(appContext)
        return SftpServerStatus.Stopped
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 20_000L
    }
}
