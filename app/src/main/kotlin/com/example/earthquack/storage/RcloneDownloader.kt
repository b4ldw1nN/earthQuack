package com.example.earthquack.storage

import java.io.File
import java.security.MessageDigest

/**
 * A resolved request to pull one remote file into a local cache directory.
 *
 * Constructed before any I/O so the mapping from the row the user tapped to the
 * two strings `operations/copyfile` needs is a pure function that can be tested
 * without a device, a remote or a filesystem.
 *
 * @param srcFs the rclone filesystem root the entry was listed from, i.e. the
 *   screen's current `fs` (`name:`, `:local:/abs/path`, …).
 * @param srcRemote `FileEntry.path`: the path **relative to [srcFs]**, exactly as
 *   rclone's `operations/list` returned it. Already escaped by rclone, so it is
 *   passed through unmodified.
 * @param dstFs the local cache filesystem root, always `:local:` + an absolute
 *   directory. A bare path is *not* a filesystem; without the prefix rclone
 *   answers 400 "invalid remote".
 * @param dstRemote the destination filename relative to [dstFs].
 * @param localFile the destination as a local path, used for existence checks,
 *   size polling and deletion.
 */
data class RcloneDownloadRequest(
    val srcFs: String,
    val srcRemote: String,
    val dstFs: String,
    val dstRemote: String,
    val localFile: String
) {
    val params: Map<String, String>
        get() = mapOf(
            "srcFs" to srcFs,
            "srcRemote" to srcRemote,
            "dstFs" to dstFs,
            "dstRemote" to dstRemote
        )
}

/** Outcome of a single download. */
sealed class RcloneDownloadResult {

    /** The file is complete and its size is known to match the remote's. */
    data class Success(val localPath: String, val size: Long) : RcloneDownloadResult()

    /** Failed, with a message safe to show. [detail] is rclone's own text where
     * it gave one, and a plain sentence otherwise. */
    data class Failure(val detail: String) : RcloneDownloadResult()
}

/**
 * The rules for naming and locating downloaded files, kept free of Android
 * imports so the unit tests run on the JVM.
 *
 * Two properties matter and they pull in opposite directions:
 *
 *  1. The **original name and extension** must survive, because the opening
 *     application (and the OS's MIME resolution) works off the extension.
 *  2. Two taps of the same file, or two different files that happen to share a
 *     name, must not collide, and must not overwrite a file that is currently
 *     open in another app.
 *
 * The resolution is one directory per *file*, not per attempt:
 * `cacheDir/opened/<key>/<name>`.
 *
 * The key is derived from the file's identity (remote + path + size), so the
 * second tap of the same file finds the first download already there and opens
 * it without a round trip — which is what "shows downloading each time" was.
 *
 * The consequence to design around: a directory keyed on the file is *not*
 * unique per attempt, so a re-download would land on a name another app may be
 * reading. Two things keep that safe. The transfer writes `<name>.part` and
 * renames it into place, so a reader either sees the old complete file or the
 * new complete one and never a half-writing one. And cleanup is age-based, so
 * it never deletes a file it only suspects is unused.
 */
class RcloneDownloadPlanner(private val cacheRoot: String) {

    /**
     * Builds the destination for [fileName].
     *
     * @param key the file's cache key, from [cacheKey].
     */
    fun request(
        srcFs: String,
        srcRemote: String,
        fileName: String,
        key: String
    ): RcloneDownloadRequest {
        val dir = "$cacheRoot/$OPENED_DIR/$key"
        val safeName = safeFileName(fileName)
        return RcloneDownloadRequest(
            srcFs = srcFs,
            srcRemote = srcRemote,
            dstFs = LOCAL_FS_PREFIX + dir,
            dstRemote = safeName,
            localFile = "$dir/$safeName"
        )
    }

    /**
     * The already-downloaded copy of a file, or null.
     *
     * @param size the size the listing reported, so a cached copy of an older
     *   version of the same path is not served as if it were current.
     */
    fun cachedFile(srcFs: String, srcRemote: String, size: Long?, fileName: String): File? {
        val file = File(request(srcFs, srcRemote, fileName, cacheKey(srcFs, srcRemote, size)).localFile)
        return file.takeIf { DownloadVerification.isComplete(it.length(), size) }
    }

    /** The directory holding every downloaded file. */
    fun openedDir(): String = "$cacheRoot/$OPENED_DIR"

