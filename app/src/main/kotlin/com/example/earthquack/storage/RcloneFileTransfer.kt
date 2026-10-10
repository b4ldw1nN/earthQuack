package com.example.earthquack.storage

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.earthquack.storage.RcloneDownloadPlanner.Companion.PART_SUFFIX
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Runs a single `operations/copyfile`, remote → local cache directory.
 *
 * ## Why this method
 *
 * `operations/copyfile` takes `srcFs`/`srcRemote`/`dstFs`/`dstRemote` and copies
 * **the object itself**. The bytes move from the remote backend into the local
 * file inside rclone, in chunks, and the Kotlin layer never sees them — so a
 * 2 GB video costs the same amount of JVM memory as a 2 KB text file. The
 * alternative of streaming the reply of a list/stat call through a `ByteArray`
 * is what this avoids.
 *
 * The method is registered in `fs/operations`, which the shim already links
 * (see the blank imports in `rclone-android/rclone/rclone.go`), so no native
 * change was needed. It is the same call the on-device config test exercises.
 *
 * ## Why not async jobs
 *
 * `async/…` + `job/status` would give real per-file progress. It needs
 * `fs/rc/jobs` linked into the shim, which is a Go rebuild plus an NDK, and no
 * NDK is available here — see the memory note on the `gomobile bind` blocker.
 * The copy therefore runs synchronously and progress is *observed* by polling
 * the local file's length, which is accurate because rclone writes the
 * destination file as it streams.
 *
 * ## Cancellation
 *
 * `librclone.RPC` is a blocking native call. A coroutine cannot interrupt it, so
 * pressing stop does not stop the bytes: the call is allowed to return, and the
 * destination is then deleted on both exit paths — inside the transfer when the
 * coroutine is already cancelled, and by the caller when it discards a result
 * it no longer wants. Nothing incomplete ever reaches the caller to open.
 *
 * @param engine the process-wide rclone engine; not owned or closed here.
 * @param pollDelayMillis how often the local file is stat'ed for progress. Not
 *   a UI constant; it is injectable so tests can make the poller tick fast.
 * @param progressScope where the poller runs. Injectable for the same reason.
 */
class RcloneFileTransfer(
    private val engine: RcloneEngine,
    private val pollDelayMillis: Long = POLL_MS,
    private val progressScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /**
     * Downloads [request] to its local destination.
     *
     * Runs entirely on [Dispatchers.IO]: the RPC is blocking and the file-size
     * polling touches the disk.
     *
     * @param expectedSize size the listing reported, or null. Used only to
     *   verify completeness; a mismatch fails the transfer rather than opening
     *   a truncated file.
     * @param onProgress called with the local file's length as it grows. Called
     *   from the IO dispatcher, so the caller dispatches to the UI.
     */
    suspend fun download(
        request: RcloneDownloadRequest,
        expectedSize: Long? = null,
        onProgress: suspend (Long) -> Unit = {}
    ): RcloneDownloadResult = withContext(Dispatchers.IO) {
        val destination = File(request.localFile)
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return@withContext RcloneDownloadResult.Failure(
                "Could not create a place to store the file"
            )
        }

        // Downloaded into a `.part` and renamed at the end, always. The
        // destination is keyed on the file rather than on this attempt, so
        // something may be reading it right now: renaming in a complete file
        // means a reader sees either the old one or the new one, never a half
        // written one, and an interrupted copy leaves a `.part` to clean up
        // rather than a truncated file that looks like a success.
        val staging = File(request.localFile + PART_SUFFIX)

        try {
            val watcher = watchProgress(staging, onProgress)
            try {
                engine.call(
                    METHOD_COPYFILE,
                    JSONObject(
                        request.params.mapValues { it.value } + (
                            "dstRemote" to (request.dstRemote + PART_SUFFIX)
                            )
                    )
                )
            } catch (e: RcloneException) {
                return@withContext RcloneDownloadResult.Failure(describeOf(e))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else is a transport problem — a backend that does
                // not implement copying, a dropped network, a full disk.
                Log.w(TAG, "copyfile failed: ${e.javaClass.simpleName}")
                return@withContext RcloneDownloadResult.Failure(
                    e.message?.takeIf { it.isNotBlank() } ?: FALLBACK_MESSAGE
                )
            } finally {
                watcher.cancel()
            }

            // Cancellation of the enclosing coroutine is checked only after the
            // blocking call returns; see the class docs.
            currentCoroutineContext().ensureActive()

            if (!staging.exists()) {
                // A 200 with nothing on disk is not a download. `--dry-run`
                // backends and quota-limited remotes both do this.
                Log.w(TAG, "copyfile reported success but ${staging.path} is absent")
                staging.delete()
                return@withContext RcloneDownloadResult.Failure(FALLBACK_MESSAGE)
            }
            val size = staging.length()
            if (!DownloadVerification.isComplete(size, expectedSize)) {
                staging.delete()
                return@withContext RcloneDownloadResult.Failure(
                    "The file did not download completely"
                )
            }

            // Rename rather than copy: same filesystem, so it is atomic and
            // costs nothing for a 1.6 GB file.
            if (!staging.renameTo(destination)) {
                staging.delete()
                return@withContext RcloneDownloadResult.Failure(FALLBACK_MESSAGE)
            }

            RcloneDownloadResult.Success(request.localFile, size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The native call returned (or never started). Drop the partial
            // file so an abandoned download cannot later be opened.
            staging.delete()
            throw e
        } catch (e: IOException) {
            staging.delete()
            RcloneDownloadResult.Failure("Could not write the file to storage")
        }
    }

    /**
     * Polls the destination's length while the copy runs.
     *
     * rclone creates the destination and fills it as it streams, so the local
     * length is a real progress signal rather than a pretend one. Returns the
     * polling job so the caller can stop it once the copy has returned.
     */
    private fun watchProgress(
        destination: File,
        onProgress: suspend (Long) -> Unit
    ): kotlinx.coroutines.Job =
        progressScope.launch {
            var last = Long.MIN_VALUE
            while (isActive) {
                val size = try {
                    if (destination.exists()) destination.length() else 0L
                } catch (_: SecurityException) {
                    // A cache entry that cannot be stat'ed still makes progress:
                    // reporting the stale value is better than stopping the
                    // indicator, which would read as a stalled transfer.
                    last
                }
                if (size != last) {
                    last = size
                    onProgress(size)
                }
                kotlinx.coroutines.delay(pollDelayMillis)
            }
        }

    /** rclone's human-readable error text, or a fixed fallback. */
    private fun describeOf(e: RcloneException): String {
        val detail = try {
            JSONObject(e.rcloneOutput).optString("error").trim()
        } catch (_: Exception) {
            ""
        }
        if (detail.isNotEmpty()) {
            // The first line only: rclone sometimes concatenates the raw
            // backend error onto a wrapped one, which reads as noise on screen.
            return detail.lineSequence().firstOrNull()?.trim().orEmpty()
                .ifEmpty { FALLBACK_MESSAGE }
                .take(MAX_DETAIL)
        }
        return e.message?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL) ?: FALLBACK_MESSAGE
    }

    companion object {
        private const val TAG = "RcloneFileTransfer"

        private const val METHOD_COPYFILE = "operations/copyfile"

        /** Fast enough to look alive, slow enough not to waste battery. */
        private const val POLL_MS = 250L

        private const val FALLBACK_MESSAGE = "The file could not be downloaded"

        /** Matches the listing screen's cap on rclone's error text. */
        private const val MAX_DETAIL = 160
    }
}
