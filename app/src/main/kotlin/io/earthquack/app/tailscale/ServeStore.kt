package io.earthquack.app.tailscale

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ServeStore — the serves the user asked for, kept across restarts.
 *
 * A serve lives in the node, and the node dies with the process. Without
 * this, every app restart silently unpublishes the ports the user exposed,
 * and the phone looks unreachable to the desktop again. The store is the
 * user's intent; the node is the current state of it.
 */
class ServeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun entries(): List<ServeEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                ServeEntry(
                    localPort = o.optInt("localPort"),
                    tailnetPort = o.optInt("tailnetPort"),
                    tls = o.optBoolean("tls"),
                    funnel = o.optBoolean("funnel"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<ServeEntry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("localPort", e.localPort)
                    .put("tailnetPort", e.tailnetPort)
                    .put("tls", e.tls)
                    .put("funnel", e.funnel)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "earthquack_tailscale_serves"
        const val KEY_ENTRIES = "entries"
    }
}
