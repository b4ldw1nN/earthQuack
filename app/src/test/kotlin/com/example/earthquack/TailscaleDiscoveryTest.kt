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
            checkServiceFn = { ip, _ -> answered.add(ip); ip == SECOND_CANDIDATE }
        )
        assertEquals(SECOND_CANDIDATE, discovered)
        // The probe stopped at the answer rather than scanning every candidate.
        assertEquals(listOf(FIRST_CANDIDATE, SECOND_CANDIDATE), answered)
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
            checkServiceFn = { ip, _ -> ip == FIRST_CANDIDATE || ip == SECOND_CANDIDATE }
        )
        assertEquals(listOf(FIRST_CANDIDATE, SECOND_CANDIDATE), found)
    }

    private companion object {
        // The two entries in TailscaleDiscovery.TAILSCALE_CANDIDATES, in order.
        // Named rather than inlined so the test cannot drift from the source's
        // candidate list the way it did when a third address was removed.
        const val FIRST_CANDIDATE = "100.92.160.31"    // Arch Linux
        const val SECOND_CANDIDATE = "100.105.106.87"  // Windows (currently offline)
    }
}
