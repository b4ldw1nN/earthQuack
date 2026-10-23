package io.earthquack.app.ssh

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Connects once, as a test, and reports what happened.
 *
 * ## Why a separate probe from the terminal's connect
 *
 * The terminal uses the shared factory with the profile's own policy, where
 * TOUF records a first key on sight. That is fine for a session the user is
 * already watching. A *test* is different: the user pressed a button whose
 * entire purpose is to see the result before saving, and a host key recorded
 * by a test they did not understand is the same as recording it silently.
 *
 * So the probe runs every connection through [InteractiveHostKeyTrust] with
 * the profile's policy forced to [HostKeyPolicy.STRICT], which turns
 * "unknown host" into a failure carrying the fingerprint. The caller shows
 * that fingerprint and asks; only on acceptance does the key reach the real
 * [KnownHostsStore]. A changed key keeps its hard failure — the probe has no
 * branch that accepts one.
 *
 * ## Seams
 *
 * The constructor takes the same stores the factory does, so the probe can be
 * driven against an embedded server in JVM tests exactly like the connection
 * itself is (see `SshTerminalEndToEndTest`); [create] wires the real Android
 * stores for the app.
 */
class SshProfileProbe(
    private val identityKeys: ClientIdentityProvider,
    private val secrets: SecretStore,
    private val knownHosts: HostKeyTrust,
    private val filesDir: File
) {

    /** The app's real stores. */
    companion object {
        const val TAG = "SshProfileProbe"

        fun create(context: Context): SshProfileProbe = SshProfileProbe(
            identityKeys = SshServices.identityKeys(context),
            secrets = SshServices.secretStore(context),
            knownHosts = SshServices.knownHosts(context),
            filesDir = context.applicationContext.filesDir
        )
    }

    private val trust = InteractiveHostKeyTrust(knownHosts)

    /** The fingerprint of a host key presented and not yet accepted. */
    val pendingFingerprint: String? get() = trust.pendingFingerprint

    /**
     * Connects, authenticates, and closes.
     *
     * The stored profile is never mutated; the strict copy exists only so an
     * unfamiliar key fails with its fingerprint instead of being recorded.
     */
    suspend fun connect(profile: ConnectionProfile): ProbeResult =
        withContext(Dispatchers.IO) {
            val factory = SshConnectionFactory(
                identityKeys = identityKeys,
                secrets = secrets,
                knownHosts = trust,
                filesDir = filesDir
            )
            val result = factory.connect(profile.copy(hostKeyPolicy = HostKeyPolicy.STRICT))
            when (result) {
                is SshConnectResult.Connected -> {
                    Log.i(TAG, "probe connected to ${profile.endpoint}")
                    // The connection is closed here: the probe's job was to
                    // answer "can this work", not to hold a session open.
                    result.connection.close()
                    ProbeResult.Connected
                }
                is SshConnectResult.Failed -> {
                    Log.i(TAG, "probe failed for ${profile.endpoint}: ${result.failure.message}")
                    ProbeResult.Failed(result.failure)
                }
            }
        }

    /** Records the parked host key after the user accepted the fingerprint. */
    fun acceptHostKey(profileId: String): Boolean = trust.acceptPending(profileId)

    /** Drops the parked key without recording: the user said no. */
    fun rejectHostKey() = trust.rejectPending()
}

/** What a [SshProfileProbe.connect] found out. */
sealed class ProbeResult {
    object Connected : ProbeResult()
    data class Failed(val failure: SshFailure) : ProbeResult()
}
