package com.example.clipboardsync

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * TailscaleDiscovery — Auto-discovers online Tailscale peers on Android.
 *
 * Reads `tailscale status --json` output (or queries local Tailscale API if available),
 * parses Self and Peers, checks port 8765 reachability with short timeout via /health (or /clipboard),
 * and selects the active clipboard server deterministically.
 */
object TailscaleDiscovery {

    private const val TAG = "TailscaleDiscovery"
    private const val DEFAULT_PORT = 8765

    data class PeerNode(
        val hostname: String,
        val dnsName: String,
        val os: String,
        val tailscaleIpv4: List<String>,
        val online: Boolean,
        val active: Boolean
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] WARN: $msg")
        }
    }

    private fun logI(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] INFO: $msg")
        }
    }

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] DEBUG: $msg")
        }
    }

    /**
     * Parse tailscale status JSON string into (Self, Peers).
     */
    fun parseTailscaleStatus(jsonStr: String): Pair<PeerNode?, List<PeerNode>> {
        if (jsonStr.isBlank()) return Pair(null, emptyList())
        return try {
            val root = JSONObject(jsonStr)

            fun parseNode(nodeObj: JSONObject?): PeerNode? {
                if (nodeObj == null) return null
                val hostname = if (nodeObj.has("HostName")) nodeObj.getString("HostName") else ""
                val dnsName = if (nodeObj.has("DNSName")) nodeObj.getString("DNSName") else ""
                val os = if (nodeObj.has("OS")) nodeObj.getString("OS") else ""
                val online = if (nodeObj.has("Online")) nodeObj.getBoolean("Online") else false
                val active = if (nodeObj.has("Active")) nodeObj.getBoolean("Active") else false

                val ipsArray = if (nodeObj.has("TailscaleIPs")) nodeObj.getJSONArray("TailscaleIPs") else null
                val ipv4List = mutableListOf<String>()
                if (ipsArray != null) {
                    for (i in 0 until ipsArray.length()) {
                        val ip = ipsArray.getString(i)
                        if (ip.contains(".")) {
                            ipv4List.add(ip)
                        }
                    }
                }

                return PeerNode(hostname, dnsName, os, ipv4List, online, active)
            }

            val selfNode = if (root.has("Self")) parseNode(root.getJSONObject("Self")) else null
            val peersList = mutableListOf<PeerNode>()

            val peersObj = if (root.has("Peer")) root.getJSONObject("Peer") else null
            if (peersObj != null) {
                val keys = peersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val pObj = peersObj.getJSONObject(key)
                    val pNode = parseNode(pObj)
                    if (pNode != null) {
                        peersList.add(pNode)
                    }
                }
            }

            Pair(selfNode, peersList)
        } catch (e: Exception) {
            logW(TAG, "[tailscale] Malformed JSON from tailscale status: ${e.message}")
            Pair(null, emptyList())
        }
    }

    /**
     * Test if clipboard service is reachable on given IP.
     * Tries /health first, falls back to /clipboard.
     */
    fun checkAppService(ip: String, port: Int = DEFAULT_PORT): Boolean {
        val healthUrl = "http://$ip:$port/health"
        val healthReq = Request.Builder().url(healthUrl).get().build()
        try {
            httpClient.newCall(healthReq).execute().use { resp ->
                if (resp.isSuccessful) return true
            }
        } catch (_: Exception) {
        }

        val clipUrl = "http://$ip:$port/clipboard"
        val clipReq = Request.Builder().url(clipUrl).get().build()
        try {
            httpClient.newCall(clipReq).execute().use { resp ->
                if (resp.isSuccessful) return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    /**
     * Execute `tailscale status --json` via Runtime exec (works if tailscale CLI binary is accessible on device).
     */
    fun getTailscaleStatusJsonFromExec(): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("tailscale", "status", "--json"))
            val reader = process.inputStream.bufferedReader()
            val output = reader.readText()
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotBlank()) output else null
        } catch (e: Throwable) {
            logD(TAG, "[tailscale] tailscale executable not reachable via exec: ${e.message}")
            null
        }
    }

    /**
     * Return list of all active working peer server nodes.
     */
    fun discoverAllWorkingServers(
        jsonOverride: String? = null,
        checkServiceFn: (String, Int) -> Boolean = ::checkAppService
    ): List<PeerNode> {
        val jsonStr = jsonOverride ?: getTailscaleStatusJsonFromExec()
        if (jsonStr.isNullOrBlank()) return emptyList()

        val (_, peers) = parseTailscaleStatus(jsonStr)
        val onlinePeers = peers.filter { it.online && it.tailscaleIpv4.isNotEmpty() }

        val workingNodes = mutableListOf<PeerNode>()
        for (peer in onlinePeers) {
            val primaryIp = peer.tailscaleIpv4[0]
            if (checkServiceFn(primaryIp, DEFAULT_PORT)) {
                workingNodes.add(peer)
            }
        }
        return workingNodes
    }

    /**
     * Main discovery entry point.
     * Filters online peers with IPv4 addresses, verifies service on port 8765,
     * and selects a server deterministically.
     */
    fun discoverServerIp(
        jsonOverride: String? = null,
        checkServiceFn: (String, Int) -> Boolean = ::checkAppService
    ): String? {
        val jsonStr = jsonOverride ?: getTailscaleStatusJsonFromExec()
        if (jsonStr.isNullOrBlank()) {
            logW(TAG, "[tailscale] Could not fetch tailscale status JSON")
            return null
        }

        val (selfNode, peers) = parseTailscaleStatus(jsonStr)
        val onlinePeers = peers.filter { it.online && it.tailscaleIpv4.isNotEmpty() }
        logI(TAG, "[tailscale] discovered ${onlinePeers.size} online peers")

        if (onlinePeers.isEmpty()) {
            logI(TAG, "[tailscale] no online peers found")
            return null
        }

        // Sort candidates by hostname then primary IP for deterministic order
        val sortedPeers = onlinePeers.sortedWith(compareBy({ it.hostname }, { it.tailscaleIpv4[0] }))

        val workingIps = mutableListOf<String>()
        for (peer in sortedPeers) {
            val primaryIp = peer.tailscaleIpv4[0]
            val name = if (peer.hostname.isNotBlank()) peer.hostname else primaryIp
            logI(TAG, "[tailscale] checking $name ($primaryIp)")
            if (checkServiceFn(primaryIp, DEFAULT_PORT)) {
                logI(TAG, "[clipboard] service found at $primaryIp:$DEFAULT_PORT")
                workingIps.add(primaryIp)
            }
        }

        if (workingIps.isEmpty()) {
            logW(TAG, "[clipboard] clipboard port unreachable on all online peers")
            return null
        }

        if (workingIps.size > 1) {
            // Sort IP numerically
            workingIps.sortWith(Comparator { ip1, ip2 ->
                val p1 = ip1.split(".").mapNotNull { it.toIntOrNull() }
                val p2 = ip2.split(".").mapNotNull { it.toIntOrNull() }
                for (i in 0 until min(p1.size, p2.size)) {
                    if (p1[i] != p2[i]) return@Comparator p1[i].compareTo(p2[i])
                }
                ip1.compareTo(ip2)
            })
            logW(TAG, "[clipboard] multiple clipboard servers found: $workingIps. Selected deterministic: ${workingIps[0]}")
        }

        return workingIps[0]
    }
}
