package io.earthquack.app.storage

import android.content.Context
import android.system.Os
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
 *
 * ## At-rest protection (Section C hardening)
 *
 * rclone.conf holds OAuth refresh tokens and passwords. The gomobile
 * integration requires it as plaintext on disk (`config.SetConfigPath` +
 * rclone reads/writes it directly on every config RPC), so app-side
 * encryption of the file is incompatible: rclone itself must read and
 * rewrite it. Protection is therefore location + permissions + backup
 * exclusion + never logging contents:
 *
 * - app-private `filesDir/rclone/`, never shared storage,
 * - owner-only (600) permissions, repaired on every [ensureReady],
 * - excluded from backup (`allowBackup="false"` — no backup-rules file
 *   re-includes it),
 * - never served via FileProvider (root is `cache/opened/`, not `files/`),
 * - never logged: use [RcloneLogRedactor] for any error derived from config.
 */
class RcloneConfigManager(context: Context) {

    private companion object {
        const val TAG = "RcloneConfigManager"
        const val CONFIG_DIR = "rclone"
        const val CONFIG_FILE = "rclone.conf"

        /** 0700: the directory must be searchable+listable by its owner. */
        const val DIR_MODE = 448

        /** 0600: owner read+write, and nothing for anyone else. */
        const val FILE_MODE = 384
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
        repairPermissions(dir)
        val file = configFile
        if (file.exists()) {
            // Never clobber: this is where remote credentials live.
            repairPermissions(file)
            return false
        }
        // Create with owner-only permissions (0600). Android's filesDir is
        // already private to the app, but an explicit mode means the file is
        // not even group/other readable if the umask is ever relaxed.
        if (!file.createNewFile()) {
            throw IllegalStateException("could not create ${file.absolutePath}")
        }
        chmod(file, FILE_MODE)
        Log.i(TAG, "created empty rclone config at ${file.absolutePath}")
        return true
    }

    /**
     * Repairs owner-only permissions on the config dir and file.
     *
     * Best-effort and silent on failure: a restrictive umask keeps files
     * private anyway, and this must never throw on a valid install. Never
     * logs file contents — only paths, which contain no secrets.
     *
     * ## Why this is an absolute chmod and not the File setters
     *
     * `File.setReadable/Writable/Executable` each perform an *absolute*
     * `chmod` on Android — `setReadable(true, true)` chmods to 0400,
     * `setWritable(true, true)` to 0600, `setExecutable(true, true)` to
     * 0700 — so chaining them does not add bits, it replaces the mode with
     * each one. The earlier version of this method ended in
     * `setExecutable(false, false)`, which chmods to 0000: the config
     * directory was left owner-unwritable, `ensureReady`'s
     * `createNewFile` failed with EACCES on a fresh install, and any
     * install that did get past it had an rclone.conf rclone itself could
     * not open. One `Os.chmod` with the intended mode states the intent
     * exactly once.
     */
    fun repairPermissions(target: File = configFile) {
        runCatching {
            if (target.exists() || target == configDir) {
                chmod(target, if (target == configDir) DIR_MODE else FILE_MODE)
            }
        }
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

    /**
     * Chmods [target] to the absolute [mode], quietly.
     *
     * Quiet because a failure here leaves the app-private path with the
     * umask's mode, which is still private: the caller has already failed to
     * reach anything better, and throwing would take down the screen that
     * only wanted to show a directory listing.
     */
    private fun chmod(target: File, mode: Int) {
        runCatching { Os.chmod(target.absolutePath, mode) }
    }
}