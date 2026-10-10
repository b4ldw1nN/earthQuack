package com.example.earthquack.storage

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * A cached `operations/list` reply, with the instant it was fetched.
 *
 * [rows] is the reply **as rclone returned it**, not a list of [ui.FileEntry]:
 * the row model in this app is derived at the parse boundary, so caching the
 * raw reply means a cached listing renders through exactly the same code as a
 * live one. A second parser would be a second chance to disagree with the
 * first about sizes, dates and escaping.
 */
data class CachedListing(
    val reply: String,
    val cachedAt: Long,
    val key: String
) {
    /** Age in whole days, rounded down. Zero means "fetched today". */
    fun ageDays(now: Long): Long {
        // A clock in the past, or a skew of a few seconds either way, must not
        // turn a fresh entry into a negative age that no day count matches.
        val elapsed = (now - cachedAt).coerceAtLeast(0L)
        return if (elapsed <= 0L) 0L else elapsed / DAY_MILLIS
    }

    private companion object {
        /** Own copy of the constant, so the class does not reach into its own. */
        val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * Disk cache for directory listings, keyed by the two parameters the listing
 * RPC takes.
 *
 * ## Why the raw reply is stored
 *
 * `operations/list` is the expensive part of this screen: a round trip per
 * tap, on a remote that may be a slow connection. The rows themselves are
 * small — a few hundred bytes each — so a directory of a thousand entries is
 * a file of tens of kilobytes. Caching that and showing it immediately is the
 * difference between the list appearing and a spinner the user watches.
 *
 * ## Why files and not a database
 *
 * The entries have no relationships, are written once and replaced whole, and
 * are read by primary key. One small file per directory, in the cache dir the
 * OS already prunes under pressure, is the least machinery that can do it — a
 * Room table would be more code, more migrations, and no clearer here. The
 * cache is disposable by design: losing all of it costs one re-list.
 *
 * ## Why the key is hashed
 *
 * An `fs` spec and a path can contain anything a remote name and a filename can
 * contain — slashes, colons, non-Latin scripts — and a path is not a valid
 * filename. SHA-256 over the two parameters gives a fixed-length, stable,
 * collision-resistant name, so `temp:/a/b` and `temp:/a` cannot share an entry
 * however similar their paths look.
 *
 * @param root the directory the cache files live in; created on construction.
 * @param now injectable clock, so tests do not sleep.
 */
class DirectoryListingCache(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis
) {

    init {
        if (!root.exists() && !root.mkdirs()) {
            // A cache that cannot be created is a cache that is empty; the
            // listing path still works, just without the shortcut.
            Log.w(TAG, "cache root ${root.path} could not be created")
        }
    }

    /**
     * The cached listing for [fs] and [remote], or null.
     *
     * Null for every reason a listing should not be used: retention disabled,
     * expired, unreadable, or corrupt. A corrupt entry is deleted rather than
     * tolerated, so a bad file costs one re-list instead of failing every load
     * until the cache is cleared.
     *
     * @param retentionDays entries older than this are ignored. Zero or less
     *   disables the cache entirely, which is what "keep listings for no time"
     *   has to mean when the setting is off.
     */
    fun load(fs: String, remote: String, retentionDays: Int): CachedListing? {
        if (retentionDays <= 0) return null
        val file = fileFor(fs, remote)
        if (!file.exists()) return null

        val read = try {
            file.readText()
        } catch (e: Exception) {
            Log.w(TAG, "cache entry unreadable: ${e.javaClass.simpleName}")
            delete(file)
            return null
        }
        if (read.isEmpty()) {
            delete(file)
            return null
        }

        val listing = try {
            val json = org.json.JSONObject(read)
            CachedListing(
                reply = json.getString(KEY_REPLY),
                cachedAt = json.getLong(KEY_CACHED_AT),
                key = keyOf(fs, remote)
            )
        } catch (e: Exception) {
            // Written by a future or older version, truncated by a full disk,
            // or hand-edited. None of those can be recovered from.
            Log.w(TAG, "cache entry unusable: ${e.javaClass.simpleName}")
            delete(file)
            return null
        }

        return if (listing.ageDays(now()) >= retentionDays) {
            delete(file)
            null
        } else {
            listing
        }
    }

    /**
     * Stores [reply] for [fs] and [remote], replacing any previous entry.
     *
     * Written to a temporary file and renamed, so a process killed mid-write
     * leaves the old entry intact rather than a half-file. Readers never see a
     * partial JSON document.
     */
    fun store(fs: String, remote: String, reply: String) {
        val file = fileFor(fs, remote)
        val payload = try {
            org.json.JSONObject()
                .put(KEY_VERSION, VERSION)
                .put(KEY_FS, fs)
                .put(KEY_REMOTE, remote)
                .put(KEY_CACHED_AT, now())
                .put(KEY_REPLY, reply)
                .toString()
        } catch (e: Exception) {
            Log.w(TAG, "cache entry could not be built: ${e.javaClass.simpleName}")
            return
        }

        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            if (!file.parentFile.exists() && !file.parentFile.mkdirs()) return
            temp.writeText(payload)
            if (!temp.renameTo(file)) {
                // The destination rejecting a rename is a full disk or a
                // concurrent entry; both leave the old value usable.
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed: ${e.javaClass.simpleName}")
            temp.delete()
        }
    }

    /** Removes the entry for [fs] and [remote], if any. */
    fun invalidate(fs: String, remote: String) = delete(fileFor(fs, remote))

    /**
     * Drops every entry older than [retentionDays].
     *
     * Called when the browser opens, so an entry whose directory was never
     * revisited still goes away, and the cache cannot grow without bound as
     * the user explores. Entries beyond [maxEntries] newest-kept are dropped
     * as well, which caps the total even at "keep forever".
     *
     * @return the number of entries removed, for logs and tests.
     */
    fun prune(retentionDays: Int, maxEntries: Int = DEFAULT_MAX_ENTRIES): Int {
        val files = root.listFiles()?.filter { it.isFile && it.name?.endsWith(".tmp") == false }
            ?: return 0
        val nowMs = now()
        var removed = 0

        for (file in files) {
            val age = try {
                nowMs - org.json.JSONObject(file.readText()).getLong(KEY_CACHED_AT)
            } catch (e: Exception) {
                // Unreadable or from a newer schema: treat as expired.
                removed += if (delete(file)) 1 else 0
                continue
            }
            val expired = age < 0L || age > retentionDays.toLong() * MILLIS_PER_DAY
            if (expired) removed += if (delete(file)) 1 else 0
        }

        // Newest-kept trim. Only reached when the setting is effectively
        // "forever", since an offline day bound keeps the count small already.
        if (retentionDays > 0 && files.size - removed > maxEntries) {
            val survivors = files.filter { it.exists() }
                .sortedByDescending { file ->
                    try {
                        org.json.JSONObject(file.readText()).getLong(KEY_CACHED_AT)
                    } catch (e: Exception) {
                        Long.MIN_VALUE
                    }
                }
                .drop(maxEntries)
            for (file in survivors) removed += if (delete(file)) 1 else 0
        }

        if (removed > 0) Log.i(TAG, "pruned $removed cached listing(s)")
        return removed
    }

    /** Entries currently on disk. */
    fun count(): Int = root.listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } ?: 0

    // ── internals ─────────────────────────────────────────────────────────────

    private fun fileFor(fs: String, remote: String): File =
        File(root, hashOf(keyOf(fs, remote)) + SUFFIX)

    private fun keyOf(fs: String, remote: String): String = "$fs\u0000$remote"

    /**
     * SHA-256 of the key, hex encoded.
     *
     * SHA-256 rather than a shorter hash: the key is unique per directory and a
     * collision would silently show a different directory's contents, which is
     * the worst failure this cache could have. MessageDigest is available from
     * API 26, which is this app's minSdk.
     */
    private fun hashOf(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) hex.append('0')
            hex.append(Integer.toHexString(value))
        }
        return hex.toString()
    }

    private fun delete(file: File): Boolean = try {
        !file.exists() || file.delete()
    } catch (e: Exception) {
        // A file that cannot be deleted will be re-attempted on the next
        // prune; failing a listing over it would be the wrong trade.
        Log.w(TAG, "cache delete failed for ${file.name}")
        false
    }

    companion object {
        private const val TAG = "DirListingCache"

        private const val DIR_NAME = "dir-index"

        /** Cache root under the app's cache dir. */
        fun rootFor(cacheDir: File): File = File(cacheDir, DIR_NAME)

        private const val SUFFIX = ".json"

        private const val VERSION = 1
        private const val KEY_VERSION = "v"
        private const val KEY_FS = "fs"
        private const val KEY_REMOTE = "remote"
        private const val KEY_CACHED_AT = "cachedAt"
        private const val KEY_REPLY = "reply"

        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /**
         * How many cached directories to keep when retention is "forever".
         *
         * A large number, because it only bounds the disk: a directory with a
         * thousand entries is tens of kilobytes, so this is a few megabytes at
         * the outside, and the whole directory lives in a cache the OS may
         * reclaim at any time anyway.
         */
        const val DEFAULT_MAX_ENTRIES = 400

        /** A clock that answers a fixed instant; used by tests. */
        fun fixedClock(instant: Long): () -> Long = { instant }
    }
}