    companion object {
        /** Under `cacheDir`, next to the caches the OS already prunes for us. */
        const val OPENED_DIR = "opened"

        /** Name rclone writes while the transfer is in flight. */
        const val PART_SUFFIX = ".part"

        /**
         * Identity of a file in a remote, as a hex digest.
         *
         * Size is part of the key deliberately: a file whose size changed is a
         * different file, and a stale copy of the old one must not be served
         * from cache. The size is what the local backend reports for free, so
         * this costs no extra RPC. A remote that reports no size at all gets
         * the literal "unknown" in the key, which is the same every time — so
         * backing that remote, a one-size-fits-all download, still caches per
         * path rather than colliding on a shared constant.
         *
         * SHA-256 rather than a shorter hash: the key is unique per file and a
         * collision would silently show one file's contents under another's
         * name, which is the worst failure this cache could have.
         */
        fun cacheKey(fs: String, srcRemote: String, size: Long?): String {
            val raw = "$fs\u0000$srcRemote\u0000${size ?: UNKNOWN_SIZE}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            val hex = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                if (value < 0x10) hex.append('0')
                hex.append(Integer.toHexString(value))
            }
            return hex.toString()
        }

        /** A backend that reports no size, spelled so it cannot collide with 0. */
        private const val UNKNOWN_SIZE = "unknown"

        /**
         * rclone's on-the-fly local filesystem prefix. The leading colon is the
         * load-bearing part: `local:/…` looks for a config section named
         * `local`, while `:local:/…` constructs the backend from the path.
         */
        const val LOCAL_FS_PREFIX = ":local:"

        /**
         * Reduces [name] to a single safe path segment.
         *
         * rclone reports names as returned by the backend, which for a remote
         * can legally contain a `/`, a `..`, or a leading dot, and the local
         * filesystem would reject some of those. A separator here is *not*
         * part of a name, so it is dropped rather than kept; a file the remote
         * calls `a/b.txt` is downloaded as `b.txt` rather than navigated into.
         *
         * An empty result means the name was unusable as-is, and the caller
         * falls back to a placeholder instead of writing to the directory
         * itself.
         */
        fun safeFileName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return FALLBACK_NAME
            // Any single separator would escape the per-download directory,
            // including the Windows form, which some backends do report.
            val lastSegment = trimmed
                .split('/', '\\')
                .lastOrNull { it.isNotBlank() && it != "." && it != ".." }
                ?.trim()
                ?: return FALLBACK_NAME
            // Strip control characters: they are invisible, break the row, and
            // have no business in a filename the user is expected to see.
            val cleaned = lastSegment.filter { it.code >= 0x20 && it.code != 0x7F }
            return cleaned.trim().ifEmpty { FALLBACK_NAME }
        }

        /**
         * Placeholder when a remote file reports no usable name.
         *
         * No suffix on purpose: the MIME is resolved from content later, so an
         * invented extension would be a guess that misleads the OS.
         */
        const val FALLBACK_NAME = "download"

        /** Guard against absurd names that would blow past PATH_MAX mid-copy. */
        const val MAX_NAME_BYTES = 200
    }
}

/**
 * Tracks which [RcloneDownloadRequest.localFile] paths are being transferred.
 *
 * A map rather than a counter because the *right* key is the local destination:
 * two taps of the same row share a key and the second is dropped, while two
 * taps of two different files each get their own and both proceed.
 *
 * Purely in-memory and per-process. A process death abandons any entry, which
 * is correct because the transfer died with it and the partial file is removed
 * on the next startup by cache pruning.
 */
class DownloadTracker {

    private val inFlight = mutableSetOf<String>()

    /** True when a download of [dst] is already running. */
    fun isRunning(dst: String): Boolean = synchronized(inFlight) { dst in inFlight }

    /** Marks [dst] as running. False when it was already running. */
    fun tryStart(dst: String): Boolean = synchronized(inFlight) {
        if (dst in inFlight) false else {
            inFlight.add(dst); true
        }
    }

    /** Clears the marker. Safe to call for a path that never started. */
    fun finish(dst: String) = synchronized(inFlight) {
        inFlight.remove(dst)
    }

    /** Current count, for tests and diagnostics. */
    fun count(): Int = synchronized(inFlight) { inFlight.size }
}

/**
 * Filesystem-side expectations for a completed transfer, expressed without
 * touching the filesystem.
 *
 * `operations/copyfile` only returns `{}` — it reports success and nothing
 * else. So "the file is complete" is established here, on the local file, and
 * not taken on faith from rclone's status code. A backend that returns 200
 * while writing a truncated object is exactly the case this catches.
 */
object DownloadVerification {

    /**
     * @param actualSize length of the local file after the copy, in bytes.
     * @param expectedSize the size `operations/list` reported, or null when the
     *   backend reported no size. Null means "nothing to compare against" and
     *   is *not* treated as zero.
     */
    fun isComplete(actualSize: Long, expectedSize: Long?): Boolean =
        actualSize > 0L && (expectedSize == null || actualSize >= expectedSize)
}
