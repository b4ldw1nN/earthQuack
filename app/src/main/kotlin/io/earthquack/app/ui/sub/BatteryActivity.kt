package io.earthquack.app.ui.sub

import android.view.LayoutInflater
import android.view.ViewGroup
import io.earthquack.app.R
import io.earthquack.app.ServerConfig
import io.earthquack.app.databinding.ActivityBatteryBinding
import io.earthquack.app.state.SystemStatusProvider
import io.earthquack.app.ui.SubScreenActivity

/**
 * Battery and background behaviour.
 *
 * The two toggles are the pre-existing [ServerConfig] settings, relocated. The
 * dependency between them is preserved: "Pause when screen is off" only makes
 * sense when battery saver is on, so it is disabled otherwise rather than
 * silently having no effect.
 *
 * The battery-optimisation row is a system grant, not a preference, so it gets a
 * button that opens settings instead of a switch. It is included because on
 * aggressive OEM builds (Vivo/FuntouchOS especially) a non-exempt foreground
 * service is killed after a while, and that failure looks like random breakage.
 */
class BatteryActivity : SubScreenActivity() {

    override val titleRes = R.string.settings_battery
    override val subtitleRes = R.string.settings_battery_sub

    private lateinit var body: ActivityBatteryBinding
    private lateinit var statusProvider: SystemStatusProvider

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        statusProvider = SystemStatusProvider(this)
        wire()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) {
            render()
        }
    }

    override fun inflateBody(inflater: LayoutInflater, container: ViewGroup?) {
        body = ActivityBatteryBinding.inflate(inflater, container, false)
        container?.removeAllViews()
        container?.addView(body.root)
    }

    private fun wire() {
        body.switchBatterySaver.setOnCheckedChangeListener { _, checked ->
            ServerConfig.setBatterySaverEnabled(this, checked)
            body.switchPauseScreenOff.isEnabled = checked
            body.switchPauseScreenOff.alpha = if (checked) 1f else 0.4f
        }
        body.switchPauseScreenOff.setOnCheckedChangeListener { _, checked ->
            ServerConfig.setPauseOnScreenOff(this, checked)
        }
        body.btnConfigureOptimisation.setOnClickListener {
            openBatterySettings()
        }
    }

    private fun openBatterySettings() {
        val intents = mutableListOf<android.content.Intent>()
        if (!statusProvider.isIgnoringBatteryOptimisations()) {
            intents.add(statusProvider.batterySettingsIntent())
        }
        intents.add(statusProvider.batteryOptimizationListIntent())
        intents.add(statusProvider.appDetailsSettingsIntent())

        for (intent in intents) {
            try {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                android.util.Log.i("BatteryActivity", "Attempting to launch battery settings intent: $intent")
                startActivity(intent)
                return
            } catch (e: Exception) {
                android.util.Log.w("BatteryActivity", "Failed to launch intent: $intent", e)
            }
        }
        android.widget.Toast.makeText(this, "Unable to open battery settings", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val saver = ServerConfig.isBatterySaverEnabled(this)
        body.switchBatterySaver.isChecked = saver
        body.switchPauseScreenOff.isChecked = ServerConfig.isPauseOnScreenOff(this)
        body.switchPauseScreenOff.isEnabled = saver
        body.switchPauseScreenOff.alpha = if (saver) 1f else 0.4f

        val optimised = statusProvider.isIgnoringBatteryOptimisations()
        body.textOptimisation.text = if (optimised) {
            "Exempt (Unrestricted) — Android allows earthQuack to run in the background."
        } else {
            "Restricted (Optimized) — Android may stop background services when idle."
        }
        body.btnConfigureOptimisation.text = if (optimised) {
            "Configure Battery Settings"
        } else {
            "Request Unrestricted Background"
        }
        body.btnConfigureOptimisation.visibility = android.view.View.VISIBLE
    }
}
