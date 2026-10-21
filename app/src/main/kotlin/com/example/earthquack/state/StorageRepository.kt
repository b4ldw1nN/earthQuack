package com.example.earthquack.state

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.earthquack.storage.RcloneConfigManager
import com.example.earthquack.storage.RcloneEngine
import com.example.earthquack.storage.RcloneHealthProbe
import com.example.earthquack.storage.RcloneRemoteManager
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real storage figures for the Storage screen.
 *
 * Every number here is measured or queried. Nothing is a plausible-looking
 * constant: a storage meter that invents its own capacity is worse than no
 * meter, because the user trusts it.
 *
 * ## Where the numbers come from
 *
 * - **Internal storage** — [StatFs] against the app's files directory. This is
 *   genuinely queryable, so the mockup's "78.3 GB free" becomes a real figure.
 * - **rclone remotes** — `config/listremotes` through the native rclone layer.
 *   On a fresh install this is genuinely empty, and "No remotes" is the
 *   correct thing to show.
 *
 * ## What is deliberately absent
 *
 * The mockup showed per-remote aggregate usage ("Google Drive — Connected",
 * quota bars). Getting that needs `about` RPCs per remote against live cloud
 * accounts, each of which may prompt for credentials or fail on a flaky
 * network. Showing a quota that was never queried would be exactly the fake
 * state this redesign is supposed to eliminate, so [RemoteSummary] carries
 * only the name and whether it is reachable.
 */
class StorageRepository(context: Context) {

    private companion object {
        /** Per-remote probe budget; a dead host must not stall the list. */
        const val PROBE_TIMEOUT_MS = 12_000L
    }

    private val appContext = context.applicationContext
    private val configManager = RcloneConfigManager(appContext)

    // ── Local storage ────────────────────────────────────────────────────────

