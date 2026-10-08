package com.example.earthquack.ssh

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists [ConnectionProfile]s.
 *
 * ## Why JSON in SharedPreferences rather than a Room table
 *
 * The list is a handful of rows, read on every screen resume and written only
 * from the profile editor. A database would add a dependency, a migration and
 * a schema file for no query that this does not already answer in memory. If
 * the list ever grows to dozens of hosts, or needs querying, this class is the
 * only thing that changes — the callers hold `List<ConnectionProfile>` and do
 * not know where it came from.
 *
 * ## Credentials
 *
 * Profiles reference secrets by alias. Nothing written here is sensitive, so
 * this file is safe to read while debugging. That is deliberate: it is what
 * makes it acceptable to log a profile.
 */
class ConnectionProfileStore(context: Context) {

    private companion object {
        const val PREFS = "earthquack_connections"
        const val KEY_PROFILES = "profiles"
        const val KEY_VERSION = "version"

        /** Bumped only if the JSON shape changes incompatibly. */
        const val CURRENT_VERSION = 1
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every stored profile, in insertion order. */
    fun list(): List<ConnectionProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return parse(raw)
    }

    fun get(id: String): ConnectionProfile? = list().firstOrNull { it.id == id }

    /**
     * Inserts or replaces [profile].
     *
     * @return true when written. An invalid profile is rejected here rather
     *   than saved, because an invalid profile is a connection that cannot
     *   work and would only fail later with a timeout.
     */
    fun upsert(profile: ConnectionProfile): Boolean {
        if (!profile.isValid) return false
        val updated = list().filterNot { it.id == profile.id } + profile
        return write(updated)
    }

    /** Removes the profile with [id]. Also forgets its recorded host key. */
    fun delete(id: String, knownHosts: KnownHostsStore? = null) {
        write(list().filterNot { it.id == id })
        knownHosts?.forget(id)
    }

    private fun write(profiles: List<ConnectionProfile>): Boolean {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putString(KEY_PROFILES, array.toString())
            .apply()
        return true
    }

    // ── Serialisation ────────────────────────────────────────────────────────

    internal fun parse(raw: String): List<ConnectionProfile> {
        val array = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            // A corrupt blob is not worth crashing the Connections screen over,
            // and there is nothing to recover: these are user-entered records
            // that can be re-entered. Returning empty makes the failure visible
            // (the list is simply empty) instead of silent-but-wrong.
            return emptyList()
        }
        val out = mutableListOf<ConnectionProfile>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val profile = obj.toProfileOrNull()
            if (profile != null) out += profile
        }
        return out
    }

    private fun ConnectionProfile.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("auth", authMethod.name)
        put("key", identityKeyAlias ?: JSONObject.NULL)
        put("pw", passwordAlias ?: JSONObject.NULL)
        put("policy", hostKeyPolicy.name)
    }

    private fun JSONObject.toProfileOrNull(): ConnectionProfile? = try {
        ConnectionProfile(
            id = optString("id"),
            name = optString("name"),
            host = optString("host"),
            port = optInt("port", ConnectionProfile.DEFAULT_SSH_PORT),
            username = optString("username"),
            authMethod = AuthMethod.valueOf(optString("auth", AuthMethod.PUBLIC_KEY.name)),
            identityKeyAlias = optStringOrNull("key"),
            passwordAlias = optStringOrNull("pw"),
            hostKeyPolicy = HostKeyPolicy.valueOf(optString("policy", HostKeyPolicy.TOUF.name))
        ).takeIf { it.id.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
