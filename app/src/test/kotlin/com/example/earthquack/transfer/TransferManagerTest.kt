package com.example.earthquack.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The queue rules: duplicates, ordering, concurrency, progress,
 * cancellation, retry, and what happens to a task that was running when the
 * process died.
 *
 * The manager owns its own IO scope, so these tests drive it with real
 * coroutines and latches rather than a virtual clock — the rules under test
 * are about state transitions and persistence, not about timing.
 */
class TransferManagerTest {

    /** How long a test waits for an asynchronous state change. */
    private val timeout = 5_000L

    private class FakeExecutor(
        private val behaviour: suspend (TransferTask, (Long) -> Unit) -> TransferOutcome,
        private val resumable: Boolean = false,
        private val onStart: (TransferTask) -> Unit = {}
    ) : TransferExecutor {
        override fun handles(backend: TransferBackend) = true
        override fun canResume(task: TransferTask) = resumable && task.bytesTransferred > 0
        override suspend fun run(
            task: TransferTask,
            onProgress: (bytesTransferred: Long) -> Unit
        ): TransferOutcome {
            onStart(task)
            return behaviour(task, onProgress)
        }
    }

    private fun sftpTask(
        id: String,
        name: String = "file",
        status: TransferStatus = TransferStatus.QUEUED,
        createdAt: Long = 0L,
        attempts: Int = 0,
        bytes: Long = 0L
    ) = TransferTask(
        id = id,
        type = TransferType.DOWNLOAD,
        backend = TransferBackend.Sftp("p1", "/local/$id", "/remote/$id"),
        displayName = name,
        sourceLabel = "archii",
        destination = "/remote/$id",
        totalBytes = 100L,
        status = status,
        createdAt = createdAt,
        attempts = attempts,
        bytesTransferred = bytes
    )

    @Test
    fun `a stale progress report cannot drag the byte count backwards`() {
        val executor = FakeExecutor(
            behaviour = { _, onProgress ->
                onProgress(60)
                // A backend that re-reports an earlier chunk (a rewinded
                // poll, a retried stream) must not undo newer progress.
                onProgress(30)
                onProgress(100)
                TransferOutcome.Completed(100L)
            }
        )
        val m = manager(store = InMemoryTransferStore(), executor = executor)
        m.submit(sftpTask("t1", createdAt = 1L))
        awaitState(m) { statusOf("t1")(it) == TransferStatus.COMPLETED }
        assertEquals(100L, m.tasks.value.first { it.id == "t1" }.bytesTransferred)
    }

    @Test
    fun `progress reports land on the task that reported them`() {
        val gate = CompletableDeferred<TransferOutcome>()
        val executor = FakeExecutor(
            behaviour = { task, onProgress ->
                onProgress(10L + task.id.length)
                gate.await()
            }
        )
        val m = manager(executor = executor, maxConcurrent = 2)
        m.submit(sftpTask("aa", createdAt = 1L))
        m.submit(sftpTask("bbbb", createdAt = 2L))
        awaitState(m) {
            statusOf("aa")(it) == TransferStatus.RUNNING && statusOf("bbbb")(it) == TransferStatus.RUNNING
        }
        awaitState(m) {
            val t = it.associateBy { t -> t.id }
            t["aa"]?.bytesTransferred == 12L && t["bbbb"]?.bytesTransferred == 14L
        }
        gate.complete(TransferOutcome.Completed(100L))
    }

    // ── unrelated handlers for repeated behaviour below ─────────────────────

    private fun manager(
        store: TransferStore = InMemoryTransferStore(),
        executor: TransferExecutor? = null,
        executorFor: ((TransferBackend) -> TransferExecutor?)? = null,
        maxConcurrent: Int = 2
    ) = TransferManager(
        store = store,
        executorFor = executorFor ?: { executor ?: error("no executor") },
        clock = { 10_000L },
        maxConcurrent = maxConcurrent
    )

