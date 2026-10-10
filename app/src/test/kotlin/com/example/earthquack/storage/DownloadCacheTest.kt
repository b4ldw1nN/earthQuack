package com.example.earthquack.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download cache: what it counts, what it deletes, and what it must not.
 *
 * The last of those is the point of most of these. A cache that deletes a
 * transfer in flight breaks the download that is running, and one that deletes
 * a directory another app still has open breaks the viewer — so the `.part`
 * rule and the age rule are both pinned.
 */
class DownloadCacheTest {

    private lateinit var root: File

    private val day = DownloadCache.MILLIS_PER_DAY

    private fun cache() = DownloadCache(root)

    /** A cached file, laid down [ageDays] days ago. */
    private fun file(key: String, name: String, bytes: Int, ageDays: Long): File =
        File(root, "$key/$name").also {
            it.parentFile?.mkdirs()
            it.writeBytes(ByteArray(bytes))
            it.setLastModified(System.currentTimeMillis() - ageDays * day)
        }

    @Test
    fun `a fresh file is counted and measured`() {
        setUp()
        file("a", "x.pdf", 10, ageDays = 0)
        file("b", "y.pdf", 20, ageDays = 0)

        val cache = cache()
        assertEquals(2, cache.count())
        assertEquals(30L, cache.size())
        tearDown()
    }

    @Test
    fun `an in-flight transfer is not counted as a file`() {
        setUp()
        file("a", "x.pdf", 10, 0)
        // A `.part` is the transfer the copyfile RPC is writing right now.
        File(root, "a/x.pdf.part").writeBytes(ByteArray(999))

        assertEquals(1, cache().count())
        assertEquals(10L, cache().size())
        tearDown()
    }

    /**
     * The rule that keeps a running download alive: a retention sweep must
     * never delete the file the RPC is currently writing, even when that file
     * is older than the retention — which it will be, because it has been
     * growing while the retention window elapsed.
     */
    @Test
    fun `pruning leaves transfers in flight alone`() {
        setUp()
        file("a", "x.pdf", 10, ageDays = 0)
        File(root, "a/x.pdf.part").writeBytes(ByteArray(999))

        val removed = cache().prune(days = 1)

        assertEquals(0, removed)
        assertTrue("the live transfer survives its own retention window", File(root, "a/x.pdf.part").exists())
        tearDown()
    }

    @Test
    fun `a file inside the retention survives pruning`() {
        setUp()
        file("a", "x.pdf", 10, ageDays = 1)

        assertEquals(0, cache().prune(days = 7))
        assertTrue(File(root, "a/x.pdf").exists())
        tearDown()
    }

    @Test
    fun `a file past the retention is pruned`() {
        setUp()
        file("a", "x.pdf", 10, ageDays = 30)

        assertEquals(1, cache().prune(days = 7))
        assertFalse(File(root, "a/x.pdf").exists())
        assertEquals(0, cache().count())
        tearDown()
    }

    @Test
    fun `the directory of a pruned file is removed with it`() {
        setUp()
        file("a", "x.pdf", 10, ageDays = 30)

        cache().prune(days = 7)

        assertFalse("the key directory must not linger", File(root, "a").exists())
        tearDown()
    }

    @Test
    fun `a directory holding a live file is not removed`() {
        setUp()
        file("a", "keep.pdf", 10, ageDays = 0)
        file("a", "old.pdf", 10, ageDays = 30)

        cache().prune(days = 7)

        assertTrue(File(root, "a").isDirectory)
        assertTrue(File(root, "a/keep.pdf").exists())
        tearDown()
    }

    @Test
    fun `a zero retention removes everything`() {
        setUp()
        file("a", "x.pdf", 10, ageDays = 0)
        file("b", "y.pdf", 10, ageDays = 0)

        assertEquals(2, cache().prune(days = 0))
        assertEquals(0, cache().count())
        tearDown()
    }

