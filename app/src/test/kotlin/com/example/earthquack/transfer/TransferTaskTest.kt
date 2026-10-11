package com.example.earthquack.transfer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transfer model: what is stored, what is recovered, and what a
 * duplicate submission looks like.
 *
 * These are the rules that decide whether history survives a restart and
 * whether a rapid double-tap becomes two transfers, so they are worth testing
 * without a device.
 */
class TransferTaskTest {

    private fun rcloneTask(
        id: String = "t1",
        type: TransferType = TransferType.DOWNLOAD,
        status: TransferStatus = TransferStatus.QUEUED,
        total: Long = 100L,
        done: Long = 0L
    ) = TransferTask(
        id = id,
        type = type,
        backend = TransferBackend.RcloneDownload(
            srcFs = "drive:",
            srcRemote = "docs/note.txt",
            dstFs = ":local:/cache",
            dstRemote = "note.txt",
            localFile = "/cache/opened/note.txt"
        ),
        displayName = "note.txt",
        sourceLabel = "drive:",
        destination = "/cache/opened/note.txt",
        totalBytes = total,
        bytesTransferred = done,
        status = status,
        createdAt = 1000L,
        attempts = 1
    )

    @Test
    fun `json round-trips every field`() {
        val task = rcloneTask(status = TransferStatus.FAILED, done = 40L)
            .copy(error = "network dropped", finishedAt = 5000L)
        val restored = TransferTask.fromJson(task.toJson())
        assertEquals(task, restored)
    }

    @Test
    fun `a stored running task loads as interrupted`() {
        val restored = TransferTask.fromJson(rcloneTask(status = TransferStatus.RUNNING, done = 60L).toJson())
        assertNotNull(restored)
        assertEquals(TransferStatus.INTERRUPTED, restored!!.status)
        // The partial byte count survives: it is real work that happened.
        assertEquals(60L, restored.bytesTransferred)
    }

    @Test
    fun `fraction is null when the size is unknown`() {
        assertNull(rcloneTask(total = -1L).fraction)
    }

    @Test
    fun `fraction is derived from bytes, never invented`() {
        assertEquals(0.25f, rcloneTask(total = 100L, done = 25L).fraction!!, 1e-4f)
        // Clamped so an over-count cannot draw a full bar.
        assertEquals(1f, rcloneTask(total = 100L, done = 500L).fraction!!, 1e-4f)
    }

    @Test
    fun `same target and destination dedupes, different ones do not`() {
        val a = rcloneTask(id = "a")
        val b = rcloneTask(id = "b")
        val other = rcloneTask(id = "c").copy(
            backend = TransferBackend.RcloneDownload(
                srcFs = "drive:", srcRemote = "docs/other.txt",
                dstFs = ":local:/cache", dstRemote = "other.txt", localFile = "/cache/opened/other.txt"
            )
        )
        assertEquals(a.dedupeKey, b.dedupeKey)
        assertFalse(a.dedupeKey == other.dedupeKey)
    }

    @Test
    fun `upload and download of the same pair are different transfers`() {
        val down = rcloneTask(id = "down")
        val up = rcloneTask(id = "up", type = TransferType.UPLOAD)
        assertFalse(down.dedupeKey == up.dedupeKey)
    }

    @Test
    fun `an sftp task round-trips`() {
        val task = TransferTask(
            id = "s1",
            type = TransferType.UPLOAD,
            backend = TransferBackend.Sftp(
                profileId = "p1",
                localFile = "/data/local/photo.jpg",
                remotePath = "/home/zoro/photo.jpg"
            ),
            displayName = "photo.jpg",
            sourceLabel = "archii",
            destination = "/home/zoro/photo.jpg",
            totalBytes = 2048L,
            status = TransferStatus.COMPLETED
        )
        val restored = TransferTask.fromJson(task.toJson())
        assertEquals(task, restored)
        assertTrue(restored!!.status.isTerminal)
    }

    @Test
    fun `a task with an unknown backend kind is dropped, not crashed on`() {
        val json = rcloneTask().toJson()
        val backend = json.getJSONObject("backend").put("kind", "carrier-pigeon")
        val restored = TransferTask.fromJson(JSONObject(json.toString()).put("backend", backend))
        // A transfer no executor can run must not resurrect as a broken row.
        assertNull(restored)
    }

    @Test
    fun `a task with a blank id is dropped`() {
        val json = rcloneTask(id = "").toJson()
        assertNull(TransferTask.fromJson(json))
    }

    @Test
    fun `active versus terminal predicates`() {
        assertTrue(TransferStatus.QUEUED.isActive)
        assertTrue(TransferStatus.RUNNING.isActive)
        assertTrue(TransferStatus.COMPLETED.isTerminal)
        assertTrue(TransferStatus.FAILED.isTerminal)
        assertTrue(TransferStatus.CANCELLED.isTerminal)
        assertFalse(TransferStatus.INTERRUPTED.isActive)
        assertFalse(TransferStatus.INTERRUPTED.isTerminal)
    }

    @Test
    fun `loadRecovered keeps bytes and marks interrupted`() {
        val task = rcloneTask(status = TransferStatus.RUNNING, done = 30L).loadRecovered()
        assertEquals(TransferStatus.INTERRUPTED, task.status)
        assertEquals(30L, task.bytesTransferred)
    }
}
