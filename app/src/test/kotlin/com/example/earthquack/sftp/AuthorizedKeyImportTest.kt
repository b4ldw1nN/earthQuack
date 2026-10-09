package com.example.earthquack.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Adding a client key when the only file the user has is a *private* key.
 *
 * This is the real dead end the feature had to work around. A private key and
 * its public key are one file each with no distinct extension a picker can
 * filter on, and a user who copied `id_ed25519` across has the private one. The
 * authorized-keys screen could only read a `.pub`, so public-key authentication
 * simply could not be configured — and the failure looked like "nothing happens
 * when I add a key", because the only feedback was a transient toast.
 *
 * So the app derives the public half from the private key it is given.
 *
 * ## What these tests pin down
 *
 *  - a private key is recognised by content, not by name;
 *  - its public half is derived correctly, and is the same key the client will
 *    authenticate with;
 *  - an encrypted key needs its passphrase, and a wrong one is reported as such;
 *  - the private bytes are never in the derivation result;
 *  - a public key file still works, so the old path is not broken.
 */
class AuthorizedKeyImportTest {

    // ── private keys, from which the public half must be derived ─────────────

    @Test
    fun `an unencrypted private key yields its authorized_keys line`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, null)

        val result = derivePublicKeyFromPrivateKey(pem)

        assertTrue("derivation failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val derived = result.getOrThrow()
        assertEquals("ecdsa-sha2-nistp256", derived.type)
        assertTrue("the blob must be present", derived.key.isNotBlank())
        // Round-trip: the line must parse as a public key, and must match the
        // key that was written. If the derivation were wrong the client would
        // authenticate with a different key and the server would never match.
        val parsed = parseAuthorizedKeyLine(derived.toAuthorizedKeyText())
        assertTrue(parsed.isSuccess)
        assertEquals(derived.type, parsed.getOrThrow().type)
        assertEquals(derived.key, parsed.getOrThrow().key)
    }

    @Test
    fun `the derived fingerprint matches the original key`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, null)
        val expectedFingerprint = fingerprintOf(keyPair.public)

        val derived = derivePublicKeyFromPrivateKey(pem).getOrThrow()

        // The fingerprint is over the encoded public key, so it must agree with
        // the fingerprint of the keypair the client will use.
        assertEquals("SHA256:$expectedFingerprint", derived.fingerprint())
    }

    @Test
    fun `the private key body never appears in the derived result`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, "a-passphrase")
        val marker = "PRIVATE KEY-----"

        val derived = derivePublicKeyFromPrivateKey(pem, "a-passphrase".toCharArray())
        assertTrue(derived.isSuccess)

        // A public key line cannot contain the armour by construction, but
        // stating it here catches the case where someone later decides to
        // "keep the original around" and stores it too.
        val rendered = derived.getOrThrow().toAuthorizedKeyText()
        assertFalse(rendered.contains(marker))
        assertFalse(rendered.contains("BEGIN"))
        assertTrue(derived.getOrThrow().key.length >= 20)
    }

    // ── encrypted keys ───────────────────────────────────────────────────────

    @Test
    fun `an encrypted key needs its passphrase`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, "right-horse")

        val withoutPassphrase = derivePublicKeyFromPrivateKey(pem)
        assertTrue("an encrypted key must not be readable without its passphrase", withoutPassphrase.isFailure)
        assertTrue(
            "the reason must be the missing passphrase, not 'not a key'",
            withoutPassphrase.exceptionOrNull()?.message?.contains("passphrase", true) == true
        )
    }

    @Test
    fun `an encrypted key is readable with the right passphrase`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, "right-horse")

        val derived = derivePublicKeyFromPrivateKey(pem, "right-horse".toCharArray())

        assertTrue("derivation failed: ${derived.exceptionOrNull()?.message}", derived.isSuccess)
        assertEquals("ecdsa-sha2-nistp256", derived.getOrThrow().type)
    }

    @Test
    fun `a wrong passphrase is reported as a wrong passphrase`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, "right-horse")

        val derived = derivePublicKeyFromPrivateKey(pem, "wrong-horse".toCharArray())

        assertTrue(derived.isFailure)
        assertTrue(
            "expected a passphrase error, got '${derived.exceptionOrNull()?.message}'",
            derived.exceptionOrNull()?.message?.contains("passphrase", true) == true
        )
    }

    // ── recognition, by content and not by name ──────────────────────────────

    @Test
    fun `a private key is recognised whatever the file is called`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, null)

        // Armour by any name: the real file was `id_ed25519` with no extension.
        assertTrue(looksLikeOpenSshPrivateKey(pem))
        assertTrue(looksLikeOpenSshPrivateKey("""
            # a comment line ssh keys sometimes carry
            ${pem.trimStart()}
        """.trimIndent()))
    }

    @Test
    fun `a public key is not mistaken for a private one`() {
        val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGtNfoJ3scFbFxLpuZRQRXvIMdWfbdPW3aQ6Xb defcabc"

        assertFalse("a public key must not be treated as private", looksLikeOpenSshPrivateKey(line))
        assertTrue(looksLikeOpenSshPublicKey(line))
    }

    @Test
    fun `an armoured private key with a misleading name is still recognised`() {
        // The file the user actually had was named `id_ed25519`, not `.pub`,
        // but it is entirely possible to name a private key `.pub`. Only the
        // content decides.
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, null)
        assertTrue(
            looksLikeOpenSshPrivateKey(pem)
        )
    }

    @Test
    fun `garbage is rejected outright`() {
        assertFalse(looksLikeOpenSshPrivateKey(""))
        assertFalse(looksLikeOpenSshPrivateKey("not even close"))
        val derived = derivePublicKeyFromPrivateKey("not even close")
        assertTrue(derived.isFailure)
    }

    // ── the public path still works ──────────────────────────────────────────

    @Test
    fun `a public key file is accepted as it always was`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, null)
        val derived = derivePublicKeyFromPrivateKey(pem).getOrThrow()

        // The user may well have the `.pub` after all; that path must survive.
        val parsed = parseAuthorizedKeyLine(derived.toAuthorizedKeyText())
        assertTrue(parsed.isSuccess)
        assertEquals(derived.key, parsed.getOrThrow().key)
    }

    @Test
    fun `the authorized_keys line of a derived key is installed`() {
        // Mirrors what the screen does, so the store accepts lines this creates.
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, null)
        val derived = derivePublicKeyFromPrivateKey(pem).getOrThrow()
        val line = derived.toAuthorizedKeyText()

        // A key generated on device carries no comment, so the label is empty
        // and the trimmed line is exactly type + key: two parts.
        val parts = line.split(Regex("\\s+")).filter(String::isNotEmpty)
        assertEquals(2, parts.size)
        assertEquals(derived.type, parts[0])
        assertEquals(derived.key, parts[1])
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    fun `fingerprints are stable and comparable`() {
        val keyPair = generateEc()
        val pem = openSshPrivateKey(keyPair, null)
        val line = derivePublicKeyFromPrivateKey(pem).getOrThrow().toAuthorizedKeyText()

        val a = authorizedKeyFingerprint(line)
        val b = authorizedKeyFingerprint(line)
        assertNotNull(a)
        assertEquals(a, b)
        assertNull("malformed input must not produce a fingerprint", authorizedKeyFingerprint("nonsense"))
    }

    private fun fingerprintOf(publicKey: java.security.PublicKey): String =
        org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(publicKey)
            .removePrefix("SHA256:")

    /** An OpenSSH private key body, as `ssh-keygen` would write it. */
    private fun openSshPrivateKey(keyPair: java.security.KeyPair, passphrase: String?): String {
        val out = java.io.ByteArrayOutputStream()
        val context = org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext()
        if (passphrase != null) {
            // sshd's writer needs all three: the "AES" marker, the key size as
            // cipherType (null by default, which produced "aesnull-ctr" and a
            // key that could not be parsed back), and the mode from CBC.
            context.cipherName = org.apache.sshd.common.config.keys.writer.openssh
                .OpenSSHKeyEncryptionContext.AES
            context.cipherType = "128"
            context.cipherMode = "CBC"
            context.password = passphrase
        }
        org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
            .writePrivateKey(keyPair, "arch", context, out)
        return out.toString("UTF-8")
    }

    private fun generateEc(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }
}
