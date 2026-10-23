package io.earthquack.app.tailscale

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional DPI bypass toggle. Attempts to load the bundled native library;
 * degrades gracefully when it is absent.
 */
object DpiBypass {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private const val PREFS = "earthquack_dpi"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        if (!bundled && value) {
            prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
            _enabled.value = false
            return
        }
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) start(context) else stop()
    }

    /** Whether the native DPI-bypass library is shipped with this build. */
    val bundled: Boolean = false

    /** Human-readable reason the toggle cannot be enabled. */
    val unavailableReason: String = "DPI bypass native library is not bundled in this build."

    fun start(context: Context): Boolean {
        // The native library is not shipped, so enabling is a no-op that
        // reports failure instead of silently flipping the toggle.
        _enabled.value = false
        return false
    }

    fun stop() {
        _enabled.value = false
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}