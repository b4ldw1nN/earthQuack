package com.example.earthquack.ui.sub

import android.view.LayoutInflater
import android.view.ViewGroup
import com.example.earthquack.R
import com.example.earthquack.ServerConfig
import com.example.earthquack.databinding.ActivityBatteryBinding
import com.example.earthquack.state.SystemStatusProvider
import com.example.earthquack.ui.SubScreenActivity

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
            runCatching { startActivity(statusProvider.batterySettingsIntent()) }
        }
    }

    private fun render() {
        val saver = ServerConfig.isBatterySaverEnabled(this)
        body.switchBatterySaver.isChecked = saver
        body.switchPauseScreenOff.isChecked = ServerConfig.isPauseOnScreenOff(this)
        body.switchPauseScreenOff.isEnabled = saver
        body.switchPauseScreenOff.alpha = if (saver) 1f else 0.4f

        val optimised = statusProvider.isIgnoringBatteryOptimisations()
        body.textOptimisation.setText(
            if (optimised) R.string.battery_optimised_yes else R.string.battery_optimised_no
        )
        // Nothing to configure once exempt.
        body.btnConfigureOptimisation.visibility =
            if (optimised) android.view.View.GONE else android.view.View.VISIBLE
    }
}
