package com.example.earthquack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.math.min


/**
 * EarthQuackService
 * ====================
 * Foreground service with two concurrent coroutines:
 *
 *  1. sseLoop   — persistent SSE connection → Desktop clipboard updates → Android clipboard (Desktop→Phone)
 *  2. monitorLoop — detects Android clipboard changes → POST to server (Phone→Desktop)
 *
 * ─── Background clipboard access (Android 10+) ───────────────────────────
 * Android blocks clipboard reads from background processes. To work around
 * this legitimately we use SYSTEM_ALERT_WINDOW ("Draw over other apps"):
 *
 *   When OnPrimaryClipChangedListener fires (we know clipboard changed):
 *   1. Try a direct read — works when we have focus or on permissive OEMs
 *   2. If null: add a 1×1 transparent overlay window, wait for it to gain
 *      focus (≤300ms), read clipboard, remove the window immediately.
 *   3. The overlay is invisible to the user (1×1 translucent pixel).
 *
 * This is the standard technique used by every clipboard manager app.
 * It requires the user to grant "Draw over other apps" permission once.
 * If the permission is not granted we fall back to foreground-only reads.
 */
class EarthQuackService : LifecycleService() {

    private val TAG = "ClipSyncService"

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private lateinit var api: ClipboardApi
    private lateinit var syncState: SyncState
    // Short-lived wakelock only held briefly during clipboard POST — no permanent hold
    private var wakeLock: PowerManager.WakeLock? = null

    private var sseJob: Job? = null
    private var monitorJob: Job? = null
    private var folderWatchJob: Job? = null

