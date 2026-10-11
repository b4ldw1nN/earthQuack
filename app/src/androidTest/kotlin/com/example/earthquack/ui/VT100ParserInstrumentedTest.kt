package com.example.earthquack.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendering side of the terminal, on a device.
 *
 * The parsing state machine is a pure class now and is tested on the JVM by
 * `TerminalScreenBufferTest`; what is left here is the part that cannot be
 * tested off-device: building real spans, and confirming the runs survive the
 * trip through `android.text` without their attributes being dropped.
 */
@RunWith(AndroidJUnit4::class)
class VT100ParserInstrumentedTest {

    private val parser = VT100Parser()

    private fun feed(text: String): String = parser.feed(
        text.toByteArray(Charsets.UTF_8),
        0,
        text.length
    ).text.toString()

    @Test
    fun decodesUtf8PromptMarkerInOneRead() {
        val out = parser.feed(byteArrayOf(0xE2.toByte(), 0x96.toByte(), 0xB6.toByte()), 0, 3)

        assertEquals("\u25B6", out.text.toString())
    }

    @Test
    fun decodesUtf8PromptMarkerSplitAcrossReads() {
        assertEquals("", parser.feed(byteArrayOf(0xE2.toByte()), 0, 1).text.toString())
        assertEquals("", parser.feed(byteArrayOf(0x96.toByte()), 0, 1).text.toString())

        val out = parser.feed(byteArrayOf(0xB6.toByte()), 0, 1)

        assertEquals("\u25B6", out.text.toString())
    }

    @Test
    fun keepsAnsiControlBytesOutOfUtf8Text() {
        val bytes = byteArrayOf(
            0x1B,
            '['.code.toByte(),
            '3'.code.toByte(),
            '1'.code.toByte(),
            'm'.code.toByte(),
            0xE2.toByte(),
            0x96.toByte(),
            0xB6.toByte(),
            0x1B,
            '['.code.toByte(),
            '0'.code.toByte(),
            'm'.code.toByte()
        )

        val out = parser.feed(bytes, 0, bytes.size)

        assertEquals("\u25B6", out.text.toString())
    }

    @Test
    fun hidesSgrConcealedText() {
        val out = feed("\u001B[8m[hidden prompt payload]\u001B[0mshown")

        assertEquals("shown", out)
    }

    @Test
    fun ignoresCharsetSelection() {
        val out = feed("zoro\u001B(B in ~\u001B(B")

        assertEquals("zoro in ~", out)
    }

    @Test
    fun ignoresCsiPrivateMode() {
        val out = feed("\u001B[?2004hready\u001B[?2004l")

        assertEquals("ready", out)
    }

    // ── what only a device can prove: the spans are real ─────────────────────

    @Test
    fun sgrAttributesBecomeRealSpansOnTheText() {
        val frame = parser.feed("\u001B[1;31mred\u001B[0m".toByteArray(Charsets.UTF_8), 0, 18)

        val text = frame.text
        // Two spans, not zero: the merge in the parser and the build here both
        // agree that "red" is styled and the rest is not.
        assertTrue(
            "the styled run must carry its spans",
            text.length == 3 && text.getSpans(0, 3, Any::class.java).isNotEmpty()
        )
    }

    @Test
    fun acarriageReturnReportsHowFarBackToErase() {
        // The first draw renders the text; the carriage return tells the caller
        // to take characters off the end of what it already drew.
        parser.feed("10%\r".toByteArray(Charsets.UTF_8), 0, 4)
        val frame = parser.feed("50%".toByteArray(Charsets.UTF_8), 0, 3)

        assertEquals(3, frame.retractChars)
    }
}
