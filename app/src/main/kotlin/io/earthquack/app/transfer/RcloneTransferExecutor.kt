package io.earthquack.app.transfer

import io.earthquack.app.storage.RcloneDownloadRequest
import io.earthquack.app.storage.RcloneDownloadResult
import io.earthquack.app.storage.RcloneEngine
import io.earthquack.app.storage.RcloneFileTransfer

/**
 * Runs [TransferBackend.RcloneDownload] tasks through the existing
 * [RcloneFileTransfer].
 *
 * ## What is *not* re-implemented here
 *
 * `.part` staging, the atomic rename, size verification against the
 * listing, partial deletion on cancel, and the progress poller all live in
 * [RcloneFileTransfer] and are reused as-is. This class only maps the
 * stored task onto its request object and maps the result back — which is
 * the entire point: the download path that works today is the path the
 * manager uses.
 *
 * ## Cancellation limits, stated plainly
 *
 * `RcloneFileTransfer` runs a blocking native RPC. A coroutine cannot
 * interrupt it, so a cancel lands only once the RPC returns. The partial
 * `.part` is deleted on that path (by [RcloneFileTransfer] itself), so the
 * destination is never a truncated file — but the manager records the
 * cancel only after the bytes actually stop.
 */
class RcloneTransferExecutor(
    private val engine: RcloneEngine
) : TransferExecutor {

    override fun handles(backend: TransferBackend): Boolean =
        backend is TransferBackend.RcloneDownload

    override suspend fun run(
        task: TransferTask,
        onProgress: (bytesTransferred: Long) -> Unit
    ): TransferOutcome {
        val backend = task.backend as? TransferBackend.RcloneDownload
            ?: return TransferOutcome.Failed("stored transfer is not an rclone download")

        val request = RcloneDownloadRequest(
            srcFs = backend.srcFs,
            srcRemote = backend.srcRemote,
            dstFs = backend.dstFs,
            dstRemote = backend.dstRemote,
            localFile = backend.localFile
        )

        // A retry always restarts: the backend stages into `.part` and
        // truncates, so a clean restart is the safe resume this supports.
        val result = RcloneFileTransfer(engine).download(
            request = request,
            expectedSize = task.totalBytes.takeIf { it > 0 },
            onProgress = { copied -> onProgress(copied) }
        )
        return when (result) {
            is RcloneDownloadResult.Success -> TransferOutcome.Completed(result.size)
            is RcloneDownloadResult.Failure -> TransferOutcome.Failed(result.detail)
        }
    }
}
