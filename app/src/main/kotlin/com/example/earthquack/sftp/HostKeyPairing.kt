package com.example.earthquack.sftp

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import org.apache.sshd.common.config.keys.PublicKeyEntry

/**
 * Turns the SFTP server's host key into the three artifacts a computer needs
 * to trust it, from the key itself — never typed, never transcribed.
 *
 * 1. **The public key line** (`ecdsa-sha2-nistp256 AAAA…`): what goes into
 *    `~/.ssh/authorized_keys` on the computer when the *computer* connects
 *    out, or into a `known_hosts` when the computer connects to the phone.
 * 2. **A one-line `known_hosts` entry** for the phone: host, port, and key
 *    already combined, so fixing a stale pin is one paste.
 * 3. **The SSHFP DNS records** (`IN SSHFP 3 2 <sha256-hex>`): published into
 *    whatever DNS serves the phone's name, and read back by OpenSSH when
 *    `VerifyHostKeyDNS yes` is set. Tailscale's MagicDNS does not itself
 *    serve SSHFP, so the record needs a resolver that does — the phone's own
 *    DNS, a router with CoreDNS, or any authoritative source for the name.
 *    The record is offered here because publishing it is the only path to
 *    host-key verification that needs no manual pin at all.
 *
 * ## Everything works from the wire blob
 *
 * The artifacts are computed from the key's encoded blob, the same bytes a
 * fingerprint is defined over — no key object is reconstructed, matching how
 * [AuthorizedKeysStore] compares keys. A value that is the same thing sshd
 * computes from is safer than a second parse.
 *
 * ## What is deliberately not here
 *
 * Nothing that accepts a changed key. These artifacts distribute the *right*
 * key; a mismatch stays a hard failure everywhere else in this app.
 */
object HostKeyPairing {

    /** SSHFP algorithm numbers (RFC 4255). */
    private const val SSHFP_RSA = 1
    private const val SSHFP_ECDSA = 3
    private const val SSHFP_ED25519 = 4

    /** SSHFP fingerprint-type numbers (RFC 4255). */
    private const val SSHFP_SHA1 = 1
    private const val SSHFP_SHA256 = 2

    /** A public key split into the two fields every artifact needs. */
    data class KeyParts(val keyType: String, val blob: ByteArray) {
        override fun equals(other: Any?) =
            other is KeyParts && keyType == other.keyType && blob.contentEquals(other.blob)

        override fun hashCode(): Int = 31 * keyType.hashCode() + blob.contentHashCode()
    }

    /**
     * Splits an OpenSSH public line (`ecdsa-sha2-nistp256 AAAA… comment`).
     *
     * Null when the line is not a public key line — the caller shows the
     * "none yet" state rather than producing garbage artifacts.
     */
    fun partsOf(line: String): KeyParts? {
        val fields = line.trim().split(' ')
        if (fields.size < 2) return null
        val blob = runCatching { Base64.getDecoder().decode(fields[1]) }.getOrNull() ?: return null
        // An EC blob starts with a 4-byte length + "ecdsa-sha2-nistp256";
        // sshd's own type string is authoritative, so trust the field.
        return KeyParts(fields[0], blob).takeIf { it.blob.isNotEmpty() && it.keyType.isNotBlank() }
    }

    /** The OpenSSH public line for [publicKey], e.g. `ecdsa-sha2-nistp256 AAAA… comment`. */
    fun publicLine(publicKey: PublicKey, comment: String = "earthquack"): String {
        val out = StringBuilder()
        PublicKeyEntry.appendPublicKeyEntry(out, publicKey)
        return "$out $comment"
    }

    /** A ready-to-paste `known_hosts` entry for the phone at [host]:[port]. */
    fun knownHostsLine(parts: KeyParts, host: String, port: Int): String {
        val body = "${parts.keyType} ${Base64.getEncoder().encodeToString(parts.blob)}"
        return if (port == 22) "$host $body" else "[$host]:$port $body"
    }

    /**
     * The client-side `~/.ssh/config` stanza that reaches the phone under a
     * stable name, so the entry above never has to be re-derived when the
     * tailnet address changes.
     */
    fun sshConfigStanza(host: String, port: Int, alias: String = "phone"): String =
        "Host $alias\n" +
            "    HostName $host\n" +
            "    Port $port\n" +
            "    User earthquack\n" +
            "    VerifyHostKeyDNS yes"

    /** The SSHFP records for [parts], SHA-256 first (the one OpenSSH prefers). */
    fun sshfpRecords(parts: KeyParts, name: String): List<String> =
        listOf(
            "$name. IN SSHFP ${algorithm(parts.keyType)} $SSHFP_SHA256 ${hexDigest(parts.blob, "SHA-256")}",
            "$name. IN SSHFP ${algorithm(parts.keyType)} $SSHFP_SHA1 ${hexDigest(parts.blob, "SHA-1")}"
        )

    private fun algorithm(keyType: String): Int = when {
        keyType.contains("rsa", ignoreCase = true) -> SSHFP_RSA
        keyType.contains("ed25519", ignoreCase = true) -> SSHFP_ED25519
        else -> SSHFP_ECDSA
    }

    /** Hex, not base64: SSHFP's fingerprint field is hex by RFC. */
    private fun hexDigest(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