    /**
     * Internal storage usage, in bytes.
     *
     * Queried against the app's own files dir rather than [Environment] paths so
     * this cannot be wrong on a device where those paths are not readable.
     */
    data class InternalUsage(val totalBytes: Long, val freeBytes: Long) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)

        /** Fraction used, 0f..1f. Zero when the platform reports no total. */
        val usedFraction: Float
            get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

        /**
         * True when the figure could not be read.
         *
         * Distinguishing "0 bytes used" from "unknown" matters: the first is a
         * fact, the second means the UI should say so rather than draw a full
         * or empty bar.
         */
        val isKnown: Boolean get() = totalBytes > 0L
    }

    fun internalUsage(): InternalUsage {
        val path = appContext.filesDir
        return try {
            val stat = StatFs(path.absolutePath)
            InternalUsage(
                totalBytes = stat.blockCountLong * stat.blockSizeLong,
                freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            )
        } catch (e: IllegalArgumentException) {
            // StatFs throws rather than returning zeros on some OEM builds.
            InternalUsage(0L, 0L)
        }
    }

    // ── rclone remotes ───────────────────────────────────────────────────────

    /**
     * A configured remote, with only what has actually been established.
     *
     * [type] is null when the section exists but declares no `type`, which is a
     * real state for a hand-edited config and should not be papered over with a
     * guessed backend name.
     */
    data class RemoteSummary(
        val name: String,
        val type: String?,
        val isReachable: Boolean,
        /** True when the remote is dialled through the app's Tailscale node. */
        val routedViaTailscale: Boolean = false,
    )

    /**
     * Lists configured rclone remotes.
     *
     * Returns an empty list when rclone is unavailable or the config cannot be
     * read. A failure to list is not the same as "no remotes exist", so callers
     * that need to tell them apart should use [remotesOrNull].
     */
    suspend fun remotes(): List<RemoteSummary> = remotesOrNull().orEmpty()

    /**
     * Lists remotes, distinguishing "could not ask" from "asked, got none".
     *
     * The Storage screen shows a different message for each: an error the user
     * can act on, versus the perfectly normal fresh-install state.
     */
    suspend fun remotesOrNull(): List<RemoteSummary>? = try {
        configManager.ensureReady()
        val engine = RcloneEngine().also { it.initialize(configManager.configPath) }
        RcloneRemoteManager(engine).listRemotes().map { name ->
            val section = remoteSection(name)
            RemoteSummary(
                name = name,
                type = section?.get("type"),
                // Reachability is established by a probe ([probeRemotes]),
                // not asserted here.
                isReachable = false,
                // A tailnet remote is dialled through this app's own node
                // (SOCKS5 proxy), which is what makes it reachable without a
                // system VPN.
                routedViaTailscale = section?.get("socks_proxy")?.isNotBlank() == true,
            )
        }
    } catch (e: Exception) {
        // Includes rclone not loading at all, which is a real possibility on an
        // ABI we do not ship. Null means "unknown", not "empty".
        null
    }

    /**
     * Probes each remote and reports the outcome as it arrives.
     *
     * Sequential on purpose — one rclone engine, one call at a time — but the
     * callback fires per remote, so the list fills in progressively instead of
     * staying blank until the slowest remote answers. Each probe is bounded
     * by a timeout, so one dead host cannot stall the rest.
     */
    suspend fun probeRemotes(
        names: List<String>,
        onResult: (String, RcloneHealthProbe.Health) -> Unit,
    ) = try {
        configManager.ensureReady()
        val engine = RcloneEngine().also { it.initialize(configManager.configPath) }
        names.forEach { name ->
            val health = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                RcloneHealthProbe.probe(engine, name, remoteSection(name)?.get("type"))
            } ?: RcloneHealthProbe.Health(RcloneHealthProbe.State.Unreachable, "timed out")
            onResult(name, health)
        }
    } catch (e: Exception) {
        // A failed probe is a result, not a crash: the list stays usable.
    }

    /**
     * Creates the folder a crypt remote wraps, if it is missing.
     *
     * A crypt remote ("college_crypt") points at a folder inside a drive
     * ("college:encrypted"). Nothing creates that folder automatically, so
     * a remote that was never used fails to list with "directory not
     * found" — which reads like a credential problem and is not one.
     *
     * @return true when the folder exists afterwards.
     */
    suspend fun createCryptFolder(remote: String): Boolean {
        return try {
            configManager.ensureReady()
            val section = remoteSection(remote)
            if (section == null || section["type"] != "crypt") return false
            val target = section["remote"] ?: return false
            // "college:encrypted" → fs "college:", path "encrypted"
            val separator = target.indexOf(':')
            if (separator < 0) return false
            val fs = target.substring(0, separator + 1)
            val path = target.substring(separator + 1)
            val engine = RcloneEngine().also { it.initialize(configManager.configPath) }
            runCatching {
                engine.call(
                    "operations/mkdir",
                    org.json.JSONObject().put("fs", fs).put("remote", path),
                )
            }.isSuccess
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads a remote's `type` from the config file.
     *
     * Parsed directly rather than via rclone: there is no RPC for "what type is
     * remote X", and `config/get` would work but is heavier than reading one
     * INI section. Tolerant by design -- a malformed file yields null instead of
     * throwing, because a cosmetic failure must not hide the remote list.
     */
    private fun remoteType(name: String): String? = remoteSection(name)?.get("type")

    /**
     * Reads a remote's config section straight from the file.
     *
     * Parsed directly rather than via rclone: there is no RPC for "what type is
     * remote X", and `config/get` would work but is heavier than reading one
     * INI section. Tolerant by design -- a malformed file yields null instead of
     * throwing, because a cosmetic failure must never hide the remote list.
     */
    private fun remoteSection(name: String): Map<String, String>? {
        val file = configManager.configFile
        if (!file.exists()) return null
        var inSection = false
        val values = mutableMapOf<String, String>()
        try {
            file.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                            // Leaving the target section ends the search.
                            if (inSection) return@useLines
                            inSection = trimmed.substring(1, trimmed.length - 1).trim() == name
                        }
                        inSection -> {
                            val key = trimmed.substringBefore('=', "").trim()
                            val value = trimmed.substringAfter('=', "").trim()
                            if (key.isNotEmpty()) values[key] = value
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // A cosmetic read must never hide the remote list itself.
            return null
        }
        return if (inSection) values else null
    }

    // ── Shared storage availability ──────────────────────────────────────────

    /**
     * Whether the app can currently read Android shared storage.
     *
     * Reported honestly rather than optimistically: MANAGE_EXTERNAL_STORAGE is
     * a special permission that is easy to believe you have and not have, and
     * a row that promises "Shared Storage" and then lists nothing is the kind
     * of dishonesty this redesign is fixing.
     */
    fun sharedStorageReadable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            appContext.getExternalFilesDir(null)?.canRead() ?: false
        }
}
