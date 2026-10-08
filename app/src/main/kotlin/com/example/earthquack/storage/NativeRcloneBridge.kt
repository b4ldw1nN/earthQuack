package com.example.earthquack.storage

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only thing in the app that talks to native code.
 *
 * Responsibilities are deliberately narrow: load the library, forward three
 * calls, and turn gomobile's shapes into ours. Every decision about *what* to
 * call lives above this class.
 *
 * ## Threading
 *
 * gomobile attaches the calling thread to the Go runtime on demand via a
 * generated `Seq.touch()`. Synchronous calls like these are therefore safe
 * from any thread, but they are not free: the first call on a thread pays a
 * runtime attach. That is another reason callers use [RcloneEngine]'s suspend
 * API on an IO dispatcher rather than the raw bridge on the main thread.
 *
 * ## Concurrency
 *
 * No lock here. rclone's RPC layer is built to serve many simultaneous
 * callers — that is exactly what its rcserver does — so serialising at the JNI
 * boundary would only hide real behaviour. [RcloneEngine] is responsible for
 * not blocking the caller's thread.
 */
class NativeRcloneBridge : RcloneBridge {

    companion object {
        private const val TAG = "NativeRcloneBridge"

        /**
         * Name of the gomobile-produced library. `gomobile bind` always emits
         * `libgojni.so`, regardless of the Go package name.
         */
        private const val LIB_NAME = "gojni"

        /**
         * Guards the static initialiser. Class-load races are real when two
         * threads touch the engine at once (a service and the UI), and a
         * double `loadLibrary` would throw.
         */
        private val loadLock = Object()
        private var loaded = false

        /**
         * Loads `libgojni.so`, or throws if it is missing.
         *
         * A missing library is almost always one of two build mistakes, and
         * the message says which, because the symptom otherwise is just
         * "UnsatisfiedLinkError" with no context:
         *   - `rclone-android/` was not built (no `app/libs/rclone.aar`)
         *   - the device ABI does not match the ABIs in that AAR
         */
        private fun ensureLoaded() {
            if (loaded) return
            synchronized(loadLock) {
                if (loaded) return
                try {
                    System.loadLibrary(LIB_NAME)
                } catch (e: UnsatisfiedLinkError) {
                    throw UnsatisfiedLinkError(
                        "Failed to load lib$LIB_NAME.so. Build rclone-android and verify " +
                            "app/libs/rclone.aar exists and contains an ABI matching this " +
                            "device. Cause: ${e.message}"
                    )
                }
                loaded = true
                Log.i(TAG, "lib$LIB_NAME.so loaded")
            }
        }
    }

    /** True once the native library is loaded in this process. */
    val isLoaded: Boolean get() = loaded

    @Volatile
    private var initialised = AtomicBoolean(false)

    override fun initialize(configPath: String) {
        ensureLoaded()
        if (!initialised.compareAndSet(false, true)) {
            // Idempotent by contract: rclone's Initialize is logger setup,
            // config handler registration and accounting start. Repeating it
            // is harmless but pointless, and callers should not have to know.
            Log.d(TAG, "initialize() called more than once; ignoring")
            return
        }
        try {
            // Hands the path to rclone's own config.SetConfigPath. An empty
            // path means in-memory config with nothing persisted.
            com.example.earthquack.rclone.Rclone.initialize(configPath)
            Log.i(
                TAG,
                "rclone initialised in-process (config=${configPath.ifEmpty { "in-memory" }})"
            )
        } catch (e: Exception) {
            // Roll back, or every later RPC fails with a misleading
            // "used before initialize" instead of the real cause.
            initialised.set(false)
            throw IllegalStateException(
                "rclone failed to initialise with config " +
                    "'${configPath.ifEmpty { "<in-memory>" }}': ${e.message}",
                e
            )
        }
    }

    override fun rpc(method: String, input: String): RcloneResult {
        ensureLoaded()
        if (!initialised.get()) {
            // rclone would otherwise fail deep inside with a confusing error
            // about config or the jobs registry.
            throw IllegalStateException("rclone used before initialize(); call RcloneEngine.initialize() first")
        }
        val raw = com.example.earthquack.rclone.Rclone.rpc(method, input)
        // gomobile widens Go's int to a Java long; narrow it back.
        return RcloneResult(raw.status.toInt(), raw.output)
    }

    override fun shutdown() {
        if (!initialised.compareAndSet(true, false)) return
        // Upstream Finalize is only a GC: it does NOT cancel in-flight jobs.
        // Callers must cancel via core/stop if they need a clean drain.
        com.example.earthquack.rclone.Rclone.shutdown()
        Log.i(TAG, "rclone shutdown requested")
    }
}
