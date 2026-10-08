package com.example.earthquack.sftp

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted SFTP server settings.
 *
 * ## Why this exists with no server behind it
 *
 * The SFTP screen is built and wired now so the UI is finished and reviewable;
 * the server itself is a separate piece of work. Settings are stored for real,
 * not held in memory, so when the backend lands it reads exactly the values the
 * user already chose and nothing has to be re-entered or migrated.
 *
 * ## What is deliberately NOT stored
 *
 * No password. Two reasons:
 *
 *  - `ServerConfig` already keeps its auth token and AES key as plaintext in
 *    SharedPreferences. Copying that pattern to a new credential would be
 *    exactly the mistake the Security screen documents.
 *  - The server does not need a stored password at all. It can generate a
 *    one-time credential when it starts and show it once, or prefer public-key
 *    auth, which stores a *public* key that is not sensitive.
 *
 * So there is nothing here for a future commit to have to migrate away from.
 *
 * ## Credentials on screen
 *
 * [rootPath] and [port] are not secrets and are safe to display. Anything the
 * backend adds later that is sensitive should be masked by default, following
 * the rule the Security screen already sets.
 */
data class SftpSettings(
    /** TCP port. 8022 is the convention for a non-root SFTP service. */
    val port: Int = DEFAULT_PORT,
    /**
     * Directory served to clients, as an absolute path.
     *
     * Defaults to shared storage because that is what a phone-as-server is
     * usually for, but it is only a default -- the user chooses, and the value
     * is not silently widened to `/`.
     */
    val rootPath: String = DEFAULT_ROOT,
    val passwordAuth: Boolean = true,
    val publicKeyAuth: Boolean = false,
    /** Host key fingerprint, once the server generates one. Null until then. */
    val hostKeyFingerprint: String? = null,
    /**
     * Username clients authenticate as.
     *
     * A fixed name, matching what a single-user phone-as-server would sensibly
     * use. Not a credential: authentication is by key or a one-time password,
     * so this string grants nothing on its own.
     */
    val username: String = DEFAULT_USERNAME,
    /**
     * Maximum simultaneous clients.
     *
     * Enforced by the server, not just documented: a connection beyond the
     * limit is closed as soon as it is accepted.
     */
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS
) {
    /**
     * Everything wrong with these settings, in the order a user should fix it.
     *
     * Empty means usable. Returned as a list rather than thrown so the editor
     * can show every problem at once, and so validation can be unit tested
     * without an Android context.
     */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (port !in VALID_PORT_RANGE) out += "port must be between 1 and 65535"
        if (rootPath.isBlank()) out += "root directory is required"
        if (username.isBlank()) out += "username is required"
        if (username.any { it.isWhitespace() }) out += "username must not contain spaces"
        if (maxConnections !in 1..MAX_CONNECTIONS_LIMIT) {
            out += "maximum connections must be between 1 and $MAX_CONNECTIONS_LIMIT"
        }
        if (!passwordAuth && !publicKeyAuth) {
            out += "at least one authentication method must be enabled"
        }
        return out
    }

    val isValid: Boolean get() = problems().isEmpty()

    companion object {
        const val DEFAULT_PORT = 8022
        const val DEFAULT_USERNAME = "earthquack"
        const val DEFAULT_MAX_CONNECTIONS = 16
        const val MAX_CONNECTIONS_LIMIT = 64
        val VALID_PORT_RANGE = 1..65535

        /**
         * Shared storage.
         *
         * `Environment.getExternalStorageDirectory()` rather than a literal
         * `/storage/emulated/0`: the literal is not guaranteed and hard-coding
         * it is what the previous design notes warned against.
         */
        val DEFAULT_ROOT: String =
            android.os.Environment.getExternalStorageDirectory()?.absolutePath
                ?: "/storage/emulated/0"
    }
}

/**
 * Reads and writes [SftpSettings].
 *
 * Deliberately a separate SharedPreferences file from `ServerConfig`. The
 * Security screen is where credential storage gets fixed; keeping SFTP settings
 * in their own file means that migration is a change here rather than a
 * migration of everything `ServerConfig` has ever held.
 */
class SftpSettingsStore(context: Context) {

