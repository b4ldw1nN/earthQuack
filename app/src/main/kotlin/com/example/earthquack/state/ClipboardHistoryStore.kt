package com.example.earthquack.state

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A real clipboard sync event, as it actually happened.
 *
 * @param text the clipboard content. Truncated at capture time -- see
 *   [MAX_TEXT_CHARS] for why.
 * @param direction whether the phone sent this to the desktop or received it.
 * @param timestampMillis wall-clock time of the sync.
 */
data class ClipboardEntry(
    val text: String,
    val direction: Direction,
    val timestampMillis: Long
) {
    enum class Direction { SENT, RECEIVED }

    /** True for clipboard content that should not be written to a log. */
    val isSensitive: Boolean
        get() = text.any { it.isWhitespace() }.not() && SECRET_HINT.any { text.contains(it, ignoreCase = true) }

    private companion object {
        // Substrings that suggest a credential. Deliberately conservative:
        // this only gates logging, never storage, so a false positive costs
        // nothing while a false negative puts a key in logcat.
        val SECRET_HINT = listOf(
            "BEGIN PRIVATE KEY", "BEGIN RSA", "BEGIN OPENSSH",
            "password", "passwd", "secret", "api_key", "apikey", "token="
        )
    }
}

/**
 * Persists clipboard sync history.
 *
 * ## Provenance
 *
 * Entries are recorded inside `EarthQuackService`, at the two points where a
 * clipboard value genuinely changes hands -- pushed to the desktop, and
 * received from it. Nothing is synthesised, and no entry appears unless a real
 * sync occurred. That is why this is written to a file by the service and not
 * observed by a UI listener: a listener would silently lose history whenever
 * no screen happened to be open.
 *
 * ## Sensitivity
 *
 * Clipboard contents are frequently credentials -- an SSH key, a generated
 * token, a line from a `.env` file. So:
 *
 * - Stored in app-private storage only. Not shared, not written to logs, not
 *   exposed through the share-sheet activity.
 * - [ClipboardEntry.isSensitive] suppresses logging. It does **not** suppress
 *   storage, because the user asked for history and this is the device's own
 *   private storage; deleting it silently would be worse than storing it.
 * - The UI masks nothing but truncates, and never renders an entry into a
 *   content description or an analytics-style field.
 */
class ClipboardHistoryStore(context: Context) {

    private companion object {
        const val TAG = "ClipboardHistory"
        const val FILE = "clipboard_history.json"

        /**
         * Cap on retained entries.
         *
         * Oldest are dropped first. 50 is enough to be useful for a "what did I
         * just copy" question while keeping the file small enough that reading
         * and rewriting it on every sync stays cheap.
         */
        const val MAX_ENTRIES = 50

        /**
         * Cap on stored text length.
         *
         * Clipboard content is unbounded -- a copied file listing can be
         * megabytes. Storing that verbatim would grow the file without limit
         * and risks an OOM on a large paste. Truncated content is still useful
         * for recognising an item; the full value was always available on the
         * source machine.
         */
        const val MAX_TEXT_CHARS = 2000
    }

    private val file = File(context.applicationContext.filesDir, FILE)

    /**
     * Records a sync. Safe to call from any thread.
     *
     * Write failures are logged and swallowed. Losing a history entry is not
     * worth failing a clipboard sync over -- the sync is the feature, the
     * history is a convenience.
     */
    @Synchronized
    fun record(text: String, direction: ClipboardEntry.Direction) {
        if (text.isBlank()) return
        try {
            val trimmed = text.take(MAX_TEXT_CHARS)
            val entry = JSONObject().apply {
                put("text", trimmed)
                put("dir", direction.name)
                put("ts", System.currentTimeMillis())
            }

            val entries = readRaw().toMutableList()
            // Newest first, and de-duplicate: the desktop echo of a value we
            // just sent would otherwise appear as a second, identical row.
            entries.removeAll { it.optString("text") == trimmed }
            entries.add(0, entry)

            val trimmed2 = entries.take(MAX_ENTRIES)
            writeRaw(trimmed2)

            if (!ClipboardEntry(trimmed, direction, 0L).isSensitive) {
                Log.d(TAG, "recorded ${direction.name} clipboard entry (${trimmed.length} chars)")
            } else {
                // Say that it happened, without saying what.
                Log.d(TAG, "recorded ${direction.name} clipboard entry (content withheld: may be sensitive)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not record clipboard history: ${e.message}")
        }
    }

    /** Returns history newest-first. Empty when nothing has synced yet. */
    @Synchronized
    fun list(): List<ClipboardEntry> = try {
        readRaw().mapNotNull { obj ->
            val text = obj.optString("text")
            if (text.isEmpty()) return@mapNotNull null
            ClipboardEntry(
                text = text,
                direction = runCatching {
                    ClipboardEntry.Direction.valueOf(obj.optString("dir"))
                }.getOrDefault(ClipboardEntry.Direction.RECEIVED),
                timestampMillis = obj.optLong("ts")
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read clipboard history: ${e.message}")
        emptyList()
    }

    /** The most recent entry, or null if nothing has synced. */
    fun latest(): ClipboardEntry? = list().firstOrNull()

    /** Empties the history. */
    @Synchronized
    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "could not clear clipboard history: ${e.message}")
        }
    }

    // ── storage ──────────────────────────────────────────────────────────────

    private fun readRaw(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            // A corrupt file must not brick the sync feature. Move it aside
            // rather than deleting, so it can still be inspected.
            Log.w(TAG, "history file unreadable, quarantining: ${e.message}")
            runCatching { file.renameTo(File(file.parentFile, "$FILE.corrupt")) }
            emptyList()
        }
    }

    /**
     * Writes atomically.
     *
     * Written to a temp file and renamed. A plain [File.writeText] truncates
     * first, so a kill mid-write leaves a half-written JSON file and the
     * entire history is lost. Rename is atomic within a filesystem, so the
     * file on disk is always the previous complete version or the new one.
     */
    private fun writeRaw(entries: List<JSONObject>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            // renameTo can fail if the destination exists on some filesystems.
            file.delete()
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "could not replace history file")
                tmp.delete()
            }
        }
    }
}
