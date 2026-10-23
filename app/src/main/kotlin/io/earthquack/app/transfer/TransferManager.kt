package io.earthquack.app.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared transfer queue: one process-wide instance, one place transfers
 * are submitted, queued, run, cancelled, retried and remembered.
 *
 * ## Why this is not a ViewModel
 *
 * A transfer that lives in a ViewModel dies with the screen that created it,
 * which is the bug this class exists to fix. The manager is a process
 * singleton (see [get]) constructed with an injectable store, so it survives
 * navigation, configuration changes and — through [TransferStore] — full
 * process death: an interrupted transfer comes back as
 * [TransferStatus.INTERRUPTED], honest about the fact that nothing is
 * running it any more.
 *
 * ## What it guarantees
 *
 * - **Dedupe.** [submit] refuses a second live task with the same
 *   [TransferTask.dedupeKey], however often the user taps.
 * - **Bounded concurrency.** At most [maxConcurrent] executors run at once;
 *   the rest wait in [TransferStatus.QUEUED] with their order intact.
 * - **Persisted transitions.** Every state change is written through the
 *   store before the UI is notified, so a process death between the two
 *   leaves storage, not memory, as the source of truth.
 *
 * ## What it deliberately does not do
 *
 * Fake progress (bytes come only from the backend), promise resumability
 * (see [TransferExecutor.canResume]), or hold a reference to any UI.
 */
