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
        if (looksTailnetHost(h)) return true
        if ('.' in h) return false // plain domain: not ours to route
        val resolved = runCatching { Tailscale.resolve(h) }.getOrNull().orEmpty()
        return looksTailnetIp(resolved)
    }

    fun looksTailnetHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.endsWith(".ts.net")) return true
        return looksTailnetIp(h)
    }

    fun looksTailnetIp(value: String): Boolean {
        val parts = value.trim().split(".")
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        return nums[0] == 100 && nums[1] in 64..127
    }

    /**
     * This node's MagicDNS name, e.g. "phone.tailnet.ts.net".
     *
     * The name outlives an IP change, so it is the address to hand to a
     * peer: a remote configured with an IP breaks the next time the node
     * re-enrols. Null when the node does not report one.
     */
    fun thisDeviceDnsName(): String? = runCatching {
        val json = org.json.JSONObject(Tailscale.thisDevice())
        json.optString("dns_name").trimEnd('.').takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The MagicDNS names of the tailnet's peers, by IP address.
     *
     * Used to rewrite a remote's host from an address to a name, so the
     * configuration keeps working when the address changes.
     */
    fun peerDnsNames(): Map<String, String> = runCatching {
        val array = org.json.JSONArray(Tailscale.peers())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val ip = o.optString("ip")
            // The node reports the name in FQDN form; the trailing dot is
            // correct DNS but odd in a config file.
            val dns = o.optString("dns_name").trimEnd('.')
            if (ip.isBlank() || dns.isBlank()) return@mapNotNull null
            ip to dns
        }.toMap()
    }.getOrDefault(emptyMap())
}
