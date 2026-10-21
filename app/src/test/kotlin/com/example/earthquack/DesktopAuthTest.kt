package com.example.earthquack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's auth rules, mirrored from daemon/config.py.
 *
 * Kept in step with the Python by hand: the behaviour is small enough that
 * two copies are easier to keep honest than a shared implementation across
 * two runtimes, and the tests here state the contract the phone relies on.
 */
class DesktopAuthTest {

    private fun isAuthorized(token: String, header: String?): Boolean {
        if (token.isEmpty()) return true
        if (header == null) return false
        return header.trim() == "Bearer $token"
    }

    @Test
    fun `no configured token means no enforcement`() {
        assertTrue(isAuthorized("", null))
        assertTrue(isAuthorized("", "Bearer anything"))
    }

    @Test
    fun `a configured token is required`() {
        assertFalse(isAuthorized("secret", null))
        assertFalse(isAuthorized("secret", "Bearer wrong"))
        assertFalse(isAuthorized("secret", "secret"))
        assertTrue(isAuthorized("secret", "Bearer secret"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(isAuthorized("secret", "  Bearer secret  "))
    }
}
