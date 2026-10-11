package com.example.earthquack.storage

import com.example.earthquack.ui.FileEntry

/**
 * Pure file-management helpers for the rclone browser (Section E).
 *
 * Everything here is backend-agnostic values-in/values-out over the existing
 * [FileEntry] metadata — no RPC, no Context — so it runs on the JVM. The
 * activity is the thin shell: it renders, confirms, and issues the same
 * rclone list/copy/move/delete RPCs through the existing [RcloneEngine]. No second engine.
 *
 * Deliberately NOT included: recursive remote search (rclone's list has no
 * cheap recursive RC shape in this build; current-directory search stays
 * instant and honest), and an offline destructive queue (replaying a delete
 * after the remote changed would destroy data the user never re-confirmed).
 */
enum class FileSortMode {
    NAME,
    SIZE,
    MODIFIED,
    TYPE;

    val label: String
        get() = when (this) {
            NAME -> "Name"
            SIZE -> "Size"
            MODIFIED -> "Date modified"
            TYPE -> "Type"
        }
}

enum class FileTypeFilter {
    ALL,
    DIRECTORIES,
    FILES;

    val label: String
        get() = when (this) {
            ALL -> "All"
            DIRECTORIES -> "Folders"
            FILES -> "Files"
        }
}

/** Sorts [entries] per [mode]. Directories always lead — see below. */
fun sortEntries(entries: List<FileEntry>, mode: FileSortMode): List<FileEntry> {
    // Folders-first in every mode: finding the folder is the common case and
    // a size-sorted list that buries every folder under multi-GB files is a
    // list that hides navigation. Within each group the mode applies.
    val dirsFirst: Comparator<FileEntry> = compareByDescending { it.isDir }
    return when (mode) {
        FileSortMode.NAME -> entries.sortedWith(
            dirsFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        FileSortMode.SIZE -> entries.sortedWith(
            dirsFirst.thenByDescending { it.size ?: -1L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        FileSortMode.MODIFIED -> entries.sortedWith(
            dirsFirst.thenByDescending { it.modified }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        FileSortMode.TYPE -> entries.sortedWith(
            dirsFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { typeKey(it) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }
}

private fun typeKey(entry: FileEntry): String {
    if (entry.isDir) return ""
    val name = entry.name
    val ext = name.substringAfterLast('.', "").takeIf { name.contains('.') }.orEmpty()
    return ext.lowercase().ifEmpty { entry.mimeType.lowercase() }
}

/** Narrows [entries] to [filter]. */
fun filterEntries(entries: List<FileEntry>, filter: FileTypeFilter): List<FileEntry> =
    when (filter) {
        FileTypeFilter.ALL -> entries
        FileTypeFilter.DIRECTORIES -> entries.filter { it.isDir }
        FileTypeFilter.FILES -> entries.filter { !it.isDir }
    }

/**
 * Current-directory name search.
 *
 * Matches [FileEntry.name] case-insensitively. Intentionally NOT recursive:
 * it filters rows the backend already returned, so it is instant and can
 * never claim a match the listing did not contain.
 */
fun searchEntries(entries: List<FileEntry>, query: String): List<FileEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { it.name.contains(needle, ignoreCase = true) }
}

/**
 * Multi-selection keyed by entry path.
 *
 * Paths are rclone-relative and unique within a listing, so they identify a
 * row without holding the row. The set is cleared on navigation ([clear] on
 * every `switchTo`/`openFolder`/`load` of a new directory) because a path
 * selected in one folder is meaningless in another.
 */
class SelectionState {
    private val selected = linkedSetOf<String>()

    val count: Int get() = selected.size
    val isEmpty: Boolean get() = selected.isEmpty()
    fun isSelected(path: String): Boolean = selected.contains(path)

    /** Toggles [path]. Returns true when it is selected afterwards. */
    fun toggle(path: String): Boolean {
        if (!selected.add(path)) selected.remove(path)
        return selected.contains(path)
    }

    fun selectAll(paths: Iterable<String>) {
        paths.forEach { selected.add(it) }
    }

    fun clear() = selected.clear()
    fun paths(): Set<String> = selected.toSet()
}

/** How bulk bytes reach their destination — shown to the user, not hidden. */
enum class BulkTransferKind {
    /**
     * Source and destination share one filesystem root (`fs`): rclone can
     * copy/move server-side without pulling bytes through the phone.
     */
    SERVER_SIDE,

    /**
     * Different roots: bytes stream through rclone on this device. Costs
     * battery and data; the progress line must say so.
     */
    VIA_DEVICE;

    val label: String
        get() = when (this) {
            SERVER_SIDE -> "Server-side"
            VIA_DEVICE -> "Via this device"
        }
}

/**
 * One validated bulk plan.
 *
 * @param files entries that bulk ops support (regular files).
 * @param excludedDirs folders in the selection, which bulk copy/move skip:
 *   file copy/move RPCs address single objects, and recursing a tree without
 *   a progress or cancellation story would lie about both.
 */
data class BulkPlan(
    val files: List<FileEntry>,
    val excludedDirs: List<FileEntry>,
    val srcFs: String,
    val kind: BulkTransferKind
) {
    val isEmpty: Boolean get() = files.isEmpty()
}

/** Builds a [BulkPlan] for copying/moving [selection] out of [srcFs]. */
fun planBulkTransfer(
    entries: List<FileEntry>,
    selection: Set<String>,
    srcFs: String,
    dstFs: String
): BulkPlan {
    val picked = entries.filter { it.path in selection }
    return BulkPlan(
        files = picked.filter { !it.isDir },
        excludedDirs = picked.filter { it.isDir },
        srcFs = srcFs,
        kind = if (srcFs == dstFs) BulkTransferKind.SERVER_SIDE else BulkTransferKind.VIA_DEVICE
    )
}

/**
 * Confirmation text for bulk deletion.
 *
 * Lists up to [MAX_LISTED] names verbatim, then counts the rest; folders are
 * called out because deleting one removes its whole subtree. The irreversibility
 * warning is part of the returned string so no caller can show the list
 * without the warning.
 */
fun bulkDeleteMessage(entries: List<FileEntry>, maxListed: Int = 5): String {
    val names = entries.map { it.name.ifBlank { it.path.substringAfterLast('/') } }
    val listed = names.take(maxListed).joinToString("\n• ", prefix = "• ")
    val more = if (names.size > maxListed) "\n…and ${names.size - maxListed} more" else ""
    val dirs = entries.count { it.isDir }
    val dirNote = if (dirs > 0) {
        "\n\n$dirs folder${if (dirs == 1) "" else "s"} and everything inside ${if (dirs == 1) "it" else "them"} will be removed."
    } else ""
    return "Delete ${entries.size} item${if (entries.size == 1) "" else "s"}?\n$listed$more$dirNote\n\nThis cannot be undone."
}

/** Per-item outcome of a bulk run, for the success/partial-failure report. */
data class BulkItemResult(
    val entry: FileEntry,
    val ok: Boolean,
    val detail: String = ""
)

/** One-line summary: full success, or succeeded/failed counts with names. */
fun bulkResultMessage(results: List<BulkItemResult>, verb: String): String {
    val failed = results.filter { !it.ok }
    if (failed.isEmpty()) return "${results.size} item${if (results.size == 1) "" else "s"} $verb"
    val names = failed.take(3).joinToString(", ") { it.entry.name.ifBlank { it.entry.path } }
    val more = if (failed.size > 3) " and ${failed.size - 3} more" else ""
    return "${results.size - failed.size} of ${results.size} $verb. Failed: $names$more."
}

/**
 * Classifies a bulk-operation error for honest UI.
 *
 * Mirrors the share-link classifier's patterns (same rclone envelope, same
 * upstream v1.75.1 phrasing) without depending on it: unsupported backends
 * surface as unavailable, never as success.
 */
fun classifyBulkError(detail: String): BulkErrorKind {
    val text = detail.lowercase()
    return when {
        "doesn't support" in text || "not implemented" in text || "not supported" in text ->
            BulkErrorKind.UNSUPPORTED
        "not found" in text || "no such file" in text || "does not exist" in text ||
            "didn't find" in text ->
            BulkErrorKind.NOT_FOUND
        "permission denied" in text || "token" in text || "unauthorized" in text ||
            "401" in text || "403" in text || "forbidden" in text ->
            BulkErrorKind.AUTH
        else -> BulkErrorKind.OTHER
    }
}

enum class BulkErrorKind {
    UNSUPPORTED,
    NOT_FOUND,
    AUTH,
    OTHER;
}

/** Validates a new folder/file name before any RPC. Null means valid. */
fun validateNewName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Enter a name"
    if (trimmed == "." || trimmed == "..") return "That name is reserved"
    if (trimmed.contains('/')) return "Names cannot contain /"
    if (trimmed.length > 255) return "That name is too long"
    return null
}