class TransferManager(
    private val store: TransferStore,
    /** Picks the executor for a task's backend; null when unsupported. */
    private val executorFor: (TransferBackend) -> TransferExecutor?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxConcurrent: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val _tasks = MutableStateFlow(store.load())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    /** Jobs of running tasks, so cancellation can reach the executor. */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** [TransferTask.id]s the user asked to stop while they were still queued. */
    private val pendingCancels = ConcurrentHashMap.newKeySet<String>()

    init {
        // Storage never contains RUNNING tasks (fromJson recovers them as
        // INTERRUPTED); this is the belt to that braces for an older blob.
        val recovered = _tasks.value.map { task ->
            if (task.status == TransferStatus.RUNNING) task.loadRecovered() else task
        }
        if (recovered != _tasks.value) persist(recovered)
    }

    // ── Submission ───────────────────────────────────────────────────────────

    /**
     * Queues [task], or explains why it was refused.
     *
     * A task already carrying a live duplicate (same destination, same
     * backend, same orientation) is refused with
     * [SubmitResult.Duplicate] — the anti-double-tap rule, and it lives in
     * the manager rather than each screen because "don't start the same
     * download twice" is a property of the queue, not of one screen.
     */
    fun submit(task: TransferTask): SubmitResult {
        if (task.status.isTerminal) {
            // Refused at the type level: a stored task was re-submitted as-is.
            return SubmitResult.Rejected("task is already ${task.status.name.lowercase()}")
        }
        val current = _tasks.value
        val live = current.filter { !it.status.isTerminal }
        if (live.any { it.dedupeKey == task.dedupeKey }) {
            return SubmitResult.Duplicate(live.first { it.dedupeKey == task.dedupeKey }.id)
        }
        val toStore = (listOf(task) + current).sortedBy { it.createdAt }
        persist(toStore)
        pump()
        return SubmitResult.Accepted(task.id)
    }

    fun cancel(id: String) {
        val current = _tasks.value
        val task = current.firstOrNull { it.id == id } ?: return
        when (task.status) {
            TransferStatus.QUEUED -> {
                // Nothing is executing it yet: mark it now so the pump never
                // picks it up, and the UI stops showing a spinner.
                persist(
                    current.map {
                        if (it.id == id) it.copy(
                            status = TransferStatus.CANCELLED,
                            finishedAt = clock()
                        ) else it
                    }
                )
            }
            TransferStatus.RUNNING -> {
                pendingCancels.add(id)
                jobs[id]?.cancel()
                // State is finalised by the runner, in its finally, so the
                // executor's cleanup has run before the row settles.
            }
            else -> return
        }
    }

    /**
     * Re-queues a finished task.
     *
     * The identity is kept — this is the same transfer, attempted again —
     * and the byte counter is reset to zero unless [TransferExecutor.canResume]
     * proves continuation is possible, in which case the partial progress is
     * carried into the attempt. The attempt counter is *not* touched here:
     * promotion to RUNNING is the one place an attempt is counted, so a queued
     * retry cannot make one physical run look like two attempts.
     */
    fun retry(id: String): SubmitResult {
        val current = _tasks.value
        val old = current.firstOrNull { it.id == id } ?: return SubmitResult.Rejected("no such transfer")
        if (!old.status.isTerminal && old.status != TransferStatus.INTERRUPTED) {
            return SubmitResult.Rejected("transfer is still ${old.status.name.lowercase()}")
        }
        val executor = executorFor(old.backend)
        val keepBytes = executor?.canResume(old) == true
        val retried = old.copy(
            status = TransferStatus.QUEUED,
            bytesTransferred = if (keepBytes) old.bytesTransferred else 0L,
            error = null,
            finishedAt = 0L
        )
        persist(current.map { if (it.id == id) retried else it })
        pump()
        return SubmitResult.Accepted(id)
    }

    /** Removes every finished task. Live transfers are untouched. */
    fun clearFinished() {
        persist(_tasks.value.filter { !it.status.isTerminal })
    }

    // ── Execution ────────────────────────────────────────────────────────────

    /**
     * Starts queued transfers until [maxConcurrent] are running.
     *
     * Idempotent and safe to call on every state change: a task already
     * running has a job in [jobs] and is not promoted twice. Promotion
     * happens oldest-first across the whole queue, not per-backend, so one
     * busy backend cannot starve the other.
     */
    private fun pump() {
        while (jobs.size < maxConcurrent) {
            val next = _tasks.value
                .filter { it.status == TransferStatus.QUEUED && !jobs.containsKey(it.id) }
                .minByOrNull { it.createdAt }
                ?: return
            if (next.id in pendingCancels) {
                pendingCancels.remove(next.id)
                finalise(next, TransferStatus.CANCELLED, 0L, null)
                continue
            }
            val executor = executorFor(next.backend)
            if (executor == null) {
                finalise(next, TransferStatus.FAILED, 0L, "No backend available for ${next.backend.tag} transfers")
                continue
            }
            val running = next.copy(
                status = TransferStatus.RUNNING,
                startedAt = clock(),
                // The one place an attempt is counted: a retry queues the
                // same transfer, and counting in retry as well would report
                // one physical run as two attempts.
                attempts = next.attempts + 1,
                error = null
            )
            persist(_tasks.value.map { if (it.id == running.id) running else it })
            jobs[running.id] = scope.launch { run(running, executor) }
        }
    }

    private suspend fun run(task: TransferTask, executor: TransferExecutor) {
        try {
            when (val outcome = executor.run(task) { bytes ->
                // Only the backend's own byte count; a callback that reports
                // *less* than the store already holds is stale and is dropped,
                // so a late poll can never drag a progress bar backwards.
                persist(
                    _tasks.value.map {
                        if (it.id == task.id && bytes > it.bytesTransferred) {
                            it.copy(bytesTransferred = bytes)
                        } else {
                            it
                        }
                    }
                )
            }) {
                is TransferOutcome.Completed -> finalise(task, TransferStatus.COMPLETED, outcome.bytes, null)
                is TransferOutcome.Failed ->
                    finalise(task, TransferStatus.FAILED, currentBytesOf(task), outcome.error)
            }
        } catch (e: CancellationException) {
            // The executor has already cleaned up its partial state on the
            // way out; this only records what the user asked for.
            finalise(task, TransferStatus.CANCELLED, currentBytesOf(task), null)
            throw e
        } catch (e: Throwable) {
            // An executor throwing plain exceptions is a bug, not a transfer
            // failure. The task still reaches a terminal state — a row stuck
            // on RUNNING forever is worse than an honest FAILED one.
            finalise(task, TransferStatus.FAILED, currentBytesOf(task), e.message ?: e.javaClass.simpleName)
        } finally {
            jobs.remove(task.id)
            pump()
        }
    }

    /**
     * The byte count as *currently* stored for [task].
     *
     * The [task] handed to [run] is the value captured at promotion; progress
     * callbacks have since replaced that row. Finalising from the captured
     * copy would throw away the last reported bytes — and with them the
     * resume point [TransferExecutor.canResume] needs.
     */
    private fun currentBytesOf(task: TransferTask): Long =
        _tasks.value.firstOrNull { it.id == task.id }?.bytesTransferred ?: task.bytesTransferred

    private fun finalise(task: TransferTask, status: TransferStatus, bytes: Long, error: String?) {
        persist(
            _tasks.value.map {
                if (it.id != task.id) it else it.copy(
                    status = status,
                    bytesTransferred = bytes,
                    finishedAt = clock(),
                    error = error
                )
            }
        )
    }

    /** Writes the new list to storage and only then publishes it. */
    private fun persist(tasks: List<TransferTask>) {
        store.save(tasks)
        _tasks.value = tasks
    }
}

/** Why [TransferManager.submit] accepted or refused a task. */
sealed class SubmitResult {
    data class Accepted(val id: String) : SubmitResult()
    data class Duplicate(val existingId: String) : SubmitResult()
    data class Rejected(val reason: String) : SubmitResult()
}
