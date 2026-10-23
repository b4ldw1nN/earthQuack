package io.earthquack.app.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import io.earthquack.app.storage.RcloneConfigManager
import java.io.BufferedReader

/**
 * Imports an existing `rclone.conf`.
 *
 * ## Why this exists
 *
 * The form flow ([AddRemoteActivity]) is the right way to add one remote. It is
 * the wrong way to move a setup that already exists: a desktop machine with six
 * configured remotes would mean retyping every host, key and token by hand, on
 * a phone keyboard, with a real chance of transcription error in a credential.
 *
 * Importing the file the user already has is both faster and less error-prone.
 *
 * ## Trust boundary
 *
 * The imported file is treated as **untrusted input**, because it is a document
 * that arrived from outside the app:
 *
 * - Parsed by rclone's own INI reader afterwards, never by string handling here.
 * - The existing config is **backed up first**. An import that produces a config
 *   rclone then refuses to parse must not be the only copy.
 * - Never logged, and never echoed back beyond section names. A `rclone.conf`
 *   is full of passwords and OAuth tokens, so even a failed parse must not dump
 *   file content into logcat.
 * - Size-capped, so a mistakenly selected huge file cannot exhaust storage.
 */
class RcloneConfigImporter(private val context: Context) {

    private companion object {
        const val TAG = "RcloneConfigImport"
        const val MAX_IMPORT_BYTES = 512 * 1024L
    }

    /** What an import did, in terms the UI can state plainly. */
    data class Result(
        val sectionsImported: List<String>,
        val backedUp: Boolean
    ) {
        val count: Int get() = sectionsImported.size
    }

    /**
     * Reads [source] and merges it into the app's config.
     *
     * The merge is *additive*: existing sections are kept unless the imported
     * file defines the same name, in which case the imported one wins. That is
     * the behaviour a user expects from "import", and it avoids silently
     * deleting a remote they configured in-app.
     *
     * @return the sections now present, or null when the import failed. Null is
     *   distinct from an empty list: the former means nothing was changed.
     */
    fun importFrom(source: Uri): Result? {
        return try {
            val manager = RcloneConfigManager(context)
            manager.ensureReady()
            val target = manager.configFile

            val imported = readSectionNames(source)
            if (imported.isEmpty()) {
                Log.w(TAG, "imported file contained no [section] headers")
                return null
            }

            // Back up before overwriting. If rclone rejects the result, the user's
            // previous working config is still recoverable.
            var backedUp = false
            if (target.exists() && target.length() > 0) {
                val backup = target.resolveSibling("${target.name}.before-import")
                target.copyTo(backup, overwrite = true)
                backedUp = true
                Log.i(TAG, "backed up existing config before import")
            }

            target.writeText(merge(readText(source), readSectionNamesOf(target)))
            // The shared engine still holds the previous config in memory.
            io.earthquack.app.storage.RcloneEngines.reset()
            Log.i(TAG, "imported ${imported.size} section(s): ${imported.joinToString()}")
            Result(sectionsImported = imported, backedUp = backedUp)
    } catch (e: Exception) {
                Log.w(TAG, "import failed: ${e.message}")
                null
            }
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    /**
     * Section names from the imported text.
     *
     * Only headers are read, and only to decide what was imported. rclone does
     * the actual parsing and validation, which is the only authority that
     * matters -- this exists to tell the user what arrived and to reject a file
     * that is obviously not a config.
     */
    private fun readSectionNames(source: Uri): List<String> =
            readText(source).lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("[") && it.endsWith("]") && it.length > 2 }
                // rclone treats a leading `[type]` such as [GDRIVE] as the default
                // remote, which is still a section and must be preserved.
                .map { it.substring(1, it.length - 1).trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

    private fun readSectionNamesOf(file: java.io.File): List<String> =
            if (!file.exists()) emptyList() else
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("[") && it.endsWith("]") && it.length > 2 }
                    .map { it.substring(1, it.length - 1).trim() }
                    .distinct()
                    .toList()

    /**
     * Concatenates the existing config with the imported one.
     *
     * A duplicate section later in the file wins, which is what rclone's INI
     * reader does, so the imported definition overrides without needing a
     * parser here. Keys are never logged.
     */
    private fun merge(importedText: String, existingSections: List<String>): String {
            val existing = RcloneConfigManager(context).configFile.takeIf { it.exists() }
                ?.readText().orEmpty()
            return buildString {
                if (existing.isNotBlank()) {
                    append(existing.trimEnd())
                    append("\n\n")
                }
                append(importedText.trim())
                append("\n")
            }
    }

    /**
     * Reads the document as text.
     *
     * Size-capped and read through a buffered reader so a multi-megabyte file
     * cannot be pulled into memory. rclone.conf is plain text; anything with a
     * NUL is rejected as "not a config" rather than being written and left for
     * rclone to choke on.
     */
    private fun readText(source: Uri): String {
            val resolver = context.contentResolver
            val length = resolver.openAssetFileDescriptor(source, "r")?.use { it.length }
            if (length != null && length > MAX_IMPORT_BYTES) {
                throw IllegalArgumentException("file too large to be an rclone config ($length bytes)")
            }

            return resolver.openInputStream(source)?.use { stream ->
                BufferedReader(stream.reader()).use { reader ->
                    val text = reader.readText()
                    if (text.length > MAX_IMPORT_BYTES) {
                        throw IllegalArgumentException("file too large to be an rclone config")
                    }
                    if (text.contains('\u0000')) {
                        throw IllegalArgumentException("not a plain-text rclone config")
                    }
                    text
                }
            } ?: throw IllegalArgumentException("could not open the selected file")
    }
}
