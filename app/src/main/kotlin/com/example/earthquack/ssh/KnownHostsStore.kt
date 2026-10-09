package com.example.earthquack.ssh

import android.content.Context
import android.content.SharedPreferences
import org.apache.sshd.common.config.keys.KeyUtils
import java.security.PublicKey

/**
 * A host key we have accepted for a given profile.
 *
 * [keyType] is stored alongside the fingerprint on purpose. A fingerprint
 * identifies a *key*, not a *host*: if the server switches from ecdsa to
 * rsa, the fingerprints differ and that is a change worth failing on, not a
 * detail to smooth over.
 */
data class KnownHostKey(
    val profileId: String,
    val keyType: String,
    val fingerprint: String
)

/** What the verifier concluded about the key a server just presented. */
sealed class HostKeyVerdict {
    /** Matches what we recorded. Connection may proceed. */
    object Trusted : HostKeyVerdict()

    /** Never seen before. Record it (TOFU) or refuse (strict). */
    data class Unknown(val fingerprint: String, val keyType: String) : HostKeyVerdict()

    /**
     * The host presented a *different* key than before.
     *
     * Never proceed on this, under any policy. Either the host was rebuilt, or
     * something is answering on that address, and the app cannot tell which.
     */
    data class Mismatch(val expected: String, val actual: String) : HostKeyVerdict()
}

/**
 * The client's host-key trust decision, in one contract.
 *
 * Exists so the verification policy can be exercised without a Context, and
 * without a live socket. [KnownHostsStore] is the Android implementation;
 * [SshConnectionFactory] depends only on this.
 */
interface HostKeyTrust {

    /** Compares a presented key against what was recorded for [profileId]. */
    fun verify(profileId: String, publicKey: PublicKey): HostKeyVerdict

    /** Records (or replaces) the key for [profileId]. */
    fun record(profileId: String, publicKey: PublicKey)

    fun forget(profileId: String)
}

/**
 * Remembers the host key of every connection profile.
 *
 * ## Trust model
 *
 * On first connection the key is recorded and its fingerprint shown. After
 * that, a changed key is a hard failure — see [HostKeyVerdict.Mismatch].
 * Verification is never switched off: there is no "accept anything" policy,
 * because an SFTP server that will happily talk to a machine-in-the-middle
 * is not a server worth having.
 *
 * Host keys are *public* keys. They are stored unencrypted, but in their own
 * preferences file so that they can be wiped independently of profiles — a
 * user who suspects a compromised host should be able to reset trust without
 * losing their connections.
 */
class KnownHostsStore(context: Context) : HostKeyTrust {

    private companion object {
        const val PREFS = "earthquack_known_hosts"
        const val KEY_HOSTS = "hosts"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<KnownHostKey> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3 || parts.any { it.isBlank() }) return@mapNotNull null
            KnownHostKey(parts[0], parts[1], parts[2])
        }
    }

    fun forProfile(profileId: String): KnownHostKey? = all().firstOrNull { it.profileId == profileId }

    /** Records (or replaces) the key for [profileId]. */
    override fun record(profileId: String, publicKey: PublicKey) {
        val entry = KnownHostKey(
            profileId = profileId,
            keyType = publicKey.algorithm,
            fingerprint = KeyUtils.getFingerPrint(publicKey)
        )
        val updated = all().filterNot { it.profileId == profileId } + entry
        write(updated)
    }

    override fun forget(profileId: String) = write(all().filterNot { it.profileId == profileId })

    /** Compares a presented key against what was recorded. */
    override fun verify(profileId: String, publicKey: PublicKey): HostKeyVerdict {
        val known = forProfile(profileId)
        val fingerprint = KeyUtils.getFingerPrint(publicKey)
        return when {
            known == null -> HostKeyVerdict.Unknown(fingerprint, publicKey.algorithm)
            known.keyType != publicKey.algorithm ->
                HostKeyVerdict.Mismatch(known.fingerprint, "$fingerprint (${publicKey.algorithm})")
            known.fingerprint != fingerprint ->
                HostKeyVerdict.Mismatch(known.fingerprint, fingerprint)
            else -> HostKeyVerdict.Trusted
        }
    }

    private fun write(entries: List<KnownHostKey>) {
        val raw = entries.joinToString("\n") { "${it.profileId}|${it.keyType}|${it.fingerprint}" }
        prefs.edit().putString(KEY_HOSTS, raw).apply()
    }
}
