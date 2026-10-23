package io.earthquack.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.earthquack.app.tailscale.TailscaleLoginState
import io.earthquack.app.tailscale.TailscaleNodeMonitor
import kotlinx.coroutines.launch

class PersistentNetworkService : LifecycleService() {
    private val TAG = "PersistentNetworkService"

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        startForegroundWithNotification("Network core starting…")
        TailscaleNodeMonitor.start(applicationContext)
        lifecycleScope.launch {
            TailscaleNodeMonitor.state.collect { state ->
                updateNotification(
                    when (state) {
                        is TailscaleLoginState.Running -> "Tailscale connected (${state.nodeIp})"
                        is TailscaleLoginState.Starting -> "Tailscale connecting…"
                        is TailscaleLoginState.NeedsLogin -> "Tailscale needs sign-in"
                        is TailscaleLoginState.Error -> "Tailscale error: ${state.message}"
                        else -> "Network core running"
                    }
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_NETWORK -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Network core", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun startForegroundWithNotification(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PersistentNetworkService::class.java).setAction(ACTION_STOP_NETWORK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("earthQuack")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    companion object {
        @Volatile var isRunning: Boolean = false
        const val CHANNEL_ID = "persistent_network_channel"
        const val NOTIFICATION_ID = 9001
        const val ACTION_START_NETWORK = "io.earthquack.app.ACTION_START_NETWORK"
        const val ACTION_STOP_NETWORK = "io.earthquack.app.ACTION_STOP_NETWORK"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, PersistentNetworkService::class.java).setAction(ACTION_START_NETWORK)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PersistentNetworkService::class.java).setAction(ACTION_STOP_NETWORK))
        }
    }
}
