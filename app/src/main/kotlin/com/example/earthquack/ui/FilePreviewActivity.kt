package com.example.earthquack.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityFilePreviewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One downloaded file, shown by this app.
 *
 * ## Why an internal viewer exists at all
 *
 * A photo, a clip, a track and a PDF are the four cases where handing the file
 * to another app is the worse experience: the user wants to *see* the thing they
 * tapped, and "choose an app" is a step between them and it. PDF in particular
 * is the type where the user cannot be expected to have a viewer installed —
 * see [PdfDocument]. Everything else keeps going out, because a built-in
 * renderer for those is a rendering stack this app does not have. The
 * "Open with" button hands the file to another app from here either way, so the
 * in-app path is never a dead end.
 *
 * ## The three widget families
 *
 *  - image: `ImageView` with a downsampled decode, so a 12-megapixel photo costs
 *    about a megabyte of pixels instead of fifty.
 *  - video/audio: `VideoView` + `MediaController`, which is all a file already
 *    on disk needs.
 *  - PDF: a list of pages rendered by [PdfDocument] and zoomed by
 *    [PdfPageView], which is the smallest thing that reads a document.
 *
 * ## Rendering off the main thread
 *
 * Every path that produces a bitmap does it on [Dispatchers.Default]. A page
 * render is tens of milliseconds and a photo decode is more; blocking the frame
 * on either costs a dropped first screen.
 */
class FilePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilePreviewBinding

    /** Absolute path of the downloaded file. */
    private lateinit var localPath: String

    private lateinit var kind: PreviewKind

    private var document: PdfDocument? = null

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localPath = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        kind = PreviewKind.valueOf(
            intent.getStringExtra(EXTRA_KIND) ?: PreviewKind.IMAGE.name
        )

        binding.appbar.btnBack.isVisible = true
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.appbar.btnAction.isVisible = false
        binding.appbar.subtitle.isVisible = false
        binding.btnOpenWith.isVisible = true
        binding.btnOpenWith.setOnClickListener { openWith() }
        binding.btnRetry.setOnClickListener { render() }

        binding.appbar.title.text =
            intent.getStringExtra(EXTRA_NAME).orEmpty()
                .ifBlank { File(localPath).name }

        render()
    }

    /** Points the right widget at the file. */
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
            PreviewKind.PDF -> showPdf(file)
        }
    }

    private fun showImage(file: File) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) { decodeSampled(file) }
            binding.progress.isVisible = false
            if (bitmap == null) {
                // A badge, a wall of text saved as .png, a zero-byte placeholder:
                // "cannot be shown as an image" is the truthful message.
                showState(getString(R.string.files_preview_failed))
                return@launch
            }
            binding.image.setImageBitmap(bitmap)
            binding.image.isVisible = true
        }
    }

    private fun showVideo(file: File) {
        binding.progress.isVisible = false
        binding.image.isVisible = false
        binding.video.isVisible = true

        val controller = android.widget.MediaController(this)
        binding.video.setMediaController(controller)

        binding.video.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "preview playback failed what=$what extra=$extra")
            showState(getString(R.string.files_preview_failed))
            true
        }
        binding.video.setOnPreparedListener { it.start() }

        try {
            binding.video.setVideoURI(
                FileProvider.getUriForFile(this, FileOpener.authorityFor(this), file)
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no provider root for ${file.path}", e)
            showState(getString(R.string.files_preview_failed))
        }
    }

    /**
     * Opens the document and lists its pages.
     *
     * A document is rendered page by page by [PdfDocument], which is the
     * platform rasteriser: there is no vector path from a PDF to a view, so the
     * list scrolls through bitmaps rendered on demand. Pages that have already
     * been rendered are kept, so scrolling back does not re-render.
     */
    private fun showPdf(file: File) {
        binding.progress.isVisible = false

        val opened = PdfDocument(applicationContext, file).also { document = it }.open()
        if (!opened) {
            binding.stateGroup.isVisible = true
            binding.stateText.setText(R.string.files_pdf_failed)
            binding.btnRetry.isVisible = true
            return
        }

        binding.image.isVisible = false
        binding.video.isVisible = false
        binding.pdfList.isVisible = true

        binding.appbar.subtitle.text =
            getString(R.string.files_pdf_pages, document?.pageCount ?: 0)
        binding.appbar.subtitle.isVisible = true

        val doc = document ?: return
        binding.pdfList.layoutManager = LinearLayoutManager(this)
        binding.pdfList.adapter =
            PdfPageAdapter(this, doc, renderScope, binding.pdfList.width)
    }

    /** Offers the file to any installed app, this app included as an option. */
    private fun openWith() {
        val file = File(localPath)
        val uri = try {
            FileProvider.getUriForFile(this, FileOpener.authorityFor(this), file)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no provider root for ${file.path}", e)
            Toast.makeText(this, R.string.files_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // The type is resolved from the file, not remembered from the tap, so
        // the chooser matches what is actually on disk.
        val mime = contentResolver.getType(uri)
            ?: FileOpener(applicationContext).mimeType(file.name, null)
        val send = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(
                Intent.createChooser(send, getString(R.string.files_open_with, file.name))
            )
        } catch (e: android.content.ActivityNotFoundException) {
            dialog(getString(R.string.files_download_no_viewer, mime))
        }
    }

    private fun showState(message: String) {
        binding.progress.isVisible = false
        binding.image.isVisible = false
        binding.video.isVisible = false
        binding.pdfList.isVisible = false
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
     * Both off the main thread, since a 12-megapixel decode is tens of
     * milliseconds and blocking the frame costs more than the pause.
     */
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options()
            .apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
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

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            // A photo from a remote is already compressed; letting the decoder
            // work in software avoids a surprise on a device with little RAM.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory decoding ${file.name} at sample=$sample")
            null
        }
    }

    override fun onDestroy() {
        renderScope.cancel()
        // Pages are rendered through [renderScope], so it must be stopped
        // before the native renderer is closed, or a page render can be in
        // flight while the renderer underneath it goes away.
        document?.close()
        document = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FilePreviewActivity"

        private const val EXTRA_PATH = "local_path"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_NAME = "display_name"

        fun start(
            context: Context,
            localPath: String,
            kind: PreviewKind,
            name: String
        ) {
            context.startActivity(
                Intent(context, FilePreviewActivity::class.java)
                    .putExtra(EXTRA_PATH, localPath)
                    .putExtra(EXTRA_KIND, kind.name)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }
}

/**
 * The page list for an open document.
 *
 * One bitmap per page, held in a map so scrolling back is instant, and rendered
 * on [renderScope] when a page is bound without one. The scope is shared with
 * the activity lifecycle and cancelled before the document closes, which is
 * what keeps a render from landing under a closed `PdfRenderer`.
 */
private class PdfPageAdapter(
    private val activity: FilePreviewActivity,
    private val document: PdfDocument,
    private val renderScope: CoroutineScope,
    private val listWidth: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {

    /** Rendered bitmaps by page index. */
    private val bitmaps = mutableMapOf<Int, Bitmap>()

    /** Renders in flight, so a recycled view's bitmap can be discarded. */
    private val inFlight = mutableMapOf<Int, Job>()

    /**
     * Width to rasterise a page at.
     *
     * Not the bound view's own width: a view that has never been measured
     * reports 0, and a page rendered at 0 wide is a 1x1 bitmap -- which is
     * exactly the blank page this guards against. The list's width is already
     * correct by the time anything binds; the display width is the last
     * resort, never a default.
     */
    private val targetWidth: Int = listWidth.takeIf { it > 0 }
        ?: activity.resources.displayMetrics.widthPixels

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(PdfPageView(parent.context))

    override fun getItemCount(): Int = document.pageCount

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        // Full width, height driven by the page: a view holding no bitmap has
        // no intrinsic size, so wrap_content would measure every page to zero
        // and the whole document would render as a column of nothing.
        val params = holder.view.layoutParams
            ?: RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        params.width = MATCH_PARENT
        holder.view.layoutParams = params

        val existing = bitmaps[position]
        holder.view.show(existing)
        if (existing == null) {
            inFlight.remove(position)?.cancel()
            inFlight[position] = renderScope.launch {
                val bitmap = document.renderPage(position, targetWidth)
                if (bitmap == null) return@launch
                bitmaps[position] = bitmap
                inFlight.remove(position)
                // The holder may have been recycled while this rendered.
                activity.runOnUiThread {
                    if (holder.adapterPosition == position) {
                        holder.view.show(bitmap)
                        // Without this the page is measured to no height at all
                        // and the viewer shows an empty column: an ImageView
                        // handed a bitmap through show() has no intrinsic size.
                        holder.view.applyPageAspectRatio()
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        val position = holder.adapterPosition
        inFlight.remove(position)?.cancel()
        // The bitmap stays in the map: scrolling back should not re-render a
        // page the user just read, and the memory is bounded by the documents
        // people actually open.
        super.onViewRecycled(holder)
    }

    private companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    class PageHolder(val view: PdfPageView) : RecyclerView.ViewHolder(view)
}
