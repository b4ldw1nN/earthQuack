package com.example.earthquack.transfer

import android.util.Log
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.SshConnectResult
import com.example.earthquack.ssh.SshConnectionFactory
import com.example.earthquack.ssh.SftpSession
import com.example.earthquack.ssh.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Runs [TransferBackend.Sftp] tasks through the existing
 * [SshConnectionFactory] + [SftpSession] pair.
 *
 * ## Destination safety
 *
 * Both directions write to a sibling `.part` file and rename into place on
 * success, mirroring the rclone path. A reader of the destination therefore
 * sees either the previous file or the new complete one, never a half one,
 * and an interrupted transfer leaves a `.part` to clean rather than a
 * truncated file that looks like a success. The local source of an upload is
 * user data and is never touched.
 *
 * ## Resume, and its exact boundary
 *
 * Downloads only, and only when every one of these holds:
 *
 *  1. the task recorded a total size (otherwise "complete" is unverifiable);
 *  2. the retry kept its byte progress (the manager only does that when
 *     [canResume] said yes);
 *  3. the local `.part` is exactly that long (progress that did not happen
 *     is not a resume point);
 *  4. the remote file's size is still the total recorded at start (a file
 *     that changed underneath is a different file, and appending to the old
 *     bytes would produce a corrupt mix).
 *
 * If any fails, the transfer restarts from zero — which is the honest
 * answer for a backend that cannot promise a resume. Uploads never resume:
 * the remote partial is only reachable as a whole-file overwrite, and an
 * offset append after a dropped connection cannot be proven safe.
 */
class SftpTransferExecutor(
    /**
     * Resolves a profile id to the saved profile.
     *
     * A function rather than the store itself so the executor carries no
     * Android dependency: it is driven end-to-end against an embedded SSH
     * server in JVM tests, exactly like the connection code it wraps.
     */
    private val resolveProfile: (String) -> ConnectionProfile?,
    private val factory: SshConnectionFactory
) : TransferExecutor {

    private companion object {
        const val TAG = "SftpTransferExecutor"
        const val PART_SUFFIX = ".part"
    }

    override fun handles(backend: TransferBackend): Boolean =
        backend is TransferBackend.Sftp

    override fun canResume(task: TransferTask): Boolean =
        task.type == TransferType.DOWNLOAD &&
            task.totalBytes > 0 &&
            task.bytesTransferred > 0 &&
            File(partFile(task.destination)).length() == task.bytesTransferred

    override suspend fun run(
        task: TransferTask,
        onProgress: (bytesTransferred: Long) -> Unit
    ): TransferOutcome {
        val backend = task.backend as? TransferBackend.Sftp
            ?: return TransferOutcome.Failed("stored transfer is not an SFTP transfer")

        val profile = resolveProfile(backend.profileId)
            ?: return TransferOutcome.Failed("The connection profile for this transfer no longer exists.")

        return when (val connect = factory.connect(profile)) {
            is SshConnectResult.Failed -> TransferOutcome.Failed(connect.failure.message)
            is SshConnectResult.Connected -> withContext(Dispatchers.IO) {
                try {
                    connect.connection.openSftp().use { session ->
                        if (task.type == TransferType.DOWNLOAD) {
                            download(task, backend, session, onProgress)
                        } else {
                            upload(task, backend, session, onProgress)
                        }
                    }
                } finally {
                    runCatching { connect.connection.close() }
                }
            }
        }
    }

    private suspend fun upload(
        task: TransferTask,
        backend: TransferBackend.Sftp,
        session: SftpSession,
        onProgress: (Long) -> Unit
    ): TransferOutcome {
        val source = File(backend.localFile)
        if (!source.isFile) {
            return TransferOutcome.Failed("The file on this device no longer exists.")
        }
        val remotePart = backend.remotePath + PART_SUFFIX
        try {
            source.inputStream().use { input ->
                session.upload(
                    input = input,
                    remotePath = remotePart,
                    size = source.length(),
                    onProgress = { p: TransferProgress -> onProgress(p.bytesTransferred) }
                )
            }
            // Rename last: the destination is either the old file or the new
            // complete one, never a half-written one. Overwrite is explicit:
            // re-running an upload onto the same remote path is what replacing
            // a file means, and the partial never touched the destination.
            session.rename(remotePart, backend.remotePath, overwrite = true)
            return TransferOutcome.Completed(source.length())
        } catch (e: CancellationException) {
            // The `.part` is ours by naming convention; the destination is
            // never touched, so a pre-existing file survives.
            runCatching { session.delete(remotePart) }
            throw e
        } catch (e: IOException) {
            runCatching { session.delete(remotePart) }
            return TransferOutcome.Failed(e.message ?: "The upload failed.")
        }
    }

    private suspend fun download(
        task: TransferTask,
        backend: TransferBackend.Sftp,
        session: SftpSession,
        onProgress: (Long) -> Unit
    ): TransferOutcome {
        val destination = File(task.destination)
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return TransferOutcome.Failed("Could not create a place to store the file")
            }
        }
        val staging = File(partFile(task.destination))

        var offset = 0L
        if (task.bytesTransferred > 0) {
            // canResume admitted this attempt, which already proved the
            // `.part` matches the recorded progress; the remote size check
            // below is the remaining half of the safety rule.
            val remoteSize = session.remoteSize(backend.remotePath)
            if (task.totalBytes > 0 && remoteSize != null && remoteSize != task.totalBytes) {
                // The file changed on the server: continuing from the stored
                // bytes would splice two files together. The `.part` is removed
                // so the retry this message asks for starts clean, and a fresh
                // resume is not even offered while the sizes still differ.
                staging.delete()
                return downloadRemoteChanged()
            }
            offset = task.bytesTransferred
        }

        try {
            // Append only when the resume rule admitted a real offset: a
            // truncating open would silently discard the bytes the resume is
            // supposed to be continuing from.
            java.io.FileOutputStream(staging, offset > 0).use { output ->
                session.download(
                    remotePath = backend.remotePath,
                    output = output,
                    offset = offset,
                    onProgress = { p -> onProgress(offset + p.bytesTransferred) }
                )
            }
        } catch (e: CancellationException) {
            staging.delete()
            throw e
        } catch (e: IOException) {
            staging.delete()
            Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
            return TransferOutcome.Failed(e.message ?: "The download failed.")
        }

        val actual = staging.length()
        if (task.totalBytes > 0 && actual != task.totalBytes) {
            // The stream ended at a size that is not the expected one: a
            // truncated transfer must never be renamed into place.
            staging.delete()
            return TransferOutcome.Failed("The file did not download completely.")
        }
        if (!staging.renameTo(destination)) {
            staging.delete()
            return TransferOutcome.Failed("The file could not be moved into place.")
        }
        return TransferOutcome.Completed(actual)
    }

    private fun partFile(destination: String) = destination + PART_SUFFIX

    /**
     * The remote file changed while a resume was pending.
     *
     * An explicit failure rather than a silent restart: continuing from the
     * stored bytes would splice two different files together, and restarting
     * on its own would hide the fact that the server's copy is not the file
     * the user queued. The message says which action fixes it.
     */
    private fun downloadRemoteChanged() =
        TransferOutcome.Failed("The file on the server changed. Retry to download it again.")
}
