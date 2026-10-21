package com.example.earthquack.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RcloneEngines — one initialised rclone per process.
 *
 * `RcloneEngine.initialize()` loads the native library and the config file.
 * That is expensive: measured on a mid-range device, each call blocked the
 * main thread for a third of a second to a second. The native library is
 * global anyway, so initialising it again per screen bought nothing and
 * cost the user a visible stall on every screen that touched a remote.
 *
 * The engine is created once, off the main thread, and shared.
 */
object RcloneEngines {

    @Volatile
    private var engine: RcloneEngine? = null

    /** The shared engine, initialised on the calling coroutine's IO thread. */
    suspend fun get(context: Context): RcloneEngine = withContext(Dispatchers.IO) {
        getBlocking(RcloneConfigManager(context.applicationContext).configPath)
    }

    /**
     * The shared engine, initialised on the caller's thread.
     *
     * Only for callers that are already off the main thread; everything
     * else should use [get].
     */
    fun getBlocking(configPath: String): RcloneEngine {
        engine?.let { return it }
        return synchronized(this) {
            engine ?: RcloneEngine().also { created ->
                created.initialize(configPath)
                engine = created
            }
        }
    }

    /**
     * Drops the shared engine.
     *
     * Used when the config file is replaced, so the next call reads the new
     * file rather than the one that was loaded at startup.
     */
    fun reset() {
        synchronized(this) {
            runCatching { engine?.shutdown() }
            engine = null
        }
    }
}
