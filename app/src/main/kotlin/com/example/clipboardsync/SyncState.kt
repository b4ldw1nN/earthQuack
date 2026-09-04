package com.example.clipboardsync

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe synchronisation state.
 *
 * We track three independent pieces of state to prevent clipboard echo loops:
 *
 *  1. [lastLocalText]   — the most recent text WE pushed to the server (origin=phone).
 *                         If the clipboard matches this, we don't re-send.
 *
 *  2. [lastRemoteText]  — the most recent text we RECEIVED from the server (origin=desktop)
 *                         and wrote into the Android clipboard.
 *                         If the clipboard listener fires with this value, we know it is
 *                         our own remote-write echoing back, so we skip the POST.
 *
 *  3. [lastServerVersion] — the version number of the last SSE event we processed.
 *                           Used to detect duplicate events in case of reconnect.
 *
 * All state is protected by a [Mutex] so reads and writes from different coroutines
 * are serialised without blocking the thread.
 */
class SyncState {
    private val mutex = Mutex()

    private var lastLocalText: String? = null
    private var lastRemoteText: String? = null
    private var lastServerVersion: Int = -1

    /**
     * Called just before we POST a local clipboard change to the server.
     * Returns true if [text] is genuinely new (different from the last local
     * text we sent AND different from the last remote text we applied).
     * If true, also updates [lastLocalText] to [text].
     */
    suspend fun tryClaimLocalChange(text: String): Boolean = mutex.withLock {
        if (text.isBlank()) {
            Log.d(TAG, "tryClaimLocalChange: skipping blank text")
            return false
        }
        if (text == lastLocalText) {
            Log.d(TAG, "tryClaimLocalChange: same as lastLocal, skip")
            return false
        }
        if (text == lastRemoteText) {
            Log.d(TAG, "tryClaimLocalChange: matches lastRemote (echo), skip")
            return false
        }
        lastLocalText = text
        Log.d(TAG, "tryClaimLocalChange: NEW local → '$text'")
        return true
    }

    /**
     * Called when we receive an SSE event from the server.
     * Returns true if [version] is new and [text] should be applied to the
     * Android clipboard. Also updates [lastRemoteText] and [lastServerVersion].
     */
    suspend fun tryClaimRemoteEvent(text: String, version: Int): Boolean = mutex.withLock {
        if (version <= lastServerVersion) {
            Log.d(TAG, "tryClaimRemoteEvent: version $version already seen (last=$lastServerVersion), skip")
            return false
        }
        if (text == lastRemoteText) {
            Log.d(TAG, "tryClaimRemoteEvent: same as lastRemote, skip duplicate")
            lastServerVersion = version
            return false
        }
        lastRemoteText = text
        lastServerVersion = version
        Log.d(TAG, "tryClaimRemoteEvent: NEW remote v$version → '$text'")
        return true
    }

    /**
     * Returns the last text applied from the server, used by the clipboard
     * listener to detect echo.
     */
    suspend fun getLastRemoteText(): String? = mutex.withLock { lastRemoteText }

    /** Snapshot for logging/debugging. */
    suspend fun snapshot(): String = mutex.withLock {
        "SyncState(local='$lastLocalText', remote='$lastRemoteText', version=$lastServerVersion)"
    }

    companion object {
        private const val TAG = "SyncState"
    }
}
