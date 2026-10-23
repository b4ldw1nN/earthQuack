package io.earthquack.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store behaviour that decides what history looks like: live transfers are
 * never evicted for history, and finished history is capped and ordered.
 */
class InMemoryTransferStoreTest {

    private fun task(id: String, status: TransferStatus, finishedAt: Long = 0L) = TransferTask(
        id = id,
        type = TransferType.DOWNLOAD,
        backend = TransferBackend.Sftp("p1", "/local/$id", "/remote/$id"),
        displayName = id,
        sourceLabel = "profile",
        destination = "/remote/$id",
        status = status,
        createdAt = 0L,
        finishedAt = finishedAt
    )

    @Test
    fun `history is capped, newest finished first`() {
        val store = InMemoryTransferStore(maxHistory = 3)
        store.save((1..5).map { task("done-$it", TransferStatus.COMPLETED, finishedAt = it.toLong()) })
        val loaded = store.load()
        assertEquals(3, loaded.size)
        // Newest three survive; the oldest two are gone.
        assertEquals(setOf("done-3", "done-4", "done-5"), loaded.map { it.id }.toSet())
    }

    @Test
    fun `an active transfer is never trimmed away for history`() {
        val store = InMemoryTransferStore(maxHistory = 1)
        store.save(
            listOf(
                task("queued", TransferStatus.QUEUED),
                task("done-1", TransferStatus.COMPLETED, finishedAt = 1L),
                task("done-2", TransferStatus.COMPLETED, finishedAt = 2L)
            )
        )
        val loaded = store.load()
        assertTrue(loaded.any { it.id == "queued" })
        assertEquals(2, loaded.size)
    }

    @Test
    fun `load returns exactly what was saved when under the cap`() {
        val store = InMemoryTransferStore(maxHistory = 10)
        store.save(listOf(task("a", TransferStatus.QUEUED), task("b", TransferStatus.FAILED, 1L)))
        assertEquals(setOf("a", "b"), store.load().map { it.id }.toSet())
    }
}
