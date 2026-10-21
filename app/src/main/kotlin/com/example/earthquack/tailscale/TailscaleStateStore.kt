package com.example.earthquack.tailscale

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File

/**
 * Non-secret persistence for the embedded Tailscale node.
 *
 * This store deliberately persists *nothing sensitive*: the node key, machine
 * key and profile state all live inside tsnet's own state directory
 * (`filesDir/tailscale/`, owned and encrypted by the Go side). What lives
 * here is only what the app needs to decide how to start the node:
 *
 *  - whether the user has ever enrolled (so the UI knows to offer the
 *    browser-login flow vs. silently joining the tailnet),
 *  - the hostname this device advertises (default: sanitised `Build.MODEL`),
 *  - an optional Headscale control URL (stored early so the setting
 *    round-trips before the UI for it exists).
 *
 * ## Testability
 *
 * Everything except the constructor is overridable and the hostname
 * sanitisation is a pure function, so the interesting logic is JVM-testable
 * without an Android device.
 */
open class TailscaleStateStore(context: Context) : TailscaleNodeConfig {

    private val appContext: Context = context.applicationContext

    /** tsnet's state directory. Created on demand by the Go shim's Start(). */
    open fun stateDir(): File = File(appContext.filesDir, STATE_DIR_NAME)

    override fun stateDirPath(): String = stateDir().absolutePath

    override fun advertisedHostname(): String = hostname()

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True when the node has ever been enrolled: the state directory exists
     * and is non-empty, which means tsnet persisted a profile there.
     *
     * The directory alone is not evidence — `Start()` runs `MkdirAll` before
     * any login happens — so the directory must contain something.
     */
    open override fun isEnrolled(): Boolean {
        val dir = stateDir()
        val entries = dir.listFiles() ?: return false
        return entries.isNotEmpty()
    }

    /**
     * How this device appears in the tailnet admin console. Defaults to a
     * sanitised [Build.MODEL] (`Pixel 8` → `pixel-8`); an explicit blank
     * reverts to the default rather than starting the node nameless.
     */
    open fun hostname(): String {
        val stored = prefs().getString(KEY_HOSTNAME, null)
        return if (stored.isNullOrBlank()) defaultHostname() else stored
    }

    open fun setHostname(name: String) {
        prefs().edit().putString(KEY_HOSTNAME, sanitizeHostname(name)).apply()
    }

    /** Headscale control URL; empty string means the default tailscale.com. */
    open fun controlUrl(): String = prefs().getString(KEY_CONTROL_URL, "") ?: ""

    open fun setControlUrl(url: String) {
        prefs().edit().putString(KEY_CONTROL_URL, url.trim()).apply()
    }

    protected open fun defaultHostname(): String = sanitizeHostname(Build.MODEL)

    companion object {
        const val STATE_DIR_NAME = "tailscale"
        private const val PREFS_NAME = "earthquack_tailscale"
        private const val KEY_HOSTNAME = "hostname"
        private const val KEY_CONTROL_URL = "control_url"

        /** Longest hostname we ever send; Tailscale labels top out at 63. */
        const val MAX_HOSTNAME_LENGTH = 63

        /** Fallback when sanitisation removes literally everything. */
        const val FALLBACK_HOSTNAME = "earthquack"

        /**
         * Turn an arbitrary device model into a tailnet-safe hostname.
         *
         * Tailscale hostnames are DNS labels: lowercase letters, digits and
         * hyphens. Anything else collapses into a single hyphen, leading and
         * trailing hyphens are stripped, and the result is truncated to
         * [MAX_HOSTNAME_LENGTH]. Empty result falls back to
         * [FALLBACK_HOSTNAME] — an empty hostname would make the node
         * invisible in the admin console and unaddressable via MagicDNS.
         */
        fun sanitizeHostname(raw: String): String {
            val cleaned = raw.lowercase()
                .replace(Regex("[^a-z0-9-]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .take(MAX_HOSTNAME_LENGTH)
                .trim('-')
            return cleaned.ifEmpty { FALLBACK_HOSTNAME }
        }
    }
}
