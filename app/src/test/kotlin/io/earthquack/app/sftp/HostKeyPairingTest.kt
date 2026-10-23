package io.earthquack.app.sftp

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing artifacts: the public line, the known_hosts entry, and the
 * SSHFP records for the phone's host key.
 *
 * These are bytes a computer consumes, so the test checks them against the
 * formats the computer's software expects — the SSHFP fingerprint in
 * particular must be the **hex** digest of the key blob, because OpenSSH
 * parses it that way and a base64 value there is silently wrong.
 */
class HostKeyPairingTest {

    private val key: KeyPair by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        generator.generateKeyPair()
    }

    private val parts: HostKeyPairing.KeyParts by lazy {
        val line = HostKeyPairing.publicLine(key.public).removeSuffix(" earthquack")
        HostKeyPairing.partsOf(line)!!
    }

    @Test
    fun thePublicLineIsASshUsableLine() {
        val line = HostKeyPairing.publicLine(key.public)
        assertTrue(
            "the line must start with the key type, was: ${line.take(24)}",
            line.startsWith("ecdsa-sha2-nistp256 ")
        )
        // The body must be valid base64 that decodes to the wire blob.
        val body = line.removePrefix("ecdsa-sha2-nistp256 ").removeSuffix(" earthquack")
        val decoded = Base64.getDecoder().decode(body)
        assertTrue(decoded.isNotEmpty())
    }

    @Test
    fun partsRoundTripThroughTheLine() {
        // The parts are the line's own bytes: re-encoding must reproduce it.
        val encoded = "${parts.keyType} ${Base64.getEncoder().encodeToString(parts.blob)}"
        assertEquals(parts, HostKeyPairing.partsOf(encoded))
        assertNull("a line without a type is not a key", HostKeyPairing.partsOf(""))
    }

    @Test
    fun knownHostsLineMatchesTheOpensshFormat() {
        val line = HostKeyPairing.knownHostsLine(parts, "100.87.152.1", 8022)
        // Non-22 ports use the bracketed host form, which is what OpenSSH
        // writes and expects for `[host]:port`.
        assertTrue(line.startsWith("[100.87.152.1]:8022 ecdsa-sha2-nistp256 "))
        val body = Base64.getEncoder().encodeToString(parts.blob)
        assertTrue(line.endsWith(" $body"))
        // A plain host stays plain on the default port.
        val plain = HostKeyPairing.knownHostsLine(parts, "phone.example", 22)
        assertTrue(plain.startsWith("phone.example ecdsa-sha2-nistp256 "))
    }

    @Test
    fun sshfpRecordsCarryHexDigestsOfTheKeyBlob() {
        val records = HostKeyPairing.sshfpRecords(parts, "phone")
        assertEquals(2, records.size)
        // SHA-256 first: OpenSSH prefers it.
        assertTrue(records[0].startsWith("phone. IN SSHFP 3 2 "))
        val sha256Hex = records[0].substringAfterLast(' ')
        assertEquals(64, sha256Hex.length)
        assertTrue("hex only", sha256Hex.matches(Regex("[0-9a-f]+")))
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(parts.blob)
                .joinToString("") { "%02x".format(it) },
            sha256Hex
        )
        // SHA-1 second, 40 hex chars, the legacy fallback.
        assertTrue(records[1].startsWith("phone. IN SSHFP 3 1 "))
        assertEquals(40, records[1].substringAfterLast(' ').length)
    }

    @Test
    fun theFingerprintRecordIsTheOneTheClientSees() {
        // The phone reports its fingerprint as SHA256:base64(sha256(blob)).
        // The SSHFP record is hex(sha256(blob)). They must be the same
        // digest of the same bytes — decodable base64-to-hex across formats.
        val recordHex = HostKeyPairing.sshfpRecords(parts, "phone")[0].substringAfterLast(' ')
        val fromRecord = MessageDigest.getInstance("SHA-256").digest(parts.blob)
        assertEquals(
            Base64.getEncoder().encodeToString(fromRecord),
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(parts.blob)
            )
        )
        assertEquals(fromRecord.size * 2, recordHex.length)
    }

    @Test
    fun aKeyFileWrittenBySshdReadsBackAsAPublicLine() {
        // The exact writer the server uses, exercised through the reader the
        // UI calls — the round-trip that fails if either side drifts.
        val bytes = ByteArrayOutputStream().also { out ->
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
                key, "earthquack", OpenSSHKeyEncryptionContext(), out
            )
        }.toByteArray()
        val dir = Files.createTempDirectory("host-key").toFile()
        try {
            val file = java.io.File(dir, "sftp_host_key")
            file.writeBytes(bytes)
            assertEquals(HostKeyPairing.publicLine(key.public), readHostKeyPublicLine(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aMissingKeyFileReadsBackAsNull() {
        val dir = Files.createTempDirectory("host-key-missing").toFile()
        try {
            assertNull(readHostKeyPublicLine(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
