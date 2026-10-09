package com.example.earthquack.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VT100ParserInstrumentedTest {

    @Test
    fun decodesUtf8PromptMarkerInOneRead() {
        val parser = VT100Parser()

        val out = parser.feed(byteArrayOf(0xE2.toByte(), 0x96.toByte(), 0xB6.toByte()), 0, 3)

        assertEquals("\u25B6", out.toString())
    }

    @Test
    fun decodesUtf8PromptMarkerSplitAcrossReads() {
        val parser = VT100Parser()

        assertEquals("", parser.feed(byteArrayOf(0xE2.toByte()), 0, 1).toString())
        assertEquals("", parser.feed(byteArrayOf(0x96.toByte()), 0, 1).toString())

        val out = parser.feed(byteArrayOf(0xB6.toByte()), 0, 1)

        assertEquals("\u25B6", out.toString())
    }

    @Test
    fun keepsAnsiControlBytesOutOfUtf8Text() {
        val parser = VT100Parser()
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

        assertEquals("\u25B6", out.toString())
    }

    @Test
    fun hidesSgrConcealedText() {
        val parser = VT100Parser()
        val bytes = "\u001B[8m[hidden prompt payload]\u001B[0mshown"
            .toByteArray(Charsets.UTF_8)

        val out = parser.feed(bytes, 0, bytes.size)

        assertEquals("shown", out.toString())
    }

    @Test
    fun ignoresCharsetSelection() {
        val parser = VT100Parser()
        val bytes = "zoro\u001B(B in ~\u001B(B".toByteArray(Charsets.UTF_8)

        val out = parser.feed(bytes, 0, bytes.size)

        assertEquals("zoro in ~", out.toString())
    }

    @Test
    fun ignoresCsiPrivateMode() {
        val parser = VT100Parser()
        val bytes = "\u001B[?2004hready\u001B[?2004l".toByteArray(Charsets.UTF_8)

        val out = parser.feed(bytes, 0, bytes.size)

        assertEquals("ready", out.toString())
    }
}
