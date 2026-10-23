package io.earthquack.app.storage

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * RcloneHealthProbe — asks rclone whether a remote actually works *now*.
 *
 * The remote list used to claim every remote was reachable, which is how an
 * expired Drive token or an unroutable tailnet host stayed invisible until
 * the user opened the remote and hit an error. A probe turns that into a fact
 * the list can show.
 *
 * The probe is deliberately cheap: `about` when the backend supports it (a
 * metadata call, no listing), otherwise a `stat` of the remote root. Neither
 * transfers file content, and both fail fast when credentials or routing are
 * broken — which is exactly the signal we want.
 */
object RcloneHealthProbe {

    private const val TAG = "RcloneHealth"

    /** Outcome of a probe, in the terms the UI can act on. */
    enum class State {
        /** Never probed, or a probe is in flight. */
        Unknown,

        /** The remote answered. */
        Ok,

        /** The remote answered, but refused: expired token, bad password, revoked key. */
        NeedsAuth,

        /** The remote could not be reached at all: routing, DNS, proxy, host down. */
        Unreachable,

        /** Something else went wrong. */
        Failed,

        /** The backend is not part of the rclone build bundled in the app. */
        NotSupported,

        /**
         * The remote works, but the thing it wraps is not there yet.
         *
         * A crypt remote points at a folder ("encrypted") inside a drive.
         * When that folder was never created, listing the crypt remote fails
         * with "directory not found" — which looks like a broken token but
         * is not one.
         */
        NotSetUp,
    }

    data class Health(val state: State, val detail: String? = null)

    /**
     * Probes [remote] through [engine].
     *
     * Never throws: a probe that fails is a result, not an error, so one bad
     * remote cannot take the whole list down.
     */
    suspend fun probe(engine: RcloneEngine, remote: String, type: String? = null): Health =
        withContext(Dispatchers.IO) {
            // `operations/list` is the one call every backend supports, and
            // depth 1 keeps it to the remote's top level. `about` is not
            // registered in the bundled rclone, so it cannot be used here.
            val attempt = runCatching {
                engine.call(
                    "operations/list",
                    // The trailing colon is what makes it a remote; without it
                    // rclone reads the name as a local path.
                    JSONObject().put("fs", "$remote:").put("remote", "")
                )
            }
            attempt.exceptionOrNull()?.let { error ->
                return@withContext classify(error.message ?: "unknown error", isCrypt = type == "crypt")
            }
            Health(State.Ok, null)
        }

    /**
     * Maps an rclone error message onto a state.
     *
     * The messages come from many backends with slightly different wording, so
     * this matches on the shapes that matter: an authorisation failure (the
     * user can fix it by re-authenticating) versus a transport failure (the
     * user can fix it by fixing the route or the host).
     */
    fun classify(message: String, isCrypt: Boolean = false): Health {
        val m = message.lowercase()
        val authMarkers = listOf(
            "unauthorized", "unauthorised", "authentication", "invalid credentials",
            "access denied", "permission denied", "token expired", "expired token",
            "invalid refresh token", "oauth", "401", "403",
        )
        val networkMarkers = listOf(
            "connection refused", "connection timed out", "i/o timeout", "no such host",
            "network is unreachable", "host is unreachable", "dial tcp", "socks connect",
            "no route to host", "connection reset", "tls handshake",
        )
        return when {
            authMarkers.any { it in m } -> Health(State.NeedsAuth, message)
            networkMarkers.any { it in m } -> Health(State.Unreachable, message)
            "didn't find backend called" in m || "unknown backend" in m ->
                Health(State.NotSupported, message)
            // A crypt remote lists the folder it wraps. That folder not
            // existing is a setup step, not a broken credential.
            isCrypt && "directory not found" in m -> Health(State.NotSetUp, message)
            else -> {
                Log.w(TAG, "remote probe failed: $message")
                Health(State.Failed, message)
            }
        }
    }
}
