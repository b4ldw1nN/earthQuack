package com.example.earthquack.transfer

/**
 * Persistence for [TransferTask]s.
 *
 * ## Why an interface
 *
 * The manager must survive an app restart, and the state machine must be
 * testable on the JVM without a device. The interface splits those: the
 * Android implementation is SharedPreferences (the existing convention in
 * this codebase), the in-memory double is what the tests use.
 *
 * ## What is kept
 *
 * Everything, including finished tasks, up to [maxHistory] entries: history
 * is the point of the transfer screen. Only terminal tasks are trimmed, and
 * only the oldest of them, so an active transfer is never evicted to make
 * room for a completed one.
 */
interface TransferStore {

    /** Every stored task, oldest first by [TransferTask.createdAt]. */
    fun load(): List<TransferTask>

    /**
     * Atomically replaces the stored set with [tasks].
     *
     * Implementations trim terminal history beyond [maxHistory] here, so no
     * caller has to remember the policy.
     */
    fun save(tasks: List<TransferTask>)

    /** How many finished tasks to keep. */
    val maxHistory: Int
}
