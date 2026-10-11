package com.example.earthquack.ui.sub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityTransfersBinding
import com.example.earthquack.databinding.ItemTransferRowBinding
import com.example.earthquack.transfer.SubmitResult
import com.example.earthquack.transfer.TransferController
import com.example.earthquack.transfer.TransferStatus
import com.example.earthquack.transfer.TransferTask
import com.example.earthquack.transfer.TransferType
import com.example.earthquack.ui.SubScreenActivity
import com.example.earthquack.ui.formatSize
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The shared transfer queue, visible.
 *
 * ## Why a screen and not a notification-only feature
 *
 * Bytes move behind a foreground service precisely so this screen does not
 * need to exist — but "which of my five files failed, and why" is a question
 * a notification cannot answer. This screen reads the same
 * [com.example.earthquack.transfer.TransferManager] state the service and
 * every submitting screen read from, so it is a view of the queue rather
 * than another copy of it.
 *
 * ## Active versus recent
 *
 * Two lists, because they answer different questions: "what is happening
 * now" wants a progress bar and a Stop button; "what happened" wants the
 * error and a Retry. A terminal row never gets a progress bar and an active
 * row never gets a Retry, so neither list can mislead about the other.
 */
class TransfersActivity : SubScreenActivity() {

    override val titleRes = R.string.transfers_title

    private lateinit var body: ActivityTransfersBinding

    private val activeAdapter = TransferRowAdapter(onAction = { task, action ->
        when (action) {
            RowAction.STOP -> TransferController.manager(this).cancel(task.id)
            RowAction.RETRY -> TransferController.manager(this).retry(task.id)
        }
    })
    private val historyAdapter = TransferRowAdapter(onAction = { task, action ->
        when (action) {
            RowAction.STOP -> TransferController.manager(this).cancel(task.id)
            RowAction.RETRY -> TransferController.manager(this).retry(task.id)
        }
    })

    override fun inflateBody(inflater: LayoutInflater, container: ViewGroup?) {
        body = ActivityTransfersBinding.inflate(inflater, container ?: return, false)
        container.addView(body.root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        body.listActive.layoutManager = LinearLayoutManager(this)
        body.listActive.adapter = activeAdapter
        body.listHistory.layoutManager = LinearLayoutManager(this)
        body.listHistory.adapter = historyAdapter

        body.btnClearHistory.setOnClickListener { clearHistory() }

        lifecycleScope.launch {
            // Re-collecting after a configuration change simply re-renders;
            // the state itself lives in the manager, not in this screen.
            TransferController.manager(this@TransfersActivity).tasks.collect { tasks ->
                render(tasks)
            }
        }
    }

    private fun render(tasks: List<TransferTask>) {
        val active = tasks.filter { it.status.isActive }
        val history = tasks.filter { it.status.isTerminal || it.status == TransferStatus.INTERRUPTED }
            .sortedByDescending { it.finishedAt }

        activeAdapter.submitList(active)
        historyAdapter.submitList(history)

        body.emptyActive.visibility = if (active.isEmpty()) View.VISIBLE else View.GONE
        body.listActive.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
        body.emptyHistory.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        body.listHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        body.btnClearHistory.isEnabled = history.isNotEmpty()
    }

    /**
     * Removes finished rows without touching live transfers.
     *
     * The manager owns the store; this only asks. There is deliberately no
     * undo for something that is only a history list.
     */
    private fun clearHistory() {
        TransferController.manager(this).clearFinished()
        Toast.makeText(this, R.string.transfers_clear_history, Toast.LENGTH_SHORT).show()
    }

    private enum class RowAction { STOP, RETRY }

    private class TransferRowAdapter(
        private val onAction: (TransferTask, RowAction) -> Unit
    ) : ListAdapter<TransferTask, TransferRowAdapter.ViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transfer_row, parent, false)
            return ViewHolder(ItemTransferRowBinding.bind(view))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

