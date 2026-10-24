package io.earthquack.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.earthquack.app.R
import io.earthquack.app.databinding.FragmentDiagnosticsBinding
import io.earthquack.app.databinding.PartialDetailRowBinding
import io.earthquack.app.sftp.SftpServerControllerImpl
import io.earthquack.app.state.StorageRepository
import io.earthquack.app.storage.RcloneHealthProbe
import io.earthquack.app.tailscale.ExitNodeManager
import io.earthquack.app.tailscale.NativeExitNodeControl
import io.earthquack.app.tailscale.NativeServeControl
import io.earthquack.app.tailscale.ServeManager
import io.earthquack.app.tailscale.TailscaleLoginState
import io.earthquack.app.tailscale.TailscaleNodeMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostics — one honest page about the app's network state.
 *
 * Every line here is measured at the moment the screen is opened: the node's
 * state and address, the peers it can see, the ports it publishes, the SFTP
 * server, and whether each rclone remote actually answers. Nothing is a
 * placeholder, and the whole page can be copied into a bug report.
 */
class DiagnosticsFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var storageRepository: StorageRepository
    private lateinit var sftpController: SftpServerControllerImpl
    private lateinit var exitNodes: ExitNodeManager

    private val serveManager = ServeManager(NativeServeControl)

    private var probeJob: kotlinx.coroutines.Job? = null

    /** The report as last rendered, for the copy action. */
    private var report: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storageRepository = StorageRepository(requireContext())
        sftpController = SftpServerControllerImpl(requireContext())
        exitNodes = ExitNodeManager(NativeExitNodeControl)

        binding.btnRefresh.setOnClickListener { refresh(force = true) }
        binding.btnCopy.setOnClickListener { copyReport() }
        binding.btnSelfTest.setOnClickListener { runSelfTest() }
        binding.rowSocks.root.setOnClickListener {
            val url = io.earthquack.app.tailscale.TailnetRoute.proxyUrl() ?: return@setOnClickListener
            val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("SOCKS5 Proxy", url))
            Toast.makeText(requireContext(), "Copied $url", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TailscaleNodeMonitor.state.collect {
                    refreshNodeDetails()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refreshNodeDetails() {
        val b = _binding ?: return
        val nodeState = TailscaleNodeMonitor.state.value
        val nodeIp = (nodeState as? TailscaleLoginState.Running)?.nodeIp
        val socksPort = io.earthquack.app.tailscale.TailnetRoute.socksPort()

        b.rowNodeState.label.text = getString(R.string.diagnostics_node_state)
        b.rowNodeState.value.text = nodeState.toString()
        b.rowNodeIp.label.text = getString(R.string.diagnostics_node_ip)
        b.rowNodeIp.value.text = nodeIp ?: getString(R.string.diagnostics_none)
        b.rowSocks.label.text = getString(R.string.diagnostics_socks_port)
        b.rowSocks.value.text = if (socksPort > 0) socksPort.toString() else getString(R.string.diagnostics_none)

        val peersJson = runCatching { io.earthquack.app.tailscale.Tailscale.peers() }.getOrDefault("[]")
        val peers = io.earthquack.app.tailscale.TailnetPeer.parseList(peersJson)
        val exitNode = peers.firstOrNull { it.isExitNode }?.name
        b.rowExitNode.label.text = getString(R.string.diagnostics_exit_node)
        b.rowExitNode.value.text = exitNode ?: getString(R.string.diagnostics_none)
    }

    private fun refresh(force: Boolean = false) {
        val b = _binding ?: return
        val lines = mutableListOf<String>()

        // ── Node ────────────────────────────────────────────────────────────
        refreshNodeDetails()
        val nodeState = TailscaleNodeMonitor.state.value
        val nodeIp = (nodeState as? TailscaleLoginState.Running)?.nodeIp
        val socksPort = io.earthquack.app.tailscale.TailnetRoute.socksPort()

        lines += "node: $nodeState"
        lines += "node ip: ${nodeIp ?: "-"}"
        lines += "socks port: ${if (socksPort > 0) socksPort.toString() else "-"}"

        // ── Peers ───────────────────────────────────────────────────────────
        val peersJson = runCatching { io.earthquack.app.tailscale.Tailscale.peers() }
            .getOrDefault("[]")
        val peers = io.earthquack.app.tailscale.TailnetPeer.parseList(peersJson)
        renderPeers(peers)
        lines += "peers: ${peerSummary(peers)}"

        // ── Services ────────────────────────────────────────────────────────
        val sftpStatus = sftpController.status()
        b.rowSftp.label.text = getString(R.string.diagnostics_sftp_server)
        b.rowSftp.value.text = when (sftpStatus) {
            is io.earthquack.app.sftp.SftpServerStatus.Running ->
                getString(R.string.services_sftp_running, sftpStatus.port)
            is io.earthquack.app.sftp.SftpServerStatus.Error -> sftpStatus.message
            else -> getString(R.string.state_stopped)
        }
        lines += "sftp server: ${b.rowSftp.value.text}"

        val serves = serveManager.activeServes()
        b.rowServes.label.text = getString(R.string.diagnostics_serves)
        b.rowServes.value.text = if (serves.isEmpty()) {
            getString(R.string.diagnostics_none)
        } else {
            serves.joinToString(", ") { "${it.localPort}→${it.tailnetPort}" }
        }
        lines += "serves: ${b.rowServes.value.text}"

        val exitNode = peers.firstOrNull { it.isExitNode }?.name
        b.rowExitNode.label.text = getString(R.string.diagnostics_exit_node)
        b.rowExitNode.value.text = exitNode ?: getString(R.string.diagnostics_none)
        lines += "exit node: ${exitNode ?: "-"}"

        val desktop = context?.let { io.earthquack.app.ServerConfig.getHost(it) } ?: ""
        b.rowDesktop.label.text = getString(R.string.diagnostics_desktop)
        b.rowDesktop.value.text = desktop
        lines += "desktop: $desktop"

        val capabilities = io.earthquack.app.DesktopCapabilityStore.last()
        b.rowCapabilities.label.text = getString(R.string.diagnostics_capabilities)
        b.rowCapabilities.value.text = capabilities?.summary()
            ?: getString(R.string.diagnostics_not_queried)
        lines += "desktop capabilities: ${capabilities?.summary() ?: "not queried"}"

        // ── Remotes ─────────────────────────────────────────────────────────
        probeJob?.cancel()
        probeJob = viewLifecycleOwner.lifecycleScope.launch {
            val remotes = withContext(Dispatchers.IO) { storageRepository.remotesOrNull() }
            if (_binding == null) return@launch
            val names = remotes?.map { it.name } ?: emptyList()
            val health = mutableMapOf<String, RcloneHealthProbe.Health>()
            val remoteRows = setupRemotes(remotes.orEmpty())

            storageRepository.probeRemotes(names, forceRefresh = force) { name, result ->
                if (_binding == null) return@probeRemotes
                health[name] = result
                val row = remoteRows[name]
                if (row != null) {
                    val remote = remotes?.firstOrNull { it.name == name }
                    val route = if (remote?.routedViaTailscale == true) " · via Tailscale" else ""
                    val detail = result.detail?.let { " — ${it.take(80)}" } ?: ""
                    row.value.text = "$route · ${result.state.name.lowercase()}$detail"
                }
            }

            if (_binding == null) return@launch
            lines += "remotes:"
            names.forEach { name ->
                val state = health[name]?.state ?: RcloneHealthProbe.State.Unknown
                lines += "  - $name: ${state.name.lowercase()}"
            }
            report = lines.joinToString("\n")
        }
    }

    private fun renderPeers(peers: List<io.earthquack.app.tailscale.TailnetPeer>) {
        val b = _binding ?: return
        val container = b.cardPeers
        container.removeAllViews()
        if (peers.isEmpty()) {
            val rowBinding = PartialDetailRowBinding.inflate(layoutInflater, container, false)
            rowBinding.label.text = getString(R.string.diagnostics_no_peers)
            rowBinding.value.text = ""
            container.addView(rowBinding.root)
            return
        }
        peers.forEach { peer ->
            val flags = buildList {
                if (peer.online) add("online") else add("offline")
                if (peer.exitNodeOption) add("exit node")
                if (peer.isExitNode) add("in use")
            }.joinToString(", ")
            val address = listOfNotNull(peer.dnsName.takeIf { it.isNotBlank() }, peer.ip).joinToString(" · ")
            val rowBinding = PartialDetailRowBinding.inflate(layoutInflater, container, false)
            rowBinding.label.text = peer.name.ifBlank { peer.ip }
            rowBinding.value.text = "$address · $flags"
            container.addView(rowBinding.root)
        }
    }

    private fun setupRemotes(
        remotes: List<StorageRepository.RemoteSummary>,
    ): Map<String, PartialDetailRowBinding> {
        val b = _binding ?: return emptyMap()
        val container = b.cardRemotes
        container.removeAllViews()
        if (remotes.isEmpty()) {
            val emptyRow = PartialDetailRowBinding.inflate(layoutInflater, container, false)
            emptyRow.label.text = getString(R.string.state_no_remotes)
            emptyRow.value.text = ""
            container.addView(emptyRow.root)
            return emptyMap()
        }
        val rows = mutableMapOf<String, PartialDetailRowBinding>()
        remotes.forEach { remote ->
            val rowBinding = PartialDetailRowBinding.inflate(layoutInflater, container, false)
            rowBinding.label.text = remote.name
            val route = if (remote.routedViaTailscale) " · via Tailscale" else ""
            rowBinding.value.text = "$route · probing…"
            container.addView(rowBinding.root)
            rows[remote.name] = rowBinding
        }
        return rows
    }

    private fun peerSummary(peers: List<io.earthquack.app.tailscale.TailnetPeer>): String {
        if (peers.isEmpty()) return "none"
        return peers.joinToString(", ") { "${it.name.ifBlank { it.ip }}${if (it.online) "" else " (offline)"}" }
    }

    private fun copyReport() {
        val b = _binding
        val text = report.ifBlank {
            if (b != null) {
                listOf(
                    "node: ${b.rowNodeState.value.text}",
                    "node ip: ${b.rowNodeIp.value.text}",
                    "socks port: ${b.rowSocks.value.text}",
                    "sftp server: ${b.rowSftp.value.text}",
                    "serves: ${b.rowServes.value.text}",
                    "exit node: ${b.rowExitNode.value.text}",
                    "desktop: ${b.rowDesktop.value.text}",
                    "desktop capabilities: ${b.rowCapabilities.value.text}"
                ).joinToString("\n")
            } else getString(R.string.diagnostics_not_queried)
        }
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("earthQuack diagnostics", text))
        Toast.makeText(ctx, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun runSelfTest() {
        val b = _binding ?: return
        val card = b.cardSelfTest
        card.visibility = View.VISIBLE
        card.removeAllViews()

        val runningRow = PartialDetailRowBinding.inflate(layoutInflater, card, false)
        runningRow.label.text = "Running test…"
        runningRow.value.text = "Please wait"
        card.addView(runningRow.root)

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val results = io.earthquack.app.diagnostics.NetworkSelfTest.runAll(ctx)
            if (_binding == null) return@launch
            card.removeAllViews()
            results.forEach { res ->
                val rowBinding = PartialDetailRowBinding.inflate(layoutInflater, card, false)
                val statusIcon = if (res.passed) "✔ " else "✖ "
                rowBinding.label.text = "$statusIcon${res.name}"
                rowBinding.value.text = res.detail
                card.addView(rowBinding.root)
            }
        }
    }

    override fun onDestroyView() {
        probeJob?.cancel()
        probeJob = null
        super.onDestroyView()
        _binding = null
    }
}
