package io.earthquack.app.state

import android.content.Context
import android.content.Intent
import android.util.Log
import io.earthquack.app.AppStatus

object BatteryOptimizationManager {
    private const val TAG = "BatteryOptimizationManager"
    const val SOURCE_BATTERY = "battery_optimization"

    fun checkAndPublish(context: Context) {
        val appContext = context.applicationContext
        val provider = SystemStatusProvider(appContext)
        val isExempt = provider.isIgnoringBatteryOptimisations()

        if (isExempt) {
            AppStatus.publish(SOURCE_BATTERY, null)
            return
        }

        AppStatus.publish(
            SOURCE_BATTERY,
            AppStatus.Message(
                text = "Battery optimization is active. Background sync & Tailscale may be stopped when the screen is off.",
                severity = AppStatus.Severity.Warning,
                actionLabel = "Fix",
                action = { launchSettings(appContext, provider) }
            )
        )
    }

    private fun launchSettings(context: Context, provider: SystemStatusProvider) {
        val intents = listOf(
            provider.batterySettingsIntent(),
            provider.batteryOptimizationListIntent(),
            provider.appDetailsSettingsIntent()
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed launching battery intent: $intent", e)
            }
        }
    }
}
