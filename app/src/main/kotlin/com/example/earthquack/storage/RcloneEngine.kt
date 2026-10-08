package com.example.earthquack.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * The app's single entry point to rclone.
 *
 * ```text
 * typed Kotlin (RemoteManager, StorageProvider)
 *        ↓  JSON RPC method + params
 *    RcloneEngine          ← this class: lifecycle + error handling
 *        ↓
 *    RcloneBridge          ← native, or a fake in tests
 *        ↓  gomobile
 *      libgojni.so → rclone
 * ```
 *
 * Callers pass a method name and a JSON object; they get a parsed
 * [JSONObject] back. The protocol is rclone's documented JSON RPC
 * (https://rclone.org/rc/) — versioned, stable, and the same one rclone's own
 * HTTP server speaks.
 *
 * Every call is a suspend function dispatched to IO. The native call is a
 * blocking cgo call; running it on the main thread would freeze the UI for the
 * duration of a directory listing or a remote round trip.
 *
 * @param bridge the native surface; swapped for a fake in unit tests.
 */
class RcloneEngine(
    private val bridge: RcloneBridge = NativeRcloneBridge()
) {

    private companion object {
        /** Matches upstream librclone.Finalize's documented behaviour. */
        const val TAG = "RcloneEngine"
    }

    @Volatile
    private var ready = false

    /** True once [initialize] has completed successfully. */
    val isInitialised: Boolean get() = ready

    /**
     * Initialises rclone in this process. Safe to call repeatedly.
     *
     * [configPath] must point at a `rclone.conf` in app-private storage, or be
     * empty for in-memory config. Pass "" only in tests, or where the user has
     * genuinely asked for a session that cannot persist remotes.
     *
     * Must happen before the first RPC. The path is handed to rclone's own
     * `config.SetConfigPath`, so nothing depends on `RCLONE_CONFIG`, `$HOME`
     * or the working directory.
     */
    fun initialize(configPath: String = "") {
        if (ready) return
        synchronized(this) {
            if (ready) return
            bridge.initialize(configPath)
            ready = true
        }
    }

    /**
     * Performs one RPC call and returns the parsed reply.
     *
     * @param method an RC method, e.g. `core/version` or `operations/list`.
     * @param params method parameters; an empty object for methods that take none.
     * @throws RcloneException if rclone reports a non-200 status, if the reply
     *   is not a JSON object, or if rclone has not been initialised.
     */
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            if (!ready) {
                throw RcloneException(method, 0, "", "rclone not initialised")
            }
            // rclone tolerates an empty body, but sending "{}" is unambiguous
            // and keeps request-building on the Kotlin side uniform.
            val input = params.toString()
            val result = bridge.rpc(method, input)

            if (!result.isSuccess) {
                // Keep rclone's own envelope: it distinguishes "no such
                // remote" from "permission denied", and flattening that into
                // a string throws away information the caller needs.
                throw RcloneException(
                    method = method,
                    status = result.status,
                    rcloneOutput = result.output,
                    message = describe(method, result)
                )
            }

            try {
                JSONObject(result.output)
            } catch (e: JSONException) {
                // A 200 with unparseable output means something is wrong
                // between us and rclone, not with the caller's parameters.
                throw RcloneException(
                    method = method,
                    status = result.status,
                    rcloneOutput = result.output,
                    message = "rclone returned a non-JSON reply for $method: ${result.output.take(200)}"
                )
            }
        }

    /**
     * Performs one RPC call and returns the raw reply without throwing on a
     * non-200 status.
     *
     * Useful for calls where a failure is an expected outcome rather than an
     * error — probing whether a remote exists, for instance.
     */
    suspend fun callRaw(method: String, params: JSONObject = JSONObject()): RcloneResult =
        withContext(Dispatchers.IO) {
            if (!ready) throw RcloneException(method, 0, "", "rclone not initialised")
            bridge.rpc(method, params.toString())
        }

    /**
     * Shuts rclone down.
     *
     * Important: this does **not** cancel in-flight jobs. Upstream's Finalize
     * is a garbage collection hint and nothing more, so any running transfer
     * is abandoned when the process exits. Callers that care must cancel jobs
     * explicitly (`core/stop`) before calling this.
     */
    fun shutdown() {
        if (!ready) return
        synchronized(this) {
            if (!ready) return
            bridge.shutdown()
            ready = false
        }
    }

    /**
     * Builds a human-readable message from rclone's error envelope.
     *
     * rclone returns `{"error": "...", "path": "...", "status": N}` on
     * failure, so the useful message is in `error`, not in the raw body.
     */
    private fun describe(method: String, result: RcloneResult): String {
        val detail = try {
            JSONObject(result.output).optString("error").ifEmpty { null }
        } catch (_: JSONException) {
            null
        }
        return buildString {
            append("rclone RPC ")
            append(method)
            append(" failed with status ")
            append(result.status)
            if (detail != null) {
                append(": ")
                append(detail)
            }
        }
    }
}
