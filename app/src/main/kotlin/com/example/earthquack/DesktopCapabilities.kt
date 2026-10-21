package com.example.earthquack

import android.content.Context
import android.util.Log
import com.example.earthquack.tailscale.TailnetRoute
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * DesktopCapabilities — what the desktop daemon says it supports.
 *
 * The phone and the desktop encrypt clipboard traffic independently, and
 * nothing used to check that they agreed. When they disagreed, the phone
 * stored ciphertext the desktop could not read (or the other way round) and
 * the only symptom was garbled text in the clipboard.
 *
 * The desktop publishes `/capabilities`; the phone compares it with its own
 * settings and reports the difference in words the user can act on.
 */
data class DesktopCapabilities(
    val service: String,
    val version: String,
    val aesEnabled: Boolean,
    val aesKeyFingerprint: String,
    val filePort: Int,
) {

    /**
     * How this compares with the phone's own configuration.
     *
     * Null when the two agree (or when there is nothing to compare). The
     * message names the *action*, not the mechanism: "enable AES on the
     * desktop" is something a user can do; "AES key fingerprint mismatch" is
     * not.
     */
    fun mismatchWith(
        phoneAesEnabled: Boolean,
        phoneKeyFingerprint: String,
    ): String? = when {
        aesEnabled && !phoneAesEnabled ->
            "The desktop encrypts the clipboard but this phone does not. " +
                "Set the same AES key on both sides (Settings → Security)."
        !aesEnabled && phoneAesEnabled ->
            "This phone encrypts the clipboard but the desktop does not. " +
                "Enable AES on the desktop or turn it off here."
        aesEnabled && phoneAesEnabled && aesKeyFingerprint.isNotEmpty() &&
            phoneKeyFingerprint.isNotEmpty() && aesKeyFingerprint != phoneKeyFingerprint ->
            "The phone and the desktop use different AES keys, so the " +
                "clipboard cannot be decrypted. Use the same key on both sides."
        else -> null
    }

    fun summary(): String = buildString {
        append(service.ifBlank { "desktop" })
        if (version.isNotBlank()) append(" ").append(version)
        append(if (aesEnabled) " · AES on" else " · AES off")
    }

    companion object {
        private const val TAG = "DesktopCapabilities"

        /**
         * Fetches the desktop's capabilities, or null when it cannot be asked.
         *
         * Dials through the app's own node when the desktop is a tailnet
         * address, exactly like every other tailnet call.
         */
        suspend fun fetch(context: Context): DesktopCapabilities? {
            val host = ServerConfig.getHost(context)
            if (host.isBlank()) return null
            val url = URL("${ServerConfig.getBaseUrl(context)}/capabilities")
            return runCatching {
                val conn = url.openConnection(
                    TailnetRoute.socksAddressFor(host)?.let {
                        java.net.Proxy(java.net.Proxy.Type.SOCKS, it)
                    } ?: java.net.Proxy.NO_PROXY
                ) as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                try {
                    if (conn.responseCode !in 200..299) return null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    DesktopCapabilities(
                        service = json.optString("service"),
                        version = json.optString("version"),
                        aesEnabled = json.optBoolean("aes_enabled"),
                        aesKeyFingerprint = json.optString("aes_key_fingerprint"),
                        filePort = json.optInt("file_port"),
                    )
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "capabilities query failed: ${it.message}") }.getOrNull()
        }

        /**
         * Fingerprint of a key, matching the desktop's fingerprint format.
         */
        fun fingerprint(key: String): String {
            if (key.isBlank()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
