package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.MainActivity
import com.example.earthquack.R
import com.example.earthquack.ServerConfig
import com.example.earthquack.TailscaleDiscovery
import com.example.earthquack.databinding.FragmentConnectionsBinding
import com.example.earthquack.databinding.ItemSettingsRowBinding
import com.example.earthquack.state.SyncStateLabel
import com.example.earthquack.state.SystemStatusProvider
import com.example.earthquack.state.TailnetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tailscale connectivity.
 *
 * ## What this screen can actually know
 *
 * `TailscaleDiscovery` works by probing a configured list of candidate
 * addresses with an HTTP request. It does **not** parse `tailscale status`, so
 * it has no access to:
 *
 *   - device names ("archii")
 *   - tailnet name ("snares-hexatonic")
 *   - IPv6 addresses
 *   - per-peer latency
 *   - the full peer list, including offline peers
 *
 * The mockup showed all of those. Rather than invent them, this screen shows the
 * host EarthQuack is configured to sync with, the port, and whatever the scan
 * actually found. Peer rows are labelled by address because that is the only
 * identifier a probe yields.
 *
 * If `tailscale status` parsing is added later, the peer rows are the place it
 * will slot in — the row already carries an address and a state.
 */
class ConnectionsFragment : Fragment() {

    private var _binding: FragmentConnectionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var statusProvider: SystemStatusProvider
    private lateinit var tailnet: TailnetStatus

    private val mainActivity: MainActivity? get() = activity as? MainActivity

    /** Addresses found by the last scan, and the one currently highlighted. */
    private var discovered: List<String> = emptyList()

    /**
     * The address the app will use.
     *
     * Seeded from the configured host rather than left null. It was previously
     * null until a scan returned something, so "Use selected" sat disabled with
     * no explanation -- and in the common case (desktop not running) a scan
     * returns nothing, so it was *always* disabled. The configured host is
     * already the target; there is no reason to require a positive
     * confirmation to use it.
     *
     * Seeded in [onViewCreated], not at construction: a Fragment is instantiated
     * before it is attached to a context, so calling requireContext() in a
     * property initialiser throws "not attached to a context".
     */
    private var selected: String? = null
    private var scanning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusProvider = SystemStatusProvider(requireContext())
        tailnet = TailnetStatus(requireContext())

        selected = if (ServerConfig.isConfigured(requireContext())) {
            ServerConfig.getHost(requireContext())
        } else {
            null
        }

