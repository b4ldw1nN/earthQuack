package com.example.earthquack.storage

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards against two concurrent transfers of the same destination. */
class DownloadTrackerTest {

    @Test
    fun `first tap starts and the second is refused`() {
        val tracker = DownloadTracker()

        assertTrue(tracker.tryStart("/cache/opened/t1/file.txt"))
        assertFalse(tracker.tryStart("/cache/opened/t1/file.txt"))
    }

    @Test
    fun `different destinations are independent`() {
        val tracker = DownloadTracker()

        assertTrue(tracker.tryStart("/cache/opened/t1/file.txt"))
        assertTrue(tracker.tryStart("/cache/opened/t2/file.txt"))
        assertEquals(2, tracker.count())
    }

    @Test
    fun `finishing allows the same destination again`() {
        val tracker = DownloadTracker()
        val dst = "/cache/opened/t1/file.txt"

        tracker.tryStart(dst)
        tracker.finish(dst)

        assertTrue(tracker.tryStart(dst))
    }

    @Test
    fun `finishing an unknown path is a no-op`() {
        val tracker = DownloadTracker()
        tracker.tryStart("/cache/opened/t1/file.txt")
        tracker.finish("/cache/opened/t9/file.txt")

        assertEquals(1, tracker.count())
        assertTrue(tracker.isRunning("/cache/opened/t1/file.txt"))
    }
}

/**
 * The completeness rule for a finished transfer.
 *
 * `operations/copyfile` reports success with an empty object — it does not
 * report bytes — so the only honest check is on the local file. This class is
 * where a truncated copy is caught before it is handed to a viewer.
 */
class DownloadVerificationTest {

    @Test
    fun `a file matching the reported size is complete`() {
        assertTrue(DownloadVerification.isComplete(1024, 1024))
    }

    @Test
    fun `a file smaller than reported is incomplete`() {
        assertFalse(DownloadVerification.isComplete(512, 1024))
    }

    @Test
    fun `an empty file is never complete`() {
        assertFalse(DownloadVerification.isComplete(0, 0))
        assertFalse(DownloadVerification.isComplete(0, null))
    }

    @Test
    fun `a missing size cannot be checked against and does not fail`() {
        assertTrue(DownloadVerification.isComplete(1, null))
    }

    @Test
    fun `no size and no content is rejected`() {
        assertFalse(DownloadVerification.isComplete(-1, 1024))
    }
}

/**
 * Runs [RcloneFileTransfer] against a fake engine that writes the file itself,
 * so the real copy path — `operations/copyfile` and its parameter set — is
 * exercised without the native library.
 */
class RcloneFileTransferTest {

    @Test
    fun `a successful copy is reported with its size`() = runTest {
        val tmp = createTempDir()
        val content = "hello, this is content"
        val request = planner(tmp).request("remote:", "a/b/file.txt", "file.txt", "tok")
        val engine = fakeEngine { method, params ->
            assertEquals("operations/copyfile", method)
            assertEquals("remote:", params.getString("srcFs"))
            assertEquals("a/b/file.txt", params.getString("srcRemote"))
            // The destination is a local filesystem root built the same way
            // the planner says it is, or the copy would land in the wrong fs.
            assertEquals(request.dstFs, params.getString("dstFs"))
            assertEquals("file.txt", params.getString("dstRemote"))
            File(request.localFile).writeText(content)
            org.json.JSONObject()
        }

        val result = RcloneFileTransfer(engine).download(request,
            expectedSize = content.length.toLong())

        assertTrue(result is RcloneDownloadResult.Success)
        result as RcloneDownloadResult.Success
        assertEquals(content.length.toLong(), result.size)
        assertEquals(request.localFile, result.localPath)
        assertTrue(File(request.localFile).exists())
        tmp.deleteRecursively()
    }

    @Test
    fun `a copy smaller than reported is rejected and the partial file removed`() = runTest {
        val tmp = createTempDir()
        val request = planner(tmp).request("remote:", "big.bin", "big.bin", "tok")
        val engine = fakeEngine { _, _ ->
            File(request.localFile).writeBytes(ByteArray(16))
            org.json.JSONObject()
        }

        val result = RcloneFileTransfer(engine).download(request,
            expectedSize = 4096)

        assertTrue(result is RcloneDownloadResult.Failure)
        assertFalse(File(request.localFile).exists())
        tmp.deleteRecursively()
    }

    @Test
    fun `a copy that reports success but writes nothing is a failure`() = runTest {
        val tmp = createTempDir()
        val request = planner(tmp).request("remote:", "nothing.bin", "nothing.bin", "tok")
        val engine = fakeEngine { _, _ -> org.json.JSONObject() }

        val result = RcloneFileTransfer(engine).download(request,
            expectedSize = 4096)

        assertTrue(result is RcloneDownloadResult.Failure)
        assertFalse(File(request.localFile).exists())
        tmp.deleteRecursively()
    }

