package com.example.earthquack

import android.content.Context
import android.content.SharedPreferences

/**
 * ServerConfig — single source of truth for server addresses.
 *
 * The Tailscale IP is no longer hardcoded. It is stored in SharedPreferences
 * so the user can change it from MainActivity without recompiling.
 *
 * Python side (server.py / desktop daemon / file-server.py) reads the same value
 * from the CLIPBOARD_SERVER_HOST env var or --host CLI flag.
 */
object ServerConfig {

    private const val PREFS_NAME = "earthquack_prefs"
    private const val KEY_HOST = "server_host"
    private const val KEY_BATTERY_SAVER = "battery_saver_enabled"
    private const val KEY_PAUSE_ON_SCREEN_OFF = "pause_on_screen_off"
    private const val KEY_AES_ENABLED = "aes_enabled"
    private const val KEY_AES_KEY = "aes_key_b64"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_CACHE_RETENTION_DAYS = "files_cache_retention_days"
    private const val KEY_DOWNLOAD_CACHE_DAYS = "files_download_cache_days"

    // Fallback default — the IP that was previously hardcoded
    const val DEFAULT_HOST = "YOUR_TAILSCALE_IP"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Host ───────────────────────────────────────────────────────────────

    fun getHost(context: Context): String =
        prefs(context).getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    fun setHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_HOST, host.trim()).apply()
    }

    /**
     * Whether [host] is the shipped placeholder rather than a real address.
     *
     * The UI must not print `YOUR_TAILSCALE_IP:8875` as though it were an
     * endpoint -- it reads as a configured server that happens to have a
     * strange hostname, and the user would go looking for the problem in the
     * wrong place. Anything matching the placeholder pattern counts, so
     * changing [DEFAULT_HOST] later cannot leave a stale sentinel behind.
     */
    fun isPlaceholderHost(host: String?): Boolean {
        val trimmed = host?.trim().orEmpty()
        return trimmed.isEmpty() ||
            trimmed.equals(DEFAULT_HOST, ignoreCase = true) ||
            trimmed.startsWith("YOUR_", ignoreCase = true) ||
            trimmed.startsWith("<", ignoreCase = true)
    }

    /** True when a real host has been chosen. */
    fun isConfigured(context: Context): Boolean = !isPlaceholderHost(getHost(context))

    fun getBaseUrl(context: Context): String = "http://${getHost(context)}:$SERVER_PORT"
    fun getFileBaseUrl(context: Context): String = "http://${getHost(context)}:$FILE_SERVER_PORT"

    // ── Battery saver ─────────────────────────────────────────────────────

    /** When true, clipboard polling throttles when screen is off (30s vs 1.5s). */
    fun isBatterySaverEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_SAVER, false)

    fun setBatterySaverEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_SAVER, enabled).apply()
    }

    /**
     * When true the service FULLY disconnects SSE when screen goes off
     * and reconnects on screen on — sync STOPS while screen off.
     * Default false: sync stays active unless you tap Pause.
     * Enable only if you want aggressive battery saving at cost of missed sync while screen off.
     */
    fun isPauseOnScreenOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAUSE_ON_SCREEN_OFF, false)

    fun setPauseOnScreenOff(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSE_ON_SCREEN_OFF, enabled).apply()
    }

    // ── AES encryption ────────────────────────────────────────────────

    fun isAesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AES_ENABLED, false)

    fun setAesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AES_ENABLED, enabled).apply()
    }

    fun getAesKey(context: Context): String =
        prefs(context).getString(KEY_AES_KEY, "") ?: ""

    fun setAesKey(context: Context, b64: String) {
        prefs(context).edit().putString(KEY_AES_KEY, b64.trim()).apply()
    }

    fun hasValidAesKey(context: Context): Boolean {
        val k = getAesKey(context)
        return k.isNotBlank() && CryptoUtil.isValidKeyBase64(k)
    }

    // ── Shared bearer token (earthQuack auth) ───────────────────────────────
    // The same EARTHQUACK_AUTH_TOKEN used by the Go node / future Go daemon.
    // Sent as "Authorization: Bearer <token>" on HTTP + SSE + file transfers.
    // The current Python daemon ignores it (harmless); it becomes required once
    // the daemon is migrated to Go and enforces auth. Never store a real token
    // in source — set it from the app UI.

    fun getAuthToken(context: Context): String =
        prefs(context).getString(KEY_AUTH_TOKEN, "") ?: ""

    fun setAuthToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_AUTH_TOKEN, token.trim()).apply()
    }

    // ── Files screen: directory listing cache ─────────────────────────────────
    // A listing is the round trip the user waits on, and the same directory is
    // revisited constantly. Caching it is only useful if the user can choose how
    // long a cached listing stays believable, so the number of days is a
    // setting rather than a constant. Zero means "do not cache".

    fun getCacheRetentionDays(context: Context): Int =
        prefs(context).getInt(KEY_CACHE_RETENTION_DAYS, DEFAULT_CACHE_RETENTION_DAYS)

    fun setCacheRetentionDays(context: Context, days: Int) {
        prefs(context).edit().putInt(
            KEY_CACHE_RETENTION_DAYS,
            days.coerceIn(0, MAX_CACHE_RETENTION_DAYS)
        ).apply()
    }

    /**
     * Days a cached listing stays usable.
     *
     * Seven by default: long enough that hopping between two directories does
     * not re-list, short enough that a file uploaded from a phone between
     * visits shows up on the next one. "Off" is zero, and anything above
     * [MAX_CACHE_RETENTION_DAYS] is clamped rather than trusted, because an
     * arbitrary int in a preference file is not a promise.
     */
    const val DEFAULT_CACHE_RETENTION_DAYS = 7
    const val MAX_CACHE_RETENTION_DAYS = 365

    /** The day counts offered by the Files screen, "off" first. */
    val CACHE_RETENTION_CHOICES = listOf(0, 1, 7, 30, 90)

    // ── Downloaded files ─────────────────────────────────────────────────────
    // A different setting from the listing cache above, and for a different
    // thing: this one is file *content*, which is megabytes to gigabytes, so
    // "keep for a week" means something entirely different for a 1.6 GB video
    // than it does for a directory listing.

    fun getDownloadCacheDays(context: Context): Int =
        prefs(context).getInt(KEY_DOWNLOAD_CACHE_DAYS, DEFAULT_DOWNLOAD_CACHE_DAYS)

    fun setDownloadCacheDays(context: Context, days: Int) {
        prefs(context).edit().putInt(
            KEY_DOWNLOAD_CACHE_DAYS,
            days.coerceIn(0, MAX_DOWNLOAD_CACHE_DAYS)
        ).apply()
    }

    /**
     * Seven days by default: long enough that a file opened twice in a week
     * does not download twice, short enough that a phone does not fill up with
     * files it has seen once. "Off" is zero, meaning a file is downloaded when
     * tapped and deleted as soon as the retention is next applied — which is
     * effectively per-session behaviour.
     */
    const val DEFAULT_DOWNLOAD_CACHE_DAYS = 7
    const val MAX_DOWNLOAD_CACHE_DAYS = 365

    val DOWNLOAD_CACHE_CHOICES = listOf(0, 1, 7, 30, 90)
}
