package com.example.earthquack.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.Key
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest storage for bytes that must never sit in preferences.
 *
 * Private keys and passwords live here and nowhere else. The interface exists
 * so tests can run on the JVM with [InMemorySecretStore] instead of needing a
 * hardware-backed keystore, and so the storage mechanism can be changed without
 * touching the callers that use it.
 *
 * Implementations must:
 *  - return null for an unknown alias rather than throwing,
 *  - never write plaintext, not even temporarily,
 *  - be safe to call concurrently.
 */
interface SecretStore {

    /** Stores [bytes] under [alias], replacing anything already there. */
    fun put(alias: String, bytes: ByteArray)

    /** The stored bytes, or null when [alias] is unknown. */
    fun get(alias: String): ByteArray?

    fun delete(alias: String)

    fun has(alias: String): Boolean
}

/**
 * Test-only [SecretStore]. Keeps everything in memory and says so.
 *
 * Deliberately not backed by anything on disk: a test double that writes a
 * private key to a temporary file teaches the wrong lesson about where secrets
 * are allowed to go.
 */
class InMemorySecretStore : SecretStore {

    private val items = mutableMapOf<String, ByteArray>()

    override fun put(alias: String, bytes: ByteArray) {
        items[alias] = bytes.copyOf()
    }

    override fun get(alias: String): ByteArray? = items[alias]?.copyOf()

    override fun delete(alias: String) {
        items.remove(alias)
    }

    override fun has(alias: String): Boolean = items.containsKey(alias)
}

/**
 * [SecretStore] on the Android Keystore.
 *
 * ## How it works
 *
 * A 256-bit AES/GCM key is generated inside the Keystore and never leaves it
 * (`setUserAuthenticationRequired` is deliberately *not* set — see below). Each
 * secret is encrypted under it and written to app-private storage as
 * `Base64(12-byte IV || ciphertext+tag)`.
 *
 * The consequence that matters: `adb backup` extraction of the app's files
 * yields ciphertext. On a device with a TEE/StrongBox the AES key itself is
 * additionally non-exportable, so the blob is useless off-device.
 *
 * ## Why no user-authentication gate
 *
 * `setUserAuthenticationRequired(true)` would mean the SFTP server cannot
 * answer a request while the phone is locked — and rclone on Arch is
 * explicitly meant to be able to reach the phone unattended. The trade is
 * deliberate and worth stating plainly: confidentiality at rest, and the
 * reachability that an always-on file server needs. The secret is still
 * useless without the device's Keystore.
 *
 * ## File naming
 *
 * Blobs live in `filesDir/secrets`, which is app-private and excluded from
 * backup (`allowBackup="false"` in the manifest). The alias is sanitised
 * before it becomes a filename, so an alias containing a path separator
 * cannot escape the directory.
 */
class KeystoreSecretStore(
    filesDir: File,
    private val cipherBox: CipherBox
) : SecretStore {

    constructor(filesDir: File) : this(filesDir, AndroidKeystoreCipherBox())

    private val dir = File(filesDir, "secrets")

    override fun put(alias: String, bytes: ByteArray) {
        ensureDir()
        val blob = cipherBox.encrypt(bytes)
        File(dir, fileNameFor(alias)).writeBytes(blob)
    }

    override fun get(alias: String): ByteArray? {
        val file = File(dir, fileNameFor(alias))
        if (!file.isFile) return null
        return runCatching { cipherBox.decrypt(file.readBytes()) }.getOrNull()
    }

    override fun delete(alias: String) {
        runCatching { File(dir, fileNameFor(alias)).delete() }
    }

    override fun has(alias: String): Boolean = File(dir, fileNameFor(alias)).isFile

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    /**
     * Maps an arbitrary alias onto a safe filename.
     *
     * Aliases come from app code, not from users, but this is one `File` away
     * from a traversal and the sanitising is three lines.
     */
    private fun fileNameFor(alias: String): String {
        val safe = alias.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_'
        }.joinToString("")
        return if (safe.isBlank()) "unnamed" else safe
    }
}

/** Authenticated encryption of a byte blob. Separated so the Keystore can be faked. */
interface CipherBox {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES/GCM under a non-exportable Android Keystore key. */
class AndroidKeystoreCipherBox : CipherBox {

    private companion object {
        const val KEY_ALIAS = "earthquack_secret_vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv.copyOf(IV_BYTES)
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "cipher text too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES)
        )
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun secretKey(): Key {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }

        // javax.crypto, not java.security: that is where the JCE lives on
        // Android, and java.security.KeyGenerator does not resolve there.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement; see the class comment.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
