package com.example.earthquack.ssh

import java.security.PublicKey
import org.apache.sshd.common.config.keys.KeyUtils

/**
 * A host-key trust decision that asks before recording.
 *
 * ## Why this exists next to [KnownHostsStore]
 *
 * The store implements the *rules*: pin what you saw, refuse what changed.
 * The SFTP server and the terminal use those rules directly, with TOUF
 * recording the first key on sight — the right default for a machine the
 * user is already looking at.
 *
 * The SFTP **browser** is different. It is a screen whose whole purpose is
 * connecting somewhere new, and for a new host "record silently, show the
 * fingerprint afterwards" teaches the user that fingerprints are decoration.
 * This wrapper keeps the store's rules but routes the first-use case through
 * explicit consent: [verify] hands the key back as
 * [HostKeyVerdict.Unknown] *without recording*, and the key stays parked in
 * [pendingKey] until the user has seen its fingerprint and accepted. Only
 * then does [acceptPending] call the real [record].
 *
 * A changed key is never parked, never accepted, and has no branch here that
 * could accept it: [HostKeyVerdict.Mismatch] passes straight through, exactly
 * as the store concluded.
 */
class InteractiveHostKeyTrust(
    private val delegate: HostKeyTrust
) : HostKeyTrust {

    private val lock = Any()

    /** The key presented by an unfamiliar host, awaiting the user's decision. */
    private var pendingKey: PublicKey? = null

    override fun verify(profileId: String, publicKey: PublicKey): HostKeyVerdict =
        when (val verdict = delegate.verify(profileId, publicKey)) {
            is HostKeyVerdict.Trusted, is HostKeyVerdict.Mismatch -> verdict
            is HostKeyVerdict.Unknown -> synchronized(lock) {
                // Held, not recorded: the screen shows this fingerprint and
                // asks. If the user refuses, nothing is written.
                pendingKey = publicKey
                verdict
            }
        }

    override fun record(profileId: String, publicKey: PublicKey) =
        delegate.record(profileId, publicKey)

    override fun forget(profileId: String) = delegate.forget(profileId)

    /** The fingerprint of the parked key, or null when there is none. */
    val pendingFingerprint: String?
        get() = synchronized(lock) { pendingKey }?.let { KeyUtils.getFingerPrint(it) }

    /**
     * Records the parked key for [profileId], after consent.
     *
     * A no-op when nothing is parked, so a second tap of the accept button
     * cannot re-record an unknown key.
     */
    fun acceptPending(profileId: String) = synchronized(lock) {
        pendingKey?.let {
            delegate.record(profileId, it)
            pendingKey = null
            true
        } ?: false
    }

    /** Drops the parked key without recording: the user refused. */
    fun rejectPending() = synchronized(lock) { pendingKey = null }
}
