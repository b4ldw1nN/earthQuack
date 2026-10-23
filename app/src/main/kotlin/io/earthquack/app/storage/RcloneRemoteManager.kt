package io.earthquack.app.storage

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

        /** Dumps the whole config, secrets redacted by rclone. */
        const val OP_DUMP = "config/dump"

        /** The backend type whose remotes can host a public link share. */
        const val TYPE_DRIVE = "drive"
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

        val opt = JSONObject()
            .put("obscure", true)
            .put("nonInteractive", nonInteractive)

        return JSONObject()
            .put("name", name)
            .put("parameters", settings)
            .put("opt", opt)
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
            val settings = firstArray(o, "Options", "options", "Settings", "settings")
            ProviderInfo(
                name = firstString(o, "Name", "name"),
                description = firstString(o, "Description", "description"),
                // rclone marshals its Go struct field names verbatim, so the
                // keys are capitalised ("Name", "Help", "Type", "Options"). Lower-case
                // fallbacks are kept so a future marshalling change degrades
                // to "still works" rather than "silently empty forms".
                settings = (0 until (settings?.length() ?: 0)).mapNotNull { s ->
                    val so = settings!!.optJSONObject(s) ?: return@mapNotNull null
                    val hide = so.optInt("Hide", so.optInt("hide", 0))
                    // OptionHideBoth / internal options (flag 2) shouldn't clutter the UI
                    if ((hide and 2) != 0) return@mapNotNull null

                    val isPw = so.optBoolean("IsPassword", so.optBoolean("is_password", false))
                    val typeStr = firstString(so, "Type", "type").ifEmpty { "string" }
                    val isSens = so.optBoolean("Sensitive", so.optBoolean("sensitive", false))

                    val examplesArr = firstArray(so, "Examples", "examples")
                    val examples = if (examplesArr != null) {
                        (0 until examplesArr.length()).mapNotNull { eIdx ->
                            val eo = examplesArr.optJSONObject(eIdx) ?: return@mapNotNull null
                            val v = firstString(eo, "Value", "value")
                            if (v.isNotEmpty()) {
                                ProviderExample(
                                    value = v,
                                    help = firstString(eo, "Help", "help"),
                                    provider = firstString(eo, "Provider", "provider")
                                )
                            } else null
                        }
                    } else emptyList()

                    ProviderSetting(
                        name = firstString(so, "Name", "name"),
                        type = typeStr,
                        required = so.optBoolean("Required", so.optBoolean("required")),
                        // rclone marks password fields with IsPassword, while Sensitive
                        // marks PII (host, user) for log redaction.
                        obscure = isPw || (isSens && (typeStr.equals("Password", ignoreCase = true) || typeStr.equals("Token", ignoreCase = true))),
                        help = firstString(so, "Help", "help"),
                        isAdvanced = so.optBoolean("Advanced", so.optBoolean("advanced")),
                        isPassword = isPw || typeStr.equals("Password", ignoreCase = true) || typeStr.equals("Token", ignoreCase = true),
                        isSensitive = isSens,
                        defaultStr = firstString(so, "DefaultStr", "default_str", "Default", "default"),
                        examples = examples,
                        hide = hide
                    )
                }
            )
        }
    }

    /** Names of every backend this build can configure. */
    suspend fun providerNames(): List<String> = providers().map { it.name }

    /**
     * Names of every configured remote whose backend is Google Drive.
     *
     * The "Share as link" workflow needs a Drive destination, and it must not
     * offer a remote that cannot produce a public link. `config/listremotes`
     * returns only names, so it cannot tell a Drive remote from an SFTP one;
     * `config/dump` returns each remote's `type`, which is the discriminator.
     *
     * rclone redacts secrets in `config/dump`, so reading `type` here exposes
     * nothing sensitive — and only the `type` field is ever read. An unreadable
     * or empty config yields an empty list, which the caller renders as "no
     * Drive remote configured" rather than an error.
     */
    suspend fun driveRemotes(): List<String> {
        val reply = engine.call(OP_DUMP)
        // config/dump is { "<name>": { "type": "drive", … }, … }: one object per
        // remote, keyed by name, at the top level. Iterate keys, not a "remotes"
        // array — that is the array form config/listremotes uses, and the two
        // must not be conflated.
        val names = mutableListOf<String>()
        for (name in reply.keys()) {
            val section = reply.optJSONObject(name) ?: continue
            if (section.optString("type").equals(TYPE_DRIVE, ignoreCase = true)) {
                names.add(name)
            }
        }
        return names.sortedBy { it.lowercase() }
    }

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