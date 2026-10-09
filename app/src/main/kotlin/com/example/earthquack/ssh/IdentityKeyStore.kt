package com.example.earthquack.ssh

import android.content.Context
import android.content.SharedPreferences
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.loader.KeyPairResourceParser
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * One imported (or generated) private key.
 *
 * [publicKeyOpenSsh] is the authorized_keys line. It is a *public* key, so it
 * is safe in preferences and safe to show in the UI — that is exactly what the
 * user needs to install it on the other machine.
 */
/**
 * Where an authenticated connection obtains its private key material.
 *
 * The seam that lets the real [SshConnectionFactory] be driven end-to-end in a
 * JVM test: the Android implementation needs a Context and a hardware Keystore,
 * while a test needs only a [KeyPair].
 */
interface ClientIdentityProvider {

    /**
     * The decrypted key pair for [alias], or null when it is gone.
     *
     * Null must mean "this key is not on the device", never "this key failed" —
     * the caller turns it into an actionable message rather than into an
     * authentication failure against the server.
     */
    fun loadKeyPair(alias: String): KeyPair?
}

data class IdentityKeyInfo(
    val alias: String,
    val label: String,
    val algorithm: String,
    val fingerprint: String,
    val publicKeyOpenSsh: String,
    val generated: Boolean,
    val hasPassphrase: Boolean
)

/**
 * Stores SSH private keys, encrypted, outside preferences.
 *
 * ## Layout
 *
 * The private key bytes and its passphrase go into [SecretStore] under
 * `<alias>.key` and `<alias>.pass`. Everything safe to display — label,
 * algorithm, fingerprint, public key — lives in preferences.
 *
 * ## Why the original bytes are kept verbatim
 *
 * A key is parsed with sshd's own `KeyPairResourceParser`, so an OpenSSH-format
 * key (including `openssh-key-v1` with its bcrypt KDF) is handed over exactly
 * as the user imported it. Re-encoding to PKCS#8 would silently drop support
 * for encrypted OpenSSH keys and for any format this code does not recognise
 * today.
 */
