package io.earthquack.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.earthquack.app.sftp.SftpSettingsStore
import io.earthquack.app.tailscale.TailscaleNodeMonitor

/**
 * Starts background network services when the device boots, if configured.
 *
 * Specifically starts the embedded Tailscale node if the user configured SFTP
 * to auto-start with Tailscale, or if background services are enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Device boot completed, checking auto-start intent")

        val sftpSettings = SftpSettingsStore(context).load()
        if (sftpSettings.autoStartWithTailscale) {
            Log.i(TAG, "Auto-start SFTP with Tailscale enabled; bringing up Tailscale node")
            TailscaleNodeMonitor.start(context)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
