package com.example.earthquack

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DesktopCapabilityStore — the last known capabilities of the desktop daemon.
 *
 * Queried whenever the app talks to the desktop (or the user asks for
 * diagnostics) and kept so the mismatch warning survives the moment the
 * query happened: the interesting question is not "is the desktop up right
 * now" but "do the two sides agree on encryption".
 */
object DesktopCapabilityStore {

    private val _capabilities = MutableStateFlow<DesktopCapabilities?>(null)
    val capabilities: StateFlow<DesktopCapabilities?> = _capabilities.asStateFlow()

    /** The last known capabilities, or null when the desktop was never asked. */
    fun last(): DesktopCapabilities? = _capabilities.value

    /**
     * Asks the desktop and remembers the answer.
     *
     * A failed query is not an error to shout about — the desktop being down
     * is a different problem, reported elsewhere. Here it just means "we do
     * not know", and the previous answer is kept.
     */
    suspend fun refresh(context: Context): DesktopCapabilities? {
        val fetched = DesktopCapabilities.fetch(context)
        if (fetched != null) _capabilities.value = fetched
        return fetched
    }

    /**
     * The mismatch between the desktop and this phone, in words, or null when
     * they agree (or the desktop has not been asked yet).
     */
    fun mismatch(context: Context): String? {
        val desktop = _capabilities.value ?: return null
        return desktop.mismatchWith(
            phoneAesEnabled = ServerConfig.isAesEnabled(context),
            phoneKeyFingerprint = DesktopCapabilities.fingerprint(ServerConfig.getAesKey(context)),
        )
    }
}
