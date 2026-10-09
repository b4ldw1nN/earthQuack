package com.example.earthquack.sftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.earthquack.MainActivity
import com.example.earthquack.R
import com.example.earthquack.state.TailnetStatus
import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * Runs the SFTP server as a foreground service.
 *
 * ## Why foreground
 *
 * Android 14 kills background services within seconds, and an SFTP server that
 * dies mid-transfer is worse than no server: rclone would report a network
 * error for a file it had already half-written. The foreground notification is
 * also the only honest way to say "this is listening on a port right now" —
 * the user can see it, and can stop it from the notification.
 *
 * ## What it does not do
 *
 * It does not own the protocol. [SftpServerEngine] does; this class owns the
 * Android lifecycle around it — start, stop, notification, and making sure the
 * server is torn down when the service is destroyed, however that happens.
 *
 * ## Lifecycle
 *
 * Started with [ACTION_START] and the settings as extras, stopped with
 * [ACTION_STOP] or by the notification's action. `onDestroy` stops the engine
 * unconditionally, so there is no path where the service dies and the port
 * stays bound.
 */
class SftpServerService : Service() {

    companion object {
        const val TAG = "EarthQuackSftpServer"
        const val CHANNEL_ID = "sftp_server_channel"
        const val NOTIFICATION_ID = 3001

        const val ACTION_START = "com.example.earthquack.sftp.START"
        const val ACTION_STOP = "com.example.earthquack.sftp.STOP"

        const val EXTRA_PORT = "port"
        const val EXTRA_ROOT = "root"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD_AUTH = "password_auth"
        const val EXTRA_PUBLIC_KEY_AUTH = "public_key_auth"
        const val EXTRA_MAX_CONNECTIONS = "max_connections"
        const val EXTRA_ERROR = "error"

        const val ACTION_STATUS_UPDATE = "com.example.earthquack.sftp.STATUS_UPDATE"

        /**
         * The result of the start request currently in flight.
         *
         * A `startForegroundService` intent is asynchronous: it returns as soon
         * as the request is queued, long before the listener is bound. The
         * caller therefore has no way to learn the outcome except by polling
         * `isRunning()` — which cannot distinguish "the new configuration is
         * live" from "the previous one is still up", and so reports a
         * successful port change as a no-op on the old port.
         *
         * The service completes this future with the status it actually reached.
         * Registered *before* the intent is sent, so the result can never be
         * missed, and it is the bound port sshd reports rather than the
         * requested one.
         */
        private val pendingStart = java.util.concurrent.atomic.AtomicReference<CompletableFuture<SftpServerStatus>?>(null)

        /**
         * Requests a start and returns the future its result will arrive on.
         *
         * @return a future completed with [SftpServerStatus.Running] carrying
         *   the bound port, or [SftpServerStatus.Error] carrying the reason.
         */
        fun requestStart(context: Context, settings: SftpSettings): CompletableFuture<SftpServerStatus> {
            val future = CompletableFuture<SftpServerStatus>()
            pendingStart.set(future)
            start(context, settings)
            return future
        }

        /** Completes the in-flight request, if any. Never blocks. */
        private fun completePending(status: SftpServerStatus) {
            pendingStart.getAndSet(null)?.complete(status)
        }

        fun start(context: Context, settings: SftpSettings) {
            val intent = Intent(context, SftpServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, settings.port)
                putExtra(EXTRA_ROOT, settings.rootPath)
                putExtra(EXTRA_USERNAME, settings.username)
                putExtra(EXTRA_PASSWORD_AUTH, settings.passwordAuth)
                putExtra(EXTRA_PUBLIC_KEY_AUTH, settings.publicKeyAuth)
                putExtra(EXTRA_MAX_CONNECTIONS, settings.maxConnections)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SftpServerService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                completePending(SftpServerStatus.Stopped)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val settings = intent.toSftpSettings()
                if (settings == null || !settings.isValid) {
                    Log.w(TAG, "refusing to start with invalid settings")
                    broadcastError("Invalid settings")
                    completePending(SftpServerStatus.Error("Invalid settings"))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // Foreground first: Android requires the notification within a
                // few seconds of startForegroundService, and a server that
                // cannot start should not be advertised as running.
                startForeground(NOTIFICATION_ID, buildNotification(settings.port, 0))

                val result = SftpServerHolder.engine(this).start(settings)
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "unknown error"
                    Log.e(TAG, "start failed: $message")
                    notifyError(message)
                    broadcastError(message)
                    completePending(SftpServerStatus.Error(message))
                    // Leaving the service in the foreground with an error
                    // notification and a dead listener would show the user a
                    // persistent notification for a server that is not running.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val instance = result.getOrThrow()
                val status = SftpServerStatus.Running(instance.port, instance.connectedClients)
                updateNotification(instance.port)
                broadcastStatus()
                completePending(status)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The service owns the listener for as long as it is alive, so the port
        // must not survive this method. `engine()` returns the one process-wide
        // engine — it is never cleared out from under the service, which is what
        // stops a late onDestroy from tearing down a server that a newer start
        // request has already brought up.
        runCatching { SftpServerHolder.engine(this).stop() }
        completePending(SftpServerStatus.Stopped)
        broadcastStatus()
        super.onDestroy()
    }

    // ── notification ─────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sftp_server_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sftp_server_channel_description)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(boundPort: Int, clients: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SftpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sftp_notification_title))
            .setContentText(notificationText(boundPort, clients))
            .setSmallIcon(R.drawable.ic_eq_transfer)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_eq_stop,
                getString(R.string.sftp_stop),
                stopIntent
            )
            .build()
    }

    private fun notificationText(port: Int, clients: Int): String {
        val host = TailnetStatus(this).tailnetAddress() ?: "this device"
        val clientWord = resources.getQuantityString(
            R.plurals.sftp_client_count,
            clients,
            clients
        )
        // The bound port, not the saved preference: those differ whenever the
        // server was started on an ephemeral port or the setting changed while
        // the process was alive, and the notification is the user's only proof
        // of what is actually reachable.
        return "$host:$port · $clientWord"
    }

    private fun updateNotification(boundPort: Int) {
        val engine = SftpServerHolder.engine(this)
        val notification = buildNotification(boundPort, engine.connectedClients())
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun notifyError(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sftp_notification_error_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_eq_warning)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun stopServer() {
        runCatching { SftpServerHolder.engine(this).stop() }
        broadcastStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).setPackage(packageName))
    }

    private fun broadcastError(message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_ERROR, message)
        )
    }

    private fun Intent.toSftpSettings(): SftpSettings? {
        val root = getStringExtra(EXTRA_ROOT) ?: return null
        return try {
            SftpSettings(
                port = getIntExtra(EXTRA_PORT, SftpSettings.DEFAULT_PORT),
                rootPath = root,
                username = getStringExtra(EXTRA_USERNAME) ?: SftpSettings.DEFAULT_USERNAME,
                passwordAuth = getBooleanExtra(EXTRA_PASSWORD_AUTH, true),
                publicKeyAuth = getBooleanExtra(EXTRA_PUBLIC_KEY_AUTH, false),
                maxConnections = getIntExtra(EXTRA_MAX_CONNECTIONS, SftpSettings.DEFAULT_MAX_CONNECTIONS)
            )
        } catch (e: Exception) {
            null
        }
    }
}
