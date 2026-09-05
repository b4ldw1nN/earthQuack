package com.example.earthquack

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
