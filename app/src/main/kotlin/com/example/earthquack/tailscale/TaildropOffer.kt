package com.example.earthquack.tailscale

import org.json.JSONArray

/**
 * An incoming Taildrop file offer surfaced by the embedded node.
 */
data class TaildropOffer(
    val id: String,
    val name: String,
    val size: Long,
    val peer: String,
) {
    companion object {
        fun parseList(json: String): List<TaildropOffer> {
            val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                TaildropOffer(
                    id = id,
                    name = o.optString("name"),
                    size = o.optLong("size", 0L),
                    peer = o.optString("peer"),
                )
            }
        }

        fun parseOne(json: String): TaildropOffer? {
            val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
            val id = o.optString("id")
            if (id.isEmpty()) return null
            return TaildropOffer(
                id = id,
                name = o.optString("name"),
                size = o.optLong("size", 0L),
                peer = o.optString("peer"),
            )
        }
    }
}