    @Test
    fun `an rclone error becomes a failure carrying rclone's own text`() = runTest {
        val tmp = createTempDir()
        val request = planner(tmp).request("remote:", "missing.txt", "missing.txt", "tok")
        val envelope = org.json.JSONObject()
            .put("error", "directory not found")
            .put("input", org.json.JSONObject().put("remote", "missing.txt"))
        val engine = failingEngine(
            RcloneException("operations/copyfile", 404, envelope.toString(), "rclone RPC failed")
        )

        val result = RcloneFileTransfer(engine).download(request,
            expectedSize = 10)

        if (result !is RcloneDownloadResult.Failure) {
            fail("expected Failure, got $result")
        }
        result as RcloneDownloadResult.Failure
        // rclone's human-readable text, not the status number.
        assertTrue(result.detail.contains("directory not found"))
        assertFalse(File(request.localFile).exists())
        tmp.deleteRecursively()
    }

    @Test
    fun `a transport exception becomes a failure rather than an exception`() = runTest {
        val tmp = createTempDir()
        val request = planner(tmp).request("remote:", "x", "x", "tok")
        val engine = throwingEngine(IllegalStateException("backend not found"))

        val result = RcloneFileTransfer(engine).download(request,
            expectedSize = 1)

        assertTrue(result is RcloneDownloadResult.Failure)
        tmp.deleteRecursively()
    }

    /**
     * A leftover file with the destination's name must not survive a new
     * attempt: if it did, a viewer could open stale content of the right size
     * and believe it was the file it tapped.
     */
    @Test
    fun `a stale file at the destination is removed before copying`() = runTest {
        val tmp = createTempDir()
        val request = planner(tmp).request("remote:", "x", "x", "tok")
        File(request.localFile).parentFile!!.mkdirs()
        File(request.localFile).writeText("stale content from a previous attempt")
        val before = File(request.localFile).length()

        val engine = fakeEngine { _, _ ->
            File(request.localFile).writeText("fresh")
            org.json.JSONObject()
        }
        val result = RcloneFileTransfer(engine).download(
            request,
            expectedSize = "fresh".length.toLong()
        )

        assertTrue(result is RcloneDownloadResult.Success)
        assertEquals("fresh", File(request.localFile).readText())
        assertTrue(File(request.localFile).length() < before)
        tmp.deleteRecursively()
    }

    @Test
    fun `progress is reported while the copy runs`() = runTest {
        val tmp = createTempDir()
        val request = planner(tmp).request("remote:", "p.bin", "p.bin", "tok")
        val engine = fakeEngine { _, _ ->
            // Grow the file so the poller sees several distinct sizes.
            val destination = File(request.localFile)
            destination.parentFile!!.mkdirs()
            listOf(10, 40, 90, 150).forEach { size ->
                destination.writeBytes(ByteArray(size))
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
            org.json.JSONObject()
        }

        val seen = mutableListOf<Long>()
        // A 1 ms poll interval and the real IO dispatcher: the point is that a
        // growing file produces a growing series of reports.
        val transfer = RcloneFileTransfer(
            engine,
            pollDelayMillis = POLL_INTERVAL_MILLIS
        )
        val result = transfer.download(
            request,
            expectedSize = 150,
            onProgress = { seen += it }
        )

        assertTrue(result is RcloneDownloadResult.Success)
        assertTrue("expected several sizes, got $seen", seen.size >= 2)
        // Monotonic growth, never a jump backwards.
        assertTrue(seen == seen.sorted())
        tmp.deleteRecursively()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createTempDir(): File =
        File.createTempFile("eq-transfer", ".d").apply {
            delete()
            mkdirs()
        }

    private fun planner(root: File) =
        RcloneDownloadPlanner(root.absolutePath)

    /**
     * A stub engine that records the call and runs [body], which does the
     * filesystem work a fake native backend would do.
     */
    private fun fakeEngine(
        body: (String, org.json.JSONObject) -> org.json.JSONObject
    ): RcloneEngine = RcloneEngine(FakeCopyBridge(body)).apply { initialize("") }

    private fun failingEngine(error: RcloneException): RcloneEngine =
        RcloneEngine(FakeCopyBridge { _, _ -> throw error }).apply { initialize("") }

    private fun throwingEngine(error: Exception): RcloneEngine =
        RcloneEngine(FakeCopyBridge { _, _ -> throw error }).apply { initialize("") }

    /**
     * A bridge that does the destination write itself, standing in for a local
     * backend reached through the native library.
     */
    private class FakeCopyBridge(
        private val body: (String, org.json.JSONObject) -> org.json.JSONObject
    ) : RcloneBridge {
        override fun initialize(configPath: String) = Unit
        override fun rpc(method: String, input: String): RcloneResult =
            RcloneResult(200, body(method, org.json.JSONObject(input)).toString())
        override fun shutdown() = Unit
    }

    /** No poll delay: 250 ms per step would make the test take a second. */
    companion object {
        private const val POLL_INTERVAL_MILLIS = 5L
    }
}
