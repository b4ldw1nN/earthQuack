package com.example.earthquack.state

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.earthquack.EarthQuackService
import com.example.earthquack.ServerConfig
import com.example.earthquack.SERVER_PORT

/**
 * Snapshot of everything the Home and Services screens display about the
 * clipboard service.
 *
 * ## Honesty rules encoded here
 *
 * - [isConfigured] is false when the host is still the shipped placeholder
 *   `YOUR_TAILSCALE_IP`. Showing "100.x.x.x" or even the literal placeholder as
 *   if it were a real address would be worse than saying "Not configured".
 * - [isServiceRunning] is an actual [ActivityManager] query, not a cached flag.
 *   The app process can be killed while the service lives, and vice versa, so
 *   an in-memory boolean drifts.
 * - Permission states are queried, not inferred from whether we have asked.
 */
data class SystemStatus(
    val syncState: SyncStateLabel,
    val isServiceRunning: Boolean,
    val host: String?,
    val port: Int,
    val notificationsGranted: Boolean,
    val overlayGranted: Boolean,
    val batteryOptimised: Boolean,
    val batterySaverEnabled: Boolean,
    val pauseOnScreenOff: Boolean
) {
    /**
     * True when a real host has been chosen.
     *
     * Gates anything that would otherwise print an address. Without this, a
     * fresh install shows "YOUR_TAILSCALE_IP:8875", which is a placeholder
     * masquerading as configuration.
     */
    val isConfigured: Boolean
        get() = host != null && host.isNotBlank() && !ServerConfig.isPlaceholderHost(host)

    /** "100.92.160.31:8875", or null when unconfigured. */
    val endpoint: String?
        get() = if (isConfigured) "$host:$port" else null

    /** True when clipboard sync is actively working right now. */
    val isConnected: Boolean
        get() = syncState == SyncStateLabel.RUNNING && isServiceRunning

    /**
     * Permissions that are requested but not yet granted.
     *
     * Not a count of "problems": notifications are only needed to run the
     * foreground service, and the overlay only while backgrounding. The
     * Permissions screen lists them individually for that reason.
     */
    val missingPermissions: List<PermissionKind>
        get() = buildList {
            if (!notificationsGranted) add(PermissionKind.NOTIFICATIONS)
            if (!overlayGranted) add(PermissionKind.OVERLAY)
        }
}

/** The clipboard service's state, named for display. */
enum class SyncStateLabel(val label: String) {
    RUNNING("Running"),
    STOPPED("Stopped"),
    CONNECTING("Connecting…"),
    PAUSED("Paused"),
    ERROR("Error"),
    UNKNOWN("Unknown");

    companion object {
        /** Maps the service's `SyncStatus` name, tolerating an unknown value. */
        fun fromName(name: String?): SyncStateLabel =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** Permissions EarthQuack actually uses. Nothing speculative. */
enum class PermissionKind(val label: String, val why: String) {
    NOTIFICATIONS(
        "Notifications",
        "Required to run the clipboard sync in the foreground."
    ),
    OVERLAY(
        "Draw over other apps",
        "Lets clipboard sync keep working while other apps are in the foreground."
    )
}

/**
 * Reads live system state.
 *
 * Deliberately synchronous and cheap: every call here is an in-process query
 * or a `Settings` lookup, with no I/O and no network. That makes it safe to call
 * from `onResume`, which is when the answers actually change -- the user may
 * have granted a permission in system settings while the app was backgrounded.
 */
class SystemStatusProvider(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @param lastKnownState the most recent state broadcast by the service, or
     *   [SyncStateLabel.UNKNOWN] if none has arrived yet.
     *
     * The broadcast is the only authoritative source for *why* the service is
     * in a state. [isServiceRunning] alone cannot distinguish running from
     * retrying with backoff.
     */
    fun read(lastKnownState: SyncStateLabel = SyncStateLabel.UNKNOWN): SystemStatus {
        val running = isServiceRunning()
        return SystemStatus(
            // A dead service is STOPPED regardless of the last broadcast:
            // leaving it showing "Running" after the process died is a lie the
            // user would act on.
            syncState = if (running) lastKnownState else SyncStateLabel.STOPPED,
            isServiceRunning = running,
            host = ServerConfig.getHost(appContext),
            port = SERVER_PORT,
            notificationsGranted = hasNotificationPermission(),
            overlayGranted = canDrawOverlays(),
            batteryOptimised = isIgnoringBatteryOptimisations(),
            batterySaverEnabled = ServerConfig.isBatterySaverEnabled(appContext),
            pauseOnScreenOff = ServerConfig.isPauseOnScreenOff(appContext)
        )
    }

    /**
     * Whether the clipboard service is alive.
     *
     * [ActivityManager.getRunningServices] is deprecated and returns only this
     * app's services since Android 5, which is exactly the scope needed here.
     * It is still the only API available to a non-privileged app for this
     * question; the alternative -- asking the service and timing out -- cannot
     * distinguish "not running" from "unresponsive".
     */
    @Suppress("DEPRECATION")
    fun isServiceRunning(): Boolean {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return runCatching {
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == EarthQuackService::class.java.name
            }
        }.getOrDefault(false)
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Notifications need no runtime grant before API 33.
            true
        }

    /**
     * Whether the app is exempt from battery optimisation.
     *
     * Reported because Vivo/FuntouchOS in particular will kill a foreground
     * service that is not exempt, so this is the single most common cause of
     * "it worked for an hour then stopped" and is worth a dedicated row.
     */
    fun isIgnoringBatteryOptimisations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    /** Intent that opens this app's battery-optimisation settings. */
    fun batterySettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .setData(Uri.parse("package:${appContext.packageName}"))
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
}
