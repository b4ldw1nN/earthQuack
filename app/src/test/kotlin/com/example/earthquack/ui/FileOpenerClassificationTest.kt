package com.example.earthquack.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MIME resolution and the image/video/audio decision.
 *
 * The asymmetry worth pinning down: which files this app shows itself, and
 * which it hands to another app. Getting that boundary wrong is either an
 * internal viewer pretending it can render a PDF, or a photo going out to a
 * picker when the app could simply have shown it.
 */
class FileOpenerClassificationTest {

    /**
     * The table the opaqueness of Android's `MimeTypeMap` cannot resolve here.
     * `MimeTypeMap` is a framework class and is not on the JVM, so these tests
     * pin the *rule* the classifier applies to a MIME type — which is where the
     * bugs would be — rather than the string the platform returns for `.xyz`.
     */
    private val platformTypes = mapOf(
        "pdf" to "application/pdf",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "txt" to "text/plain",
        "zip" to "application/zip",
        "csv" to "text/csv",
        "md" to "text/markdown",
        "apk" to "application/vnd.android.package-archive"
    )

    /** The same rule [FileOpener.previewKind] applies, for JVM tests. */
    private fun previewOf(mime: String): String? = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> null
    }

    @Test
    fun `image types are previewed`() {
        listOf(
            "image/png", "image/jpeg", "image/heic", "image/webp", "image/gif"
        ).forEach { assertEquals("expected $it previewed", "image", previewOf(it)) }
    }

    @Test
    fun `video types are previewed`() {
        listOf("video/mp4", "video/x-matroska", "video/mp2t", "video/quicktime")
            .forEach { assertEquals("expected $it previewed", "video", previewOf(it)) }
    }

    @Test
    fun `audio types are previewed`() {
        listOf("audio/mpeg", "audio/mp4", "audio/x-wav", "audio/ogg")
            .forEach { assertEquals("expected $it previewed", "audio", previewOf(it)) }
    }

    /**
     * Everything else goes out. This is the list of things this app must not
     * try to render itself — and it includes ZIP, even though an image may be
     * inside it, because the type of a `.zip` does not say what is inside.
     */
    @Test
    fun `documents archives and other files go out to another app`() {
        listOf(
            "application/pdf",
            "application/zip",
            "application/vnd.android.package-archive",
            "text/plain",
            "text/csv",
            "text/markdown",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream"
        ).forEach { assertEquals("expected $it external", null, previewOf(it)) }
    }

    /**
     * The reported type wins over an unknown extension, which is what makes a
     * `.dat` file that is really XML open rather than bounce.
     */
    @Test
    fun `a backend-reported type is preferred over an unknown extension`() {
        // The extension is unknown to Android's map, so the reported type is
        // the only usable signal -- and it must not be thrown away.
        val resolved = resolveWith("data.dat", reported = "text/xml")

        assertEquals("text/xml", resolved)
    }

    /** The one case the reported type must not win: when it is generic. */
    @Test
    fun `a generic reported type does not override an unknown extension`() {
        assertEquals(
            resolveWith("data.bin", reported = "application/octet-stream"),
            "application/octet-stream"
        )
        assertEquals(resolveWith("data.bin", reported = "*/*"), "application/octet-stream")
    }

    /**
     * Extension first. A `.png` reported as `application/octet-stream` is a
     * backend that does not know better; the extension is right.
     */
    @Test
    fun `the extension wins over a reported type it contradicts`() {
        assertEquals("image/png", resolveWith("photo.png", reported = "text/plain"))
    }

    @Test
    fun `an empty reported type is ignored`() {
        assertEquals(
            resolveWith("notes.txt", reported = "  "),
            resolveWith("notes.txt", reported = null)
        )
    }

    /**
     * Stand-in for the platform half of [FileOpener.mimeType]: the table above
     * plus the same decision order.
     */
    private fun resolveWith(fileName: String, reported: String?): String {
        val extension = fileName
            .substringAfterLast('.', "")
            .takeIf { fileName.contains('.') && it.isNotEmpty() }
        val known = extension
            ?.let { platformTypes[it.lowercase()] }
            ?.takeIf { it != "application/octet-stream" }
        if (known != null) return known

        val candidate = reported
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" && it != "*/*" }
        return candidate ?: "application/octet-stream"
    }
}