    private companion object {
        const val PREFS = "earthquack_sftp"
        const val KEY_PORT = "port"
        const val KEY_ROOT = "root_path"
        const val KEY_PASSWORD_AUTH = "password_auth"
        const val KEY_PUBKEY_AUTH = "public_key_auth"
        const val KEY_HOST_KEY = "host_key_fingerprint"
        const val KEY_USERNAME = "username"
        const val KEY_MAX_CONNECTIONS = "max_connections"

        // 0 is invalid ("any port") and 65536+ is not a port. Being explicit
        // avoids depending on which side of the boundary someone later clamps.
        val VALID_PORT_RANGE = 1..65535
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): SftpSettings = SftpSettings(
        port = prefs.getInt(KEY_PORT, SftpSettings.DEFAULT_PORT),
        rootPath = prefs.getString(KEY_ROOT, SftpSettings.DEFAULT_ROOT)
            ?: SftpSettings.DEFAULT_ROOT,
        passwordAuth = prefs.getBoolean(KEY_PASSWORD_AUTH, true),
        publicKeyAuth = prefs.getBoolean(KEY_PUBKEY_AUTH, false),
        hostKeyFingerprint = prefs.getString(KEY_HOST_KEY, null),
        username = prefs.getString(KEY_USERNAME, SftpSettings.DEFAULT_USERNAME)
            ?: SftpSettings.DEFAULT_USERNAME,
        maxConnections = prefs.getInt(KEY_MAX_CONNECTIONS, SftpSettings.DEFAULT_MAX_CONNECTIONS)
    )

    /**
     * Saves settings, ignoring invalid values rather than persisting them.
     *
     * A port outside 1..65535 cannot be bound, and an empty root cannot be
     * served. Storing either would defer the failure to the moment the server
     * starts, which is much harder to diagnose than rejecting it here.
     *
     * @return true when [settings] were valid and written.
     */
    fun save(settings: SftpSettings): Boolean {
        if (!settings.isValid) return false

        prefs.edit()
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_ROOT, settings.rootPath)
            .putBoolean(KEY_PASSWORD_AUTH, settings.passwordAuth)
            .putBoolean(KEY_PUBKEY_AUTH, settings.publicKeyAuth)
            .putString(KEY_USERNAME, settings.username)
            .putInt(KEY_MAX_CONNECTIONS, settings.maxConnections)
            .apply {
                settings.hostKeyFingerprint?.let { putString(KEY_HOST_KEY, it) }
            }
            .apply()
        return true
    }

    /** Clears the stored host key, e.g. when regenerating server identity. */
    fun clearHostKey() {
        prefs.edit().remove(KEY_HOST_KEY).apply()
    }
}

/**
 * The state of the SFTP server, as far as the app can actually know it.
 *
 * [NotImplemented] is a first-class state rather than an error string or a
 * faked "Stopped". The distinction matters: a user who taps Start on a server
 * that was never built deserves to be told that, not shown a spinner that
 * never resolves or an error about a port that was never bound.
 */
sealed class SftpServerStatus {

    /** The server component does not exist yet in this build. */
    object NotImplemented : SftpServerStatus()

    /** Not running, and can be. */
    object Stopped : SftpServerStatus()

    /** Running and listening. */
    data class Running(val port: Int, val connectedClients: Int) : SftpServerStatus()

    /** Running but degraded. */
    data class Error(val message: String) : SftpServerStatus()

    /** Short label for the status row. Never carries the reason by itself. */
    val label: String
        get() = when (this) {
            NotImplemented -> "Not yet available"
            Stopped -> "Stopped"
            is Running -> "Running"
            is Error -> "Error"
        }

    /** Whether Start/Stop controls should be enabled. */
    val isControllable: Boolean get() = this != NotImplemented
}

/**
 * The seam the SFTP backend will implement.
 *
 * Everything the SFTP screen does goes through this interface, so implementing
 * the server means writing one class — not touching a single layout or
 * fragment. [UnavailableSftpController] is the current, honest implementation.
 */
interface SftpServerController {

    /** Current state. Must not block; this is called from the UI thread. */
    fun status(): SftpServerStatus

    /**
     * Starts the server with [settings].
     *
     * @return the resulting status. Failure is a value, not a thrown
     *   exception, so the screen can render it without a try/catch.
     */
    suspend fun start(settings: SftpSettings): SftpServerStatus

    /** Stops the server. Idempotent. */
    suspend fun stop(): SftpServerStatus
}

/**
 * The controller used until the SFTP server exists.
 *
 * Returns [SftpServerStatus.NotImplemented] rather than pretending to be
 * [SftpServerStatus.Stopped]. The distinction is the whole point: a "Stopped"
 * server that cannot be started is a broken promise, whereas "Not yet
 * available" is a true statement about this build.
 *
 * Settings still save, so the work done on the Settings screen is not thrown
 * away when the backend arrives.
 */
class UnavailableSftpController : SftpServerController {

    override fun status(): SftpServerStatus = SftpServerStatus.NotImplemented

    override suspend fun start(settings: SftpSettings): SftpServerStatus =
        SftpServerStatus.NotImplemented

    override suspend fun stop(): SftpServerStatus = SftpServerStatus.NotImplemented
}
