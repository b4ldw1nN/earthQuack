package com.example.earthquack.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directory listing cache.
 *
 * The behaviour worth pinning down is what a cached listing is and is not: it
 * must come back byte-identical to the reply that was stored, it must expire on
 * the day count the user chose, and two directories must never share an entry.
 * A cache that silently answers with the wrong directory's contents is worse
 * than no cache, so that gets its own test.
 */
class DirectoryListingCacheTest {

    private val now = 1_700_000_000_000L

    private lateinit var root: File

    private fun cache(instant: Long = now): DirectoryListingCache =
        DirectoryListingCache(root, { instant })

    private fun setUp() {
        root = createTempDir()
    }

    private fun tearDown() {
        root.deleteRecursively()
    }

    private fun listing(vararg names: String): String =
        """{"list":[${names.joinToString(",") { "{\"Name\":\"$it\",\"IsDir\":false}" }}]}"""

    // ── basics ────────────────────────────────────────────────────────────────

    @Test
    fun `a stored listing comes back unchanged`() {
        setUp()
        val reply = listing("a.txt", "b.txt")
        cache().store("gdrive:", "docs", reply)

        val loaded = cache().load("gdrive:", "docs", retentionDays = 7)

        assertEquals(reply, loaded?.reply)
        assertEquals(now, loaded?.cachedAt)
        tearDown()
    }

    @Test
    fun `an unknown directory has no entry`() {
        setUp()
        assertNull(cache().load("gdrive:", "nope", 7))
        tearDown()
    }

    @Test
    fun `retention off means no entry is ever served`() {
        setUp()
        cache().store("gdrive:", "docs", listing("a.txt"))

        assertNull(cache().load("gdrive:", "docs", retentionDays = 0))
        tearDown()
    }

    // ── keys ──────────────────────────────────────────────────────────────────

    @Test
    fun `two remotes with the same path do not share an entry`() {
        setUp()
        cache().store("remoteA:", "docs", listing("from A"))

        val fromB = cache().load("remoteB:", "docs", 7)

        assertNull("remoteB must not see remoteA's listing", fromB)
        tearDown()
    }

    @Test
    fun `two directories in one remote do not share an entry`() {
        setUp()
        cache().store("gdrive:", "docs", listing("in docs"))
        cache().store("gdrive:", "pics", listing("in pics"))

        assertEquals("in docs", cache().load("gdrive:", "docs", 7)?.reply
            ?.substringAfter("\"Name\":\"")?.substringBefore("\""))
        assertEquals("in pics", cache().load("gdrive:", "pics", 7)?.reply
            ?.substringAfter("\"Name\":\"")?.substringBefore("\""))
        tearDown()
    }

    /**
     * The path is not part of the filename, so `a/b` and `a_b` cannot collide
     * even though a naive separator replacement would make them identical.
     */
    @Test
    fun `paths that differ only by a separator stay distinct`() {
        setUp()
        cache().store("r:", "a/b", listing("slashed"))
        cache().store("r:", "a_b", listing("underscored"))

        assertTrue(cache().load("r:", "a/b", 7)?.reply?.contains("slashed") == true)
        assertTrue(cache().load("r:", "a_b", 7)?.reply?.contains("underscored") == true)
        tearDown()
    }

    @Test
    fun `unicode and spaces in a path are stored and found`() {
        setUp()
        cache().store("gdrive:", "Ünïcodé/dossier", listing("notes"))

        assertNotNull(cache().load("gdrive:", "Ünïcodé/dossier", 7))
        tearDown()
    }

    // ── expiry ────────────────────────────────────────────────────────────────

    @Test
    fun `an entry younger than the retention is served`() {
        setUp()
        cache().store("r:", "d", listing("a"))
        val threeDaysLater = now + 3 * DAY

        assertNotNull(cache(threeDaysLater).load("r:", "d", 7))
        tearDown()
    }

