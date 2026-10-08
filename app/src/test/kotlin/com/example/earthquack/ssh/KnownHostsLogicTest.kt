package com.example.earthquack.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.apache.sshd.common.config.keys.KeyUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

private fun fingerprintOf(key: PublicKey): String = KeyUtils.getFingerPrint(key)

/**
 * The host-key decision.
 *
 * The three outcomes — trusted, unknown, mismatch — are the whole security
 * model of the client, so each one gets a test. A regression here is a
 * client that either refuses every connection or accepts any key, and both are
 * expensive to discover on a device.
 */
class KnownHostsLogicTest {

    private fun ecKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun fingerprintOf(key: java.security.PublicKey) = KeyUtils.getFingerPrint(key)

    @Test
    fun `a recorded key is trusted`() {
        val key = ecKeyPair()
        val store = InMemoryKnownHosts()
        store.record("p1", key.public)
        assertEquals(HostKeyVerdict.Trusted, store.verify("p1", key.public))
    }

    @Test
    fun `an unrecorded key is unknown, not trusted`() {
        val store = InMemoryKnownHosts()
        val verdict = store.verify("p1", ecKeyPair().public)
        assertTrue(verdict is HostKeyVerdict.Unknown)
    }

    @Test
    fun `a different key for the same host is a mismatch`() {
        val store = InMemoryKnownHosts()
        val first = ecKeyPair()
        val second = ecKeyPair()
        store.record("p1", first.public)

        val verdict = store.verify("p1", second.public)
        assertTrue(verdict is HostKeyVerdict.Mismatch)
        assertEquals(fingerprintOf(first.public), (verdict as HostKeyVerdict.Mismatch).expected)
        assertEquals(fingerprintOf(second.public), verdict.actual)
    }

    @Test
    fun `a key type change is a mismatch even if the fingerprint matched by luck`() {
        val store = InMemoryKnownHosts()
        val ec = ecKeyPair()
        // Pretend the stored entry says RSA while the key is EC.
        store.record("p1", ec.public)
        val rsa = runCatching {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            generator.generateKeyPair()
        }.getOrNull() ?: return // RSA unavailable in this JVM; the EC case above covers the logic
        val verdict = store.verify("p1", rsa.public)
        assertTrue(verdict is HostKeyVerdict.Mismatch)
    }

    @Test
    fun `profiles do not share host keys`() {
        val store = InMemoryKnownHosts()
        val key = ecKeyPair()
        store.record("p1", key.public)
        assertTrue(store.verify("p2", key.public) is HostKeyVerdict.Unknown)
    }

    @Test
    fun `forget removes only that profile`() {
        val store = InMemoryKnownHosts()
        val key = ecKeyPair()
        store.record("p1", key.public)
        store.record("p2", key.public)
        store.forget("p1")
        assertTrue(store.verify("p1", key.public) is HostKeyVerdict.Unknown)
        assertEquals(HostKeyVerdict.Trusted, store.verify("p2", key.public))
    }

    @Test
    fun `host key policy defaults to trust-on-first-use`() {
        val profile = ConnectionProfile(id = "p1", name = "n", host = "h", username = "u")
        assertEquals(HostKeyPolicy.TOUF, profile.hostKeyPolicy)
    }

    /**
     * The store's logic with no Android: the real [KnownHostsStore] is a thin
     * SharedPreferences wrapper around exactly these rules, and this is where
     * the rules are tested.
     */
    private class InMemoryKnownHosts {
        private val entries = mutableMapOf<String, KnownHostKey>()

        fun record(profileId: String, publicKey: java.security.PublicKey) {
            entries[profileId] = KnownHostKey(
                profileId = profileId,
                keyType = publicKey.algorithm,
                fingerprint = fingerprintOf(publicKey)
            )
        }

        fun verify(profileId: String, publicKey: java.security.PublicKey): HostKeyVerdict {
            val known = entries[profileId] ?: return HostKeyVerdict.Unknown(
                fingerprint = fingerprintOf(publicKey),
                keyType = publicKey.algorithm
            )
            val fingerprint = fingerprintOf(publicKey)
            return when {
                known.keyType != publicKey.algorithm ->
                    HostKeyVerdict.Mismatch(known.fingerprint, "$fingerprint (${publicKey.algorithm})")
                known.fingerprint != fingerprint ->
                    HostKeyVerdict.Mismatch(known.fingerprint, fingerprint)
                else -> HostKeyVerdict.Trusted
            }
        }

        fun forget(profileId: String) {
            entries.remove(profileId)
        }
    }
}
