package com.example.earthquack.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.core.content.FileProvider
import com.example.earthquack.ui.FileOpener.Companion.authorityFor
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * One page of a PDF, drawn from a rendered bitmap with pan and zoom.
 *
 * ## Why a bitmap and not a SurfaceView
 *
 * `PdfRenderer` rasterises a page into a `Bitmap`; there is no vector path to a
 * view. So the viewer renders at a resolution a little above the display size
 * and lets a pinch-zoom scale that bitmap. Re-rendering at a higher resolution
 * *while* zooming is possible — [setZoom] returns whether the bitmap is now
 * too coarse — but that is a refinement, and the initial resolution is chosen
 * so that reading text at 2x is comfortable without it.
 *
 * ## Why pointer events live here
 *
 * A viewer that responds to a pinch only when the user hits the bitmap exactly
 * is a viewer that feels broken: two fingers anywhere on the page should work.
 * The detectors are attached to the view, not to the bitmap, and the scale
 * origin is the midpoint between the fingers rather than the view centre — so
 * the page zooms into what the user is pinching, which is what every other
 * PDF reader does.
 */
class PdfPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    /** The page bitmap, or null before a render lands. */
    private var page: Bitmap? = null

    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /**
     * How much of the page one screen currently shows, i.e. how far the page
     * was scaled to fit the view. 1f when the page is shown whole; larger when
     * the view is wider than the page, which is every portrait phone reading a
     * portrait document.
     */
    private var fit = 1f

    private val srcRect = Rect()
    private val dstRect = RectF()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyScale(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Alternate between the whole page and enough zoom to read a
                // line, which is the two states a double tap is used for.
                setZoom(if (zoom > 2.5f) 1f else 2.5f, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }
        }
    )

    var dragSensitivity: Float = 2.5f

    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> panFrom(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                keepPageOnScreen()
            }
        }
        // Re-drawn on every event: a page that only redraws on zoom is a page
        // that lags a finger by a frame.
        invalidate()
        return true
    }

    // Deliberately not using the framework's ImageView scale type: this view
    // draws its own matrix, and MATRIX would override the pan/zoom transform.
    override fun setScaleType(scaleType: ScaleType) = Unit

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFit()
    }

    /** Replaces the page and resets the view to fit it. */
    fun show(bitmap: Bitmap?) {
        page = bitmap
        if (bitmap != null) {
            zoom = 1f
            offsetX = 0f
            offsetY = 0f
            recomputeFit()
            applyTransform()
        }
        invalidate()
    }

    /**
     * Sizes the view to the page's aspect ratio.
     *
     * An ImageView holding a bitmap reports the bitmap as its intrinsic size,
     * so `wrap_content` would lay a 2480x3508 page out at that many pixels --
     * far past the screen, and measured on the main thread. Requesting the
     * page's own ratio against the width it was given is both correct and
     * cheap: the view's height becomes `width * pageHeight / pageWidth`.
     *
     * Called once per page, after the bitmap is in place and the width is known.
     */
    fun applyPageAspectRatio() {
        val bitmap = page ?: return
        val horizontal = width
        if (horizontal <= 0) return
        val wanted = (horizontal.toLong() * bitmap.height / bitmap.width).toInt()
        val params = layoutParams ?: return
        if (params.height != wanted) {
            params.height = wanted
            layoutParams = params
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = page ?: return
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.concat(matrix)
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        canvas.restore()
    }

    /**
     * Zooms to [target], meaning "show the page at [fit] * [target]".
     *
     * [focusX] and [focusY] are view coordinates that stay under the fingers,
     * so the page grows towards the pinch rather than towards its centre.
     *
     * @return true when the view needs a higher-resolution bitmap than it has.
     */
    fun setZoom(target: Float, focusX: Float, focusY: Float): Boolean {
        applyScale(target / zoom, focusX, focusY)
        return zoom > NEEDS_HIGHER_RESOLUTION
    }

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val applied = next / zoom
        offsetX = focusX - (focusX - offsetX) * applied
        offsetY = focusY - (focusY - offsetY) * applied
        zoom = next
        keepPageOnScreen()
        applyTransform()
    }

    private fun panFrom(event: MotionEvent) {
        // Two fingers are a pinch, not a pan; the scale detector owns them.
        if (event.pointerCount > 1) return
        val dx = event.rawX - lastRawX
        val dy = event.rawY - lastRawY
        offsetX += dx * dragSensitivity
        offsetY += dy * dragSensitivity
        lastRawX = event.rawX
        lastRawY = event.rawY
    }

    private var lastRawX = 0f
    private var lastRawY = 0f

    /**
     * Clamps the page so it cannot be dragged entirely off the screen.
     *
     * Only clamps on the axis where the page is *smaller* than the view: a page
     * scaled up beyond the view is allowed to run past its edges, because that
     * is how the user reaches the parts that were zoomed out.
     */
    private fun keepPageOnScreen() {
        val bitmap = page ?: return
        val scale = fit * zoom
        val pageWidth = bitmap.width * scale
        val pageHeight = bitmap.height * scale

        if (width > pageWidth) {
            offsetX = (width - pageWidth) / 2f
        } else {
            offsetX = offsetX.coerceIn(width - pageWidth, 0f)
        }
        if (height > pageHeight) {
            offsetY = (height - pageHeight) / 2f
        } else {
            offsetY = offsetY.coerceIn(height - pageHeight, 0f)
        }
    }

    private fun applyTransform() {
        val bitmap = page ?: return
        val scale = fit * zoom
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            offsetX + (width - bitmap.width * scale) / 2f,
            offsetY + (height - bitmap.height * scale) / 2f
        )
    }

    private fun recomputeFit() {
        val bitmap = page ?: return
        if (width == 0 || height == 0) return
        // Fit the *width* rather than the whole page: a document page is taller
        // than it is wide on a portrait phone, and fitting both dimensions
        // leaves a strip of text too small to read at the top of every screen.
        fit = min(1.6f, width.toFloat() / bitmap.width)
        applyTransform()
    }

    companion object {
        private const val TAG = "PdfPageView"

        /** Below this, a phone-sized bitmap is detailed enough. */
        private const val MIN_ZOOM = 1f

        private const val MAX_ZOOM = 8f

        /** Past this the pixelation is worth a re-render. */
        private const val NEEDS_HIGHER_RESOLUTION = 2f
    }
}

