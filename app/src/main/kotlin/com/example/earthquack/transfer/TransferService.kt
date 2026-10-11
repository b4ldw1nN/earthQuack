package com.example.earthquack.transfer

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.ssh.ConnectionProfileStore
import com.example.earthquack.ssh.SshServices
import com.example.earthquack.storage.RcloneConfigManager
import com.example.earthquack.storage.RcloneEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps queued transfers alive and visible.
 *
 * ## Why a foreground service and not a Worker
 *
 * The transfer itself is a long, *live* byte stream (a native rclone RPC or
 * an sshd session) with real byte progress to report. WorkManager cannot
 * stream progress for a job of unknown duration and cannot carry an SSH
 * session across an expedited-work window; it also cannot guarantee the
 * 10-minute cap is respected for a 2 GB download over a phone's uplink. A
 * foreground service with a `dataSync` type is the mechanism Android
 * provides for exactly this: user-initiated file transfer, visible progress,
 * surviving backgrounded screens.
 *
 * ## What the service does *not* do
 *
 * It does not own the queue. [TransferManager] is a process singleton that
 * keeps running even if the service is stopped; the service exists to hold
 * OS priority while transfers are active and to make the notification
 * honest. If the process dies anyway, storage is the source of truth (see
 * [TransferStatus.INTERRUPTED]).
 */
class TransferService : LifecycleService() {

    companion object {
        private const val ACTION_PUMP = "com.example.earthquack.transfer.PUMP"
        private const val ACTION_CANCEL = "com.example.earthquack.transfer.CANCEL"
        private const val ACTION_RETRY = "com.example.earthquack.transfer.RETRY"
        private const val EXTRA_ID = "id"
        private const val NOTIF_CHANNEL_ID = "earthquack_transfers"
        private const val NOTIF_ID = 4201

        /** Creates the notification channel. Safe to call repeatedly. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (manager.getNotificationChannel(NOTIF_CHANNEL_ID) != null) return
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL_ID,
                context.getString(R.string.transfers_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.transfers_channel_desc) }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            ensureChannel(context)
            // startForegroundService, not startService: the service must be
            // allowed to promote itself promptly even if the caller is one
            // step away from background, which is exactly when a transfer
            // outlives its screen.
            context.startForegroundService(
                Intent(context, TransferService::class.java).setAction(ACTION_PUMP)
            )
        }

        fun cancel(context: Context, id: String) {
            context.startService(
                Intent(context, TransferService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_ID, id)
            )
        }

        fun retry(context: Context, id: String) {
            context.startService(
                Intent(context, TransferService::class.java)
                    .setAction(ACTION_RETRY)
                    .putExtra(EXTRA_ID, id)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The manager is process-wide; constructing it here links it to the
        // service's stores on first use, and every later caller gets the
        // same instance with the same stores.
        TransferController.manager(this)
        goForeground(resources.getString(R.string.transfers_service_starting))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL ->
                TransferController.manager(this).cancel(intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY)
            ACTION_RETRY -> {
                TransferController.manager(this).retry(intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY)
            }
        }
        observeTransfers()
        return START_NOT_STICKY
    }

    /**
     * Mirrors the manager's state into the notification.
     *
     * Only *active* work keeps the foreground notification. When the last
     * active transfer finishes, the notification is replaced once by a
     * summary of the outcomes — a completion the user asked for is worth
     * one line — and the service stops.
     */
    private fun observeTransfers() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            var hadActive = false
            // No distinctUntilChanged: StateFlow already conflates equal
            // values, and the collection key is the task list changing.
            TransferController.manager(this@TransferService).tasks
                .collect { tasks ->
                    val active = tasks.filter { it.status.isActive }
                    if (active.isNotEmpty()) {
                        hadActive = true
                        val running = active.count { it.status == TransferStatus.RUNNING }
                        val queued = active.size - running
                        val text = buildString {
                            if (running > 0) append(resources.getQuantityString(R.plurals.transfers_service_running, running, running))
                            if (queued > 0) {
                                if (isNotEmpty()) append(" · ")
                                append(resources.getQuantityString(R.plurals.transfers_service_queued, queued, queued))
                            }
                        }
                        goForeground(text.ifBlank { getString(R.string.transfers_service_working) })
                    } else if (hadActive) {
                        hadActive = false
                        val finished = tasks.count { it.status == TransferStatus.COMPLETED }
                        val failed = tasks.count { it.status == TransferStatus.FAILED }
                        // Remove rather than detach: the ongoing notification
                        // would outlive the work it announces.
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        postSummary(finished, failed)
                        stopSelf()
                    }
                }
        }
    }

    private var observing = false

    private fun goForeground(text: String) {
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_eq_transfer)
            .setContentTitle(getString(R.string.transfers_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.example.earthquack.ui.sub.TransfersActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun postSummary(completed: Int, failed: Int) {
        val text = when {
            failed == 0 && completed > 0 ->
                resources.getQuantityString(R.plurals.transfers_service_done, completed, completed)
            failed > 0 && completed == 0 ->
                resources.getQuantityString(R.plurals.transfers_service_failed_summary, failed, failed)
            else -> getString(R.string.transfers_service_mixed, completed, failed)
        }
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(if (failed == 0) R.drawable.ic_eq_transfer else R.drawable.ic_eq_error)
            .setContentTitle(getString(R.string.transfers_service_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.example.earthquack.ui.sub.TransfersActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIF_ID + 1, notification)
    }
}

/**
 * The seam between any screen and the shared queue.
 *
 * Screens call [submit]/[cancel]/[retry]; the controller makes sure the
 * foreground service is running whenever work is active, and builds the
 * executors the manager needs. Keeping this out of the screens is what
 * stops each one from growing its own idea of "how a transfer starts".
 */
object TransferController {

    @Volatile
    private var manager: TransferManager? = null

    /** The process-wide manager, built on first use. */
    fun manager(context: Context): TransferManager = manager ?: synchronized(this) {
        manager ?: TransferManager(PrefsTransferStore(context), executors(context))
            .also { manager = it }
    }

    /**
     * Queues [task] and makes the foreground service aware of it.
     *
     * @return the [SubmitResult], so a screen can say "this is already
     *   downloading" instead of doubling the row.
     */
    fun submit(context: Context, task: TransferTask): SubmitResult {
        val result = manager(context).submit(task)
        if (result is SubmitResult.Accepted) TransferService.start(context)
        return result
    }

    fun cancel(context: Context, id: String) = manager(context).cancel(id).also { }
    fun retry(context: Context, id: String) = manager(context).retry(id)

    private fun executors(context: Context): (TransferBackend) -> TransferExecutor? = { backend ->
        when (backend) {
            is TransferBackend.RcloneDownload -> RcloneTransferExecutor(sharedRclone(context))
            is TransferBackend.Sftp -> {
                val profiles = ConnectionProfileStore(context)
                SftpTransferExecutor(
                    resolveProfile = { id -> profiles.get(id) },
                    factory = SshServices.connectionFactory(context)
                )
            }
        }
    }

    /**
     * One [RcloneEngine] per process, initialised from the same
     * `rclone.conf` the Files screen uses.
     *
     * Shared deliberately: rclone's native side is process-wide state, and a
     * second engine in one process is two initialisations of one runtime.
     */
    @Volatile
    private var engine: RcloneEngine? = null

    @Synchronized
    private fun sharedRclone(context: Context): RcloneEngine = engine ?: RcloneEngine().also { created ->
        engine = created
        val configPath = RcloneConfigManager(context).configPath
        created.initialize(configPath)
    }
}
