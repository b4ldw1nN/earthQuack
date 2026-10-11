package com.example.earthquack.transfer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * [TransferStore] on SharedPreferences, as JSON.
 *
 * The list is a handful of rows written only when a transfer changes state,
 * and read once at startup and on every UI resume. A database would add a
 * dependency and a schema for a query this does not have — the same reason
 * [com.example.earthquack.ssh.ConnectionProfileStore] gives, for the same
 * shape of data.
 *
 * A corrupt or unreadable blob degrades to "no history": the active
 * transfers are gone either way (the process died), and a crash loop on a
 * malformed row would take the transfer screen with it.
 */
class PrefsTransferStore(context: Context) : TransferStore {

    private companion object {
        const val PREFS = "earthquack_transfers"
        const val KEY_TASKS = "tasks"
    }

    override val maxHistory: Int = 50

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<TransferTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return parse(raw)
    }

    override fun save(tasks: List<TransferTask>) {
        val trimmed = trim(tasks)
        val array = JSONArray()
        trimmed.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    /**
     * Keeps every live task and the newest [maxHistory] terminal ones.
     *
     * Ordering by created-at keeps "recent history" meaning what the UI
     * says. Tasks with a zero timestamp (clock unavailable) sort first and
     * are the first to be trimmed, which is the safe end to err on.
     */
    private fun trim(tasks: List<TransferTask>): List<TransferTask> {
        val live = tasks.filter { !it.status.isTerminal }
        val terminal = tasks.filter { it.status.isTerminal }
            .sortedByDescending { it.finishedAt }
            .take(maxHistory)
        return (live + terminal).sortedBy { it.createdAt }
    }

    internal fun parse(raw: String): List<TransferTask> {
        val array = try {
            JSONArray(raw)
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        val out = mutableListOf<TransferTask>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            TransferTask.fromJson(obj)?.let { out += it }
        }
        // Duplicate ids would double-list a transfer after a save crash
        // between two writes; keep the first.
        return out.distinctBy { it.id }
    }
}

/** [TransferStore] that lives in memory. For tests, and for callers that must
 * not persist (a preview run, for instance). */
class InMemoryTransferStore(override val maxHistory: Int = 50) : TransferStore {

    private var tasks: List<TransferTask> = emptyList()

    override fun load(): List<TransferTask> = tasks

    override fun save(tasks: List<TransferTask>) {
        val live = tasks.filter { !it.status.isTerminal }
        val terminal = tasks.filter { it.status.isTerminal }
            .sortedByDescending { it.finishedAt }
            .take(maxHistory)
        this.tasks = (live + terminal).sortedBy { it.createdAt }
    }
}
