package com.example.clipboardsync

// ────────────────────────────────────────────────────────────────────────────
// Network configuration — see ServerConfig.kt for the runtime-configurable host
// ────────────────────────────────────────────────────────────────────────────

/** Default Tailscale IP (fallback; actual host is in SharedPreferences via ServerConfig). */
const val SERVER_HOST      = "YOUR_TAILSCALE_IP"
const val SERVER_PORT      = 8765
@Deprecated("Use ServerConfig.getBaseUrl(context) for user-configurable host")
const val SERVER_BASE_URL  = "http://$SERVER_HOST:$SERVER_PORT"

/** File transfer server (file-server.py) — separate process, port 8766. */
const val FILE_SERVER_PORT     = 8766
@Deprecated("Use ServerConfig.getFileBaseUrl(context) for user-configurable host")
const val FILE_SERVER_BASE_URL = "http://$SERVER_HOST:$FILE_SERVER_PORT"

/** This device's origin tag, as expected by the server. */
const val ORIGIN_PHONE = "phone"
const val ORIGIN_DESKTOP = "desktop"

// ────────────────────────────────────────────────────────────────────────────
// Notification channel / IDs
// ────────────────────────────────────────────────────────────────────────────

const val NOTIFICATION_CHANNEL_ID = "clipboard_sync_channel"
const val NOTIFICATION_ID = 1001

// ────────────────────────────────────────────────────────────────────────────
// Intent actions (service ↔ activity ↔ notification receiver)
// ────────────────────────────────────────────────────────────────────────────

const val ACTION_START_SYNC  = "com.example.clipboardsync.ACTION_START_SYNC"
const val ACTION_STOP_SYNC   = "com.example.clipboardsync.ACTION_STOP_SYNC"
const val ACTION_STATUS_UPDATE = "com.example.clipboardsync.ACTION_STATUS_UPDATE"

// ────────────────────────────────────────────────────────────────────────────
// Intent extras
// ────────────────────────────────────────────────────────────────────────────

const val EXTRA_STATUS      = "extra_status"
const val EXTRA_LAST_SYNC   = "extra_last_sync"

// ────────────────────────────────────────────────────────────────────────────
// HTTP timeouts
// ────────────────────────────────────────────────────────────────────────────

/** Connect/write timeout for standard requests (GET /clipboard, POST /clipboard). */
const val HTTP_CONNECT_TIMEOUT_MS  = 10_000L
const val HTTP_WRITE_TIMEOUT_MS    = 10_000L
/** Standard read timeout for non-SSE requests. */
const val HTTP_READ_TIMEOUT_MS     = 15_000L
/**
 * SSE read timeout MUST be 0 (infinite); the server streams indefinitely.
 * A nonzero timeout would kill the connection after the last keepalive.
 */
const val SSE_READ_TIMEOUT_MS      = 0L

// ────────────────────────────────────────────────────────────────────────────
// Clipboard polling interval (belt-and-suspenders alongside the listener)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Android 10+ blocks clipboard access for background apps.
 * OnPrimaryClipChangedListener fires reliably only when the app has focus.
 * We also poll at a short interval so that clipboard changes made immediately
 * after the user returns to the foreground are not missed.
 * The OS will block actual clipboard reads while we are backgrounded — this
 * polling loop only does meaningful work when the app has window focus.
 */
const val CLIPBOARD_POLL_INTERVAL_MS = 1_500L

// ────────────────────────────────────────────────────────────────────────────
// SSE reconnect backoff
// ────────────────────────────────────────────────────────────────────────────

const val SSE_RECONNECT_INITIAL_DELAY_MS = 2_000L
const val SSE_RECONNECT_MAX_DELAY_MS     = 60_000L
const val SSE_RECONNECT_BACKOFF_FACTOR   = 2.0

// ────────────────────────────────────────────────────────────────────────────
// Battery saver
// ────────────────────────────────────────────────────────────────────────────

/** Extra to pause/resume without fully stopping the service. */
const val ACTION_PAUSE_SYNC  = "com.example.clipboardsync.ACTION_PAUSE_SYNC"
const val ACTION_RESUME_SYNC = "com.example.clipboardsync.ACTION_RESUME_SYNC"

/** Status for paused state. */
const val EXTRA_IS_PAUSED    = "extra_is_paused"

/** When screen off and battery saver is on, how long before we consider idle. */
const val SCREEN_OFF_IDLE_DELAY_MS = 30_000L
/** Poll slower when battery saver throttles (screen off but not fully paused). */
const val CLIPBOARD_POLL_THROTTLED_MS = 30_000L
