package com.example.earthquack.tailscale

import android.util.Log
import com.example.earthquack.storage.RcloneEngine

/**
 * RcloneTailnetSync — keeps rclone's remote configs aligned with the node's
 * SOCKS proxy. Standalone (no engine instance needed) because backends can
 * be ready long before any engine object exists, and the node may be brought
 * up by the login flow rather than the engine lifecycle.
 *
 * Rule: an SFTP remote whose host belongs to the tailnet gets `socks_proxy`
 * pointed at the embedded node's SOCKS5 listener; when the node is down the
 * setting is cleared so public remotes keep their normal route.
 */
object RcloneTailnetSync {

    private const val TAG = "RcloneTailnetSync"

    /**
     * Rewrites `socks_proxy` on every tailnet SFTP remote in [backend]'s
     * in-memory config. [socksPort] of 0/null clears the proxy.
     */
    suspend fun apply(backend: RcloneEngine, socksPort: Int?) {
        val dump = runCatching { backend.call("config/dump") }.getOrElse {
            Log.w(TAG, "config/dump failed", it)
            return
        }
        // config/dump returns a flat object: remote name -> its parameters.
        val names = dump.keys()
        while (names.hasNext()) {
            val name = names.next()
            val params = dump.optJSONObject(name) ?: continue
            val type = params.optString("type", "")
            val host = params.optString("host", "")
            if (type != "sftp" || host.isEmpty()) continue
            if (!TailnetRoute.isTailnetAddress(host)) continue
            // rclone's sftp backend passes this to a SOCKS5 dialer, which
            // wants a bare host:port — a URL form is rejected as an address.
            val wanted = if (socksPort == null || socksPort <= 0) "" else "127.0.0.1:$socksPort"
            if (params.optString("socks_proxy", "") == wanted) continue
            runCatching {
                backend.call(
                    "config/update",
                    org.json.JSONObject().put("name", name).put(
                        "parameters",
                        org.json.JSONObject().put("socks_proxy", wanted),
                    )
                )
            }.onSuccess {
                Log.i(TAG, "remote '$name': socks_proxy -> ${wanted.ifEmpty { "(none)" }}")
            }.onFailure { e ->
                Log.w(TAG, "remote '$name': socks_proxy update failed", e)
            }
        }
    }
}
