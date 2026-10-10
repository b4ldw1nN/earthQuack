package com.example.earthquack.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityFilePreviewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows one downloaded file with a platform widget.
 *
 * ## Why an internal viewer exists at all
 *
 * A photo, a clip and a track are the three cases where handing the file to
 * another app is the worse experience: the user wants to *see* the thing they
 * tapped, and "choose an app" is a step between them and it. Everything else —
 * PDFs, archives, documents — keeps going out, because a built-in renderer for
 * those is a rendering stack this app does not have and is not going to grow.
 * The "Open with" button hands the file to another app from here either way, so
 * the in-app path is never a dead end.
 *
 * ## Why images are decoded with a sample size
 *
 * A modern phone photo is 12+ megapixels, which is about 48 MB of pixels for
 * one ARGB_8888 bitmap. Decoding one at full size is how an app gets killed by
 * the OS, and the view is a few hundred dp square. `inSampleSize` powers of two
 * down to at least the view's dimensions, so a 4000×3000 photo costs around a
 * megabyte instead of fifty — the screen cannot tell the difference.
 *
 * ## What "the file is on disk" buys us
 *
 * The bytes are already in this app's cache, so a decode failure here is a
 * corrupt file or one this format cannot produce a bitmap for, never a network
 * problem. That is why the failure state is honest about it.
 */
class FilePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilePreviewBinding

    /** Absolute path of the downloaded file. */
    private lateinit var localPath: String

    private lateinit var kind: PreviewKind

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localPath = intent.getStringExtra(EXTRA_PATH) ?: run {
            Log.w(TAG, "launched without a path")
            finish()
            return
        }
        kind = PreviewKind.valueOf(
            intent.getStringExtra(EXTRA_KIND) ?: PreviewKind.IMAGE.name
        )

        binding.appbar.btnBack.isVisible = true
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.appbar.title.text = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank {
            File(localPath).name
        }
        binding.appbar.subtitle.isVisible = false
        binding.appbar.btnAction.isVisible = false
        binding.btnOpenWith.setOnClickListener { openWith() }
        binding.btnRetry.setOnClickListener { render() }

        render()
    }

    /** Decodes or points the right widget at the file. */
    private fun render() {
        val file = File(localPath)
        if (!file.isFile || file.length() <= 0L) {
            showState(getString(R.string.files_preview_failed))
            return
        }
        binding.stateGroup.isVisible = false
        binding.progress.isVisible = true

        when (kind) {
            PreviewKind.IMAGE -> showImage(file)
            PreviewKind.VIDEO, PreviewKind.AUDIO -> showVideo(file)
        }
    }

    private fun showImage(file: File) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(file) }
            binding.progress.isVisible = false
            if (bitmap == null) {
                // A badge, a wall of text saved as .png, a zero-byte placeholder:
                // "cannot be shown as an image" is the truthful message.
                showState(getString(R.string.files_preview_failed))
                return@launch
            }
            binding.image.setImageBitmap(bitmap)
            binding.image.isVisible = true
            binding.video.isVisible = false
        }
    }

    private fun showVideo(file: File) {
        binding.progress.isVisible = false
        binding.image.isVisible = false
        binding.video.isVisible = true

        val controller = MediaController(this)
        binding.video.setMediaController(controller)

        binding.video.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "preview playback failed what=$what extra=$extra")
            showState(getString(R.string.files_preview_failed))
            true
        }
        binding.video.setOnPreparedListener { player: MediaPlayer -> player.start() }

        try {
            binding.video.setVideoURI(FileProvider.getUriForFile(this, FileOpener.authorityFor(this), file))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no provider root for ${file.path}")
            showState(getString(R.string.files_preview_failed))
        }
    }

    /** Offers the file to any installed app, this app included as an option. */
    private fun openWith() {
        val file = File(localPath)
        val uri = try {
            FileProvider.getUriForFile(this, FileOpener.authorityFor(this), file)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (uri == null) {
            Toast.makeText(this, R.string.files_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // The type is resolved from the file, not remembered from the tap, so
        // the chooser matches what is actually on disk.
        val mime = contentResolver.getType(uri) ?: FileOpener(applicationContext)
            .mimeType(file.name, null)
        val send = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            send,
            getString(R.string.files_open_with, file.name)
        )
        try {
            startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            dialog(getString(R.string.files_download_no_viewer, mime))
        }
    }

    private fun showState(message: String) {
        binding.progress.isVisible = false
        binding.image.isVisible = false
        binding.video.isVisible = false
        binding.stateGroup.isVisible = true
        binding.stateText.setText(message)
        binding.btnRetry.isVisible = true
    }

    private fun dialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.action_dismiss, null)
            .show()
    }

    /**
     * Decodes [file] no larger than the view.
     *
     * Two passes: one to read the intrinsic size, one to decode downsampled.
     * Both are off the main thread, since a 12-megapixel decode is tens of
     * milliseconds and blocking the frame costs more than the pause.
     */
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // How big the view actually is right now.
        val targetWidth = binding.image.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val targetHeight = binding.image.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // A photo from a remote is already compressed; letting the decoder
            // work in software avoids a surprise on a device with little RAM.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory decoding ${file.name} at sample=$sample")
            null
        }
    }

    /**
     * A duration, for the audio case: a player with nothing to show is not much
     * use, and VideoView has no way to display one without a controller. The
     * title already carries the file name; this is the second line.
     */
    private fun describeFor(kind: PreviewKind, file: File): String {
        if (kind != PreviewKind.VIDEO && kind != PreviewKind.AUDIO) return file.name
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val length = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            retriever.release()
            if (length <= 0L) file.name else "${file.name} · ${formatDuration(length)}"
        } catch (e: Exception) {
            Log.w(TAG, "no duration for ${file.name}")
            file.name
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    companion object {
        private const val TAG = "FilePreviewActivity"

        private const val EXTRA_PATH = "local_path"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_NAME = "display_name"

        /** Same authority the manifest declares and [FileOpener.authorityFor] builds. */

        fun start(context: android.content.Context, localPath: String, kind: PreviewKind, name: String) {
            context.startActivity(
                Intent(context, FilePreviewActivity::class.java)
                    .putExtra(EXTRA_PATH, localPath)
                    .putExtra(EXTRA_KIND, kind.name)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }
}
