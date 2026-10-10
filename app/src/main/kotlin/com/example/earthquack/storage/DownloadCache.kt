package com.example.earthquack.storage

import android.util.Log
import java.io.File

/**
 * The download cache: files rclone copied out of a remote so a viewer could
 * read them. All of it lives under `cacheDir/opened/`, one directory per file.
 *
 * ## What "delete the cache" means here
 *
 * Two operations, because they answer different questions:
 *
 *  - [clear] is the user saying "I want the space back now". It removes every
 *    complete file, immediately.
 *  - [prune] is the policy: files the user has not touched for longer than the
 *    retention they chose go away, on their own, without them asking.
 *
 * They share one rule about what is *not* deletable: a `.part` file is a
 * transfer in flight, and a complete file that another app was handed a content
 * URI for may still be open. The first is knowable — the suffix — and is left
 * alone by both. The second is not knowable from the filesystem, which is why
 * [prune] is age-based rather than eager and [clear] is the user's explicit
 * choice: Android's cache eviction would reclaim this space eventually anyway,
 * and a user who presses "Delete" has accepted that an open viewer loses the
 * file underneath it.
 *
 * ## Sizes
 *
 * Reported rather than assumed: `length()` on each file, summed. A directory
 * walk of a few hundred files is fast enough to do on demand, and a stale
 * figure next to a "Delete" button is worse than one computed when the screen
 * opens.
 */
class DownloadCache(private val openedRoot: File) {

    /** Bytes held by every complete cached file. Excludes `.part` transfers. */
    fun size(): Long = completeFiles().sumOf { it.length() }

    /** Complete cached files. A `.part` is not a file the user can open. */
    fun count(): Int = completeFiles().size

    /**
     * Deletes every cached file, keeping transfers that are running.
     *
     * @return the number of files removed.
     */
    fun clear(): Int {
        var removed = 0
        for (file in files()) {
            if (file.name.endsWith(PART_SUFFIX)) continue
            if (delete(file)) removed++
        }
        removeEmptyDirectories()
        return removed
    }

    /**
     * Deletes files older than [days].
     *
     * Age is the file's last-modified time, which a completed download has as
     * the moment it was written and a re-opened file keeps — so a file used
     * yesterday is not stale, and one written three weeks ago and never touched
     * again is.
     *
     * @param days zero or less disables retention and removes everything.
     * @return the number of files removed.
     */
    fun prune(days: Int): Int {
        if (days <= 0) return clear()

        val cutoff = System.currentTimeMillis() - days.toLong() * MILLIS_PER_DAY
        var removed = 0
        for (file in files()) {
            if (file.name.endsWith(PART_SUFFIX)) continue
            if (file.lastModified() < cutoff && delete(file)) removed++
        }
        removeEmptyDirectories()
        return removed
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Every `.part` and complete file, recursively. */
    private fun files(): List<File> = buildList {
        fun walk(dir: File) {
            val children = try {
                dir.listFiles() ?: return
            } catch (e: SecurityException) {
                // A directory this process cannot read contributes no files;
                // skipping it is the same outcome as it being empty.
                emptyArray()
            }
            for (child in children) {
                if (child.isDirectory) walk(child) else add(child)
            }
        }
        walk(openedRoot)
    }

    private fun completeFiles(): List<File> =
        files().filterNot { it.name.endsWith(PART_SUFFIX) }

    private fun delete(file: File): Boolean = try {
        file.delete()
    } catch (e: SecurityException) {
        // Not deletable by this process. Left alone; the next prune retries.
        Log.w(TAG, "could not delete ${file.name}")
        false
    }

    /**
     * Removes directories that are now empty, so `opened/` does not become a
     * list of dead keys.
     *
     * Only the immediate children of the root are considered, which is exactly
     * the one-level-per-file layout this cache uses. A deeper tree would need a
     * walk, and there is none.
     */
    private fun removeEmptyDirectories() {
        val children = try {
            openedRoot.listFiles() ?: return
        } catch (e: SecurityException) {
            return
        }
        for (child in children) {
            if (child.isDirectory && (child.list()?.isEmpty() == true)) {
                child.delete()
            }
        }
    }

    companion object {
        private const val TAG = "DownloadCache"

        /** Same suffix the transfer writes into; kept in one place. */
        const val PART_SUFFIX = ".part"

        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** The cache under `cacheDir`. */
        fun rootFor(cacheDir: File): File = File(cacheDir, RcloneDownloadPlanner.OPENED_DIR)
    }
}
