package io.earthquack.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey

/**
 * The consent rule for an unfamiliar host key: nothing is recorded until the
 * user says yes, and a changed key is never parked at all.
 *
 * Pure JVM: the delegate is an in-memory double, and the keys are generated
 * locally. The fingerprint is the real sshd one, so the tests exercise the
 * exact value the UI shows.
 */
class InteractiveHostKeyTrustTest {

    /** A [HostKeyTrust] that records to a map, and counts the recordings. */
    private class RecordingTrust : HostKeyTrust {
        val recorded = mutableMapOf<String, PublicKey>()
        var recordCalls = 0

        override fun verify(profileId: String, publicKey: PublicKey): HostKeyVerdict {
            val known = recorded[profileId] ?: return HostKeyVerdict.Unknown("", "")
            return if (known == publicKey) HostKeyVerdict.Trusted
            else HostKeyVerdict.Mismatch("", "")
        }

        override fun record(profileId: String, publicKey: PublicKey) {
            recordCalls++
            recorded[profileId] = publicKey
        }

        override fun forget(profileId: String) {
            recorded.remove(profileId)
        }
    }

    private fun key(): PublicKey {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        return generator.generateKeyPair().public
    }

    @Test
    fun `an unknown host key is parked, not recorded`() {
        val delegate = RecordingTrust()
        val trust = InteractiveHostKeyTrust(delegate)
        val presented = key()

        val verdict = trust.verify("p1", presented)

        assertTrue(verdict is HostKeyVerdict.Unknown)
        // The UI needs the fingerprint to show — and it is the real one.
        assertNotNull(trust.pendingFingerprint)
        // And critically: nothing was written to the store yet.
        assertEquals(0, delegate.recordCalls)
        assertTrue(delegate.recorded.isEmpty())
    }

    @Test
    fun `acceptance records exactly once`() {
        val delegate = RecordingTrust()
        val trust = InteractiveHostKeyTrust(delegate)
        val presented = key()
        trust.verify("p1", presented)

        assertTrue(trust.acceptPending("p1"))
        assertNull(trust.pendingFingerprint)
        assertEquals(1, delegate.recordCalls)

        // A second tap of accept parks nothing and records nothing.
        assertFalse(trust.acceptPending("p1"))
        assertEquals(1, delegate.recordCalls)

        // The same host now verifies as trusted.
        assertEquals(HostKeyVerdict.Trusted, trust.verify("p1", presented))
    }

    @Test
    fun `rejection forgets the parked key`() {
        val delegate = RecordingTrust()
        val trust = InteractiveHostKeyTrust(delegate)
        trust.verify("p1", key())
        trust.rejectPending()

        assertNull(trust.pendingFingerprint)
        assertFalse(trust.acceptPending("p1"))
        assertEquals(0, delegate.recordCalls)
    }

    @Test
    fun `a changed key is never parked`() {
        val delegate = RecordingTrust()
        val trust = InteractiveHostKeyTrust(delegate)
        val first = key()
        trust.verify("p1", first)
        trust.acceptPending("p1")

        val second = key()
        val verdict = trust.verify("p1", second)

        assertTrue(verdict is HostKeyVerdict.Mismatch)
        // Nothing new is pending: the verdict already says refuse.
        assertNull(trust.pendingFingerprint)
        assertEquals(1, delegate.recordCalls)
    }

    @Test
    fun `a trusted host parks nothing`() {
        val delegate = RecordingTrust()
        val trust = InteractiveHostKeyTrust(delegate)
        val presented = key()
        trust.verify("p1", presented)
        trust.acceptPending("p1")

        assertEquals(HostKeyVerdict.Trusted, trust.verify("p1", presented))
        assertNull(trust.pendingFingerprint)
    }
}