    @Test
    fun `an entry at the retention limit is still served`() {
        setUp()
        cache().store("r:", "d", listing("a"))
        val exactlySevenDays = now + 7 * DAY

        // A 7-day-old entry has lived its whole retention: 7 days in a
        // 7-day retention counts as expired, because "keep for 7 days" means
        // the entry has to be *younger* than 7 days.
        assertNull(cache(exactlySevenDays).load("r:", "d", 7))
        tearDown()
    }

    @Test
    fun `an entry older than the retention is dropped when read`() {
        setUp()
        cache().store("r:", "d", listing("a"))
        cache().store("r:", "e", listing("b"))
        val eightDaysLater = now + 8 * DAY

        assertNull(cache(eightDaysLater).load("r:", "d", 7))
        assertEquals(1, cache(eightDaysLater).count())
        tearDown()
    }

    @Test
    fun `an expired entry left on disk is pruned`() {
        setUp()
        cache().store("r:", "d", listing("a"))

        val removed = cache(now + 30 * DAY).prune(retentionDays = 7)

        assertEquals(1, removed)
        assertEquals(0, cache(now + 30 * DAY).count())
        tearDown()
    }

    @Test
    fun `pruning keeps entries that are still inside the retention`() {
        setUp()
        cache().store("r:", "d", listing("a"))
        cache().store("r:", "e", listing("b"))

        val removed = cache(now + 1 * DAY).prune(retentionDays = 7)

        assertEquals(0, removed)
        assertEquals(2, cache(now + 1 * DAY).count())
        tearDown()
    }

    @Test
    fun `pruning does not touch temporary files it did not create`() {
        setUp()
        cache().store("r:", "d", listing("a"))
        File(root, "unrelated.tmp").writeText("mine")

        cache(now + 30 * DAY).prune(7)

        assertTrue("a .tmp is not this cache's to delete", File(root, "unrelated.tmp").exists())
        tearDown()
    }

    @Test
    fun `a later store replaces the earlier entry for the same directory`() {
        setUp()
        cache().store("r:", "d", listing("first"))
        cache().store("r:", "d", listing("second"))

        assertEquals(1, cache().count())
        assertTrue(cache().load("r:", "d", 7)?.reply?.contains("second") == true)
        tearDown()
    }

    @Test
    fun `invalidate removes an entry`() {
        setUp()
        cache().store("r:", "d", listing("a"))

        cache().invalidate("r:", "d")

        assertNull(cache().load("r:", "d", 7))
        tearDown()
    }

    @Test
    fun `a corrupt entry is discarded rather than served`() {
        setUp()
        cache().store("r:", "d", listing("a"))
        // Truncate the file mid-JSON, the shape a killed process leaves.
        val files = root.listFiles()!!
        files.first { it.name.endsWith(".json") }.writeText("{\"v\":1,\"rep")

        assertNull(cache().load("r:", "d", 7))
        assertEquals("the bad entry is deleted so it cannot fail every load", 0, cache().count())
        tearDown()
    }

    @Test
    fun `an empty file is treated as no entry`() {
        setUp()
        cache().store("r:", "d", listing("a"))
        root.listFiles()!!.first { it.name.endsWith(".json") }.writeText("")

        assertNull(cache().load("r:", "d", 7))
        tearDown()
    }

    // ── age accounting ────────────────────────────────────────────────────────

    @Test
    fun `age is whole days rounded down`() {
        val entry = CachedListing("{}", now, "k")

        assertEquals(0L, entry.ageDays(now))
        assertEquals(0L, entry.ageDays(now + 12 * 60 * 60 * 1000L))
        assertEquals(1L, entry.ageDays(now + DAY))
        assertEquals(2L, entry.ageDays(now + 2 * DAY + 1000L))
    }

    @Test
    fun `a clock in the past does not produce a negative age`() {
        val entry = CachedListing("{}", now, "k")

        assertEquals(0L, entry.ageDays(now - 5 * DAY))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
