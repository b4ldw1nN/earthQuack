package com.example.earthquack.sftp

import android.content.Context
import com.example.earthquack.ssh.KeystoreSecretStore
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

    /** Drops the reference, e.g. after a stop. The engine object itself is gone. */
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
        SftpServerService.start(appContext, settings)

        // The service starts the engine asynchronously; wait for it to be
        // listening so the status the user sees is the status that is true.
        val engine = SftpServerHolder.engine(appContext)
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (engine.isRunning()) {
                return SftpServerStatus.Running(engine.serverPort(), engine.connectedClients())
            }
            kotlinx.coroutines.delay(POLL_MILLIS)
        }
        return SftpServerStatus.Error(
            "The server did not report as listening within ${START_TIMEOUT_MILLIS / 1000}s. " +
                "Check that port ${settings.port} is free."
        )
    }

    override suspend fun stop(): SftpServerStatus {
        SftpServerService.stop(appContext)
        val engine = runCatching { SftpServerHolder.engine(appContext) }.getOrNull()
        engine?.stop()
        SftpServerHolder.clear()
        return SftpServerStatus.Stopped
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 100L
    }
}
