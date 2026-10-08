package com.example.earthquack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Discovery's candidate iteration and preference order.
 *
 * The JSON-parsing variant of this test was removed with the API it tested:
 * Android cannot run `tailscale status`, so parsing that output was never
 * something this class could do. What *is* testable without a device is the
 * decision the class actually makes — probe each candidate in order, take the
 * first that answers — and that is what these tests cover.
 */
class TailscaleDiscoveryTest {

    @Test
    fun `the first candidate that answers is returned`() {
        val answered = mutableListOf<String>()
        val discovered = TailscaleDiscovery.discoverServerIp(
            checkServiceFn = { ip, _ -> answered.add(ip); ip == "100.87.152.1" }
        )
        assertEquals("100.87.152.1", discovered)
        // The probe stopped at the answer rather than scanning every candidate.
        assertEquals(listOf("100.92.160.31", "100.87.152.1"), answered)
    }

    @Test
    fun `a network where nothing answers yields null`() {
        val discovered = TailscaleDiscovery.discoverServerIp(
            checkServiceFn = { _, _ -> false }
        )
        assertNull(discovered)
    }

    @Test
    fun `every reachable server is collected`() {
        val found = TailscaleDiscovery.discoverAllWorkingServers(
            checkServiceFn = { ip, _ -> ip == "100.92.160.31" || ip == "100.87.152.1" }
        )
        assertEquals(listOf("100.92.160.31", "100.87.152.1"), found)
    }
}
