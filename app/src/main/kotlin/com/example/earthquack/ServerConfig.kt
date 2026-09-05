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
}
