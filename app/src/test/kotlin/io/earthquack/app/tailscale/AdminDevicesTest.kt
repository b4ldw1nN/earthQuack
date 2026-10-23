package io.earthquack.app.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDevicesTest {

    private val json = """
        {"devices":[
          {"id":"nABC123","name":"archii","os":"linux","addresses":["100.92.160.31","fd7a::1"]},
          {"id":"nXYZ789","name":"v2253","os":"android","addresses":["100.121.203.68"]}
        ]}
    """.trimIndent()

    @Test
    fun `devices are parsed with their ids and addresses`() {
        val devices = AdminDevices.parse(json)
        assertEquals(2, devices.size)
        assertEquals("nABC123", devices[0].id)
        assertEquals("100.92.160.31", devices[0].primaryAddress)
        assertEquals("fd7a::1", devices[0].addresses[1])
    }

    @Test
    fun `this phone is found by its tailnet address`() {
        val devices = AdminDevices.parse(json)
        val own = AdminDevices.thisDevice(devices, "100.121.203.68")
        assertNotNull(own)
        assertEquals("nXYZ789", own!!.id)
        assertEquals("v2253", own.name)
    }

    @Test
    fun `an unknown address matches nothing`() {
        val devices = AdminDevices.parse(json)
        assertNull(AdminDevices.thisDevice(devices, "100.99.99.99"))
        assertNull(AdminDevices.thisDevice(devices, null))
    }

    @Test
    fun `malformed input yields no devices`() {
        assertTrue(AdminDevices.parse("not json").isEmpty())
        assertTrue(AdminDevices.parse("{}").isEmpty())
    }
}
