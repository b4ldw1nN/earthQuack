package com.example.earthquack.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.earthquack.R
import com.example.earthquack.databinding.ItemFileEntryBinding
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One directory entry, already reduced to what the row can honestly show.
 *
 * [modified] is the *formatted* date rather than the raw `ModTime` string, and
 * is empty when rclone did not supply one we could parse. Doing the parsing at
 * the boundary means the adapter never has to defend itself against a
 * malformed timestamp and cannot crash the list.
 *
 * [size] is `null` when the backend reported no size. That is distinct from
 * zero: a zero-length file is a fact, an absent size is an absence, and the
 * row renders them differently.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long?,
    val mimeType: String,
    val modified: String
)

/**
 * Adapter for the file list.
 *
 * Holds no state of its own beyond the submitted list and the two callbacks.
 * Everything it draws comes from a [FileEntry]; nothing here invents a size,
 * a count or a date.
 */
class FileEntryAdapter(
    private val onOpen: (FileEntry) -> Unit,
    private val onOverflow: (FileEntry, View) -> Unit
) : ListAdapter<FileEntry, FileEntryAdapter.EntryHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder =
        EntryHolder(
            ItemFileEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: EntryHolder, position: Int) =
        holder.bind(getItem(position))

    inner class EntryHolder(
        private val binding: ItemFileEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FileEntry) {
            binding.title.text = entry.name

            val meta = metaFor(entry)
            binding.meta.text = meta
            // A row whose backend gave neither a size nor a date shows one
            // line, not an empty one; an empty metadata line reads as a bug.
            binding.meta.isVisible = meta.isNotEmpty()

            binding.icon.setImageResource(iconFor(entry))
            binding.icon.imageTintList =
                ContextCompat.getColorStateList(binding.root.context, tintFor(entry))

            binding.root.setOnClickListener { onOpen(entry) }
            binding.btnOverflow.setOnClickListener { onOverflow(entry, it) }
        }
    }

    /**
     * The metadata line.
     *
     * Folders get the modified date and nothing else. `operations/list` does
     * not report how many children a directory has, and asking rclone once per
     * row would be an RPC per row; so rather than invent the mockup's
     * "12 items", folders show only what was actually returned.
     */
    private fun metaFor(entry: FileEntry): String {
        val lead = if (entry.isDir) null else formatSize(entry.size)
        val date = entry.modified.ifEmpty { null }
        return when {
            lead != null && date != null -> "$lead · $date"
            lead != null -> lead
            date != null -> date
            else -> ""
        }
    }

    private fun iconFor(entry: FileEntry): Int {
        if (entry.isDir) return R.drawable.ic_eq_folder
        val hint = entry.mimeType.ifBlank { extensionOf(entry.name) }
        return when {
            hint.contains("pdf", ignoreCase = true) -> R.drawable.ic_eq_pdf
            hint.contains("image", ignoreCase = true) -> R.drawable.ic_eq_image
            else -> R.drawable.ic_eq_doc
        }
    }

    /**
     * Tint is a secondary signal only. The name is always on the row, so the
     * colour never has to be the sole carrier of "what kind of thing is this".
     *
     * @return a colour resource, resolved against the row's own context.
     */
    private fun tintFor(entry: FileEntry): Int = when {
        entry.isDir -> R.color.eq_primary
        else -> {
            val hint = entry.mimeType.ifBlank { extensionOf(entry.name) }
            when {
                hint.contains("pdf", ignoreCase = true) -> R.color.eq_error
                hint.contains("image", ignoreCase = true) -> R.color.eq_success
                else -> R.color.eq_text_secondary
            }
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").takeIf { name.contains('.') }.orEmpty()

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<FileEntry>() {
            override fun areItemsTheSame(old: FileEntry, new: FileEntry) =
                old.path == new.path && old.isDir == new.isDir

            override fun areContentsTheSame(old: FileEntry, new: FileEntry) = old == new
        }
    }
}

/** Renders as an em dash. A backend that reports no size is not reporting -1 B. */
private const val UNKNOWN_SIZE = "—"

/**
 * Human readable byte count.
 *
 * Whole bytes below 1 KB (an empty file or a short config), then one decimal
 * place. `null` and negative values render as the unknown marker, because the
 * mockup's "-1 B" is a placeholder leaking a sentinel, not a size.
 */
internal fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes < 0L) return UNKNOWN_SIZE

    // "B" is index 0 on purpose. The previous list started at "KB" with the
    // counter at 0, so one division -- the KB step -- printed "MB", and
    // everything on screen was one unit too large.
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    // The bound is `lastIndex - 1`, not `lastIndex`: once the counter is on TB
    // there is nothing left to divide into, and continuing would print a TB
    // count in the low fractions for a petabyte-scale remote.
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    // No decimal below 1 KB: "512 B" not "512.0 B", which reads as a float bug.
    if (unit == 0) return "${value.toLong()} B"

    // 1023.99 KB must not print as "1024.0 KB" — a value that rounds up into
    // the next unit belongs in that unit, exactly as the division above would
    // have placed it.
    var text = String.format(Locale.US, "%.1f", value)
    if (text.toDouble() >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
        text = String.format(Locale.US, "%.1f", value)
    }
    return "$text ${units[unit]}"
}

/**
 * Formats an rclone `ModTime` as `yyyy-MM-dd`, or null if it is not a date.
 *
 * `ModTime` is an RFC 3339 timestamp in UTC, so the offset-carrying shapes come
 * first and the parsed instant is rendered in the device's zone. Parsing a UTC
 * timestamp as if it were local time shifts the date by a day either side of
 * midnight, which looks exactly like a fabricated value.
 *
 * Nothing here throws: an unparseable timestamp costs the row its date, never
 * the whole list.
 */
internal fun formatModified(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null

    for (pattern in TIMESTAMP_PATTERNS) {
        // Strict, and a fresh instance per call: SimpleDateFormat is not thread
        // safe, and strict is what rejects a fragment that merely starts with
        // a date instead of silently rendering it as one.
        val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        try {
            val parsed = parser.parse(text) ?: continue
            // Same zone as the parse, so an offset in the text is honoured
            // instead of being read as local wall-clock time.
            return SimpleDateFormat(OUTPUT_PATTERN, Locale.US)
                .apply { timeZone = parser.timeZone }
                .format(parsed)
        } catch (_: ParseException) {
            // Try the next shape.
        }
    }
    return null
}

private val TIMESTAMP_PATTERNS = arrayOf(
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd"
)

private const val OUTPUT_PATTERN = "yyyy-MM-dd"