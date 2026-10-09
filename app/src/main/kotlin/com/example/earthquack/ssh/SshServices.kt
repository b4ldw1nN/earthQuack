package com.example.earthquack.ssh

import android.content.Context
import com.example.earthquack.sftp.fs.AndroidFileSystemFactory

/**
 * Process-wide construction of the SSH/SFTP services the UI needs.
 *
 * ## Why an object and not DI
 *
 * There is exactly one keystore, one known-hosts file and one key index per
 * process, and the fragments that touch them all live in the same process. A
 * singleton is the honest model of that. It also means the security-critical
 * choice — [KeystoreSecretStore] rather than a plaintext implementation — is
 * made in exactly one place, and a test that wants the in-memory double
 * constructs it directly instead of relying on this.
 *
 * ## Lifecycle
 *
 * The stores are cheap and lazily built. They hold no sockets and no threads:
 * an actual connection comes from [SshConnectionFactory], which every screen
 * creates per use, so a dropped TCP connection is never a leak of a service
 * object.
 */
object SshServices {

    /** Private keys and passwords, encrypted at rest under the Android Keystore. */
    fun secretStore(context: Context): SecretStore =
        KeystoreSecretStore(context.applicationContext.filesDir)

    fun identityKeys(context: Context): IdentityKeyStore =
        IdentityKeyStore(context.applicationContext, secretStore(context))

    fun knownHosts(context: Context): KnownHostsStore =
        KnownHostsStore(context.applicationContext)

    fun profiles(context: Context): ConnectionProfileStore =
        ConnectionProfileStore(context.applicationContext)

    /**
     * The one place an authenticated connection is opened.
     *
     * Returns a fresh factory per call: it is stateless apart from the stores,
     * and sharing one object would buy nothing while coupling screens through a
     * shared instance for no reason.
     */
    fun connectionFactory(context: Context): SshConnectionFactory = SshConnectionFactory(
        identityKeys = identityKeys(context),
        secrets = secretStore(context),
        knownHosts = knownHosts(context)
    )
}