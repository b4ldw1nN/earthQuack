package com.example.earthquack.state

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * What the phone itself knows about the tailnet.
 *
 * ## Why this exists
 *
 * "Tailscale is not working" is not a diagnosis. When clipboard sync refuses to
 * connect, the useful question is *which half is missing*:
 *
 *   - the phone has no tailnet address at all -> the VPN is not up
 *   - the phone has an address but no desktop is found -> the desktop node is
 *     not running, or the tailnet policy blocks it
 *
 * `TailscaleDiscovery` cannot answer the first question: it probes remote
 * addresses and knows nothing about the local interface. This does.
 *
 * ## How it is read
 *
 * Via [ConnectivityManager] over the active network's link addresses, filtered
 * to addresses in `100.64.0.0/10` — the CGNAT range Tailscale allocates from.
 * Filtering matters: the phone also has ordinary Wi-Fi and cellular addresses,
 * and reporting one of those as "your Tailscale IP" would be actively wrong.
 */
class TailnetStatus(context: Context) {

    // Application context: this is read from fragments that may outlive an
    // Activity, and holding one would leak it.
    private val appContext: Context = context.applicationContext

    private companion object {
        // Tailscale allocates from 100.64.0.0/10. Checked explicitly below
        // rather than with a mask, because LinkAddress carries a prefix but the
        // allocation rule is what we actually care about.
        private fun isTailnetAddress(address: Inet4Address): Boolean {
            val raw = address.address.map { it.toInt() and 0xFF }
            // 100.64.0.0/10 -> first byte 100, second byte in 64..127.
            if (raw.size < 2) return false
            if (raw[0] != 100) return false
            val second = raw[1]
            return second in 64..127
        }
    }

    /**
     * The phone's tailnet address, or null when the VPN is not up.
     *
     * `null` is meaningfully different from "unknown": it means the interface
     * was inspected and no tailnet address exists on it.
     */
    fun tailnetAddress(): String? = tailnetAddresses().firstOrNull()

    /**
     * Every tailnet address on the active network, in interface order.
     *
     * Written with explicit `if`/`: null` rather than `?: return` because Kotlin
     * forbids a `return` inside a `try` block that is itself the operand of a
     * `return`.
     */
    fun tailnetAddresses(): List<String> {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) return emptyList()

        val network = try { cm.activeNetwork } catch (e: SecurityException) { null }
        if (network == null) return emptyList()

        val properties = try { cm.getLinkProperties(network) } catch (e: SecurityException) { null }
            ?: return emptyList()

        return properties.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .filter { isTailnetAddress(it) }
            .mapNotNull { it.hostAddress }
    }

    /**
     * True when a network interface exists that looks like a tailnet tunnel.
     *
     * Separate from [tailnetAddress] because "VPN up but no usable address" is a
     * distinct failure from "no VPN".
     */
    fun tunnelPresent(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            // Tailscale presents as a VPN transport. INTERNET alone would match
            // every network and tell the user nothing. A tailnet link address
            // on any transport is equally conclusive.
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                tailnetAddresses().isNotEmpty()
        } catch (e: SecurityException) {
            false
        }
    }

    /** Short label for the UI. Never fabricates an address. */
    fun describe(): Status = when {
        tailnetAddress() != null -> Status.Connected(tailnetAddress()!!)
        tunnelPresent() -> Status.TunnelWithoutAddress
        else -> Status.NotConnected
    }

    sealed class Status {
        /** The VPN is up and we have an address. */
        data class Connected(val address: String) : Status()

        /**
         * A tunnel interface exists but carries no tailnet address. Usually
         * means Tailscale is logged out, or the tailnet is disconnected.
         */
        object TunnelWithoutAddress : Status()

        /** No tailnet interface at all. */
        object NotConnected : Status()
    }
}
