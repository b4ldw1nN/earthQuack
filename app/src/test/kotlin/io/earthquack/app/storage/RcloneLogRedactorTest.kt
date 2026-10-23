package io.earthquack.app.storage

import io.earthquack.app.ssh.HostKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section C: log redaction + host-key policy guard.
 *
 * - No test or log path may carry a secret value; the redactor is the
 *   enforcement point for rclone-derived strings.
 * - Host-key verification must stay enabled: the policy enum must offer no
 *   accept-all/disable option (a future `DISABLED` variant would be a
 *   silent MITM). Behavioural TOFU/STRICT cases live in `KnownHostsLogicTest`.
 */
class RcloneLogRedactorTest {

    @Test
    fun `secret values are redacted`() {
        val raw = """{"password": "s3cr3t-value", "type": "sftp", "token": "abc123"}"""
        val out = RcloneLogRedactor.redact(raw)
        assertFalse(out.contains("s3cr3t-value"))
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("connection refused", RcloneLogRedactor.redact("connection refused"))
        assertEquals("", RcloneLogRedactor.redact(null))
    }

    @Test
    fun `failure description never carries the raw envelope`() {
        val raw = """{"error": "auth failed", "token": "live-secret-1"}"""
        val out = RcloneLogRedactor.describeFailure("config/create", 400, raw)
        assertTrue(out.contains("config/create"))
        assertTrue(out.contains("400"))
        assertFalse(out.contains("live-secret-1"))
    }

    @Test
    fun `only the exact config filename is sensitive`() {
        assertTrue(RcloneLogRedactor.isSensitivePath("/data/data/x/files/rclone/rclone.conf"))
        assertFalse(RcloneLogRedactor.isSensitivePath("/data/data/x/files/rclone/other.conf"))
        assertFalse(RcloneLogRedactor.isSensitivePath(null))
    }

    @Test
    fun `host-key verification has no disable switch`() {
        val names = HostKeyPolicy.values().map { it.name }.sorted()
        assertEquals(listOf("STRICT", "TOUF"), names)
    }
}
