package com.example.earthquack.transfer

import kotlinx.coroutines.CancellationException

/**
 * Performs one transfer's bytes.
 *
 * ## Why an interface
 *
 * rclone and SFTP move bytes in completely different ways — a blocking
 * native RPC versus an sshd stream — but the manager needs one contract:
 * run this task, tell me how far along it is, and tell me whether it worked.
 * The existing transfer code ([com.example.earthquack.storage.RcloneFileTransfer],
 * [com.example.earthquack.ssh.SftpSession]) stays behind these executors
 * unwrapped and unmodified; the executors are the seam, not a rewrite.
 *
 * ## Cancellation
 *
 * [run] must be cancellable: cancellation arrives as a thrown
 * [CancellationException] at the next suspension point, or on return for
 * backends that cannot be interrupted. Either way the executor owns partial
 * cleanup — see each implementation for what it may delete and why.
 */
interface TransferExecutor {

    /** True when this executor runs tasks with [backend]. */
    fun handles(backend: TransferBackend): Boolean

    /**
     * Whether a retried [task] can continue from
     * [TransferTask.bytesTransferred] rather than starting over.
     *
     * The honest default is false: most backends here do not expose a safe
     * resume, and saying otherwise would restart a half-written file at an
     * offset that assumes bytes that never arrived. An executor may return
     * true only when it can prove the partial state exists and the backend
     * accepts an offset.
     */
    fun canResume(task: TransferTask): Boolean = false

    /**
     * Runs the transfer to a terminal outcome.
     *
     * [onProgress] is called with the authoritative byte count as the
     * backend reports it; it is never called with an estimate. Throwing
     * [CancellationException] means "the user stopped it" and the partial
     * state has already been cleaned by the executor.
     */
    suspend fun run(task: TransferTask, onProgress: (bytesTransferred: Long) -> Unit): TransferOutcome
}

/** How a transfer ended. */
sealed class TransferOutcome {

    /**
     * The bytes are where [TransferTask.destination] says they should be.
     *
     * [bytes] is the final, verified count — not the last progress value.
     */
    data class Completed(val bytes: Long) : TransferOutcome()

    /** Failed, with a message safe to show in the UI. */
    data class Failed(val error: String) : TransferOutcome()
}
