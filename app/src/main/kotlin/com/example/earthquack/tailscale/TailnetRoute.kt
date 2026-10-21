package com.example.earthquack.tailscale

import java.net.InetSocketAddress

/**
 * TailnetRoute — dialing policy for this app's embedded Tailscale node.
 *
 * The app owns a node with its own tailnet IP (kept entirely in-process via
 * tsnet; there is no system VPN). Any address in the tailnet is only
 * reachable from this app by dialing through the node's embedded SOCKS5
 * proxy — packets sent straight at a 100.x address leave via the ordinary
 * Android routing table and go nowhere.
 *
 * The SOCKS port comes from the shim itself, not the lifecycle engine, so
 * the answer is correct no matter which flow (login manager, engine, or
 * auto-start) brought the node up. The helper deliberately knows nothing
 * about HTTP client libraries: it returns the bare SOCKS address and each
 * caller frames it with its own proxy type. Hosts are meant to come from
 * user configuration (ServerConfig, rclone remotes, profiles) — nothing is
 * hardcoded here. Thread-safe; safe from any network-call context.
 */
object TailnetRoute {

    /** Port of the embedded node's SOCKS5 listener, or 0 if it is not up. */
    fun socksPort(): Int = runCatching { Tailscale.socksPort().toInt() }.getOrDefault(0)

    /**
     * The SOCKS endpoint (loopback + node port) through which [host] is
     * reachable, or null when the node is down or [host] is not a tailnet
     * address. Public-internet and LAN destinations must keep ordinary
     * connectivity, so callers treat null as "no proxying".
     */
    fun socksAddressFor(host: String): InetSocketAddress? {
        val port = socksPort()
        if (port <= 0) return null
        if (!isTailnetAddress(host)) return null
        return InetSocketAddress("127.0.0.1", port)
    }

    /**
     * True for addresses the embedded node knows how to dial: CGNAT IPs
     * (100.64/10), *.ts.net names, and bare MagicDNS machine names — those
     * last ones are confirmed with the node's resolver so a LAN name that
     * happens to collide is not misrouted.
     */
    fun isTailnetAddress(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty() || h == "localhost") return false
        if (h.endsWith(".ts.net")) return true
        if (looksTailnetIp(h)) return true
        if ('.' in h) return false // plain domain: not ours to route
        val resolved = runCatching { Tailscale.resolve(h) }.getOrNull().orEmpty()
        return looksTailnetIp(resolved)
    }

    private fun looksTailnetIp(value: String): Boolean {
        val parts = value.trim().split(".")
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        return nums[0] == 100 && nums[1] in 64..127
    }
}
