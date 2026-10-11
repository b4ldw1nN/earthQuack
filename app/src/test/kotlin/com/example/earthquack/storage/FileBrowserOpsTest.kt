package com.example.earthquack.storage

import com.example.earthquack.ui.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section E: file-management helpers.
 *
 * Pure values-in/values-out over [FileEntry] — no RPC, no Context — so every
 * case runs on the JVM. The activity test-matrix (bulk success, partial
 * failure, cancellation, capability differences) executes against these exact
 * functions; the RPC layer is the already-tested [RcloneEngine].
 */
class FileBrowserOpsTest {

    private fun entry(
        name: String,
        path: String = name,
        isDir: Boolean = false,
        size: Long? = 10L,
        mime: String = "",
        modified: String = ""
    ) = FileEntry(name, path, isDir, size, mime, modified)

    // ── sorting ──────────────────────────────────────────────────────────

    @Test
    fun `default sort is folders first then case-insensitive name`() {
        val rows = listOf(
            entry("zebra.txt"), entry("Apple.txt"), entry("docs", isDir = true),
            entry("banana", isDir = true)
        )
        val out = sortEntries(rows, FileSortMode.NAME).map { it.name }
        assertEquals(listOf("banana", "docs", "Apple.txt", "zebra.txt"), out)
    }

    @Test
    fun `size sort keeps folders first and puts unknown sizes last`() {
        val rows = listOf(
            entry("big.bin", size = 9000L), entry("unknown.bin", size = null),
            entry("small.bin", size = 3L), entry("folder", isDir = true)
        )
        val out = sortEntries(rows, FileSortMode.SIZE).map { it.name }
        assertEquals(listOf("folder", "big.bin", "small.bin", "unknown.bin"), out)
    }

    @Test
    fun `modified sort is newest first with folders first`() {
        val rows = listOf(
            entry("old.txt", modified = "2024-01-01"),
            entry("new.txt", modified = "2026-05-05"),
            entry("folder", isDir = true, modified = "2026-06-06")
        )
        val out = sortEntries(rows, FileSortMode.MODIFIED).map { it.name }
        assertEquals(listOf("folder", "new.txt", "old.txt"), out)
    }

    @Test
    fun `type sort groups by extension`() {
        val rows = listOf(entry("b.mp4"), entry("a.pdf"), entry("c.mp4"))
        val out = sortEntries(rows, FileSortMode.TYPE).map { it.name }
        assertEquals(listOf("b.mp4", "c.mp4", "a.pdf"), out)
    }

    // ── filtering + search ───────────────────────────────────────────────

    @Test
    fun `type filter splits folders and files`() {
        val rows = listOf(entry("a.txt"), entry("d", isDir = true))
        assertEquals(1, filterEntries(rows, FileTypeFilter.FILES).size)
        assertEquals(1, filterEntries(rows, FileTypeFilter.DIRECTORIES).size)
        assertEquals(2, filterEntries(rows, FileTypeFilter.ALL).size)
    }

    @Test
    fun `search matches names case-insensitively within the listing`() {
        val rows = listOf(entry("Report.PDF"), entry("notes.txt"), entry("photos", isDir = true))
        assertEquals(listOf("Report.PDF"), searchEntries(rows, "report").map { it.name })
        assertEquals(rows, searchEntries(rows, "  "))
    }

    // ── selection ────────────────────────────────────────────────────────

    @Test
    fun `selection toggles and clears`() {
        val sel = SelectionState()
        assertTrue(sel.isEmpty)
        assertTrue(sel.toggle("a/b"))
        assertEquals(1, sel.count)
        assertFalse(sel.toggle("a/b"))
        assertTrue(sel.isEmpty)
        sel.selectAll(listOf("x", "y"))
        assertEquals(2, sel.count)
        sel.clear()
        assertTrue(sel.isEmpty)
    }

    // ── bulk plans ───────────────────────────────────────────────────────

    @Test
    fun `bulk plan excludes folders and labels the transfer kind`() {
        val rows = listOf(entry("a.txt", path = "a.txt"), entry("d", path = "d", isDir = true))
        val same = planBulkTransfer(rows, setOf("a.txt", "d"), "g:", "g:")
        assertEquals(1, same.files.size)
        assertEquals(1, same.excludedDirs.size)
        assertEquals(BulkTransferKind.SERVER_SIDE, same.kind)
        val cross = planBulkTransfer(rows, setOf("a.txt"), "g:", "sftp:")
        assertEquals(BulkTransferKind.VIA_DEVICE, cross.kind)
    }

    @Test
    fun `bulk delete message lists items and warns`() {
        val rows = (1..7).map { entry("f$it.txt", path = "f$it.txt") } +
            entry("d", path = "d", isDir = true)
        val msg = bulkDeleteMessage(rows)
        assertTrue(msg.contains("f1.txt"))
        assertTrue(msg.contains("…and 3 more"))
        assertTrue(msg.contains("cannot be undone"))
        assertTrue(msg.contains("folder"))
    }

    @Test
    fun `bulk result message reports partial failure honestly`() {
        val ok = BulkItemResult(entry("a.txt"), true)
        val bad = BulkItemResult(entry("gone.txt"), false, "not found")
        assertEquals("2 items done", bulkResultMessage(listOf(ok, ok), "done"))
        val partial = bulkResultMessage(listOf(ok, bad), "deleted")
        assertTrue(partial.contains("1 of 2"))
        assertTrue(partial.contains("gone.txt"))
    }

    // ── errors + names ───────────────────────────────────────────────────

    @Test
    fun `bulk errors classify unsupported instead of succeeding`() {
        assertEquals(
            BulkErrorKind.UNSUPPORTED,
            classifyBulkError("sftp: doesn't support server-side copy")
        )
        assertEquals(BulkErrorKind.NOT_FOUND, classifyBulkError("directory not found"))
        assertEquals(BulkErrorKind.AUTH, classifyBulkError("401 unauthorized"))
        assertEquals(BulkErrorKind.OTHER, classifyBulkError("weird backend hiccup"))
    }

    @Test
    fun `new-name validation rejects reserved and slashed names`() {
        assertEquals(null, validateNewName("photos 2024"))
        assertTrue(validateNewName("  ") != null)
        assertTrue(validateNewName("..") != null)
        assertTrue(validateNewName("a/b") != null)
    }
}
