package com.example.earthquack.sftp

import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Recognises an OpenSSH **private** key body, whatever its file name says.
 *
 * A private key and a public key are one file each, and neither has an extension
 * a file picker can filter on: `~/.ssh/id_ed25519` is the private key and
 * `~/.ssh/id_ed25519.pub` is the public one, and a user who copied one file off
 * their machine has the private one. Naming a private key `id_ed25519.pub` is
 * also entirely possible. So the *contents* decide, never the name.
 */
fun looksLikeOpenSshPrivateKey(text: String): Boolean {
    // Skip leading comment and blank lines: a key file copied off a machine can
    // carry them, and a file whose first line is a comment still holds a key.
    val body = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return false
    val trimmed = body
    if (trimmed.startsWith("-----BEGIN") && trimmed.contains("PRIVATE KEY-----")) return true
    // The OpenSSH v1 format carries no PEM armour at all: it begins with the
    // ASCII magic "openssh-key-v1", base64'd. Decoding the first token is the
    // only way to recognise it.
    val token = trimmed.takeWhile { !it.isWhitespace() }
    if (token.isEmpty()) return false
    return runCatching {
        String(java.util.Base64.getDecoder().decode(token), Charsets.ISO_8859_1)
            .contains("openssh-key-v1")
    }.getOrDefault(false)
}

/** The public half of a private key, in `authorized_keys` form. */
data class DerivedPublicKey(
    /** e.g. `ssh-ed25519`, `ecdsa-sha2-nistp256`. */
    val type: String,
    /** The base64 key blob. */
    val key: String,
    /** The comment carried by the key file, usually empty for generated keys. */
    val label: String
) {
    /** The line as `authorized_keys` wants it. */
    fun toAuthorizedKeyText(): String =
        listOf(type, key, label).joinToString(" ").trim()

    /** sha256 fingerprint of the blob, matching `ssh-keygen -lf`. */
    fun fingerprint(): String = "SHA256:" + fingerprintBase64(decodeBlob(key))
}

/** The key type tokens OpenSSH uses, for recognising a *public* key line. */
val OPENSSH_PUBLIC_KEY_TYPES = listOf(
    "ssh-rsa", "ssh-dss", "ssh-ed25519",
    "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
    "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com"
)

/** Whether [text] starts with a recognised public-key type token. */
fun looksLikeOpenSshPublicKey(text: String): Boolean {
    val first = text.trim().split(Regex("\\s+")).firstOrNull() ?: return false
    return first in OPENSSH_PUBLIC_KEY_TYPES
}

/**
 * Derives the public key from an OpenSSH private key body.
 *
 * This is the fix for an observed dead end: the only key material a user brought
 * to the phone was `id_ed25519` — a *private* key — so the authorized-keys screen
 * had nothing it could use, and public-key authentication was unreachable for
 * as long as the `.pub` file was not also copied across.
 *
 * The private key is read, parsed and used for exactly one thing: extracting its
 * public half. No private bytes are returned, logged or stored. If the key is
 * encrypted, [passphrase] decrypts it in memory and is then discarded; only the
 * public line survives.
 *
 * @param passphrase for an encrypted key; ignored for an unencrypted one.
 * @return the public key, or a failure naming what to do about it — "enter the
 *   passphrase" is actionable, "not a key" is not.
 */
fun derivePublicKeyFromPrivateKey(
    text: String,
    passphrase: CharArray? = null
): Result<DerivedPublicKey> {
    if (!looksLikeOpenSshPrivateKey(text)) {
        return Result.failure(IOException("that file is not a private key either"))
    }
    val parser = SecurityUtils.getKeyPairResourceParser()
    val provider = if (passphrase == null || passphrase.isEmpty()) {
        FilePasswordProvider.EMPTY
    } else {
        FilePasswordProvider.of(passphrase.concatToString())
    }
    return runCatching {
        parser.loadKeyPairs(null, null, provider, ByteArrayInputStream(text.toByteArray()))
    }.recoverCatching { throwable ->
        // sshd reports a missing passphrase as one IOException and a wrong one
        // as a decode failure, and the two need different instructions.
        val message = throwable.message ?: ""
        throw when {
            message.contains("password", ignoreCase = true) ||
                message.contains("bad decrypt", ignoreCase = true) ||
                // sshd reports a wrong passphrase as a failed block check
                message.contains("mismatched private key check", ignoreCase = true) ->
                IOException("That key is protected by a passphrase. Enter it and try again.")
            else ->
                IOException("That private key could not be read (${message.take(120)})")
        }
    }.mapCatching { pairs ->
        val pair = pairs.firstOrNull()
            ?: throw IOException("no key was found in that file")
        val sb = StringBuilder()
        PublicKeyEntry.appendPublicKeyEntry(sb, pair.public)
        val parts = sb.toString().trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        require(parts.size >= 2) { "the derived line was incomplete" }
        // A generated key has no comment; a human's key carries the host name it
        // was generated on, which is as good a label as any and tells the user
        // which key they are looking at.
        DerivedPublicKey(parts[0], parts[1], parts.drop(2).joinToString(" "))
    }
}

/** The `authorized_keys` body of [key], which the caller already holds. */
fun publicKeyLine(key: PublicKey): String = StringBuilder()
    .also { PublicKeyEntry.appendPublicKeyEntry(it, key) }
    .toString()
    .trim()

// ── small helpers, pure so they are testable on the JVM ────────────────────

/** The blob a base64 token decodes to. */
internal fun decodeBlob(token: String): ByteArray =
    java.util.Base64.getDecoder().decode(token)

/**
 * sha256 of [bytes] in OpenSSH's fingerprint form: standard base64 alphabet,
 * padding stripped.
 *
 * `-`/`_` (base64url) is *not* what ssh uses, so a base64url fingerprint would
 * not match the one `ssh-keygen -lf` prints even though it is the same digest —
 * which is the whole point of showing it. sshd's own `KeyUtils.getFingerPrint`
 * produces exactly this form.
 */
internal fun fingerprintBase64(bytes: ByteArray): String =
    Base64Codec.encode(MessageDigest.getInstance("SHA-256").digest(bytes)).trimEnd('=')

/** The fingerprint of an authorized_keys line, or null when it is malformed. */
fun authorizedKeyFingerprint(line: String): String? = runCatching {
    val parts = line.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (parts.size < 2) return null
    "SHA256:" + fingerprintBase64(decodeBlob(parts[1]))
}.getOrNull()
