package com.example.earthquack.ssh

import java.util.UUID

/**
 * One remote machine EarthQuack can talk to.
 *
 * ## Why a profile and not two configurations
 *
 * SSH and SFTP are two protocols over *one* TCP connection to *one* account on
 * *one* host. Storing them separately guarantees they drift: a port changed on
 * the terminal screen and the file browser keeps dialling the old one, and the
 * symptom is a timeout that looks like the server is down.
 *
 * So this is the single record. [SshConnectionFactory] authenticates with it
 * and hands back a session that can open a shell, an exec channel, or an SFTP
 * subsystem on the same authenticated channel.
 *
 * ## What is deliberately absent
 *
 * No password and no key material. Those live in [SecretStore] /
 * [IdentityKeyStore] and are referenced by alias, so a profile can be logged,
 * serialised, or shown in the UI without carrying a secret across.
 */
data class ConnectionProfile(
    /** Stable identifier. Generated once, never derived from the host. */
    val id: String = UUID.randomUUID().toString(),
    /** User-facing label, e.g. "archii". */
    val name: String,
    /** Hostname or IP address. Resolved by the OS, so MagicDNS works. */
    val host: String,
    val port: Int = DEFAULT_SSH_PORT,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.PUBLIC_KEY,
    /**
     * Alias of the private key in [IdentityKeyStore].
     *
     * Null only when [authMethod] is [AuthMethod.PASSWORD], or when the key has
     * not been imported yet — which [problems] reports rather than failing at
     * connect time with an opaque "no identity" error.
     */
    val identityKeyAlias: String? = null,
    /**
     * Alias of the password in [SecretStore], for [AuthMethod.PASSWORD].
     *
     * The password itself is never in this object, so a profile is safe to put
     * in a log line or a bug report.
     */
    val passwordAlias: String? = null,
    /**
     * How an unfamiliar host key is treated.
     *
     * [HostKeyPolicy.TOUF] records the first key seen and refuses any later
     * change. [HostKeyPolicy.STRICT] refuses a host it has never seen. Neither
     * ever accepts a changed key silently — that is the whole point of the
     * type existing.
     */
    val hostKeyPolicy: HostKeyPolicy = HostKeyPolicy.TOUF
) {
    /** Human-readable endpoint. Never used for display of the secret parts. */
    val endpoint: String get() = "$host:$port"

    /**
     * Everything wrong with this profile, in the order a user should fix it.
     *
     * Empty means usable. Returned as a list rather than thrown so the editor
     * can show all the problems at once, and so validation can be unit tested
     * without an Android context.
     */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (name.isBlank()) out += "name is required"
        if (host.isBlank()) out += "host is required"
        if (host.any { it.isWhitespace() }) out += "host must not contain spaces"
        if (port !in VALID_PORT_RANGE) out += "port must be between 1 and 65535"
        if (username.isBlank()) out += "username is required"
        if (username.any { it.isWhitespace() }) out += "username must not contain spaces"
        when (authMethod) {
            AuthMethod.PUBLIC_KEY -> if (identityKeyAlias.isNullOrBlank()) {
                out += "public key authentication needs an imported key"
            }
            AuthMethod.PASSWORD -> if (passwordAlias.isNullOrBlank()) {
                out += "password authentication needs a stored password"
            }
        }
        return out
    }

    val isValid: Boolean get() = problems().isEmpty()

    companion object {
        const val DEFAULT_SSH_PORT = 22
        val VALID_PORT_RANGE = 1..65535
    }
}

/** How a [ConnectionProfile] proves who it is. */
enum class AuthMethod {
    /** Public key. The default, and the only method the UI recommends. */
    PUBLIC_KEY,
    PASSWORD
}

/** What to do when the server presents a host key we have not recorded. */
enum class HostKeyPolicy {
    /**
     * Record on first sight, then require an exact match forever after.
     *
     * The fingerprint is shown in the UI so the first connection can actually
     * be verified out of band. A mismatch is always a hard failure.
     */
    TOUF,

    /** Refuse any host that is not already recorded. */
    STRICT
}