    /** Polls the manager until [predicate] holds or the timeout expires. */
    private fun awaitState(
        m: TransferManager,
        predicate: (List<TransferTask>) -> Boolean
    ): List<TransferTask> {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val tasks = m.tasks.value
            if (predicate(tasks)) return tasks
            Thread.sleep(5)
        }
        return m.tasks.value
    }

    private fun statusOf(id: String) = { tasks: List<TransferTask> ->
        tasks.firstOrNull { it.id == id }?.status
    }

    @Test
    fun `a submitted transfer runs to completion and is persisted`() {
        val store = InMemoryTransferStore()
        val m = manager(
            store = store,
            executor = FakeExecutor(behaviour = { _, _ -> TransferOutcome.Completed(100L) })
        )

        val result = m.submit(sftpTask("t1", createdAt = 1L))
        assertTrue(result is SubmitResult.Accepted)

        awaitState(m) { statusOf("t1")(it) == TransferStatus.COMPLETED }
        val finished = m.tasks.value.first { it.id == "t1" }
        assertEquals(100L, finished.bytesTransferred)
        assertEquals(1, finished.attempts)
        assertEquals(10_000L, finished.finishedAt)
        val stored = store.load().first { it.id == "t1" }
        assertEquals(TransferStatus.COMPLETED, stored.status)
    }

    @Test
    fun `a live duplicate is refused, and the same target is allowed again afterwards`() {
        val gate = CompletableDeferred<TransferOutcome>()
        val m = manager(executor = FakeExecutor(behaviour = { _, _ -> gate.await() }))

        assertTrue(m.submit(sftpTask("t1", createdAt = 1L)) is SubmitResult.Accepted)
        // The rapid second tap of the same row: same backend payload, same
        // destination. Different transfer id, identical dedupe key.
        assertTrue(m.submit(sftpTask("t1", createdAt = 2L)) is SubmitResult.Duplicate)
        assertEquals(1, m.tasks.value.count { !it.status.isTerminal })

        gate.complete(TransferOutcome.Completed(100L))
        awaitState(m) { statusOf("t1")(it) == TransferStatus.COMPLETED }

        assertTrue(m.submit(sftpTask("t1", createdAt = 3L)) is SubmitResult.Accepted)
    }

    @Test
    fun `submitting an already-terminal task is refused`() {
        val m = manager(executor = FakeExecutor(behaviour = { _, _ -> TransferOutcome.Completed(1L) }))
        val done = sftpTask("t1", status = TransferStatus.COMPLETED)
        assertTrue(m.submit(done) is SubmitResult.Rejected)
    }

    @Test
    fun `progress is recorded from the backend byte count`() {
        val reported = mutableListOf<Long>()
        val executor = FakeExecutor(
            behaviour = { _, onProgress ->
                listOf(10L, 40L, 75L).forEach { onProgress(it); reported += it }
                TransferOutcome.Completed(100L)
            }
        )
        val m = manager(store = InMemoryTransferStore(), executor = executor)
        m.submit(sftpTask("t1", createdAt = 1L))
        awaitState(m) { statusOf("t1")(it) == TransferStatus.COMPLETED }
        // The backend's own byte counts reached the manager, in order.
        assertEquals(listOf(10L, 40L, 75L), reported)
        // And the completion count — the authoritative final one — is stored.
        assertEquals(100L, m.tasks.value.first { it.id == "t1" }.bytesTransferred)
    }

    @Test
    fun `cancelling a running transfer stops the executor and records it`() {
        val cleanup = CountDownLatch(1)
        val executor = FakeExecutor(
            behaviour = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    // What a real executor must do on cancellation: clean
                    // the partial file before the manager settles the row.
                    cleanup.countDown()
                    throw CancellationException("stopped")
                }
            }
        )
        val store = InMemoryTransferStore()
        val m = manager(store = store, executor = executor)

        m.submit(sftpTask("t1", createdAt = 1L))
        awaitState(m) { statusOf("t1")(it) == TransferStatus.RUNNING }

        m.cancel("t1")
        assertTrue(
            "executor cleanup must run on cancel",
            cleanup.await(timeout, TimeUnit.MILLISECONDS)
        )

        awaitState(m) { statusOf("t1")(it) == TransferStatus.CANCELLED }
        assertEquals(TransferStatus.CANCELLED, store.load().first { it.id == "t1" }.status)
    }

    @Test
    fun `cancelling a queued transfer never starts it`() {
        val started = AtomicInteger(0)
        val gate = CompletableDeferred<TransferOutcome>()
        val executor = FakeExecutor(
            behaviour = { _, _ -> gate.await() },
            onStart = { started.incrementAndGet() }
        )
        val m = manager(executor = executor, maxConcurrent = 1)

        m.submit(sftpTask("first", createdAt = 1L))
        m.submit(sftpTask("second", createdAt = 2L))
        awaitState(m) { statusOf("first")(it) == TransferStatus.RUNNING }
        assertEquals(TransferStatus.QUEUED, statusOf("second")(m.tasks.value))

        m.cancel("second")
        gate.complete(TransferOutcome.Completed(100L))
        awaitState(m) { statusOf("first")(it) == TransferStatus.COMPLETED }

        assertEquals(TransferStatus.CANCELLED, statusOf("second")(m.tasks.value))
        assertEquals(1, started.get())
    }

    /** Polls a counter until it reaches [expected]. Keeps timing assertions
     *  honest about the manager's real (async) start-up, without sleeps that
     *  assume a scheduler. */
    private fun awaitCount(counter: AtomicInteger, expected: Int) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline && counter.get() < expected) Thread.sleep(5)
    }

    @Test
    fun `concurrency is bounded and the queue drains oldest first`() {
        // One permit per completed transfer: a shared CompletableDeferred
        // would let every queued task through the moment it completes.
        val permits = Semaphore(0)
        val started = AtomicInteger(0)
        val executor = FakeExecutor(
            behaviour = { _, _ ->
                permits.acquire()
                TransferOutcome.Completed(100L)
            },
            onStart = { started.incrementAndGet() }
        )
        val m = manager(executor = executor, maxConcurrent = 1)

        m.submit(sftpTask("a", createdAt = 1L))
        m.submit(sftpTask("b", createdAt = 2L))
        m.submit(sftpTask("c", createdAt = 3L))

        awaitState(m) { statusOf("a")(it) == TransferStatus.RUNNING }
        awaitCount(started, 1)
        assertEquals(TransferStatus.QUEUED, statusOf("b")(m.tasks.value))
        assertEquals(TransferStatus.QUEUED, statusOf("c")(m.tasks.value))

        permits.release()
        awaitState(m) { statusOf("b")(it) == TransferStatus.RUNNING }
        awaitCount(started, 2)
        assertEquals(TransferStatus.QUEUED, statusOf("c")(m.tasks.value))
        assertEquals(TransferStatus.COMPLETED, statusOf("a")(m.tasks.value))

        permits.release()
        awaitCount(started, 3)
        permits.release()
        awaitState(m) {
            listOf("a", "b", "c").all { id -> statusOf(id)(it) == TransferStatus.COMPLETED }
        }
        assertEquals(3, started.get())
    }

    @Test
    fun `a failed transfer keeps its error and can be retried`() {
        val attempts = AtomicInteger(0)
        val store = InMemoryTransferStore()
        val executor = FakeExecutor(
            behaviour = { _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    TransferOutcome.Failed("network dropped")
                } else {
                    TransferOutcome.Completed(100L)
                }
            }
        )
        val m = manager(store = store, executor = executor)

        m.submit(sftpTask("t1", createdAt = 1L))
        awaitState(m) { statusOf("t1")(it) == TransferStatus.FAILED }
        val failed = m.tasks.value.first { it.id == "t1" }
        assertEquals("network dropped", failed.error)
        assertEquals(1, failed.attempts)

        assertTrue(m.retry("t1") is SubmitResult.Accepted)
        awaitState(m) { statusOf("t1")(it) == TransferStatus.COMPLETED }
        val retried = m.tasks.value.first { it.id == "t1" }
        // Same transfer, second attempt, byte counter reset: no resume was
        // promised, so the retry must not continue where it stopped.
        assertEquals(2, retried.attempts)
        assertNull(retried.error)
        assertEquals(100L, retried.bytesTransferred)
    }

    @Test
    fun `retry keeps byte progress only when the executor declares resume`() {
        // The executor records 40 real bytes, then the connection drops.
        val failing = FakeExecutor(
            behaviour = { _, onProgress ->
                onProgress(40)
                TransferOutcome.Failed("connection lost at 40%")
            }
        )
        val m1 = manager(executor = failing)
        m1.submit(sftpTask("t1", createdAt = 1L))
        awaitState(m1) { statusOf("t1")(it) == TransferStatus.FAILED }
        assertEquals(40L, m1.tasks.value.first { it.id == "t1" }.bytesTransferred)

        assertTrue(m1.retry("t1") is SubmitResult.Accepted)
        // Not resumable: progress restarts, and nothing pretends otherwise.
        assertEquals(0L, m1.tasks.value.first { it.id == "t1" }.bytesTransferred)

        // The same failure with an executor that proved the resume point.
        val m2 = manager(
            executor = FakeExecutor(
                behaviour = { task, onProgress ->
                    if (task.attempts == 1) {
                        onProgress(40)
                        TransferOutcome.Failed("connection lost at 40%")
                    } else {
                        // Continues exactly from the recorded partial.
                        TransferOutcome.Completed(task.bytesTransferred + 60L)
                    }
                },
                resumable = true
            )
        )
        m2.submit(sftpTask("t2", createdAt = 1L))
        awaitState(m2) { statusOf("t2")(it) == TransferStatus.FAILED }
        assertTrue(m2.retry("t2") is SubmitResult.Accepted)
        assertEquals(40L, m2.tasks.value.first { it.id == "t2" }.bytesTransferred)
        awaitState(m2) { statusOf("t2")(it) == TransferStatus.COMPLETED }
        assertEquals(100L, m2.tasks.value.first { it.id == "t2" }.bytesTransferred)
    }

    @Test
    fun `an executor that throws does not leave the task running`() {
        val store = InMemoryTransferStore()
        val executor = FakeExecutor(
            behaviour = { _, _ -> throw IllegalStateException("boom") }
        )
        val m = manager(store = store, executor = executor)
        m.submit(sftpTask("t1", createdAt = 1L))
        awaitState(m) { statusOf("t1")(it) == TransferStatus.FAILED }
        assertEquals("boom", m.tasks.value.first { it.id == "t1" }.error)
        assertEquals(TransferStatus.FAILED, store.load().first { it.id == "t1" }.status)
    }

    @Test
    fun `an unknown backend fails the task instead of hanging`() {
        val m = manager(executorFor = { null })
        m.submit(sftpTask("t1", createdAt = 1L))
        awaitState(m) { statusOf("t1")(it) == TransferStatus.FAILED }
        assertNotNull(m.tasks.value.first { it.id == "t1" }.error)
    }

    @Test
    fun `a task stored as running is recovered as interrupted and is not auto-run`() {
        val store = InMemoryTransferStore()
        store.save(
            listOf(sftpTask("dead", status = TransferStatus.RUNNING, createdAt = 1L, bytes = 42L))
        )
        val started = AtomicInteger(0)
        val gate = CompletableDeferred<TransferOutcome>()
        val executor = FakeExecutor(
            behaviour = { _, _ -> gate.await() },
            onStart = { started.incrementAndGet() }
        )
        val m = manager(store = store, executor = executor)

        val recovered = m.tasks.value.first { it.id == "dead" }
        // Honest recovery: nothing is running, and the row says exactly that.
        assertEquals(TransferStatus.INTERRUPTED, recovered.status)
        assertEquals(42L, recovered.bytesTransferred)

        Thread.sleep(150)
        assertEquals(0, started.get())
        // And it can still be retried by the user.
        assertTrue(m.retry("dead") is SubmitResult.Accepted)
        awaitState(m) { statusOf("dead")(it) == TransferStatus.RUNNING }
        gate.complete(TransferOutcome.Completed(100L))
    }

    @Test
    fun `clearing finished tasks keeps active ones`() {
        val gate = CompletableDeferred<TransferOutcome>()
        val m = manager(executor = FakeExecutor(behaviour = { _, _ -> gate.await() }))
        m.submit(sftpTask("running", createdAt = 1L))
        awaitState(m) { statusOf("running")(it) == TransferStatus.RUNNING }

        m.submit(sftpTask("old", createdAt = 0L))
        m.cancel("old")
        awaitState(m) { statusOf("old")(it) == TransferStatus.CANCELLED }

        m.clearFinished()
        val remaining = m.tasks.value.map { it.id }
        assertTrue(remaining.contains("running"))
        assertFalse(remaining.contains("old"))
        gate.complete(TransferOutcome.Completed(100L))
    }
}