class IdentityKeyStore(
    context: Context,
    private val secrets: SecretStore
) : ClientIdentityProvider {

    private companion object {
        const val PREFS = "earthquack_identity_keys"
        const val KEY_INDEX = "keys"
        const val SUFFIX_KEY = ".key"
        const val SUFFIX_PASS = ".pass"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<IdentityKeyInfo> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<IdentityKeyInfo>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val alias = obj.optString("alias")
            if (alias.isBlank()) continue
            out += IdentityKeyInfo(
                alias = alias,
                label = obj.optString("label", alias),
                algorithm = obj.optString("algorithm", "?"),
                fingerprint = obj.optString("fingerprint", ""),
                publicKeyOpenSsh = obj.optString("public", ""),
                generated = obj.optBoolean("generated", false),
                hasPassphrase = obj.optBoolean("passphrase", false)
            )
        }
        return out
    }

    fun find(alias: String): IdentityKeyInfo? = list().firstOrNull { it.alias == alias }

    /**
     * Imports a private key.
     *
     * @param passphrase optional; pass `null` for an unencrypted key. When
     *   present it is stored alongside the key, also encrypted at rest — an
     *   encrypted key whose passphrase sits in plaintext next to it is not
     *   encrypted in any meaningful sense.
     * @return the stored key's metadata, or a failure carrying a message
     *   suitable for showing to a user ("no key found in that file"), rather
     *   than a stack trace.
     */
    fun importKey(
        alias: String,
        label: String,
        pem: ByteArray,
        passphrase: CharArray? = null
    ): Result<IdentityKeyInfo> = runCatching {
        val keyPair = parseKeyPair(pem, passphrase)
            ?: error("That file does not contain a private key I can read. " +
                "Expected an OpenSSH, PKCS#1 or PKCS#8 private key.")
        store(alias, label, keyPair, pem, passphrase, generated = false)
    }

    /**
     * Generates a fresh key pair on the device.
     *
     * Exists so public-key authentication is actually usable without a
     * computer-side detour: the app shows the authorized_keys line, the user
     * appends it to `~/.ssh/authorized_keys` on Arch, and done. ECDSA
     * nistp256 because it is fast on a phone, universally supported by OpenSSH,
     * and does not need an extra crypto provider the way Ed25519 does.
     */
    fun generateKey(alias: String = UUID.randomUUID().toString(), label: String): Result<IdentityKeyInfo> =
        runCatching {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = generator.generateKeyPair()

            val pem = exportOpenSshPrivateKey(keyPair)
            store(alias, label, keyPair, pem, passphrase = null, generated = true)
        }

    /** Removes the key, its passphrase and its index entry. */
    fun delete(alias: String) {
        secrets.delete(alias + SUFFIX_KEY)
        secrets.delete(alias + SUFFIX_PASS)
        writeIndex(list().filterNot { it.alias == alias })
    }

    /**
     * The decrypted key pair, ready to authenticate with.
     *
     * Returns null when the key is gone or cannot be decrypted — the caller
     * turns that into "this profile's key is missing", which is actionable,
     * rather than an authentication failure against the server, which is not.
     */
    override fun loadKeyPair(alias: String): KeyPair? {
        val pem = secrets.get(alias + SUFFIX_KEY) ?: return null
        val passphrase = secrets.get(alias + SUFFIX_PASS)?.let {
            runCatching { String(it, Charsets.UTF_8) }.getOrNull()?.toCharArray()
        }
        return parseKeyPair(pem, passphrase)
    }

    /** The authorized_keys line for [alias], e.g. `ecdsa-sha2-nistp256 AAAA… name`. */
    fun publicKeyLine(alias: String, comment: String? = null): String? {
        val info = find(alias) ?: return null
        val label = comment ?: info.label
        return info.publicKeyOpenSsh + " " + label
    }

    /** The exported OpenSSH private key, for installing elsewhere. Requires the passphrase. */
    fun exportOpenSshPrivateKey(alias: String, passphrase: CharArray): ByteArray? {
        val keyPair = loadKeyPair(alias) ?: return null
        return runCatching { encodeOpenSsh(keyPair, passphrase) }.getOrNull()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun store(
        alias: String,
        label: String,
        keyPair: KeyPair,
        originalBytes: ByteArray,
        passphrase: CharArray?,
        generated: Boolean
    ): IdentityKeyInfo {
        secrets.put(alias + SUFFIX_KEY, originalBytes)
        if (passphrase != null) {
            secrets.put(
                alias + SUFFIX_PASS,
                passphrase.concatToString().toByteArray(Charsets.UTF_8)
            )
        } else {
            secrets.delete(alias + SUFFIX_PASS)
        }

        val info = IdentityKeyInfo(
            alias = alias,
            label = label,
            algorithm = keyPair.public.algorithm,
            fingerprint = KeyUtils.getFingerPrint(keyPair.public),
            publicKeyOpenSsh = publicKeyText(keyPair.public),
            generated = generated,
            hasPassphrase = passphrase != null
        )
        writeIndex(list().filterNot { it.alias == alias } + info)
        return info
    }

    private fun writeIndex(keys: List<IdentityKeyInfo>) {
        val array = JSONArray()
        keys.forEach { key ->
            array.put(JSONObject().apply {
                put("alias", key.alias)
                put("label", key.label)
                put("algorithm", key.algorithm)
                put("fingerprint", key.fingerprint)
                put("public", key.publicKeyOpenSsh)
                put("generated", key.generated)
                put("passphrase", key.hasPassphrase)
            })
        }
        prefs.edit().putString(KEY_INDEX, array.toString()).apply()
    }

    private fun parseKeyPair(pem: ByteArray, passphrase: CharArray?): KeyPair? {
        val parser: KeyPairResourceParser = SecurityUtils.getKeyPairResourceParser()
        val provider = FilePasswordProvider.of(passphrase?.concatToString())
        val pairs = parser.loadKeyPairs(null, null, provider, ByteArrayInputStream(pem))
        return pairs.firstOrNull()
    }

    private fun publicKeyText(publicKey: java.security.PublicKey): String {
        val sb = StringBuilder()
        PublicKeyEntry.appendPublicKeyEntry(sb, publicKey)
        return sb.toString()
    }

    private fun exportOpenSshPrivateKey(keyPair: KeyPair): ByteArray = encodeOpenSsh(keyPair, null)

    private fun encodeOpenSsh(keyPair: KeyPair, passphrase: CharArray?): ByteArray {
        val out = ByteArrayOutputStream()
        val context = org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext()
        if (passphrase != null && passphrase.isNotEmpty()) {
            context.cipherName =
                org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext.AES
            context.password = passphrase.concatToString()
        }
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
            keyPair, "earthquack", context, out
        )
        return out.toByteArray()
    }
}