        private inner class ViewHolder(private val binding: ItemTransferRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(task: TransferTask) = with(binding) {
                val context = root.context

                rowName.text = task.displayName.ifBlank { task.destination.substringAfterLast('/') }
                rowSubtitle.text = "${task.sourceLabel} → ${task.destination.substringAfterLast('/')}"
                rowTransferIcon.setImageResource(
                    if (task.type == TransferType.DOWNLOAD) R.drawable.ic_eq_download
                    else R.drawable.ic_eq_send
                )

                when (task.status) {
                    TransferStatus.QUEUED -> {
                        rowProgress.isIndeterminate = false
                        rowProgress.progress = 0
                        showProgressRow(false)
                        rowStatus.setText(R.string.transfers_starting)
                        rowError.visibility = View.GONE
                        bindAction(TransferStatus.QUEUED, task)
                    }
                    TransferStatus.RUNNING -> {
                        val fraction = task.fraction
                        if (fraction == null) {
                            rowProgress.isIndeterminate = true
                        } else {
                            rowProgress.isIndeterminate = false
                            rowProgress.progress = (fraction * 100).toInt()
                        }
                        showProgressRow(true)
                        rowStatus.text = progressText(context, task)
                        rowError.visibility = View.GONE
                        bindAction(TransferStatus.RUNNING, task)
                    }
                    TransferStatus.INTERRUPTED -> {
                        showProgressRow(false)
                        rowStatus.setText(R.string.transfers_interrupted_hint)
                        rowError.visibility = View.GONE
                        bindAction(TransferStatus.INTERRUPTED, task)
                    }
                    TransferStatus.COMPLETED -> {
                        showProgressRow(false)
                        rowStatus.text = context.getString(
                            R.string.transfers_done_meta,
                            formatSize(task.bytesTransferred),
                            timeOf(task.finishedAt)
                        )
                        rowError.visibility = View.GONE
                        bindAction(TransferStatus.COMPLETED, task)
                    }
                    TransferStatus.FAILED -> {
                        showProgressRow(false)
                        rowStatus.text = context.getString(
                            R.string.transfers_failed_meta,
                            timeOf(task.finishedAt)
                        )
                        rowError.visibility = View.VISIBLE
                        rowError.text = task.error ?: context.getString(R.string.transfers_unknown_size)
                        bindAction(TransferStatus.FAILED, task)
                    }
                    TransferStatus.CANCELLED -> {
                        showProgressRow(false)
                        rowStatus.text = context.getString(
                            R.string.transfers_cancelled_meta,
                            timeOf(task.finishedAt)
                        )
                        rowError.visibility = View.GONE
                        bindAction(TransferStatus.CANCELLED, task)
                    }
                }
            }

            /** Shows or hides the bar and the byte/meta line together. */
            private fun showProgressRow(visible: Boolean) = with(binding) {
                val visibility = if (visible) View.VISIBLE else View.GONE
                rowProgress.visibility = visibility
                rowProgressMeta.visibility = visibility
            }

            /** The one row-level action this state permits — never both. */
            private fun bindAction(status: TransferStatus, task: TransferTask) = with(binding) {
                when (status) {
                    TransferStatus.QUEUED, TransferStatus.RUNNING -> {
                        rowAction.visibility = View.VISIBLE
                        rowAction.setText(R.string.transfers_cancel)
                        rowAction.setOnClickListener { onAction(task, RowAction.STOP) }
                    }
                    TransferStatus.FAILED, TransferStatus.INTERRUPTED, TransferStatus.CANCELLED -> {
                        rowAction.visibility = View.VISIBLE
                        rowAction.setText(R.string.transfers_retry)
                        rowAction.setOnClickListener { onAction(task, RowAction.RETRY) }
                    }
                    TransferStatus.COMPLETED -> {
                        rowAction.visibility = View.GONE
                        rowAction.setOnClickListener(null)
                    }
                }
            }

            private fun progressText(context: android.content.Context, task: TransferTask): String {
                val done = formatSize(task.bytesTransferred)
                val total = if (task.totalBytes > 0) formatSize(task.totalBytes) else null
                return if (total == null) {
                    "$done · ${context.getString(R.string.transfers_unknown_size)}"
                } else {
                    val percent = (task.fraction ?: 0f) * 100
                    "$done / $total · ${percent.toInt()}%"
                }
            }

            private fun timeOf(millis: Long): String =
                if (millis <= 0L) "" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<TransferTask>() {
                override fun areItemsTheSame(old: TransferTask, new: TransferTask) = old.id == new.id
                override fun areContentsTheSame(old: TransferTask, new: TransferTask) = old == new
            }
        }
    }
}
