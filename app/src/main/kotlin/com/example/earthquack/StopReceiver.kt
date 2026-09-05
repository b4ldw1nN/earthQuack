package com.example.earthquack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that handles the "Stop" action from the persistent
 * foreground-service notification.
 *
 * The notification action sends a broadcast (rather than starting an activity)
 * so that the service can be stopped without bringing any UI to the foreground.
 */
class StopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP_SYNC -> {
                Log.i("StopReceiver", "Stop broadcast received — stopping service")
                context.startService(Intent(context, EarthQuackService::class.java).apply { action = ACTION_STOP_SYNC })
            }
            ACTION_PAUSE_SYNC -> {
                Log.i("StopReceiver", "Pause broadcast received")
                context.startService(Intent(context, EarthQuackService::class.java).apply { action = ACTION_PAUSE_SYNC })
            }
            ACTION_RESUME_SYNC -> {
                Log.i("StopReceiver", "Resume broadcast received")
                context.startService(Intent(context, EarthQuackService::class.java).apply { action = ACTION_RESUME_SYNC })
            }
        }
    }
}
