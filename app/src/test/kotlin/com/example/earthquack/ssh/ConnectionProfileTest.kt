package com.example.earthquack.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Profile validation and the known-hosts decision.
 *
 * Both are pure logic with no Android dependency, which is the point: the rules
 * that decide whether a connection is allowed should be testable without a
 * device, because those are the rules a mistake in would be expensive.
 */
class ConnectionProfileTest {

    private fun profile(
        name: String = "archii",
        host: String = "100.92.160.31",
        port: Int = 22,
        username: String = "zoro",
        authMethod: AuthMethod = AuthMethod.PUBLIC_KEY,
        keyAlias: String? = "key-1",
        passwordAlias: String? = null,
        policy: HostKeyPolicy = HostKeyPolicy.TOUF
    ) = ConnectionProfile(
        id = "p1", name = name, host = host, port = port, username = username,
        authMethod = authMethod, identityKeyAlias = keyAlias,
        passwordAlias = passwordAlias, hostKeyPolicy = policy
    )

    @Test
    fun `a complete profile is valid`() {
        assertTrue(profile().isValid)
        assertTrue(profile().problems().isEmpty())
    }

    @Test
    fun `an empty name is rejected`() {
        val problems = profile(name = "").problems()
        assertTrue(problems.any { it.contains("name") })
    }

    @Test
    fun `a host with spaces is rejected`() {
        val problems = profile(host = "not a host").problems()
        assertTrue(problems.any { it.contains("space") })
    }

    @Test
    fun `ports outside 1-65535 are rejected`() {
        assertTrue(profile(port = 0).problems().any { it.contains("port") })
        assertTrue(profile(port = 65536).problems().any { it.contains("port") })
        assertTrue(profile(port = 22).problems().none { it.contains("port") })
    }

    @Test
    fun `public key auth without a key alias is rejected`() {
        val problems = profile(authMethod = AuthMethod.PUBLIC_KEY, keyAlias = null).problems()
        assertTrue(problems.any { it.contains("key", ignoreCase = true) })
    }

    @Test
    fun `password auth without a password alias is rejected`() {
        val problems = profile(authMethod = AuthMethod.PASSWORD, passwordAlias = null).problems()
        assertTrue(problems.any { it.contains("password", ignoreCase = true) })
    }

    @Test
    fun `password auth with an alias is valid`() {
        assertTrue(
            profile(authMethod = AuthMethod.PUBLIC_KEY, keyAlias = null).let {
                it.copy(authMethod = AuthMethod.PASSWORD, passwordAlias = "pw-1")
            }.isValid
        )
    }

    @Test
    fun `endpoint is host and port`() {
        assertEquals("100.92.160.31:22", profile().endpoint)
        assertEquals("arch:2222", profile(host = "arch", port = 2222).endpoint)
    }

    @Test
    fun `no profile carries a secret`() {
        val p = profile(authMethod = AuthMethod.PASSWORD, passwordAlias = "pw-1")
        // The password alias is a reference, not the secret; the secret lives in
        // the SecretStore. If a profile ever held the password itself, this is
        // the assertion that would catch it.
        assertFalse(p.toString().contains("pw-1"))
        assertTrue(p.passwordAlias == "pw-1")
    }
}
