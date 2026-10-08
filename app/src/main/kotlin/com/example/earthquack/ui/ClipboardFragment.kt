package com.example.earthquack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.earthquack.MainActivity
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentClipboardBinding
import com.example.earthquack.databinding.ItemHistoryRowBinding
import com.example.earthquack.state.ClipboardEntry
import com.example.earthquack.state.ClipboardHistoryStore
import com.example.earthquack.state.SyncStateLabel
import com.example.earthquack.state.SystemStatusProvider

/**
 * Clipboard sync and history.
 *
 * ## History is real
 *
 * Entries come from [ClipboardHistoryStore], which `EarthQuackService` writes at
 * the two moments a value actually changes hands. Nothing is invented: if nothing
 * has synced, the section says so.
 *
 * The existing sync controls are unchanged. Send/Receive delegate to the same
 * service intents the old layout used; this screen adds presentation and history,
 * it does not replace the mechanism.
 *
 * ## Handling of clipboard content
 *
 * Clipboard contents are frequently secrets. They are stored app-private and
 * never logged in full ([ClipboardEntry.isSensitive] suppresses that), and this
 * screen truncates for display without ever putting a value into a content
 * description that a screen reader or an accessibility service might capture
 * beyond the user's control.
 */
class ClipboardFragment : Fragment() {

    private var _binding: FragmentClipboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var historyStore: ClipboardHistoryStore
    private lateinit var statusProvider: SystemStatusProvider

    private val mainActivity: MainActivity? get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClipboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        historyStore = ClipboardHistoryStore(requireContext())
        statusProvider = SystemStatusProvider(requireContext())

        binding.btnClear.setOnClickListener { confirmClear() }
        // Send/Receive act on the live sync service, which is the authoritative
        // channel. They do not write to history themselves -- the service does
        // that when the transfer actually succeeds, so a failed send cannot
        // leave a phantom entry.
        binding.btnSend.setOnClickListener { startSync() }
        binding.btnReceive.setOnClickListener { startSync() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val status = statusProvider.read(mainActivity?.syncState?.value ?: SyncStateLabel.UNKNOWN)
        val entries = historyStore.list()

        binding.statusClipboard.apply {
            toggle.visibility = View.GONE
            action.visibility = View.GONE
            dot.setBackgroundResource(
                if (status.isConnected) R.drawable.eq_dot_hollow_success
                else R.drawable.eq_dot_hollow_muted
            )
            statusText.text = when {
                status.isConnected -> getString(R.string.state_connected)
                status.syncState == SyncStateLabel.CONNECTING -> getString(R.string.state_connecting)
                status.syncState == SyncStateLabel.PAUSED -> getString(R.string.state_paused)
                status.isServiceRunning -> getString(R.string.state_starting)
                else -> getString(R.string.state_disconnected)
            }
            statusSub.text = status.host
                ?.takeIf { ServerConfigured(it) }
                ?.let { getString(R.string.clipboard_connected_to, it) }
                ?: getString(R.string.subtitle_not_configured)
        }

        binding.textLastSync.text = entries.firstOrNull()
            ?.let { relativeTime(it.timestampMillis) }
            ?: getString(R.string.clipboard_never_synced)

        renderDecryptWarning(entries)
        renderCurrent(entries.firstOrNull())
        renderHistory(entries)
    }

    /** True only for a real configured host, never the shipped placeholder. */
    private fun ServerConfigured(host: String) =
        com.example.earthquack.ServerConfig.isConfigured(requireContext())

