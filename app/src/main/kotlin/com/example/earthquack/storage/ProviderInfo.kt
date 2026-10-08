package com.example.earthquack.storage

/**
 * A backend rclone can configure, as reported by `config/providers`.
 *
 * @param name backend name, e.g. `sftp`. This is the value to pass to
 *   [RcloneRemoteManager.createRemote] as the type.
 * @param description human-readable summary, suitable for a picker row.
 * @param settings the configuration keys this backend accepts.
 */
data class ProviderInfo(
    val name: String,
    val description: String,
    val settings: List<ProviderSetting>
) {
    /** Setting names, for callers that only need the shape. */
    val settingNames: List<String> get() = settings.map { it.name }

    /** Setting names that must be provided for [RcloneRemoteManager.createRemote]. */
    val requiredSettings: List<String> get() = settings.filter { it.required }.map { it.name }

    /** Settings whose values are secrets and must never be logged or echoed. */
    val secretSettings: List<String> get() = settings.filter { it.obscure }.map { it.name }
}

/**
 * One configuration key of a [ProviderInfo].
 *
 * @param type rclone's declared type, e.g. `String`, `Password`, `Token`,
 *   `Bool`, `Int`, `Duration`. The UI can use this to pick a widget and to
 *   avoid echoing a value back to the screen.
 * @param required whether [RcloneRemoteManager.createRemote] needs it.
 * @param obscure true when the value is sensitive. rclone masks these in
 *   `config/dump`; we must do the same everywhere.
 */
data class ProviderSetting(
    val name: String,
    val type: String,
    val required: Boolean,
    val obscure: Boolean,
    val help: String
) {
    /** True when this setting holds a credential. */
    val isSecret: Boolean
        get() = obscure || type.equals("Password", ignoreCase = true) ||
            type.equals("Token", ignoreCase = true)
}