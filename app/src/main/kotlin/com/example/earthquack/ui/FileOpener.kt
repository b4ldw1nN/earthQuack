package com.example.earthquack.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** How a downloaded file should be shown. */
sealed interface OpenTarget {

    /** Shown inside the app: this type renders with platform widgets alone. */
    data class Preview(val kind: PreviewKind) : OpenTarget

    /** Handed to another app for this MIME type. */
    data class External(val mime: String) : OpenTarget
}

/** The kinds [PreviewKind] covers, each with a matching platform widget. */
enum class PreviewKind {
    /** Bitmap: ImageView, decoded with a downsample to fit the view. */
    IMAGE,

    /** Video: VideoView + MediaController. */
    VIDEO,

    /** Audio: VideoView's audio-only mode with just a MediaController. */
    AUDIO
}

/**
 * Decides what a downloaded file is and how to show it, and performs the
 * external-launch half.
 *
 * All of Android's file sharing goes through a `FileProvider`: the file lives
 * in cache, so the receiving app needs a content URI it can be granted read
 * access to. Three things are deliberately not done here:
 *
 *  - no `file://` URI, which throws `FileUriExposedException` on API 24+ and
 *    would take the screen down with it;
 *  - no `Intent.FLAG_GRANT_WRITE_URI_PERMISSION`, since a viewer never needs
 *    to write to a file it was handed;
 *  - no broad grant — the permission is scoped to the URI by
 *    [Intent.FLAG_GRANT_READ_URI_PERMISSION] and only for the duration of the
 *    receiving activity.
 */
class FileOpener(private val context: Context) {

    /**
     * Resolves the MIME type for [fileName], falling back to any type the
     * backend reported.
     *
     * Extension first, because it is the only signal that survives being copied
     * to a local file, and it is what the receiving app will match against.
     * [reportedMime] is consulted when the extension is unknown — a backend
     * that says `text/plain` for a `.dat` file knows more than the extension
     * map does. Content is *not* sniffed: magic-byte detection needs the whole
     * file in memory to be reliable, which the download path avoids.
     *
     * @return a concrete type, or `application/octet-stream` when nothing
     *   better is available. `* /*` is deliberately not used: a viewer app that
     *   matches `*/*` opens the file as garbage more often than it opens it
     *   usefully, and the "no viewer" message is more honest.
     */
    fun mimeType(fileName: String, reportedMime: String? = null): String {
        val byExtension = extensionOf(fileName)?.let { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.US))
        }
        if (!byExtension.isNullOrBlank() && !isGeneric(byExtension)) {
            return byExtension
        }
        val reported = reportedMime?.substringBefore(';')?.trim()?.lowercase(Locale.US)
        if (!reported.isNullOrEmpty() && !isGeneric(reported)) {
            return reported
        }
        // A few types Android's map misses but the OS and most viewers accept.
        for (alias in KNOWN_EXTENSIONS) {
            if (extensionOf(fileName)?.equals(alias.first, ignoreCase = true) == true) {
                return alias.second
            }
        }
        return DEFAULT_TYPE
    }

    /**
     * Whether this file is one this app can render itself.
     *
     * Only image, video and audio. A photo, a clip and a track are exactly the
     * three kinds where "find an app" is a worse experience than showing it,
     * and where the platform widgets (ImageView, VideoView) are sufficient
     * without a third-party rendering stack. PDFs, archives and documents have
     * no adequate built-in renderer in an app of this size, so they keep going
     * out to whatever the user has installed.
     *
     * ZIP files are excluded even though some are images: a `.zip` can be
     * anything, and guessing wrong means showing an error in place of a file
     * that another app would have opened fine.
     */
    fun previewKind(fileName: String, reportedMime: String? = null): PreviewKind? {
        val mime = mimeType(fileName, reportedMime)
        return when {
            mime.startsWith("image/") -> PreviewKind.IMAGE
            mime.startsWith("video/") -> PreviewKind.VIDEO
            mime.startsWith("audio/") -> PreviewKind.AUDIO
            else -> null
        }
    }

    /**
     * Where [fileName] should be shown, given what it is.
     *
     * Videos that are actually archives or subtitled containers are still
     * previewed, because the MIME is what the video widget decodes against.
     */
    fun target(fileName: String, reportedMime: String? = null): OpenTarget {
        val kind = previewKind(fileName, reportedMime)
        return if (kind != null) {
            OpenTarget.Preview(kind)
        } else {
            OpenTarget.External(mimeType(fileName, reportedMime))
        }
    }

    /**
     * Whether at least one installed app can view [type].
     *
     * Used before offering an external open, so the message can name the type
     * the user tried rather than presenting a system-level failure.
     */
    fun hasViewer(type: String): Boolean {
        val probe = Intent(Intent.ACTION_VIEW).apply {
            // No data, no component: PackageManager matches on the type alone,
            // which is enough to answer "can anything show this?".
            addCategory(Intent.CATEGORY_DEFAULT)
            setType(type)
        }
        return context.packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()
    }

    /**
     * The content URI for [file], or null when no configured provider root
     * contains it. A viewer receives this URI and nothing else — never a path.
     */
    fun contentUriFor(file: File): android.net.Uri? = try {
        FileProvider.getUriForFile(context, authorityFor(context), file)
    } catch (e: IllegalArgumentException) {
        // "Failed to find configured root that contains ..." — the path is
        // outside what file_paths.xml declares, so it is not a file this app is
        // allowed to share. Logged, not shown.
        Log.w(TAG, "file ${file.path} is not inside the FileProvider root", e)
        null
    }

    /**
     * Opens [file] with another app.
     *
     * @param chooserTitle what the chooser sheet says, when a choice is offered.
     *   Null launches the resolved target directly, which is what the preview
     *   screen's "Open with" wants only when there is no choice to make.
     */
    fun openExternally(file: File, mime: String, chooserTitle: String?): Boolean {
        val uri = contentUriFor(file) ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val intent = if (chooserTitle != null) {
            Intent.createChooser(view, chooserTitle)
        } else {
            view
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "no viewer installed for $mime")
            false
        } catch (e: SecurityException) {
            // A picker may return a component that is no longer exported, or
            // the grant may be refused. Nothing useful to show.
            Log.w(TAG, "viewer refused the grant", e)
            false
        }
    }

    private fun isGeneric(type: String): Boolean =
        type == DEFAULT_TYPE || type == "application/octet-stream" || type == "*/*"

    private fun extensionOf(fileName: String): String? =
        fileName.substringAfterLast('.', "").takeIf { fileName.contains('.') && it.isNotEmpty() }

    companion object {
        private const val TAG = "FileOpener"

        private const val DEFAULT_TYPE = "application/octet-stream"

        /**
         * The provider authority.
         *
         * `${applicationId}` expands to the app id, so a debug build and a
         * release build installed side by side cannot collide over one
         * provider. Same string as the manifest declares.
         */
        fun authorityFor(context: Context): String =
            "${context.packageName}.fileprovider"

        /**
         * Types Android's `MimeTypeMap` misses or gets wrong. The list is
         * short on purpose: anything longer is guessing, and the fallback
         * `application/octet-stream` lets the user's own file app decide.
         */
        private val KNOWN_EXTENSIONS = listOf(
            "md" to "text/markdown",
            "log" to "text/plain",
            "ini" to "text/plain",
            "csv" to "text/csv",
            "tsv" to "text/tab-separated-values",
            "apk" to "application/vnd.android.package-archive",
            "epub" to "application/epub+zip",
            "m4v" to "video/x-m4v",
            "mkv" to "video/x-matroska",
            "heic" to "image/heic"
        )
    }
}
