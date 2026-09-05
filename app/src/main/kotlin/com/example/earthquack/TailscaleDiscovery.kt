package com.example.earthquack

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TailscaleDiscovery
 *
 * Android cannot execute the desktop `tailscale status --json` CLI.
 *
 * The Android Tailscale client provides network connectivity through the
 * Tailscale VPN, so we discover the EarthQuack desktop by probing its
 * Tailscale address directly.
 *
 * Current desktop:
 *   Arch Linux -> 100.92.160.31
 *
 * Future:
 *   Windows    -> 100.105.106.87
 */
object TailscaleDiscovery {

    private const val TAG = "TailscaleDiscovery"

    private const val DEFAULT_PORT = SERVER_PORT

    /*
     * Tailscale addresses of machines that may run EarthQuack.
     *
     * Keep these as candidates rather than making the rest of the application
     * care which OS is currently running the server.
     */
    private val TAILSCALE_CANDIDATES = listOf(
        "100.92.160.31",   // Arch Linux
        "100.105.106.87"    // Windows (currently offline)
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .writeTimeout(1500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun logW(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
            println("[$TAG] WARN: $message")
        }
    }

    private fun logI(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            println("[$TAG] INFO: $message")
        }
    }

    /**
     * Check whether the EarthQuack clipboard server is reachable.
     *
     * /health is preferred because it does not require clipboard data.
     * /clipboard is retained as a fallback for compatibility.
     */
    fun checkAppService(
        ip: String,
        port: Int = DEFAULT_PORT
    ): Boolean {

        val urls = listOf(
            "http://$ip:$port/health",
            "http://$ip:$port/clipboard"
        )

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        logI("[clipboard] EarthQuack server reachable at $ip:$port")
                        return true
                    }

                    logI(
                        "[clipboard] $url returned HTTP ${response.code}"
                    )
                }
            } catch (e: Exception) {
                logI(
                    "[clipboard] unable to reach $url: ${e.message}"
                )
            }
        }

        return false
    }

    /**
     * Return every currently reachable EarthQuack server.
     */
    fun discoverAllWorkingServers(
        checkServiceFn: (String, Int) -> Boolean = ::checkAppService
    ): List<String> {

        val working = mutableListOf<String>()

        for (ip in TAILSCALE_CANDIDATES) {
            logI("[tailscale] checking $ip:$DEFAULT_PORT")

            if (checkServiceFn(ip, DEFAULT_PORT)) {
                working.add(ip)
            }
        }

        return working
    }

    /**
     * Discover the EarthQuack server.
     *
     * Preference:
     *
     *   1. Probe every configured Tailscale candidate.
     *   2. Return the first working server.
     *
     * We deliberately do not use Runtime.exec("tailscale ...") because
     * the Android application does not have access to the desktop Tailscale
     * CLI.
     */
    fun discoverServerIp(
        checkServiceFn: (String, Int) -> Boolean = ::checkAppService
    ): String? {

        logI(
            "[tailscale] starting EarthQuack server discovery"
        )

        for (ip in TAILSCALE_CANDIDATES) {

            logI(
                "[tailscale] probing EarthQuack server at $ip:$DEFAULT_PORT"
            )

            if (checkServiceFn(ip, DEFAULT_PORT)) {

                logI(
                    "[clipboard] discovered EarthQuack server at $ip:$DEFAULT_PORT"
                )

                return ip
            }
        }

        logW(
            "[clipboard] no EarthQuack server found on configured Tailscale peers"
        )

        return null
    }
}
