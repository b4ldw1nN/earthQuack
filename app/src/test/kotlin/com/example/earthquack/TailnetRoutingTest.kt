package com.example.earthquack

import com.example.earthquack.storage.RcloneHealthProbe
import com.example.earthquack.tailscale.TailnetRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetRoutingTest {

    @Test
    fun `tailnet addresses are recognised`() {
        assertTrue(TailnetRoute.isTailnetAddress("100.121.203.68"))
        assertTrue(TailnetRoute.isTailnetAddress("100.92.160.31"))
        assertTrue(TailnetRoute.isTailnetAddress("phone.tail-scale.ts.net"))
    }

    @Test
    fun `other addresses are not tailnet`() {
        assertFalse(TailnetRoute.isTailnetAddress("8.8.8.8"))
        assertFalse(TailnetRoute.isTailnetAddress("drive.google.com"))
        assertFalse(TailnetRoute.isTailnetAddress("192.168.1.10"))
        assertFalse(TailnetRoute.isTailnetAddress("localhost"))
        assertFalse(TailnetRoute.isTailnetAddress(""))
    }

    @Test
    fun `no socks address without a running node`() {
        // No node is running in a JVM test, so nothing can be proxied.
        assertNull(TailnetRoute.socksAddressFor("100.121.203.68"))
    }
}

class RemoteHealthClassificationTest {

    @Test
    fun `auth failures are reported as needing sign-in`() {
        val health = RcloneHealthProbe.classify("Invalid refresh token, token expired")
        assertEquals(RcloneHealthProbe.State.NeedsAuth, health.state)
    }

    @Test
    fun `transport failures are reported as unreachable`() {
        val health = RcloneHealthProbe.classify("dial tcp 100.92.160.31:4545: connect: connection timed out")
        assertEquals(RcloneHealthProbe.State.Unreachable, health.state)
    }

    @Test
    fun `missing backends are reported as not supported`() {
        val health = RcloneHealthProbe.classify("didn't find backend called \"koofr\"")
        assertEquals(RcloneHealthProbe.State.NotSupported, health.state)
    }

    @Test
    fun `anything else is a plain failure`() {
        val health = RcloneHealthProbe.classify("error in ListJSON: directory not found")
        assertEquals(RcloneHealthProbe.State.Failed, health.state)
    }
}

class DesktopCapabilitiesTest {

    private fun capabilities(aes: Boolean, fingerprint: String = "") = DesktopCapabilities(
        service = "earthQuack",
        version = "0.1.0",
        aesEnabled = aes,
        aesKeyFingerprint = fingerprint,
        filePort = 8876,
    )

    @Test
    fun `matching encryption settings produce no mismatch`() {
        assertNull(capabilities(aes = true, fingerprint = "abc").mismatchWith(true, "abc"))
        assertNull(capabilities(aes = false).mismatchWith(false, ""))
    }

    @Test
    fun `desktop encrypting while the phone does not is a mismatch`() {
        val message = capabilities(aes = true).mismatchWith(phoneAesEnabled = false, phoneKeyFingerprint = "")
        assertTrue(message != null && message.contains("desktop encrypts"))
    }

    @Test
    fun `phone encrypting while the desktop does not is a mismatch`() {
        val message = capabilities(aes = false).mismatchWith(phoneAesEnabled = true, phoneKeyFingerprint = "abc")
        assertTrue(message != null && message.contains("phone encrypts"))
    }

    @Test
    fun `different keys are a mismatch`() {
        val message = capabilities(aes = true, fingerprint = "aaa").mismatchWith(true, "bbb")
        assertTrue(message != null && message.contains("different AES keys"))
    }

    @Test
    fun `fingerprint is stable and short`() {
        val fingerprint = DesktopCapabilities.fingerprint("secret")
        assertEquals(16, fingerprint.length)
        assertEquals(fingerprint, DesktopCapabilities.fingerprint("secret"))
        assertEquals("", DesktopCapabilities.fingerprint(""))
    }
}