    @Volatile private var isManuallyPaused = false
    @Volatile private var isScreenOn = true
    @Volatile private var currentSseCall: okhttp3.Call? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    Log.i(TAG, "Screen OFF")
                    if (ServerConfig.isPauseOnScreenOff(this@EarthQuackService)) {
                        updateNotification(SyncStatus.PAUSED)
                        broadcastStatus(SyncStatus.PAUSED)
                        currentSseCall?.cancel()
                        sseJob?.cancel()
                        sseJob = lifecycleScope.launch(Dispatchers.IO) { sseLoop() }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    Log.i(TAG, "Screen ON")
                    if (ServerConfig.isPauseOnScreenOff(this@EarthQuackService)) {
                        updateNotification(SyncStatus.CONNECTING)
                        broadcastStatus(SyncStatus.CONNECTING)
                        sseJob?.cancel()
                        sseJob = lifecycleScope.launch(Dispatchers.IO) { sseLoop() }
                    }
                }
            }
        }
    }

    // ─── Clipboard listener ───────────────────────────────────────────────────

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isSyncPaused()) {
            Log.d(TAG, "ClipChangedListener ignored — sync is paused (screen off / manual)")
            return@OnPrimaryClipChangedListener
        }
        // Launch on Main so overlay operations (WindowManager) stay on UI thread
        lifecycleScope.launch(Dispatchers.Main) {
            if (isSyncPaused()) {
                Log.d(TAG, "ClipChangedListener dropped — became paused before read")
                return@launch
            }
            val text = readClipboardWithOverlayFallback()
            if (text == null) {
                Log.d(TAG, "Listener fired but clipboard read returned null (OS restriction)")
                return@launch
            }
            Log.d(TAG, "ClipChangedListener got: '${text.take(60)}'")
            withContext(Dispatchers.IO) { handleLocalClipboard(text) }
        }
    }

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        clipboardManager  = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        windowManager     = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager      = getSystemService(Context.POWER_SERVICE) as PowerManager
        api               = ClipboardApi(ServerConfig.getBaseUrl(this))
        syncState         = SyncState()
        isScreenOn        = powerManager.isInteractive
        createNotificationChannel()
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // START_STICKY restart may deliver null intent — treat as plain start
        if (intent == null) {
            Log.i(TAG, "onStartCommand: null intent (system restart) — resuming with host=${ServerConfig.getHost(this)}")
        }
        when (intent?.action) {
            "QUERY_STATUS" -> {
                val current = if (isSyncPaused()) SyncStatus.PAUSED else SyncStatus.RUNNING
                broadcastStatus(current)
                return START_STICKY
            }
            ACTION_STOP_SYNC -> {
                Log.i(TAG, "Stop requested")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_SYNC -> {
                Log.i(TAG, "Pause requested")
                isManuallyPaused = true
                currentSseCall?.cancel()
                sseJob?.cancel()
                sseJob = lifecycleScope.launch(Dispatchers.IO) { sseLoop() }
                updateNotification(SyncStatus.PAUSED)
                broadcastStatus(SyncStatus.PAUSED)
                return START_STICKY
            }
            ACTION_RESUME_SYNC -> {
                Log.i(TAG, "Resume requested")
                isManuallyPaused = false
                updateNotification(SyncStatus.CONNECTING)
                broadcastStatus(SyncStatus.CONNECTING)
                // Kick loops to resume immediately
                sseJob?.cancel()
                sseJob = lifecycleScope.launch(Dispatchers.IO) { sseLoop() }
                return START_STICKY
            }
        }
        // (Re)start — refresh API with current host (user may have changed IP or auto-discover)
        var newHost = ServerConfig.getHost(this)
        if (newHost == ServerConfig.DEFAULT_HOST || newHost.isBlank() || newHost == "YOUR_TAILSCALE_IP") {
            lifecycleScope.launch(Dispatchers.IO) {
                val discovered = TailscaleDiscovery.discoverServerIp()
                if (discovered != null) {
                    Log.i(TAG, "Auto-discovered Tailscale clipboard server IP: $discovered")
                    withContext(Dispatchers.Main) {
                        ServerConfig.setHost(this@EarthQuackService, discovered)
                        if (::api.isInitialized) {
                            try { api.shutdown() } catch (_: Exception) {}
                        }
                        api = ClipboardApi(ServerConfig.getBaseUrl(this@EarthQuackService))
                    }
                }
            }
        }
        if (::api.isInitialized) {
            try { api.shutdown() } catch (_: Exception) {}
        }
        api = ClipboardApi(ServerConfig.getBaseUrl(this))
        isScreenOn = powerManager.isInteractive
        Log.i(TAG, "Starting foreground service (host=$newHost)")
        isManuallyPaused = false
        // Must call startForeground even if already foreground (updates notification)
        if (isSyncPaused()) {
            startForegroundWithNotification(SyncStatus.PAUSED)
        } else {
            startForegroundWithNotification(SyncStatus.CONNECTING)
        }
        startCoroutines()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service stopping")
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        sseJob?.cancel()
        monitorJob?.cancel()
        folderWatchJob?.cancel()
        try { clipboardManager.removePrimaryClipChangedListener(clipChangedListener) } catch (_: Exception) {}

        releaseWakeLock()
        api.shutdown()
        broadcastStatus(SyncStatus.STOPPED)
        super.onDestroy()
    }

    // ─── Wake lock — short-lived only during network ops ─────────────────────

    /** Acquire a short 10s wake lock so a clipboard POST isn't killed mid-flight. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "earthQuack:WakeLock")
            .also { it.acquire(10_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun isSyncPaused(): Boolean {
        if (isManuallyPaused) return true
        if (!ServerConfig.isPauseOnScreenOff(this)) return false
        return !isScreenOn && !powerManager.isInteractive
    }

    // ─── Coroutines ───────────────────────────────────────────────────────────

    private fun startCoroutines() {
        sseJob?.cancel()
        monitorJob?.cancel()
        folderWatchJob?.cancel()

        sseJob        = lifecycleScope.launch(Dispatchers.IO)   { sseLoop() }
        monitorJob    = lifecycleScope.launch(Dispatchers.Main) { clipboardMonitorLoop() }
        folderWatchJob = lifecycleScope.launch(Dispatchers.IO)  { folderWatchLoop() }
    }


    // ─── SSE loop — Desktop → Phone ───────────────────────────────────────────────

    private suspend fun sseLoop() {
        var delayMs = SSE_RECONNECT_INITIAL_DELAY_MS
        while (currentCoroutineContext().isActive) {
            // Battery saver: pause SSE while screen off / manually paused
            if (isSyncPaused()) {
                Log.d(TAG, "SSE paused (screen off / manual) — sleeping 15s")
                // Use cancellable delay so screen-on cancels quickly via job cancel
                try { delay(15_000) } catch (_: CancellationException) { delayMs = SSE_RECONNECT_INITIAL_DELAY_MS; continue }
                continue
            }
            broadcastStatus(SyncStatus.CONNECTING)
            updateNotification(SyncStatus.CONNECTING)
            try {
                connectSse()
                delayMs = SSE_RECONNECT_INITIAL_DELAY_MS      // EOF → reset backoff
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "SSE error: ${e.message}")
                broadcastStatus(SyncStatus.ERROR, "SSE: ${e.message}")
                updateNotification(SyncStatus.ERROR)
            }
            if (currentCoroutineContext().isActive) {
                // When battery saver is on and screen was off, don't hammer reconnect
                val effectiveDelay = if (!isScreenOn && ServerConfig.isBatterySaverEnabled(this@EarthQuackService)) 15_000L else delayMs
                Log.i(TAG, "SSE reconnecting in ${effectiveDelay}ms")
                try { delay(effectiveDelay) } catch (_: CancellationException) { break }
                if (isScreenOn || !ServerConfig.isBatterySaverEnabled(this@EarthQuackService)) {
                    delayMs = min((delayMs * SSE_RECONNECT_BACKOFF_FACTOR).toLong(), SSE_RECONNECT_MAX_DELAY_MS)
                }
            }
        }
    }

    private suspend fun connectSse() = withContext(Dispatchers.IO) {
        val call = api.sseClient.newCall(api.buildSseRequest())
        currentSseCall = call
        val job  = coroutineContext[Job]
        val cancel = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                if (isSyncPaused()) {
                    Log.i(TAG, "SSE connected but immediately paused — closing")
                    return@use
                }
                broadcastStatus(SyncStatus.RUNNING)
                updateNotification(SyncStatus.RUNNING)
                Log.i(TAG, "SSE connected")

                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8))
                var eventType = ""
                var dataBuffer = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    if (isSyncPaused()) {
                        Log.i(TAG, "SSE paused mid-stream — closing connection")
                        break
                    }
                    val l = line!!
                    when {
                        l.startsWith(":")        -> Unit   // keepalive comment
                        l.startsWith("event:")   -> eventType = l.removePrefix("event:").trim()
                        l.startsWith("data:")    -> dataBuffer.append(l.removePrefix("data:").trim())
                        l.isEmpty() -> {
                            val data = dataBuffer.toString(); dataBuffer = StringBuilder()
                            when (eventType) {
                                "clipboard"  -> if (data.isNotBlank()) handleSseEvent(data)
                                "file_ready" -> if (data.isNotBlank()) handleFileReadyEvent(data)
                                "open_url"   -> if (data.isNotBlank()) handleOpenUrlEvent(data)
                            }
                            eventType = ""
                        }

                    }
                }
                Log.i(TAG, "SSE EOF — will reconnect")
            }
        } finally {
            cancel?.dispose()
            if (currentSseCall === call) currentSseCall = null
        }
    }

    private suspend fun handleSseEvent(jsonData: String) {
        try {
            val json    = JSONObject(jsonData)
            val origin  = json.optString("origin")
            val rawText = json.optString("clipboard")
            val version = json.optInt("version", -1)

            if (origin != ORIGIN_DESKTOP) return
            if (rawText.isBlank()) return
            // Decrypt first so SyncState dedup compares plain text (prevents echo loop when AES is on)
            val text = CryptoUtil.unwrapIfNeeded(rawText, ServerConfig.getAesKey(this), ServerConfig.isAesEnabled(this))
            if (text.isBlank()) return
            if (!syncState.tryClaimRemoteEvent(text, version)) return

            withContext(Dispatchers.Main) {
                val clip = android.content.ClipData.newPlainText("earthQuack", text)
                clipboardManager.setPrimaryClip(clip)
            }
            broadcastStatus(SyncStatus.RUNNING, "← $text")
            Log.i(TAG, "Applied remote clipboard v$version: '${text.take(60)}'")
        } catch (e: Exception) {
            Log.e(TAG, "SSE parse error: ${e.message}")
        }
    }

    /** Called when the SSE stream delivers a file_ready event from file-server.py. */
    private fun handleFileReadyEvent(jsonData: String) {
        if (isSyncPaused()) {
            Log.i(TAG, "file_ready dropped — sync paused (screen off), will sync on resume")
            return
        }
        try {
            val json  = JSONObject(jsonData)
            val id    = json.optString("id")
            val name  = json.optString("name", "file")
            val size  = json.optLong("size", -1L)
            if (id.isBlank()) return
            Log.i(TAG, "file_ready: $name ($size bytes) id=$id")
            FileTransferService.startDownload(this, id, name, size)
        } catch (e: Exception) {
            Log.e(TAG, "file_ready parse error: ${e.message}")
        }
    }

    /** Called when the SSE stream delivers an open_url event from Desktop. */
    private fun handleOpenUrlEvent(jsonData: String) {
        try {
            val json = JSONObject(jsonData)
            val url  = json.optString("url")
            if (url.isBlank()) return
            Log.i(TAG, "Opening URL on Android: $url")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "open_url error: ${e.message}")
        }
    }



    // ─── Clipboard monitor — Phone → Desktop ─────────────────────────────────────

    private suspend fun clipboardMonitorLoop() {
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener)
        Log.i(TAG, "Clipboard listener registered")
        try {
            while (currentCoroutineContext().isActive) {
                // Throttle or skip polling when paused / screen off
                if (isSyncPaused()) {
                    delay(15_000)
                    continue
                }
                val interval = if (!isScreenOn && ServerConfig.isBatterySaverEnabled(this@EarthQuackService))
                    CLIPBOARD_POLL_THROTTLED_MS else CLIPBOARD_POLL_INTERVAL_MS
                    delay(interval)
                if (isSyncPaused()) continue
                // Polling MUST use overlay fallback on Phone — direct read
                // is blocked in background (Android 10+). Removing overlay
                // broke Phone→Desktop (you had to return to app). The 1×1
                // overlay triggers a brief system toast "displaying over
                // other apps" — we keep it but the interval is the
                // CLIPBOARD_POLL_INTERVAL_MS (1.5s, throttled 30s on
                // screen-off) so background copies are caught within
                // ~1.5s without returning. The listener path also uses
                // overlay, so worst-case flash is ~1.5s.
                val text = readClipboardWithOverlayFallback()
                if (text != null) withContext(Dispatchers.IO) { handleLocalClipboard(text) }
            }
        } finally {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener)
            Log.i(TAG, "Clipboard listener unregistered")
        }
    }

    // ─── Clipboard reading ────────────────────────────────────────────────────

    /**
     * Try direct clipboard read first; if null (OS background restriction),
     * fall back to the overlay-focus trick if SYSTEM_ALERT_WINDOW is granted.
     *
     * The overlay trick:
     *   - Adds a 1×1 transparent window via WindowManager
     *   - Waits for onWindowFocusChanged(true) — that focus gives us clipboard access
     *   - Reads the clipboard, removes the window
     *   The window is invisible (1px translucent) and removed in <300ms.
     */
    private suspend fun readClipboardWithOverlayFallback(): String? {
        val direct = readClipboardText()
        if (direct != null) return direct

        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "No SYSTEM_ALERT_WINDOW — cannot read clipboard in background")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { 
                alpha = 0.01f 
                flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            }

            val view = object : View(this@EarthQuackService) {
                override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                    super.onWindowFocusChanged(hasWindowFocus)
                    if (hasWindowFocus && cont.isActive) {
                        val text = readClipboardText()
                        Log.d(TAG, "Overlay focus read: ${text?.take(40)}")
                        removeOverlaySafe(this)
                        cont.resume(text)
                    }
                }
            }

            try {
                windowManager.addView(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Overlay add failed: ${e.message}")
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            // Timeout — don't leave overlay dangling
            lifecycleScope.launch(Dispatchers.Main) {
                delay(400)
                if (cont.isActive) {
                    Log.d(TAG, "Overlay focus timeout")
                    removeOverlaySafe(view)
                    cont.resume(null)
                }
            }

            cont.invokeOnCancellation { removeOverlaySafe(view) }
        }
    }

    private fun removeOverlaySafe(view: View) {
        try { windowManager.removeView(view) } catch (_: Exception) {}
    }

    private fun readClipboardText(): String? {
        return try {
            val clip = clipboardManager.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "Clipboard read blocked: ${e.message}")
            null
        }
    }

    private suspend fun handleLocalClipboard(text: String) {
        if (isSyncPaused()) {
            Log.d(TAG, "handleLocalClipboard dropped — sync paused (screen off)")
            return
        }
        if (!syncState.tryClaimLocalChange(text)) return
        if (isSyncPaused()) return
        val toSend = CryptoUtil.wrapIfNeeded(text, ServerConfig.getAesKey(this), ServerConfig.isAesEnabled(this))
        Log.i(TAG, "Posting to server: '${text.take(60)}'${if (toSend != text) " [AES]" else ""}")
        acquireWakeLock()
        try {
            val version = api.postClipboard(toSend)
            if (version >= 0) {
                broadcastStatus(SyncStatus.RUNNING, "→ $text")
                Log.i(TAG, "POST ok, version=$version")
            } else {
                Log.w(TAG, "POST failed")
            }
        } finally {
            // Wake lock auto-releases after 10s; explicit release not needed
        }
    }

    // ─── Folder watch — auto-upload Phone→Desktop ────────────────────────────────
    //
    //  Watches public  Downloads/earthQuack-send/  folder using direct
    //  file system polling (bypasses MediaStore owner package restrictions).
    //  Any new file dropped here by Google Files or any file manager will be
    //  uploaded to file-server.py on Desktop.

    private suspend fun folderWatchLoop() {
        val downloadDir = try {
            @Suppress("DEPRECATION")
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
        } catch (e: Exception) {
            Log.w(TAG, "getExternalStoragePublicDirectory failed: ${e.message}")
            null
        }
        if (downloadDir == null) {
            Log.w(TAG, "Folder watch disabled — no public Downloads dir (scoped storage)")
            return
        }
        val sendDir = File(downloadDir, "earthQuack-send")
        try {
            if (!sendDir.exists()) sendDir.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create watch dir ${sendDir.absolutePath}: ${e.message}")
            return
        }
        Log.i(TAG, "Folder watch listening on: ${sendDir.absolutePath}")

        val seenFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        val checkFile: (File) -> Unit = { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                if (!seenFiles.contains(file.name)) {
                    seenFiles.add(file.name)
                    Log.i(TAG, "Folder watch upload trigger: '${file.name}' (${file.length()} bytes)")
                    val uri = Uri.fromFile(file)
                    FileTransferService.startUpload(this@EarthQuackService, uri.toString(), file.name)
                }
            }
        }


        // FileObserver for sub-second instant response — respects pause
        val obs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(sendDir, CREATE or CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (isSyncPaused()) return
                    if (path != null) checkFile(File(sendDir, path))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(sendDir.absolutePath, CREATE or CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (isSyncPaused()) return
                    if (path != null) checkFile(File(sendDir, path))
                }
            }
        }
        obs.startWatching()

        try {
            while (currentCoroutineContext().isActive) {
                if (isSyncPaused()) {
                    delay(15_000)
                    continue
                }
                try {
                    sendDir.listFiles()?.forEach { checkFile(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Folder watch error: ${e.message}")
                }
                val pollMs = if (!isScreenOn && ServerConfig.isBatterySaverEnabled(this@EarthQuackService)) 30_000L else 1_000L
                delay(pollMs)
            }
        } finally {
            obs.stopWatching()
        }
    }






    // ─── Notification ─────────────────────────────────────────────────────────



    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "earthQuack", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Clipboard synchronisation status" }
        )
    }

    private fun buildNotification(status: SyncStatus): Notification {
        val host = ServerConfig.getHost(this)
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, StopReceiver::class.java).apply { action = ACTION_STOP_SYNC },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, StopReceiver::class.java).apply {
                action = if (status == SyncStatus.PAUSED) ACTION_RESUME_SYNC else ACTION_PAUSE_SYNC
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val isPaused = status == SyncStatus.PAUSED || isManuallyPaused
        val statusText = when (status) {
            SyncStatus.RUNNING    -> "Active ($host:$SERVER_PORT)"
            SyncStatus.CONNECTING -> "Connecting to $host:$SERVER_PORT…"
            SyncStatus.PAUSED     -> if (isManuallyPaused) "Paused — tap Resume" else "Paused (screen off — battery saver)"
            SyncStatus.ERROR      -> "Connection error — retrying…"
            SyncStatus.STOPPED    -> "Stopped"
        }
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("earthQuack")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Pause/Resume action
        if (status != SyncStatus.STOPPED) {
            val label = if (isPaused) "Resume" else "Pause"
            val icon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            builder.addAction(icon, label, pauseIntent)
        }
        return builder.build()
    }

    private fun startForegroundWithNotification(status: SyncStatus) {
        val n = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(status: SyncStatus) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun broadcastStatus(status: SyncStatus, lastSync: String? = null) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status.name)
            if (lastSync != null) putExtra(EXTRA_LAST_SYNC, lastSync)
            setPackage(packageName)
        })
    }
}
