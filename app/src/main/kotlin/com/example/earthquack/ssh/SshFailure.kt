package com.example.earthquack.ssh

/**
 * Every way connecting can fail, as data.
 *
 * ## Why a sealed type rather than an exception
 *
 * Each of these needs a *different* thing from the UI: a timeout offers
 * "retry", a host-key mismatch offers "check the fingerprint I expected",
 * missing key material offers "import a key". All of them arrive at the same
 * place — the profile's Terminal/Files buttons — and the screen has to tell
 * them apart to say anything useful. An exception string cannot be switched on
 * without string-matching, which is how a UI ends up claiming "connection
 * failed" for a missing key.
 */
sealed class SshFailure {

    /** The profile itself is unusable; [problems] says why. */
    data class InvalidProfile(val problems: List<String>) : SshFailure()

    /** TCP connect or key exchange did not complete in time. */
    object Timeout : SshFailure()

    /** DNS, no route, connection refused, TLS-like IO noise. */
    data class Network(val detail: String) : SshFailure()

    /**
     * The host presented a key we have not recorded.
     *
     * Only reachable under [HostKeyPolicy.STRICT].
     */
    data class UnknownHost(val fingerprint: String) : SshFailure()

    /** The host presented a *different* key than before. Never connect. */
    data class HostKeyMismatch(val expected: String, val actual: String) : SshFailure()

    /** The server rejected our credentials. */
    data class AuthFailed(val method: AuthMethod, val detail: String) : SshFailure()

    /** The profile references a key or password that is not in the store. */
    data class CredentialMissing(val detail: String) : SshFailure()

    /** The session ended while we were using it. */
    data class Disconnected(val detail: String) : SshFailure()

    /** Anything else, with sshd's own message preserved. */
    data class Unexpected(val detail: String) : SshFailure()

    /** A one-line description safe to show in a toast or a status row. */
    val message: String
        get() = when (this) {
            is InvalidProfile -> problems.firstOrNull() ?: "Connection profile is incomplete"
            Timeout -> "Connection timed out"
            is Network -> "Network error: $detail"
            is UnknownHost -> "Host key not known yet ($fingerprint)"
            is HostKeyMismatch ->
                "Host key changed — expected $expected, got $actual. Refusing to connect."
            is AuthFailed -> "Authentication failed (${method.name.lowercase()}): $detail"
            is CredentialMissing -> detail
            is Disconnected -> "Disconnected: $detail"
            is Unexpected -> detail
        }

    /**
     * Whether retrying unchanged could plausibly work.
     *
     * A timeout might. A rejected key will not, and a screen offering "retry"
     * for it teaches the user that retrying is the answer to everything.
     */
    val retryable: Boolean
        get() = when (this) {
            is Timeout, is Network, is Disconnected -> true
            else -> false
        }
}

/** Result of [SshConnectionFactory.connect]. */
sealed class SshConnectResult {
    data class Connected(val connection: SshConnection) : SshConnectResult()
    data class Failed(val failure: SshFailure) : SshConnectResult()
}

/** Result of running a one-shot command over SSH. */
data class SshCommandResult(
    val exitStatus: Int?,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitStatus == null || exitStatus == 0
}
