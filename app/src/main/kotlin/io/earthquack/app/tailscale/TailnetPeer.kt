package io.earthquack.app.tailscale

import org.json.JSONObject

/**
 * A tailnet peer, parsed from the Go shim's `Peers()` JSON.
 *
 * `id` is the peer's **StableNodeID** — the same ID space
 * `SetExitNode(peerID)` expects and `Prefs.ExitNodeID` stores. Do not
 * substitute the transient NodeID; the two are disjoint and a NodeID will
 * never match in EditPrefs.
 *
 * @property exitNodeOption true when the peer offers exit-node service
 *   (advertised *and* approved in the tailnet policy).
 * @property isExitNode true when this peer is **our** current exit node.
 */
data class TailnetPeer(
    val id: String,
    val name: String,
    val ip: String,
    val os: String,
    val online: Boolean,
    /** The peer's MagicDNS name, which survives an address change. */
    val dnsName: String = "",
    val exitNodeOption: Boolean = false,
    val isExitNode: Boolean = false,
) {
    companion object {
        /**
         * Parse the shim's peer array.
         *
         * Malformed input yields an empty list rather than a throw: a peer
         * list is best-effort UI data, and the caller's worst case should be
         * "picker shows nothing", not a crash. Entries missing an `id` are
         * skipped — an entry you cannot act on is not a peer.
         */
        fun parseList(json: String): List<TailnetPeer> {
            val arr = runCatching { org.json.JSONArray(json) }.getOrElse { return emptyList() }
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                TailnetPeer(
                    id = id,
                    name = o.optString("name"),
                    ip = o.optString("ip"),
                    os = o.optString("os"),
                    online = o.optBoolean("online"),
                    dnsName = o.optString("dns_name").trimEnd('.'),
                    exitNodeOption = o.optBoolean("exit_node_option"),
                    isExitNode = o.optBoolean("exit_node"),
                )
            }
        }

        /** Single-peer parse ([ThisDevice()]-shaped objects use different keys; for peer entries). */
        internal fun parseOne(json: String): TailnetPeer? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val id = o.optString("id")
            if (id.isEmpty()) return null
            return TailnetPeer(
                id = id,
                name = o.optString("name"),
                ip = o.optString("ip"),
                os = o.optString("os"),
                online = o.optBoolean("online"),
                exitNodeOption = o.optBoolean("exit_node_option"),
                isExitNode = o.optBoolean("exit_node"),
            )
        }
    }
}
