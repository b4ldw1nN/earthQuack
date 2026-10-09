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
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val settings = intent.toSftpSettings()
                if (settings == null || !settings.isValid) {
                    Log.w(TAG, "refusing to start with invalid settings")
                    broadcastError("Invalid settings")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // Foreground first: Android requires the notification within a
                // few seconds of startForegroundService, and a server that
                // cannot start should not be advertised as running.
                startForeground(NOTIFICATION_ID, buildNotification(settings, 0))

                val result = SftpServerHolder.engine(this).start(settings)
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "unknown error"
                    Log.e(TAG, "start failed: $message")
                    notifyError(message)
                    broadcastError(message)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                updateNotification()
                broadcastStatus()
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Unconditional: however the service ends, the port must not stay bound.
        runCatching { SftpServerHolder.engine(this).stop() }
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

    private fun buildNotification(settings: SftpSettings, clients: Int): Notification {
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
            .setContentText(notificationText(settings, clients))
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

    private fun notificationText(settings: SftpSettings, clients: Int): String {
        val host = TailnetStatus(this).tailnetAddress() ?: "this device"
        val clientWord = resources.getQuantityString(
            R.plurals.sftp_client_count,
            clients,
            clients
        )
        return "$host:${settings.port} · $clientWord"
    }

    private fun updateNotification() {
        val engine = SftpServerHolder.engine(this)
        val settings = SftpSettingsStore(this).load()
        val notification = buildNotification(settings, engine.connectedClients())
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
