package com.example.earthquack.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-count formatting for a file row.
 *
 * Every assertion here exists because of a bug that shipped: the units list
 * started at `KB` while the counter started at 0, so one division printed
 * "MB" and the screen read one unit too large for everything — a 112 MB PDF
 * showed as 112.0 GB. The boundary cases are pinned so a future rewrite
 * cannot reintroduce it quietly.
 */
class FormatSizeTest {

    @Test
    fun `bytes stay bytes`() {
        assertEquals("0 B", formatSize(0L))
        assertEquals("1 B", formatSize(1L))
        assertEquals("1023 B", formatSize(1023L))
    }

    /** The boundary itself: 1024 is exactly one KB, not 0.98 of one. */
    @Test
    fun `exactly one kilobyte is one kilobyte`() {
        assertEquals("1.0 KB", formatSize(1024L))
    }

    @Test
    fun `kilobytes are kilobytes`() {
        assertEquals("2.0 KB", formatSize(2048L))
        assertEquals("500.0 KB", formatSize(500L * 1024))
        // 1023.99 KB rounds up and belongs in the next unit, not as "1024.0 KB".
        assertEquals("1.0 MB", formatSize(1024L * 1024 - 1))
    }

    @Test
    fun `megabytes are megabytes`() {
        assertEquals("1.0 MB", formatSize(1024L * 1024))
        assertEquals("112.0 MB", formatSize(112L * 1024 * 1024))
        // 112,000,000 is 106.8 of these 1024-based units, not 112.3 decimal ones.
        assertEquals("106.8 MB", formatSize(112_000_000L))
    }

    @Test
    fun `gigabytes are gigabytes`() {
        assertEquals("1.0 GB", formatSize(1024L * 1024 * 1024))
        assertEquals("1.5 GB", formatSize(1_610_612_736L))
        assertEquals("2.1 GB", formatSize(2_300_000_000L))
    }

    @Test
    fun `terabytes are terabytes and stop there`() {
        assertEquals("1.0 TB", formatSize(1024L * 1024 * 1024 * 1024))
        // Past TB the value must keep counting in TB rather than dividing
        // into a unit that does not exist, which printed "0.0 PB" once.
        assertEquals("1024.0 TB", formatSize(1024L * 1024 * 1024 * 1024 * 1024))
    }

    /**
     * A missing size and a nonsense size are both "unknown", and neither may
     * be rendered as a number the user would act on.
     */
    @Test
    fun `unknown renders as a marker not a number`() {
        assertEquals("—", formatSize(null))
        assertEquals("—", formatSize(-1L))
    }
}
