package com.example.earthquack.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed access to rclone's remote configuration.
 *
 * Everything here is a thin, typed translation of documented rclone JSON-RPC
 * calls (https://rclone.org/rc/). Callers deal in names and parameters; they
 * never construct RPC method strings or parse rclone's envelopes.
 *
 * ```text
 * config/listremotes    list configured remote names
 * config/create          create a remote
 * config/update          change an existing remote
 * config/delete          remove a remote
 * config/providers       what rclone can configure, and with what keys
 * config/dump            the full config (secrets redacted by rclone)
 * ```
 */
class RcloneRemoteManager(private val engine: RcloneEngine) {

    private companion object {
        const val OP_LIST = "config/listremotes"
        const val OP_CREATE = "config/create"
        const val OP_UPDATE = "config/update"
        const val OP_DELETE = "config/delete"
        const val OP_PROVIDERS = "config/providers"
    }

    /**
     * Lists the names of every configured remote.
     *
     * An empty list is the expected result on a fresh install and is **not** an
     * error. rclone returns `{"remotes":[]}`.
     */
    suspend fun listRemotes(): List<String> {
        val reply = engine.call(OP_LIST)
        val arr = reply.optJSONArray("remotes") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { idx ->
            val entry = arr.opt(idx)
            when (entry) {
                is String -> entry
                // Newer rclone returns objects with a name field; accept both
                // so a server-side format change does not break us silently.
                is JSONObject -> entry.optString("name").ifEmpty { null }
                else -> null
            }
        }
    }

    /**
     * True when [name] is configured.
     */
    suspend fun hasRemote(name: String): Boolean = listRemotes().contains(name)

    /**
     * Creates a new remote.
     *
     * @param name remote name, i.e. the `[section]` in rclone.conf.
     * @param type rclone backend name, e.g. `sftp`, `drive`, `mega`.
     * @param params backend-specific key/value pairs, e.g. `host`, `user`.
     * @param nonInteractive must be true for an embedded app: it stops rclone
     *   trying to open a browser for an OAuth flow we cannot service. OAuth
     *   needs a deliberate flow of its own; see the storage design notes.
     */
    suspend fun createRemote(
        name: String,
        type: String,
        params: Map<String, String> = emptyMap(),
        nonInteractive: Boolean = true
    ): String {
        require(name.isNotBlank()) { "remote name must not be blank" }
        require(type.isNotBlank()) { "remote type must not be blank" }

        val reply = engine.call(OP_CREATE, remoteBody(name, type, params, nonInteractive))
        return reply.optString("name", name)
    }

    /**
     * Updates an existing remote. Same shape as [createRemote].
     */
    suspend fun updateRemote(
        name: String,
        params: Map<String, String>,
        nonInteractive: Boolean = true
    ): String {
        require(name.isNotBlank()) { "remote name must not be blank" }
        val reply = engine.call(OP_UPDATE, remoteBody(name, null, params, nonInteractive))
        return reply.optString("name", name)
    }

    /**
     * Builds the request body for config/create and config/update.
     *
     * Backend settings go inside a nested `parameters` object, not at the top
     * level. Flattening them fails with
     *
     *     400 Didn't find key "parameters" in input
     *
     * which is rclone's required-parameter check rather than anything about
     * the values themselves. Easy to get wrong, because the flattened shape
     * looks entirely plausible.
     */
    private fun remoteBody(
        name: String,
        type: String?,
        params: Map<String, String>,
        nonInteractive: Boolean
    ): JSONObject {
        val settings = JSONObject()
        params.forEach { (k, v) -> settings.put(k, v) }

        return JSONObject()
            .put("name", name)
            .put("parameters", settings)
            .put("nonInteractive", nonInteractive)
            .apply { if (type != null) put("type", type) }
    }

    /**
     * Deletes a remote.
     *
     * @return true when rclone confirmed the removal. A remote that was not
     *   there is reported as false rather than throwing, since the caller's
     *   intent ("this remote should not exist") is satisfied either way.
     */
    suspend fun deleteRemote(name: String): Boolean {
        require(name.isNotBlank()) { "remote name must not be blank" }
        return try {
            engine.call(OP_DELETE, JSONObject().put("name", name))
            true
        } catch (e: RcloneException) {
            // "couldn't find key ... in config file" means it was already gone.
            if (e.rcloneOutput.contains("config file", ignoreCase = true)) false
            else throw e
        }
    }

    /**
     * The backends this build of rclone can configure, and the settings each
     * one accepts.
     *
     * This is the authoritative list for the UI: because rclone registers
     * backends at link time, the set available on Android is a subset of
     * upstream's, and hard-coding provider names in the app would drift from
     * what is actually linked in.
     */
    suspend fun providers(): List<ProviderInfo> {
        val reply = engine.call(OP_PROVIDERS)
        val arr = reply.optJSONArray("providers") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            val settings = firstArray(o, "Settings", "settings")
            ProviderInfo(
                name = firstString(o, "Name", "name"),
                description = firstString(o, "Description", "description"),
                // rclone marshals its Go struct field names verbatim, so the
                // keys are capitalised ("Name", "Help", "Type"). Lower-case
                // fallbacks are kept so a future marshalling change degrades
                // to "still works" rather than "silently empty forms".
                settings = (0 until (settings?.length() ?: 0)).mapNotNull { s ->
                    val so = settings!!.optJSONObject(s) ?: return@mapNotNull null
                    ProviderSetting(
                        name = firstString(so, "Name", "name"),
                        type = firstString(so, "Type", "type"),
                        required = so.optBoolean("Required", so.optBoolean("required")),
                        // rclone calls it "Sensitive"; "Obscure" was my guess
                        // before reading fs.ConfigProvider.
                        obscure = so.optBoolean("Sensitive", so.optBoolean("obscure")),
                        help = firstString(so, "Help", "help")
                    )
                }
            )
        }
    }

    /** Names of every backend this build can configure. */
    suspend fun providerNames(): List<String> = providers().map { it.name }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            o.optString(k).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) o.optJSONArray(k)?.let { return it }
        return null
    }
}