        binding.btnScan.setOnClickListener { scan() }
        binding.btnUse.setOnClickListener { applySelection() }
        binding.btnManualAddress.setOnClickListener { promptManualAddress() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /**
     * Renders current state.
     *
     * Returns early when the view is gone. A scan is asynchronous and can
     * outlive the screen: the user taps Scan, presses Back, and the result
     * arrives afterwards. Touching [binding] then is an NPE on a destroyed
     * view, which is a crash the user caused by doing nothing unreasonable.
     */
    private fun render() {
        if (_binding == null) return
        val status = statusProvider.read(mainActivity?.syncState?.value ?: SyncStateLabel.UNKNOWN)

        // Status row: dot + text + subtext. The toggle is not shown because there
        // is nothing here to toggle -- sync start/stop lives on Home. A disabled
        // switch would imply a control that does nothing.
        binding.statusConnection.apply {
            toggle.visibility = View.GONE
            action.visibility = View.GONE
            dot.setBackgroundResource(
                when {
                    status.isConnected -> R.drawable.eq_dot_hollow_success
                    else -> R.drawable.eq_dot_hollow_muted
                }
            )
            statusText.text = when {
                status.isConnected -> getString(R.string.state_connected)
                status.syncState == SyncStateLabel.CONNECTING -> getString(R.string.state_connecting)
                else -> getString(R.string.state_disconnected)
            }
            statusSub.text = when {
                status.isConnected -> getString(R.string.subtitle_connected_via_tailscale)
                !status.isConfigured -> getString(R.string.connections_not_configured)
                else -> getString(R.string.subtitle_tap_start)
            }
        }

        renderTailnetRow()

        if (status.isConfigured) {
            binding.rowIp.root.visibility = View.VISIBLE
            binding.rowIp.label.text = getString(R.string.home_row_ipv4)
            binding.rowIp.value.text = status.host
            binding.rowPeers.root.visibility = View.VISIBLE
            binding.rowPeers.label.text = getString(R.string.home_row_peers)
            binding.rowPeers.value.text = resources.getQuantityString(
                R.plurals.peer_count,
                discovered.size,
                discovered.size
            )
        } else {
            // No configured address: hide the rows rather than print the
            // placeholder as though it were a real peer.
            binding.rowIp.root.visibility = View.GONE
            binding.rowPeers.root.visibility = View.GONE
        }

        renderPeers()
    }

    /**
     * The "nothing found" message, chosen to name the likely cause.
     *
     * "No devices found, tap scan again" is not actionable. Whether the phone is
     * on the tailnet decides who to go and look at.
     */
    private fun emptyPeersMessage(): String = when (val t = tailnet.describe()) {
        is TailnetStatus.Status.Connected ->
            getString(R.string.connections_none_found_hint, t.address)
        else -> getString(R.string.connections_not_on_tailnet)
    }

    /**
     * Probes every configured candidate address.
     *
     * Runs on [Dispatchers.IO] because each probe is an HTTP request with a
     * timeout — a synchronous scan on the main thread would freeze the UI for
     * the worst case of every candidate.
     */
    private fun scan() {
        if (scanning) return
        scanning = true
        binding.btnScan.isEnabled = false
        binding.btnScan.setText(R.string.connections_scanning_generic)
        binding.textPeerMessage.text = getString(R.string.connections_scanning_generic)

        viewLifecycleOwner.lifecycleScope.launch {
            // Deliberately NOT runCatching: it catches CancellationException and
            // swallows it, so a cancelled scan would carry on and touch a
            // destroyed view. Cancellation has to propagate.
            val found = try {
                withContext(Dispatchers.IO) {
                    TailscaleDiscovery.discoverAllWorkingServers()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A probe failure is an empty result, not a crash.
                emptyList()
            }

            scanning = false
            _binding?.let {
                it.btnScan.isEnabled = true
                it.btnScan.setText(R.string.connections_scan)
            }
            discovered = found
            // Prefer the current host if the scan confirmed it; otherwise keep
            // the existing selection rather than resetting to the first result.
            selected = found.firstOrNull { it == selected } ?: selected ?: found.firstOrNull()
            render()

            _binding?.let { b ->
                if (found.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.connections_none_found,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Shows the phone's own tailnet address, or states plainly that it has none.
     *
     * This is the single most useful row for diagnosing "Tailscale is not
     * working": it separates a phone-side problem from a desktop-side one
     * without the user having to read logs.
     */
    private fun renderTailnetRow() {
        when (val t = tailnet.describe()) {
            is TailnetStatus.Status.Connected -> {
                binding.rowThisDevice.root.visibility = View.VISIBLE
                binding.rowThisDevice.label.text = getString(R.string.connections_this_device)
                binding.rowThisDevice.value.text = t.address
            }
            else -> {
                // No address to show, so the row would read "This device —".
                // The condition is stated in the peer message instead.
                binding.rowThisDevice.root.visibility = View.GONE
            }
        }
    }

    private fun renderPeers() {
        val list = binding.listPeers
        list.removeAllViews()

        binding.textPeerCount.visibility =
            if (discovered.isEmpty()) View.GONE else View.VISIBLE
        binding.textPeerCount.text = discovered.size.toString()

        if (discovered.isEmpty()) {
            list.visibility = View.GONE
            binding.peerMessage.visibility = View.VISIBLE
            if (binding.textPeerMessage.text.isNullOrBlank() ||
                binding.textPeerMessage.text == getString(R.string.connections_scanning_generic)
            ) {
                // Name the likely cause rather than repeating "tap scan again".
                binding.textPeerMessage.text = emptyPeersMessage()
            }
            // Enabled whenever there is a target, NOT only when a scan returned
            // something. Forcing this off here is what left the button dead in
            // the common case: the desktop is not running, so the scan finds
            // nothing, so the one control that could (re)apply the address was
            // the one control that got disabled.
            binding.btnUse.isEnabled = selected != null
            return
        }

        binding.peerMessage.visibility = View.GONE
        list.visibility = View.VISIBLE
        binding.btnUse.isEnabled = selected != null

        discovered.forEach { address ->
            val row = ItemSettingsRowBinding.inflate(layoutInflater, list, false)
            row.rowIcon.setImageResource(R.drawable.ic_eq_computer)
            row.rowTitle.text = address

            // A probe answers "is the EarthQuack service reachable", which is
            // stronger than "the host is up" but weaker than "Tailscale is
            // healthy". The label says exactly what was checked.
            val isCurrent = address == ServerConfig.getHost(requireContext())
            row.rowSubtitle.text = if (isCurrent) {
                getString(R.string.connections_peer_current)
            } else {
                getString(R.string.connections_peer_found)
            }
            row.rowIcon.imageTintList = resources.getColorStateList(
                if (isCurrent) R.color.eq_success else R.color.eq_text_secondary,
                requireContext().theme
            )
            // Selection is shown by the subtitle, not only by a tint: colour
            // alone would be the sole signal for a state that matters.
            // Visible selected state. The previous version signalled selection
            // only through a subtitle difference, which is too subtle to notice
            // and impossible to spot at a glance in a list.
            val isSelected = address == selected
            row.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_eq_pill_primary else android.R.color.transparent
            )
            row.rowChevron.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            row.rowChevron.imageTintList = resources.getColorStateList(
                R.color.eq_primary, requireContext().theme
            )
            row.root.isSelected = isSelected
            row.root.setOnClickListener {
                selected = address
                render()
            }
            list.addView(row.root)
        }
    }

    /**
     * Sets the desktop address by hand.
     *
     * Discovery can only confirm what is already reachable, so it cannot be the
     * only way to configure the host. Validated rather than accepted blindly:
     * a typo here would silently produce "Disconnected" forever with no clue
     * why.
     */
    private fun promptManualAddress() {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(ServerConfig.getHost(requireContext()))
            setSelection(text.length)
            hint = getString(R.string.connections_manual_hint)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_manual_title)
            .setView(
                android.widget.FrameLayout(requireContext()).apply {
                    val pad = resources.getDimensionPixelSize(R.dimen.eq_gap_lg)
                    setPadding(pad, pad / 2, pad, 0)
                    addView(input)
                }
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = input.text.toString().trim()
                if (!isPlausibleHost(host)) {
                    Toast.makeText(
                        requireContext(),
                        R.string.connections_manual_invalid,
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                selected = host
                applyHost(host)
            }
            .show()
    }

    /**
     * Cheap sanity check on a typed address.
     *
     * Not a full parser: it rejects the obvious mistakes (empty, a bare port, a
     * path) without pretending to validate IPv6 rigorously. rclone and OkHttp
     * will produce the real error if something slips through.
     */
    private fun isPlausibleHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (host.any { it.isWhitespace() }) return false
        if (host.contains('/') || host.contains(':')) return false
        return host.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) ||
            host.matches(Regex("^[0-9a-fA-F:]{2,}$")) && host.contains(':')
    }

    /**
     * Points EarthQuack at the selected address and restarts sync.
     *
     * Restarts the service because a running service holds the previous host in
     * memory; changing the preference alone would look like it did nothing until
     * the app was next launched.
     */
    private fun applySelection() {
        val host = selected ?: return
        applyHost(host)
    }

    /** Saves [host] and restarts the service if it was running. */
    private fun applyHost(host: String) {
        val wasRunning = statusProvider.isServiceRunning()

        ServerConfig.setHost(requireContext(), host)

        if (wasRunning) {
            val context = requireContext()
            context.startService(
                android.content.Intent(
                    context,
                    com.example.earthquack.EarthQuackService::class.java
                ).setAction(com.example.earthquack.ACTION_STOP_SYNC)
            )
            // Brief delay so the service can tear down its SSE connection before
            // being asked to reconnect to a different host.
            binding.root.postDelayed({
                runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        requireContext(),
                        android.content.Intent(
                            requireContext(),
                            com.example.earthquack.EarthQuackService::class.java
                        ).setAction(com.example.earthquack.ACTION_START_SYNC)
                    )
                }
                render()
            }, RESTART_DELAY_MS)
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.connections_using, host),
            Toast.LENGTH_SHORT
        ).show()
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val RESTART_DELAY_MS = 600L
    }
}
