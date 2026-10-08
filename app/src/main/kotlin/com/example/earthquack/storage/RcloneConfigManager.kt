package com.example.earthquack.storage

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Owns the on-disk location and lifecycle of rclone's `rclone.conf`.
 *
 * ## Where it lives, and why
 *
 * ```text
 * <app-private files dir>/rclone/rclone.conf
 * ```
 *
 * `context.filesDir` is app-private storage: not readable by other apps, not
 * in shared storage, and not served by the SFTP transfer service. That matters
 * because this file holds OAuth refresh tokens and passwords for every
 * configured remote — a strictly more sensitive payload than the clipboard AES
 * key currently stored in `ServerConfig`.
 *
 * It is deliberately *not* in `sdcard`/`Downloads`/`Documents`: anything in
 * shared storage is world-readable by other apps with the right permission and
 * survives uninstall in a way app-private storage does not.
 *
 * ## Why the path is passed in rather than discovered
 *
 * rclone resolves a config path from `RCLONE_CONFIG`, `--config`, `$XDG_CONFIG_HOME`
 * or `$HOME` — all of which are unreliable or uncontrollable inside an Android
 * process, and all of which depend on the environment rather than the app. So
 * the path is computed here and handed to rclone explicitly via the supported
 * `config.SetConfigPath` API. Nothing is left to the working directory.
 *
 * ## Fresh installs
 *
 * A missing config file is a valid state, not an error: rclone treats it as
 * "use defaults" and `config/listremotes` returns `[]`. [ensureReady] still
 * creates the file so the location is explicit and the first write has
 * somewhere to go, rather than relying on rclone to create it later.
 */
class RcloneConfigManager(context: Context) {

    private companion object {
        const val TAG = "RcloneConfigManager"
        const val CONFIG_DIR = "rclone"
        const val CONFIG_FILE = "rclone.conf"
    }

    // Application context: this outlives any Activity and must not leak one.
    private val appContext: Context = context.applicationContext

    /**
     * The directory holding rclone.conf. Created on first use.
     *
     * Not marked private because it is a path, and callers may legitimately
     * want to show it to the user during support.
     */
    val configDir: File
        get() = File(appContext.filesDir, CONFIG_DIR).also { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "could not create rclone config dir: ${dir.absolutePath}")
            }
        }

    /** The rclone.conf file itself. The file may not exist yet. */
    val configFile: File
        get() = File(configDir, CONFIG_FILE)

    /** Absolute path to hand to rclone. Empty string means "in-memory only". */
    val configPath: String
        get() = configFile.absolutePath

    /**
     * Ensures the config location is ready to use.
     *
     * Creates the directory, and creates an empty rclone.conf if there is
     * none. An empty file is a legitimate rclone configuration meaning "no
     * remotes configured", which is exactly the first-launch state.
     *
     * Never truncates or overwrites an existing file — losing a configured
     * remote because the app started twice would be far worse than a missing
     * file.
     *
     * @return true when a new, empty config was created.
     */
    fun ensureReady(): Boolean {
        val dir = configDir
        if (!dir.isDirectory) {
            throw IllegalStateException(
                "rclone config directory unavailable: ${dir.absolutePath}"
            )
        }
        val file = configFile
        if (file.exists()) {
            // Never clobber: this is where remote credentials live.
            return false
        }
        // Create with owner-only permissions. Android's filesDir is already
        // private to the app, but an explicit mode means the file is not even
        // group/other readable if the umask is ever relaxed.
        if (!file.createNewFile()) {
            throw IllegalStateException("could not create ${file.absolutePath}")
        }
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        Log.i(TAG, "created empty rclone config at ${file.absolutePath}")
        return true
    }

    /**
     * True when a config file exists on disk.
     *
     * Not a guarantee that it is *valid* — rclone reports that separately —
     * but it distinguishes "never configured" from "configured".
     */
    fun hasConfig(): Boolean = configFile.exists()

    /**
     * True when at least one remote is configured.
     *
     * A cheap regex rather than a parse: rclone.conf is an INI document and a
     * full parse would mean duplicating rclone's own reader. Deliberately
     * approximate — callers that need certainty use `config/listremotes`.
     */
    fun looksConfigured(): Boolean =
        configFile.exists() &&
            configFile.useLines { lines ->
                lines.any { line -> line.trimStart().startsWith("[") }
            }

    /** Deletes the config file. Intended for an explicit "reset rclone" action. */
    fun deleteConfig(): Boolean {
        val file = configFile
        return if (file.exists()) file.delete() else false
    }
}