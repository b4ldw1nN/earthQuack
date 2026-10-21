package com.example.earthquack.tailscale

import org.json.JSONArray

data class TaildriveShare(
    val peer: String,
    val name: String,
    val path: String,
) {
    companion object {
        fun parseList(json: String): List<TaildriveShare> {
            val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TaildriveShare(
                    peer = o.optString("peer"),
                    name = o.optString("name"),
                    path = o.optString("path"),
                )
            }
        }
    }
}