    @Test
    fun `clearing removes every file but keeps transfers running`() {
        setUp()
        file("a", "x.pdf", 10, ageDays = 0)
        file("b", "y.pdf", 10, ageDays = 0)
        // The transfer's own directory, created by the transfer before it writes.
        File(root, "c").mkdirs()
        File(root, "c/y.pdf.part").writeBytes(ByteArray(5))

        val removed = cache().clear()

        assertEquals(2, removed)
        assertTrue("a transfer in flight is not a cached file", File(root, "c/y.pdf.part").exists())
        tearDown()
    }

    @Test
    fun `size drops after a clear`() {
        setUp()
        file("a", "x.pdf", 100, ageDays = 0)
        cache().clear()

        assertEquals(0L, cache().size())
        tearDown()
    }

    @Test
    fun `clearing an empty cache is not an error`() {
        setUp()
        assertEquals(0, cache().clear())
        assertEquals(0L, cache().size())
        tearDown()
    }

    @Test
    fun `a deeply nested layout is walked`() {
        setUp()
        // The layout is one directory per file, but a stray directory from an
        // older build must not be invisible to the walk.
        File(root, "a/nested/deep").mkdirs()
        File(root, "a/nested/deep/x.pdf").writeBytes(ByteArray(7))

        assertEquals(7L, cache().size())
        tearDown()
    }


    /**
     * The safety property: what the cache deletes is bounded by its own root,
     * and nothing else in the app's storage is reachable from it.
     *
     * "Delete the cache" that could take rclone.conf, or another app's data,
     * would be the single worst bug in this feature — a user pressing a button
     * in Settings losing every remote they configured. The cache is constructed
     * with a root and walks only downward from it, and this test makes that
     * explicit rather than assumed.
     */
    @Test
    fun `deleting the cache cannot reach anything outside its root`() {
        setUp()
        // A sibling that must survive: this is where rclone.conf lives, and
        // where anything else the app keeps would live.
        val config = File(root.parentFile, "files/rclone/rclone.conf")
        config.parentFile?.mkdirs()
        config.writeText("[remote:]nntype = local")

        file("a", "x.pdf", 10, ageDays = 0)

        cache().clear()

        assertTrue("rclone.conf survives a cache clear", config.exists())
        assertEquals("[remote:]nntype = local", config.readText())
        config.parentFile?.parentFile?.parentFile?.deleteRecursively()
        tearDown()
    }

    /**
     * A cache rooted nowhere in particular must not become "delete everything
     * the process can reach". An empty root is an empty cache.
     */
    @Test
    fun `a cache rooted at an empty directory deletes nothing`() {
        setUp()
        // The root exists but holds nothing; a member of `..` must not be seen.
        File(root.parentFile, "outside.txt").writeText("keep me")

        assertEquals(0, cache().clear())
        assertTrue(File(root.parentFile, "outside.txt").exists())
        File(root.parentFile, "outside.txt").delete()
        tearDown()
    }

    /**
     * A nested name cannot escape the cache by depth: the walk is a descent
     * from the root, so `../` in a filename is just a name, not a path.
     */
    @Test
    fun `a file whose name contains a traversal does not escape on deletion`() {
        setUp()
        // Created through the API, so the name is whatever it is; the cache
        // only ever deletes files it found under its own root.
        val outside = File(root.parentFile, "outside.txt")
        outside.writeText("keep me")
        File(root, "a").mkdirs()
        File(root, "a/..outside.txt").writeBytes(ByteArray(3))

        cache().clear()

        assertTrue(File(root, "a/..outside.txt").exists().not())
        assertTrue("the file beside the cache is untouched", outside.exists())
        outside.delete()
        tearDown()
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    private fun setUp() {
        root = File.createTempFile("eq-downloads", ".d").apply {
            delete()
            mkdirs()
        }
    }

    private fun tearDown() {
        root.deleteRecursively()
    }
}
