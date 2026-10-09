package com.example.earthquack.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.apache.sshd.common.config.keys.KeyUtils
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The base64 codec, and what its brokenness cost.
 *
 * `Base64Codec.encode` used to emit padding by indexing a 64-character alphabet
 * with the literal `0x40`. A SHA-256 digest is 32 bytes, and 32 is not a
 * multiple of 3, so *every* fingerprint took that branch and threw
 * `StringIndexOutOfBoundsException`. `AuthorizedKeysStore` computes a
 * fingerprint in `matches()`, and its `encode(key)` helper swallows exceptions —
 * so the throw came from `fingerprintOf`, uncaught, inside
 * `PublickeyAuthenticator.authenticate`.
 *
 * The observable consequence: public-key authentication on the embedded SFTP
 * server never succeeded for an installed key. The client authenticated
 * against the server's host key, was refused, and the failure looked like a
 * server-side rejection of a key that was in fact authorized. Nothing slept and
 * no exception was swallowed to hide it — it was simply an out-of-range index.
 *
 * The codec now delegates to `java.util.Base64`, which exists on Android API 26,
 * this app's minSdk.
 */
class Base64CodecTest {

    /** The shape that triggered the bug: a sha256 digest is 32 bytes. */
    @Test
    fun `a sha256 digest encodes without throwing`() {
        val digest = MessageDigestSha256(ByteArray(32) { it.toByte() })

        val encoded = Base64Codec.encode(digest)

        assertTrue("a 32-byte digest must encode", encoded.isNotBlank())
        assertEquals(44, encoded.length)
        // ...and must round-trip.
        assertEquals(
            digest.toList(),
            Base64Codec.decode(encoded)!!.toList()
        )
    }

    @Test
    fun `encoding matches the JDK for every length mod 3`() {
        // 0, 1 and 2 leftover bytes exercise all three padding branches.
        for (length in 0..64) {
            val bytes = ByteArray(length) { (it * 37).toByte() }
            val mine = Base64Codec.encode(bytes)
            val real = java.util.Base64.getEncoder().encodeToString(bytes)
            assertEquals(
                "encoding diverged from the JDK at length $length",
                real,
                mine
            )
            assertEquals("decoding diverged at length $length", bytes.toList(), Base64Codec.decode(mine)!!.toList())
        }
    }

    @Test
    fun `an OpenSSH fingerprint is the padded form minus the equals signs`() {
        // ssh-keygen -lf prints standard base64 with padding stripped.
        val digest = MessageDigestSha256(ByteArray(48) { it.toByte() })

        val padded = java.util.Base64.getEncoder().encodeToString(digest)
        assertEquals(padded, Base64Codec.encode(digest))
        assertEquals(padded.trimEnd('='), Base64Codec.encode(digest).trimEnd('='))
    }

    @Test
    fun `decode tolerates missing padding and rejects non-base64`() {
        val bytes = ByteArray(20) { (it * 5).toByte() }
        val padded = java.util.Base64.getEncoder().encodeToString(bytes)
        assertEquals(
            bytes.toList(),
            Base64Codec.decode(padded.removeSuffix("=="))!!.toList()
        )
        assertTrue("a non-base64 body must yield null, not a throw", Base64Codec.decode("!!!!not base64!!!!") == null)
    }

    // ── what the bug actually broke ───────────────────────────────────────────

    /**
     * The fingerprint that the codec was failing to produce.
     *
     * This is the call that used to throw, and which `AuthorizedKeysStore`
     * reaches on every public-key authentication attempt.
     */
    @Test
    fun `a fingerprint of a public key can be computed`() {
        val key = generateEc().public

        val fingerprint = KeyUtils.getFingerPrint(key)

        assertTrue(fingerprint.startsWith("SHA256:"))
        // The body must be decodable, which it cannot be if the encoder is
        // producing something other than real base64.
        val body = fingerprint.removePrefix("SHA256:").trimEnd('=')
        assertNotNull(Base64Codec.decode(body))
    }

    /**
     * Two keys compared by fingerprint must compare unequal when they differ.
     *
     * Equal fingerprints are how `AuthorizedKeysStore.matches` decides whether
     * a presented key is authorized. A throwing or wrong-length codec makes
     * every comparison fail, so a key that IS authorized is refused.
     */
    @Test
    fun `different keys have different fingerprints`() {
        val a = KeyUtils.getFingerPrint(generateEc().public)
        val b = KeyUtils.getFingerPrint(generateEc().public)

        assertFalse("distinct keys must not share a fingerprint", a == b)
    }

    /**
     * The same key always fingerprints the same.
     *
     * Pinned because a stable fingerprint is what makes TOFU usable at all, and
     * because the old codec's failure mode was *silent*: `encode(key)` returns
     * null on throw, so a mismatched fingerprint looked like an unknown key.
     */
    @Test
    fun `the same key fingerprints identically every time`() {
        val key: PublicKey = generateEc().public
        val first = KeyUtils.getFingerPrint(key)
        val second = KeyUtils.getFingerPrint(key)
        val third = KeyUtils.getFingerPrint(key)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    private fun MessageDigestSha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun generateEc(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }
}