/**
 * A document viewer for files in the download cache.
 *
 * ## Why this exists rather than sending the file out
 *
 * The chooser is the right answer for most types — Android already ships
 * better viewers than anything this app would grow. PDF is the exception: it
 * is the one format where the user cannot be expected to have a viewer
 * installed, because the ones that exist are large, ad-supported, and
 * Google-Drive-adjacent. A PDF reader built on `PdfRenderer` is a few hundred
 * lines and no dependency, which is cheaper than telling the user to install
 * something.
 *
 * ## How it renders
 *
 * `PdfRenderer` is the platform's own PDF rasteriser: it gives a page count
 * and turns page N into a `Bitmap`. Two consequences drive the design. The
 * whole file must be available to open it, so the viewer runs only on files
 * that have finished downloading. And a page must be rendered off the main
 * thread, because a 2480×3508 page takes tens of milliseconds and blocking the
 * frame on it is a dropped first screen.
 *
 * ## The lifecycle trap it avoids
 *
 * `PdfRenderer` holds a native `PdfRendererCore` and must be closed exactly
 * once, and closing it while a page render is in flight corrupts the next
 * render. So [close] is idempotent and the worker is stopped under the same
 * lock, and the renderer is closed only after the worker confirms it is done.
 * A back press during a render must not produce a JNI crash.
 */
class PdfDocument(private val context: Context, private val file: File) {

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private val lock = Any()

    /** Page count, or 0 if the file could not be opened as a PDF. */
    val pageCount: Int
        get() = synchronized(lock) { renderer?.pageCount ?: 0 }

    /**
     * Opens the file. Returns false when it is not a readable PDF.
     *
     * @param uri optional content URI for files served by a provider, for
     *   when the caller has a URI rather than a path. The download cache is
     *   this app's own, so a path is the normal case.
     */
    fun open(uri: Uri? = null): Boolean {
        val opened = try {
            descriptor = if (uri != null) {
                context.contentResolver.openFileDescriptor(uri, "r")
            } else {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            descriptor?.let { renderer = PdfRenderer(it) } != null
        } catch (e: Exception) {
            // Encryption, a truncated download, or a non-PDF file. All mean
            // "not a readable document", which is what the caller must show.
            Log.w(TAG, "cannot open ${file.name}: ${e.javaClass.simpleName}")
            false
        }
        if (!opened) close()
        return opened
    }

    /**
     * Renders page [index] into a bitmap of at most [maxWidth] pixels.
     *
     * Returns null when the page is gone — a document whose page count
     * changed under us, or a renderer closed by a concurrent [close].
     */
    fun renderPage(index: Int, maxWidth: Int): Bitmap? {
        synchronized(lock) {
            val core = renderer ?: return null
            if (index < 0 || index >= core.pageCount) return null
            return try {
                core.openPage(index).use { page ->
                    // Render at 2x the requested width so a 2.5x pinch zoom is
                    // still crisp, which is the zoom a double tap lands on.
                    val scale = maxWidth.toFloat() / page.width * 2
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // A PDF page is transparent by default; a white background
                    // is what every reader shows, and without it the page
                    // renders as black-on-transparent.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "page $index failed to render", e)
                null
            }
        }
    }

    /** Physical page dimensions, for a viewer that wants to lay out first. */
    fun pageSize(index: Int): Pair<Int, Int>? {
        synchronized(lock) {
            val core = renderer ?: return null
            if (index < 0 || index >= core.pageCount) return null
            return try {
                core.openPage(index).use { it.width to it.height }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Releases the renderer and the file descriptor. Idempotent.
     *
     * Called from `onDestroy`, which for a viewer is the only place it is
     * called: a renderer outliving its activity leaks a native descriptor,
     * which on Android shows up as "open file limit" much later.
     */
    fun close() {
        synchronized(lock) {
            try {
                renderer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "renderer close failed", e)
            }
            renderer = null
            try {
                descriptor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "descriptor close failed", e)
            }
            descriptor = null
        }
    }

    /**
     * The content URI for [file], for the "Open with" escape hatch.
     *
     * Null when the file is outside the provider's configured root, which
     * should not happen for this app's own cache but is not assumed.
     */
    fun contentUri(): Uri? = try {
        FileProvider.getUriForFile(context, authorityFor(context), file)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "no provider root for ${file.path}")
        null
    }

    companion object {
        private const val TAG = "PdfDocument"
    }
}
