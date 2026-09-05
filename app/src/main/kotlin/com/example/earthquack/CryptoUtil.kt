package com.example.earthquack

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for clipboard sync — no SHA hashing, pure AES.
 * Key is 32 bytes (256-bit) stored as Base64 in ServerConfig.
 * Wire format: Base64( 12-byte IV || ciphertext+tag )
 * If no key or AES disabled, data is sent plaintext.
 */
object CryptoUtil {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun generateKeyBase64(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun isValidKeyBase64(b64: String): Boolean {
        return try {
            val k = Base64.decode(b64.trim(), Base64.NO_WRAP)
            k.size == 32
        } catch (_: Exception) { false }
    }

    fun encrypt(plain: String, base64Key: String): String {
        val keyBytes = Base64.decode(base64Key.trim(), Base64.NO_WRAP)
        require(keyBytes.size == 32) { "AES key must be 32 bytes (256-bit) Base64" }
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv, 0, IV_BYTES))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(IV_BYTES + ct.size)
        System.arraycopy(iv, 0, combined, 0, IV_BYTES)
        System.arraycopy(ct, 0, combined, IV_BYTES, ct.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(b64Cipher: String, base64Key: String): String {
        val keyBytes = Base64.decode(base64Key.trim(), Base64.NO_WRAP)
        require(keyBytes.size == 32) { "AES key must be 32 bytes Base64" }
        val combined = Base64.decode(b64Cipher.trim(), Base64.NO_WRAP)
        require(combined.size > IV_BYTES) { "cipher too short" }
        val iv = combined.copyOfRange(0, IV_BYTES)
        val ct = combined.copyOfRange(IV_BYTES, combined.size)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv, 0, IV_BYTES))
        val pt = cipher.doFinal(ct)
        return String(pt, Charsets.UTF_8)
    }

    /** Heuristic: AES payload is Base64 and longer than plain; we tag encrypted clipboard with prefix for reliable detection */
    const val PREFIX = "AES:"
    fun wrapIfNeeded(plain: String, base64Key: String?, enabled: Boolean): String {
        if (!enabled || base64Key.isNullOrBlank() || !isValidKeyBase64(base64Key)) return plain
        return PREFIX + encrypt(plain, base64Key)
    }
    fun unwrapIfNeeded(maybeEncrypted: String, base64Key: String?, enabled: Boolean): String {
        if (!enabled || base64Key.isNullOrBlank() || !isValidKeyBase64(base64Key)) return maybeEncrypted
        if (!maybeEncrypted.startsWith(PREFIX)) return maybeEncrypted
        return try {
            decrypt(maybeEncrypted.removePrefix(PREFIX), base64Key)
        } catch (e: Exception) {
            // Wrong key or corrupted — return as-is to avoid data loss, log will show
            maybeEncrypted
        }
    }
}
