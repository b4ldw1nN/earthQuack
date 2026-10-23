package io.earthquack.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.earthquack.app.EarthQuackService
import io.earthquack.app.MainActivity
import android.content.Intent
import io.earthquack.app.R
import io.earthquack.app.ServerConfig
import io.earthquack.app.tailscale.TailnetPeer
import io.earthquack.app.tailscale.TailscaleEngine
import io.earthquack.app.databinding.FragmentConnectionsBinding
import io.earthquack.app.databinding.ItemSettingsRowBinding
import io.earthquack.app.state.SyncStateLabel
import io.earthquack.app.state.SystemStatusProvider

import io.earthquack.app.tailscale.ExitNodeManager
import io.earthquack.app.tailscale.NativeExitNodeControl
import io.earthquack.app.tailscale.TailscaleNodeMonitor
import io.earthquack.app.tailscale.TailscaleLoginState
import io.earthquack.app.tailscale.TailscaleStateStore
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

    /** Exit-node selection (embedded Tailscale). Node must be Running. */
    private lateinit var exitNodes: ExitNodeManager

    /** Drives the embedded node to Running so the exit-node picker has something to talk to. */

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

        selected = if (ServerConfig.isConfigured(requireContext())) {
            ServerConfig.getHost(requireContext())
        } else {
            null
        }

        binding.btnManualAddress.setOnClickListener { promptManualAddress() }

        exitNodes = ExitNodeManager(NativeExitNodeControl)
        binding.btnChangeExitNode.setOnClickListener { pickExitNode() }
        binding.btnTailscaleLogin.setOnClickListener {
            TailscaleNodeMonitor.login(requireContext())
        }
        binding.btnServe.setOnClickListener {
            (activity as? MainActivity)?.pushSubScreen(ServeFragment(), "Serve", "")
        }
        binding.btnAdmin.setOnClickListener {
            (activity as? MainActivity)?.pushSubScreen(AdminFragment(), "Admin", "")
        }
        binding.btnBackground.setOnClickListener {
            val intent = Intent(requireContext(), EarthQuackService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            android.widget.Toast.makeText(requireContext(), R.string.connections_background_enabled, android.widget.Toast.LENGTH_SHORT).show()
        }

        observeTailscaleNode()
    }

    /**
     * Starts the embedded tsnet node (silently when already enrolled) and
     * shows the exit-node section once it reports Running.
     *
     * The picker is hidden, not disabled, until then: exit-node selection
     * requires a live node, and a visible control that cannot work is worse
     * than an absent one. Non-enrolled users get nothing here until the
     * browser-login flow has run — that UI belongs to the auth screen.
     */
    private fun observeTailscaleNode() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Re-render on every state change; cheap, and it keeps the card
            // in sync when the node drops out from under the screen. The
            // state comes from the app-wide monitor, so every screen sees
            // the same node instead of starting its own.
            launch {
                TailscaleNodeMonitor.state.collect { state ->
                    render()
                    if (state is TailscaleLoginState.Running) {
                        renderExitNodeSection()
                        binding.btnTailscaleLogin.visibility = View.GONE
                        binding.btnBackground.setText(R.string.connections_background_running)
                        refreshPeers()
                    } else {
                        hideExitNodeSection()
                        binding.btnBackground.setText(R.string.connections_background_start)
                    }
                    if (state is TailscaleLoginState.NeedsLogin && state.loginUrl.isNotBlank()) {
                        TailscaleNodeMonitor.openLoginTab(requireContext())
                    }
                }
            }

            binding.btnTailscaleLogin.visibility = View.VISIBLE

            TailscaleNodeMonitor.start(requireContext())
        }
    }

    private fun hideExitNodeSection() {
        if (_binding == null) return
        binding.headerExitNode.visibility = View.GONE
        binding.cardExitNode.visibility = View.GONE
    }

    /** Loads exit-node-capable peers and renders the current choice. */
    private fun renderExitNodeSection() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val (current, available) = try {
                withContext(Dispatchers.IO) {
                    exitNodes.currentExitNode() to exitNodes.availableExitNodes()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The node can go Stopped between the state emission and this
                // read. An unreadable picker is an absent one.
                hideExitNodeSection()
                return@launch
            }

            // No exit-node-capable peer and none selected: the section would
            // offer a dialog with only "None" in it. Say so by hiding it.
            if (available.isEmpty() && current == null) {
                hideExitNodeSection()
                return@launch
            }

            binding.headerExitNode.visibility = View.VISIBLE
            binding.cardExitNode.visibility = View.VISIBLE
            binding.textExitNodeValue.text = current?.name
                ?: getString(R.string.exit_node_none)
        }
    }

    /**
     * The picker: "None" plus every exit-node-capable peer, current one
     * pre-selected. Offline peers stay listed but are labelled — the only
     * approved exit node being temporarily down is a real situation, and
     * hiding it would leave the user with no way to select it.
     */
    private fun pickExitNode() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (current, available) = try {
                withContext(Dispatchers.IO) {
                    exitNodes.currentExitNode() to exitNodes.availableExitNodes()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.exit_node_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            val labels = mutableListOf(getString(R.string.exit_node_none))
            available.forEach { peer ->
                labels += if (peer.online) peer.name
                else getString(R.string.exit_node_offline, peer.name)
            }
            val checked = if (current == null) 0
            else available.indexOfFirst { it.id == current.id } + 1

            // setSingleChoiceItems takes a ListAdapter/Cursor/CharSequence[] —
            // not a Kotlin List, and Array<String> is not Array<CharSequence>.
            val items: Array<CharSequence> = labels.map { it as CharSequence }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.exit_node_picker_title)
                .setSingleChoiceItems(items, checked.coerceAtLeast(0)) { dialog, which ->
                    val chosen = if (which == 0) null else available.getOrNull(which - 1)
                    dialog.dismiss()
                    applyExitNode(chosen)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Applies the selection; EditPrefs is a JNI round-trip, hence IO. */
    private fun applyExitNode(peer: TailnetPeer?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { exitNodes.setExitNode(peer) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The manager already wrapped the backend's message.
                Toast.makeText(requireContext(), R.string.exit_node_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val message = if (peer == null) getString(R.string.exit_node_cleared)
            else getString(R.string.exit_node_set, peer.name)
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            renderExitNodeSection()
        }
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
        viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                statusProvider.read(mainActivity?.syncState?.value ?: SyncStateLabel.UNKNOWN)
            }
            if (_binding == null) return@launch
            renderWith(status)
        }
    }

    private fun renderWith(status: io.earthquack.app.state.SystemStatus) {

        // Status row: dot + text + subtext. The toggle is not shown because there
        // is nothing here to toggle -- sync start/stop lives on Home. A disabled
        // switch would imply a control that does nothing.
        binding.statusConnection.apply {
            toggle.visibility = View.GONE
            action.visibility = View.GONE
            // This screen is about the tailnet, so the card reports the node.
            // The desktop link is a separate service and is shown by its own
            // row; saying "Disconnected" here because the desktop binary is
            // down would misdescribe the node.
            val nodeRunning = TailscaleNodeMonitor.state.value is TailscaleLoginState.Running
            dot.setBackgroundResource(
                if (nodeRunning) R.drawable.eq_dot_hollow_success else R.drawable.eq_dot_hollow_muted
            )
            statusText.text = when {
                nodeRunning -> getString(R.string.state_connected)
                TailscaleNodeMonitor.state.value is TailscaleLoginState.Starting ->
                    getString(R.string.state_connecting)
                else -> getString(R.string.state_disconnected)
            }
            statusSub.text = when {
                nodeRunning -> getString(R.string.subtitle_connected_via_tailscale)
                else -> getString(R.string.connections_not_on_tailnet)
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
    private fun tailscaleNodeIp(): String? {
        val fromAuth = (TailscaleNodeMonitor.state.value as? TailscaleLoginState.Running)?.nodeIp
        if (!fromAuth.isNullOrBlank()) return fromAuth
        return runCatching {
            org.json.JSONObject(io.earthquack.app.tailscale.Tailscale.thisDevice()).optString("ip")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun emptyPeersMessage(): String {
        val addr = tailscaleNodeIp()
        return if (addr != null) getString(R.string.connections_none_found_hint, addr)
        else getString(R.string.connections_not_on_tailnet)
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
        binding.textPeerMessage.text = getString(R.string.connections_scanning_generic)

        viewLifecycleOwner.lifecycleScope.launch {
            // Deliberately NOT runCatching: it catches CancellationException and
            // swallows it, so a cancelled scan would carry on and touch a
            // destroyed view. Cancellation has to propagate.
            val found = try {
                withContext(Dispatchers.IO) {
                    TailnetPeer.parseList(io.earthquack.app.tailscale.Tailscale.peers()).map { it.ip }.filter { it.isNotBlank() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A probe failure is an empty result, not a crash.
                emptyList()
            }

            scanning = false
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
        val addr = tailscaleNodeIp()
        if (addr != null) {
            binding.rowThisDevice.root.visibility = View.VISIBLE
            binding.rowThisDevice.label.text = getString(R.string.connections_this_device)
            binding.rowThisDevice.value.text = addr
        } else {
            binding.rowThisDevice.root.visibility = View.GONE
        }
    }

    private fun refreshPeers() {
        val found = TailnetPeer.parseList(io.earthquack.app.tailscale.Tailscale.peers())
            .map { it.ip }.filter { it.isNotBlank() }
        discovered = found
        selected = found.firstOrNull { it == ServerConfig.getHost(requireContext()) }
            ?: found.firstOrNull() ?: selected
        render()
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
            return
        }

        binding.peerMessage.visibility = View.GONE
        list.visibility = View.VISIBLE

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
                applySelection()
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
                    io.earthquack.app.EarthQuackService::class.java
                ).setAction(io.earthquack.app.ACTION_STOP_SYNC)
            )
            // Brief delay so the service can tear down its SSE connection before
            // being asked to reconnect to a different host.
            binding.root.postDelayed({
                runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        requireContext(),
                        android.content.Intent(
                            requireContext(),
                            io.earthquack.app.EarthQuackService::class.java
                        ).setAction(io.earthquack.app.ACTION_START_SYNC)
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
