package com.example.earthquack.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Destination-naming rules for a downloaded file.
 *
 * These are the decisions the user actually notices: the file keeps its name
 * and extension, two taps do not collide, and a hostile or merely odd remote
 * filename cannot escape the cache directory it was put in.
 */
class RcloneDownloadPlannerTest {

    private val cacheRoot = "/data/user/0/com.example.earthquack/cache"

    @Test
    fun `original name and extension are preserved`() {
        val request = planner.request("gdrive:", "Reports/final.PDF", "final.PDF", "t1")

        assertEquals("gdrive:", request.srcFs)
        assertEquals("Reports/final.PDF", request.srcRemote)
        assertTrue(request.localFile.endsWith("/final.PDF"))
        assertEquals("final.PDF", request.dstRemote)
    }

    @Test
    fun `destination is a local filesystem rooted at a unique token directory`() {
        val request = planner.request("remote:", "a/b/c.bin", "c.bin", "token-123")

        assertEquals(":local:$cacheRoot/opened/token-123", request.dstFs)
        assertEquals("$cacheRoot/opened/token-123/c.bin", request.localFile)
    }

    /**
     * The point of a token: the same remote file downloaded twice gets two
     * independent destinations, so the first copy that a viewer still holds
     * open is never deleted underneath it.
     */
    @Test
    fun `two downloads of the same remote file do not collide`() {
        val first = planner.request("remote:", "x.txt", "x.txt", "token-a")
        val second = planner.request("remote:", "x.txt", "x.txt", "token-b")

        assertFalse(first.localFile == second.localFile)
        assertTrue(first.localFile.startsWith("$cacheRoot/opened/token-a/"))
        assertTrue(second.localFile.startsWith("$cacheRoot/opened/token-b/"))
    }

    @Test
    fun `nested remote path is passed through unchanged`() {
        val request = planner.request("remote:", "one/two/three/file.txt", "file.txt", "t")

        // rclone's own escaping, so it must not be re-joined or split.
        assertEquals("one/two/three/file.txt", request.srcRemote)
    }

    @Test
    fun `unicode and spaces survive`() {
        val name = "Ünïcodé wörkß — notes.txt"
        val request = planner.request("remote:", name, name, "t")

        assertEquals(name, request.dstRemote)
        assertTrue(request.localFile.endsWith("/$name"))
    }

    @Test
    fun `params carry exactly the four copyfile keys`() {
        val request = planner.request("r:", "a.txt", "a.txt", "t")

        assertEquals(
            setOf("srcFs", "srcRemote", "dstFs", "dstRemote"),
            request.params.keys
        )
        assertEquals(":local:$cacheRoot/opened/t", request.params["dstFs"])
    }

    @Test
    fun `path separators in a name cannot escape the destination directory`() {
        assertEquals("c.bin", RcloneDownloadPlanner.safeFileName("a/b/c.bin"))
        assertEquals("passwd", RcloneDownloadPlanner.safeFileName("../../etc/passwd"))
        assertEquals("b.txt", RcloneDownloadPlanner.safeFileName("a\\b.txt"))
    }

    @Test
    fun `dot names are not traversal`() {
        assertTrue("download" == RcloneDownloadPlanner.safeFileName("..") ||
            RcloneDownloadPlanner.safeFileName("..") == RcloneDownloadPlanner.FALLBACK_NAME)
    }

    @Test
    fun `blank or absent names fall back rather than emptying the path`() {
        assertEquals(
            RcloneDownloadPlanner.FALLBACK_NAME,
            RcloneDownloadPlanner.safeFileName("   ")
        )
        assertEquals(
            RcloneDownloadPlanner.FALLBACK_NAME,
            RcloneDownloadPlanner.safeFileName("//")
        )
        // A name of only control characters is invisible, so it is dropped too.
        assertEquals(
            RcloneDownloadPlanner.FALLBACK_NAME,
            RcloneDownloadPlanner.safeFileName("\u0001\u0002")
        )
    }

    @Test
    fun `a normal name is left alone`() {
        assertEquals("report.txt", RcloneDownloadPlanner.safeFileName("report.txt"))
    }

    private val planner = RcloneDownloadPlanner(cacheRoot)
}
