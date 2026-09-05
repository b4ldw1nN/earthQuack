package com.example.earthquack

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * FileTransferService
 * ===================
 * Handles file transfers in both directions:
 *
 * UPLOAD (Phone → Desktop):
 *   Started by FileShareActivity when the user shares a file.
 *   Streams file bytes to POST /upload on file-server.py (port 8876).
 *   Shows upload progress notification.
 *
 * DOWNLOAD (Desktop → Phone):
 *   Triggered by EarthQuackService when it receives a "file_ready" SSE event.
 *   Streams GET /download/<id> and saves to Downloads/earthQuack/ via MediaStore.
 *   Shows download progress notification.
 */
class FileTransferService : Service() {

    private val TAG  = "FileTransferService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var notifManager: NotificationManager
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)   // unlimited for large uploads
        .readTimeout(0, TimeUnit.SECONDS)    // unlimited for large downloads
        .build()

    companion object {
        const val ACTION_UPLOAD   = "com.example.earthquack.ACTION_UPLOAD"
        const val ACTION_DOWNLOAD = "com.example.earthquack.ACTION_DOWNLOAD"

        const val EXTRA_FILE_URI   = "extra_file_uri"
        const val EXTRA_FILE_NAME  = "extra_file_name"
        const val EXTRA_FILE_ID    = "extra_file_id"
        const val EXTRA_FILE_SIZE  = "extra_file_size"

        const val NOTIF_CHANNEL_ID = "file_transfer_channel"

        // Notification IDs — use unique IDs per transfer
        private var notifIdCounter = 2000

        fun nextNotifId() = notifIdCounter++

        /** Helper: start a download when SSE fires file_ready. */
        fun startDownload(ctx: Context, fileId: String, fileName: String, fileSize: Long) {
            ctx.startService(Intent(ctx, FileTransferService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_FILE_ID,   fileId)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_FILE_SIZE, fileSize)
            })
        }

        /** Helper: start an upload from folder watch or other internal trigger. */
        fun startUpload(ctx: Context, fileUri: String, fileName: String) {
            ctx.startService(Intent(ctx, FileTransferService::class.java).apply {
                action = ACTION_UPLOAD
                putExtra(EXTRA_FILE_URI,  fileUri)
                putExtra(EXTRA_FILE_NAME, fileName)
            })
        }

    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPLOAD -> {
                val uriStr = intent.getStringExtra(EXTRA_FILE_URI) ?: return stop(startId)
                val name   = intent.getStringExtra(EXTRA_FILE_NAME) ?: "file"
                val uri    = Uri.parse(uriStr)
                val nid    = nextNotifId()
                scope.launch { upload(uri, name, nid, startId) }
            }
            ACTION_DOWNLOAD -> {
                val id   = intent.getStringExtra(EXTRA_FILE_ID)   ?: return stop(startId)
                val name = intent.getStringExtra(EXTRA_FILE_NAME) ?: "file"
                val size = intent.getLongExtra(EXTRA_FILE_SIZE, -1L)
                val nid  = nextNotifId()
                scope.launch { download(id, name, size, nid, startId) }
            }
            else -> stop(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private fun stop(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    // ─── Upload (Phone → Desktop) ────────────────────────────────────────────────

    private suspend fun upload(uri: Uri, name: String, nid: Int, startId: Int) {
        Log.i(TAG, "Upload start: $name")
        showProgress(nid, "↑ $name", 0, -1, upload = true)

        try {
            val file = if (uri.scheme == "file") File(uri.path ?: "") else null
            val stream = if (file != null && file.exists()) {
                java.io.FileInputStream(file)
            } else {
                contentResolver.openInputStream(uri)
            } ?: throw Exception("Cannot open $uri")

            val size = if (file != null && file.exists()) {
                file.length()
            } else {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    c.moveToFirst()
                    val col = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (col >= 0) c.getLong(col) else -1L
                } ?: -1L
            }


            val url = "${ServerConfig.getFileBaseUrl(this@FileTransferService)}/upload"
            val body = streamingBody(stream, size, nid, name)
            val req  = Request.Builder()
                .url(url)
                .addHeader("X-Filename", name)
                .addHeader("X-Origin",   ORIGIN_PHONE)
                .post(body)
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            }

            Log.i(TAG, "Upload done: $name")
            notifyDone(nid, "↑ $name — sent to Desktop", success = true)

            // Delete file after successful send (same as Desktop watch-send-folder.py)
            if (file != null && file.exists() && file.parentFile?.name == "earthQuack-send") {
                val deleted = file.delete()
                Log.i(TAG, "Deleted sent file from watch folder: $name (success=$deleted)")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}")
            notifyDone(nid, "↑ $name — failed: ${e.message}", success = false)
        } finally {
            stopSelf(startId)
        }
    }

    /**
     * Wraps an InputStream in an OkHttp RequestBody that updates the progress
     * notification as bytes are written.
     */
    private fun streamingBody(
        stream: InputStream,
        totalBytes: Long,
        nid: Int,
        name: String,
    ): RequestBody {
        return object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = totalBytes
            override fun writeTo(sink: okio.BufferedSink) {
                val buf = ByteArray(256 * 1024)
                var sent = 0L
                var n: Int
                stream.use { s ->
                    while (s.read(buf).also { n = it } != -1) {
                        sink.write(buf, 0, n)
                        sent += n
                        if (totalBytes > 0) {
                            val pct = (sent * 100 / totalBytes).toInt()
                            showProgress(nid, "↑ $name", pct, totalBytes, upload = true)
                        }
                    }
                }
            }
        }
    }

    // ─── Download (Desktop → Phone) ──────────────────────────────────────────────

    private suspend fun download(
        fileId: String, name: String, totalSize: Long,
        nid: Int, startId: Int,
    ) {
        Log.i(TAG, "Download start: $name id=$fileId")
        showProgress(nid, "↓ $name", 0, totalSize, upload = false)

        try {
            val url = "${ServerConfig.getFileBaseUrl(this@FileTransferService)}/download/$fileId"
            val req = Request.Builder().url(url).get().build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val actualSize = resp.header("Content-Length")?.toLongOrNull() ?: totalSize
                val inputStream = resp.body!!.byteStream()
                saveToDownloads(name, inputStream, actualSize, nid)
            }

            Log.i(TAG, "Download done: $name")
            notifyDone(nid, "↓ $name — saved to Downloads/earthQuack/", success = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            notifyDone(nid, "↓ $name — failed: ${e.message}", success = false)
        } finally {
            stopSelf(startId)
        }
    }

    /**
     * Save downloaded bytes into the public Downloads/earthQuack/ folder.
     * Uses MediaStore on Android 10+ (no storage permission needed).
     */
    private fun saveToDownloads(
        name: String, stream: InputStream,
        totalSize: Long, nid: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, guessMime(name))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/earthQuack")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore insert failed")

            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    copyWithProgress(stream, out, totalSize, nid, name)
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 9 and below — write directly to public Downloads
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val sub = File(dir, "earthQuack").also { it.mkdirs() }
            val out = File(sub, name).outputStream()
            out.use { copyWithProgress(stream, it, totalSize, nid, name) }
        }
    }

    private fun copyWithProgress(
        input: InputStream, output: java.io.OutputStream,
        totalSize: Long, nid: Int, name: String,
    ) {
        val buf   = ByteArray(256 * 1024)
        var read  = 0L
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            output.write(buf, 0, n)
            read += n
            if (totalSize > 0) {
                val pct = (read * 100 / totalSize).toInt()
                showProgress(nid, "↓ $name", pct, totalSize, upload = false)
            }
        }
    }

    private fun guessMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"  -> "image/png"
            "gif"  -> "image/gif"
            "mp4"  -> "video/mp4"
            "mp3"  -> "audio/mpeg"
            "pdf"  -> "application/pdf"
            "zip"  -> "application/zip"
            "apk"  -> "application/vnd.android.package-archive"
            "txt"  -> "text/plain"
            else   -> "application/octet-stream"
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL_ID, "File Transfers", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "File transfer progress" }
        notifManager.createNotificationChannel(ch)
    }

    private fun showProgress(
        nid: Int, title: String, pct: Int, total: Long, upload: Boolean,
    ) {
        val sizeStr = if (total > 0) "  ${formatSize(total)}" else ""
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (pct > 0) "$pct%$sizeStr" else "Starting…$sizeStr")
            .setSmallIcon(if (upload) android.R.drawable.ic_menu_upload
                          else android.R.drawable.ic_menu_save)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notifManager.notify(nid, n)
    }

    private fun notifyDone(nid: Int, text: String, success: Boolean) {
        val n = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(if (success) "Transfer complete" else "Transfer failed")
            .setContentText(text)
            .setSmallIcon(if (success) android.R.drawable.ic_menu_share
                          else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notifManager.notify(nid, n)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024       -> "$bytes B"
        bytes < 1_048_576  -> "${"%.1f".format(bytes / 1024.0)} KB"
        else               -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    }
}
