package com.example.earthquack.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Renders [TerminalRun]s into spans, and bridges [TerminalScreenBuffer] to the
 * fragment.
 *
 * This is the only file in the terminal that touches `android.text`. Keeping it
 * to colour lookup and span building is what lets the parsing state machine be
 * a pure Kotlin class with JVM unit tests: the logic worth testing never
 * reaches here, and the mapping worth eyeballing (does 31m look red) never
 * leaves here.
 */

/** Xterm-256 indices as ARGB. */
private val PALETTE: IntArray by lazy {
    IntArray(256).also { table ->
        // 0–7: the dark-terminal base colours, unchanged from the previous
        // renderer so the terminal still looks like it did.
        val base = intArrayOf(
            0xFF1E1E1E.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
            0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt()
        )
        val bright = intArrayOf(
            0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
            0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt()
        )
        base.copyInto(table, 0)
        bright.copyInto(table, 8)
        // 16–231: the standard 6×6×6 colour cube.
        for (i in 0 until 216) {
            val r = i / 36
            val g = (i % 36) / 6
            val b = i % 6
            fun channel(v: Int): Int = if (v == 0) 0 else 55 + v * 40
            table[16 + i] = android.graphics.Color.rgb(channel(r), channel(g), channel(b))
        }
        // 232–255: the grey ramp.
        for (i in 0 until 24) {
            val level = 8 + i * 10
            table[232 + i] = android.graphics.Color.rgb(level, level, level)
        }
    }
}

/** The ARGB colour for [index], clamped into the palette. */
fun terminalColor(index: Int): Int = PALETTE[index.coerceIn(0, PALETTE.lastIndex)]

/**
 * Applies [style] to the range [start]..[builder.length] of [builder].
 *
 * Shared by [toSpan] and [toScreenSpan] so a colour means the same thing
 * whether one feed's runs or a whole screen's rows are being styled.
 */
private fun applyStyle(builder: SpannableStringBuilder, start: Int, style: TerminalStyle) {
    val fg = style.foreground
    val bg = style.background
    if (style.reverse) {
        // Reverse video is a real swap, not a foreground tint: the background is
        // what the eye reads as "the character".
        builder.setSpan(
            ForegroundColorSpan(bg?.let { terminalColor(it) } ?: DEFAULT_FOREGROUND),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            BackgroundColorSpan(fg?.let { terminalColor(it) } ?: DEFAULT_BACKGROUND),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    } else {
        if (fg != null) {
            builder.setSpan(
                ForegroundColorSpan(terminalColor(fg)),
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (bg != null) {
            builder.setSpan(
                BackgroundColorSpan(terminalColor(bg)),
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    if (style.bold) {
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    if (style.underline) {
        builder.setSpan(
            StyleSpan(Typeface.ITALIC),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

/**
 * Builds a styled [Spannable] from the runs one feed produced.
 *
 * Runs are appended in order, so a colour set mid-run survives as a separate
 * span over exactly the characters it applied to.
 */
fun List<TerminalRun>.toSpan(): Spannable =
    SpannableStringBuilder().also { builder ->
        for (run in this) {
            val start = builder.length
            builder.append(run.text)
            applyStyle(builder, start, run.style)
        }
    }

/**
 * Builds the whole visible screen as one [Spannable]: every row joined by a
 * newline, styled per run.
 *
 * This is what the fixed-grid renderer produces — exactly `rows` logical lines,
 * the last of which is where the cursor sits. Because the content is a fixed
 * grid rather than an ever-growing log, the prompt stays at its cell and there
 * is no chase-to-the-bottom drift. Spans are indexed against the single builder
 * so a colour set mid-row survives.
 */
fun List<List<TerminalRun>>.toScreenSpan(): Spannable =
    SpannableStringBuilder().also { builder ->
        forEachIndexed { rowIndex, runs ->
            if (rowIndex > 0) builder.append('\n')
            for (run in runs) {
                val start = builder.length
                builder.append(run.text)
                applyStyle(builder, start, run.style)
            }
        }
    }

private const val DEFAULT_FOREGROUND = 0xFFE6EDF3.toInt()
private const val DEFAULT_BACKGROUND = 0xFF000000.toInt()

/**
 * One [TerminalScreenBuffer.feed] as the fragment sees it: the styled text plus
 * what to correct in the view that was already drawn.
 *
 * The corrections exist because a scrollback log rewrites itself in place at
 * the tail — a carriage return, a backspace, an erase-line. The fragment cannot
 * know that unless the buffer says how far back to delete.
 */
class TerminalFrame(
    val text: Spannable,
    val cleared: Boolean,
    val retractChars: Int,
    val trimmedFromFront: Int
)

/**
 * Android-side adapter over [TerminalScreenBuffer].
 *
 * The parsing is all in the buffer; this adds the span rendering and keeps the
 * shape the fragment had, so swapping one for the other is invisible to the
 * rest of [TerminalFragment].
 */
class VT100Parser {

    private val buffer = TerminalScreenBuffer()

    fun feed(data: ByteArray, offset: Int, len: Int): TerminalFrame {
        val result = buffer.feed(data, offset, len)
        return TerminalFrame(
            text = result.runs.toSpan(),
            cleared = result.cleared,
            retractChars = result.retractChars,
            trimmedFromFront = result.trimmedFromFront
        )
    }

    fun reset() = buffer.reset()
}
