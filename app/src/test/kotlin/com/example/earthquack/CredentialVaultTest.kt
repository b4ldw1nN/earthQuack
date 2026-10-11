package com.example.earthquack

import com.example.earthquack.ssh.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section C: vault migration and failure safety.
 *
 * All cases drive the pure [CredentialVault] core with an in-memory vault
 * and a map-backed legacy store — no Context, no Keystore — so they run on
 * the JVM. [ServerConfig] is a thin adapter over exactly this logic.
 */
class CredentialVaultTest {

    private class MapLegacy : CredentialVault.LegacySecrets {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun clear(key: String) { map.remove(key) }
    }

    private class FailingSecrets : com.example.earthquack.ssh.SecretStore {
        override fun put(alias: String, bytes: ByteArray) = throw RuntimeException("keystore locked")
        override fun get(alias: String): ByteArray? = throw RuntimeException("keystore locked")
        override fun delete(alias: String) = throw RuntimeException("keystore locked")
        override fun has(alias: String): Boolean = throw RuntimeException("keystore locked")
    }

    @Test
    fun `legacy value migrates to vault and plaintext is cleared`() {
        val secrets = InMemorySecretStore()
        val legacy = MapLegacy().apply { map["auth_token"] = "tok-123" }
        val out = CredentialVault.resolve(secrets, legacy, "auth_token", CredentialVault.ALIAS_AUTH_TOKEN)
        assertEquals("tok-123", out)
        assertEquals("tok-123", secrets.get(CredentialVault.ALIAS_AUTH_TOKEN)?.toString(Charsets.UTF_8))
        assertTrue("plaintext must be deleted after verified migration", legacy.map.isEmpty())
    }

    @Test
    fun `vault value wins and legacy is left alone when vault already set`() {
        val secrets = InMemorySecretStore()
        secrets.put(CredentialVault.ALIAS_AES_KEY, "vault-key".toByteArray(Charsets.UTF_8))
        val legacy = MapLegacy().apply { map["aes_key_b64"] = "stale-legacy" }
        val out = CredentialVault.resolve(secrets, legacy, "aes_key_b64", CredentialVault.ALIAS_AES_KEY)
        assertEquals("vault-key", out)
    }

    @Test
    fun `credentials remain usable after restart (vault persists, legacy empty)`() {
        val secrets = InMemorySecretStore()
        val legacy = MapLegacy()
        CredentialVault.store(secrets, legacy, "auth_token", CredentialVault.ALIAS_AUTH_TOKEN, "tok-abc")
        // "Restart": new legacy wrapper, same vault.
        val legacy2 = MapLegacy()
        val out = CredentialVault.resolve(secrets, legacy2, "auth_token", CredentialVault.ALIAS_AUTH_TOKEN)
        assertEquals("tok-abc", out)
        assertTrue(legacy.map.isEmpty() && legacy2.map.isEmpty())
    }

    @Test
    fun `vault failure never destroys the legacy configuration`() {
        val secrets = FailingSecrets()
        val legacy = MapLegacy().apply { map["auth_token"] = "keep-me" }
        val out = CredentialVault.resolve(secrets, legacy, "auth_token", CredentialVault.ALIAS_AUTH_TOKEN)
        assertEquals("keep-me", out)
        assertEquals("keep-me", legacy.map["auth_token"])
    }

    @Test
    fun `store failure falls back to legacy rather than losing the value`() {
        val secrets = FailingSecrets()
        val legacy = MapLegacy()
        CredentialVault.store(secrets, legacy, "auth_token", CredentialVault.ALIAS_AUTH_TOKEN, "tok-new")
        assertEquals("tok-new", legacy.map["auth_token"])
    }

    @Test
    fun `blank store forgets both backends`() {
        val secrets = InMemorySecretStore()
        secrets.put(CredentialVault.ALIAS_AUTH_TOKEN, "x".toByteArray(Charsets.UTF_8))
        val legacy = MapLegacy().apply { map["auth_token"] = "x" }
        CredentialVault.store(secrets, legacy, "auth_token", CredentialVault.ALIAS_AUTH_TOKEN, "  ")
        assertEquals(null, secrets.get(CredentialVault.ALIAS_AUTH_TOKEN))
        assertTrue(legacy.map.isEmpty())
    }
}
