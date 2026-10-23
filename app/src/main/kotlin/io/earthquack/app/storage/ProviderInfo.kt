package io.earthquack.app.storage

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
    val secretSettings: List<String> get() = settings.filter { it.isSecret }.map { it.name }
}

/**
 * Example option value and documentation reported by rclone.
 */
data class ProviderExample(
    val value: String,
    val help: String = "",
    val provider: String = ""
)

/**
 * One configuration key of a [ProviderInfo].
 *
 * @param type rclone's declared type, e.g. `String`, `Password`, `Token`,
 *   `Bool`, `Int`, `Duration`. The UI uses this to pick a widget and to avoid
 *   echoing a value back to the screen.
 * @param required whether [RcloneRemoteManager.createRemote] needs it.
 * @param obscure true when the value is sensitive. rclone masks these in
 *   `config/dump`; we must do the same everywhere.
 * @param help human-readable documentation.
 * @param isAdvanced true for keys a user rarely needs. SFTP exposes a dozen; only
 *   a few are essential, so the generated form hides the rest by default rather
 *   than presenting every backend's full key set at once.
 * @param isPassword true when rclone explicitly marks this field as a password.
 * @param isSensitive true when rclone marks this field as sensitive personal data (host, user, etc.).
 * @param defaultStr default value as string from rclone.
 * @param examples suggested values or examples from rclone.
 * @param hide rclone hide flags (internal options).
 */
data class ProviderSetting(
    val name: String,
    val type: String,
    val required: Boolean,
    val obscure: Boolean = false,
    val help: String = "",
    val isAdvanced: Boolean = false,
    val isPassword: Boolean = false,
    val isSensitive: Boolean = false,
    val defaultStr: String = "",
    val examples: List<ProviderExample> = emptyList(),
    val hide: Int = 0
) {
    /** True when this setting holds a credential or password. */
    val isSecret: Boolean
        get() = isPassword || obscure || type.equals("Password", ignoreCase = true) ||
            type.equals("Token", ignoreCase = true)
}
