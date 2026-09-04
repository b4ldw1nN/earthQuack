package com.example.clipboardsync

/**
 * Sync status reported by the service to the UI.
 */
enum class SyncStatus {
    STOPPED,
    CONNECTING,
    RUNNING,
    PAUSED,
    ERROR
}
