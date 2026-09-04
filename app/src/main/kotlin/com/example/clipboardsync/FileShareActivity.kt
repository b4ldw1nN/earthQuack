package com.example.clipboardsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * FileShareActivity
 * =================
 * Transparent Activity registered as an Android share-sheet target for *all* file types.
 * When the user shares a file from any app (Gallery, Files, Chrome, WhatsApp…):
 *
 *   Share sheet → FileShareActivity (transparent, no UI) → FileTransferService (upload)
 *
 * The activity finishes immediately after handing the intent to FileTransferService,
 * so the user sees no interface — just a toast and a progress notification.
 *
 * Supports:
 *   ACTION_SEND          — single file
 *   ACTION_SEND_MULTIPLE — multiple files (each uploaded independently)
 */
class FileShareActivity : Activity() {

    private val TAG = "FileShareActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        when (action) {
            Intent.ACTION_SEND -> handleSingle(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleMultiple(intent)
            else -> {
                Log.w(TAG, "Unexpected action: $action")
                finish()
            }
        }
    }

    private fun handleSingle(intent: Intent) {
        val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) {
            Toast.makeText(this, "No file to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        startUpload(uri)
        finish()
    }

    private fun handleMultiple(intent: Intent) {
        val uris: List<Uri> = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "No files to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        uris.forEach { startUpload(it) }
        Toast.makeText(this, "Sending ${uris.size} file(s) to Desktop…", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startUpload(uri: Uri) {
        val name = resolveFileName(uri)
        Log.i(TAG, "Queuing upload: $name  ($uri)")
        Toast.makeText(this, "Sending $name to Desktop…", Toast.LENGTH_SHORT).show()
        startService(Intent(this, FileTransferService::class.java).apply {
            action = FileTransferService.ACTION_UPLOAD
            putExtra(FileTransferService.EXTRA_FILE_URI,  uri.toString())
            putExtra(FileTransferService.EXTRA_FILE_NAME, name)
        })
    }

    private fun resolveFileName(uri: Uri): String {
        // Try ContentResolver display name first
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (col >= 0) {
                    val n = cursor.getString(col)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        // Fall back to last path segment
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
}
