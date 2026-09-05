package com.example.earthquack

import org.junit.Assert.*
import org.junit.Test

class TailscaleDiscoveryTest {

    private val sampleJson = """{
      "Version": "1.102.3",
      "Self": {
        "HostName": "archii",
        "DNSName": "archii.snares-hexatonic.ts.net.",
        "OS": "linux",
        "TailscaleIPs": ["100.92.160.31"],
        "Online": true
      },
      "Peer": {
        "node1": {
          "HostName": "DESKTOP-9S55DRM",
          "DNSName": "desktop-9s55drm.snares-hexatonic.ts.net.",
          "OS": "windows",
          "TailscaleIPs": ["100.105.106.87"],
          "Online": false
        },
        "node2": {
          "HostName": "V2253",
          "DNSName": "v2253.snares-hexatonic.ts.net.",
          "OS": "android",
          "TailscaleIPs": ["100.87.152.1"],
          "Online": true
        }
      }
    }"""

    @Test
    fun testParseTailscaleStatus() {
        val (selfNode, peers) = TailscaleDiscovery.parseTailscaleStatus(sampleJson)
        assertNotNull(selfNode)
        assertEquals("archii", selfNode?.hostname)
        assertEquals(listOf("100.92.160.31"), selfNode?.tailscaleIpv4)

        assertEquals(2, peers.size)
        val offline = peers.first { it.hostname == "DESKTOP-9S55DRM" }
        assertFalse(offline.online)

        val online = peers.first { it.hostname == "V2253" }
        assertTrue(online.online)
        assertEquals("100.87.152.1", online.tailscaleIpv4[0])
    }

    @Test
    fun testDiscoverServerIpSingleOnline() {
        val discovered = TailscaleDiscovery.discoverServerIp(
            jsonOverride = sampleJson,
            checkServiceFn = { ip, _ -> ip == "100.87.152.1" }
        )
        assertEquals("100.87.152.1", discovered)
    }

    @Test
    fun testDiscoverServerIpMultipleDeterministic() {
        val multiJson = """{
          "Self": {"HostName": "V2253", "Online": true, "TailscaleIPs": ["100.87.152.1"]},
          "Peer": {
            "p1": {"HostName": "archii", "Online": true, "TailscaleIPs": ["100.92.160.31"]},
            "p2": {"HostName": "DESKTOP-9S55DRM", "Online": true, "TailscaleIPs": ["100.105.106.87"]}
          }
        }"""

        val discovered = TailscaleDiscovery.discoverServerIp(
            jsonOverride = multiJson,
            checkServiceFn = { _, _ -> true }
        )
        // Numerical sort of 100.92.160.31 vs 100.105.106.87 should pick 100.92.160.31
        assertEquals("100.92.160.31", discovered)
    }
}