    /**
     * Warns when the desktop is encrypting and this app is not.
     *
     * Detected from the data rather than assumed: rclone-free, just looking for
     * the marker the desktop daemon prefixes onto encrypted payloads while
     * `ServerConfig.isAesEnabled` is false. That combination has exactly one
     * cause and exactly one fix, so naming it is more useful than showing
     * ciphertext and letting the user guess.
     */
    private fun renderDecryptWarning(entries: List<ClipboardEntry>) {
        val encryptionOn = com.example.earthquack.ServerConfig.isAesEnabled(requireContext())
        val hasCiphertext = entries.any { it.text.startsWith(AES_MARKER) }

        val show = hasCiphertext && !encryptionOn
        binding.cardDecryptWarning.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.btnFixEncryption.setOnClickListener {
                startActivity(
                    android.content.Intent(
                        requireContext(),
                        com.example.earthquack.ui.sub.SecurityActivity::class.java
                    )
                )
            }
        }
    }

    private fun renderCurrent(entry: ClipboardEntry?) {
        if (entry == null) {
            binding.cardCurrent.visibility = View.GONE
            binding.textCurrentEmpty.visibility = View.VISIBLE
            return
        }

        binding.cardCurrent.visibility = View.VISIBLE
        binding.textCurrentEmpty.visibility = View.GONE
        binding.textCurrentValue.text = entry.text
        binding.textCurrentMeta.text = getString(
            R.string.clipboard_chars,
            entry.text.length
        ) + " · " + relativeTime(entry.timestampMillis)

        binding.btnCopyCurrent.setOnClickListener { copyToClipboard(entry.text) }
    }

    private fun renderHistory(entries: List<ClipboardEntry>) {
        val list = binding.listHistory
        list.removeAllViews()

        // "Current item" is the newest entry, so history shows the rest.
        val older = entries.drop(1)

        if (older.isEmpty()) {
            binding.headerHistory.visibility = View.GONE
            binding.historyEmpty.visibility = View.VISIBLE
            binding.btnClear.visibility = View.GONE
            return
        }

        binding.headerHistory.visibility = View.VISIBLE
        binding.historyEmpty.visibility = View.GONE
        binding.btnClear.visibility = View.VISIBLE
        binding.listHistory.visibility = View.VISIBLE

        older.forEachIndexed { index, entry ->
            val row = ItemHistoryRowBinding.inflate(layoutInflater, list, false)
            row.historyText.text = entry.text

            val direction = when (entry.direction) {
                ClipboardEntry.Direction.SENT -> getString(R.string.direction_sent)
                ClipboardEntry.Direction.RECEIVED -> getString(R.string.direction_received)
            }
            row.historyMeta.text = "$direction · ${getString(R.string.clipboard_chars, entry.text.length)} · ${relativeTime(entry.timestampMillis)}"

            row.historyIcon.imageTintList = resources.getColorStateList(
                when (entry.direction) {
                    ClipboardEntry.Direction.SENT -> R.color.eq_primary
                    ClipboardEntry.Direction.RECEIVED -> R.color.eq_text_muted
                },
                requireContext().theme
            )

            row.historyCopy.setOnClickListener { copyToClipboard(entry.text) }
            row.root.setOnClickListener { copyToClipboard(entry.text) }

            list.addView(row.root)
            if (index < older.lastIndex) list.addView(divider())
        }
    }

    private fun divider(): View = View(requireContext()).apply {
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.eq_divider)
        ).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
            marginEnd = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
        }
        setBackgroundColor(resources.getColor(R.color.eq_border, requireContext().theme))
    }

    /**
     * Starts (or resumes) the clipboard service.
     *
     * Both Send and Receive route through this because the service pushes
     * automatically: the desktop's clipboard is mirrored by the SSE connection,
     * so "receive" is really "make sure we are listening". Offering two
     * identical buttons would imply a distinction the transport does not have,
     * so they start the service and let it do what it already does.
     */
    private fun startSync() {
        val context = requireContext()
        if (!statusProvider.isServiceRunning()) {
            context.startService(
                android.content.Intent(
                    context,
                    com.example.earthquack.EarthQuackService::class.java
                ).setAction(com.example.earthquack.ACTION_START_SYNC)
            )
        }
        Toast.makeText(context, R.string.clipboard_sync_running, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("earthQuack", text))
        Toast.makeText(requireContext(), R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clipboard_clear)
            .setMessage(R.string.clipboard_clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clipboard_clear) { _, _ ->
                historyStore.clear()
                render()
                Toast.makeText(
                    requireContext(),
                    R.string.clipboard_history_cleared,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    /**
     * Relative time, falling back to an absolute clock time.
     *
     * [DateUtils.getRelativeTimeSpanString] returns a relative form such as
     * "2 minutes ago" which is friendlier, but it collapses anything older than
     * a week to a date the user did not ask for; the absolute fallback keeps the
     * list readable either way.
     */
    private fun relativeTime(timestampMillis: Long): String {
        if (timestampMillis <= 0L) return "—"
        val now = System.currentTimeMillis()
        if (now - timestampMillis > DateUtils.WEEK_IN_MILLIS) {
            return DateUtils.formatDateTime(
                requireContext(),
                timestampMillis,
                DateUtils.FORMAT_SHOW_TIME
            )
        }
        return DateUtils.getRelativeTimeSpanString(
            timestampMillis,
            now,
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /**
         * Prefix the desktop daemon puts on AES-encrypted clipboard payloads.
         *
         * Used only to detect a configuration mismatch, never to decrypt: the
         * app holds no key when encryption is off, and the payload is not
         * reversible from this side.
         */
        const val AES_MARKER = "AES:"
    }
}
