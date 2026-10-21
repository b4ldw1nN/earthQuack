package com.example.earthquack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentDiagnosticsBinding
import com.example.earthquack.databinding.PartialDetailRowBinding
import com.example.earthquack.sftp.SftpServerControllerImpl
import com.example.earthquack.state.StorageRepository
import com.example.earthquack.storage.RcloneHealthProbe
import com.example.earthquack.tailscale.ExitNodeManager
import com.example.earthquack.tailscale.NativeExitNodeControl
import com.example.earthquack.tailscale.NativeServeControl
import com.example.earthquack.tailscale.ServeManager
import com.example.earthquack.tailscale.TailscaleLoginState
import com.example.earthquack.tailscale.TailscaleNodeMonitor
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

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnCopy.setOnClickListener { copyReport() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val b = _binding ?: return
        val lines = mutableListOf<String>()

        // ── Node ────────────────────────────────────────────────────────────
        val nodeState = TailscaleNodeMonitor.state.value
        val nodeIp = (nodeState as? TailscaleLoginState.Running)?.nodeIp
        val socksPort = com.example.earthquack.tailscale.TailnetRoute.socksPort()

        b.rowNodeState.label.text = getString(R.string.diagnostics_node_state)
        b.rowNodeState.value.text = nodeState.toString()
        b.rowNodeIp.label.text = getString(R.string.diagnostics_node_ip)
        b.rowNodeIp.value.text = nodeIp ?: getString(R.string.diagnostics_none)
        b.rowSocks.label.text = getString(R.string.diagnostics_socks_port)
        b.rowSocks.value.text = if (socksPort > 0) socksPort.toString() else getString(R.string.diagnostics_none)

        lines += "node: ${nodeState}"
        lines += "node ip: ${nodeIp ?: "-"}"
        lines += "socks port: ${if (socksPort > 0) socksPort.toString() else "-"}"

        // ── Peers ───────────────────────────────────────────────────────────
        val peers = runCatching { com.example.earthquack.tailscale.Tailscale.peers() }
            .getOrDefault("[]")
        renderPeers(peers)
        lines += "peers: ${peerSummary(peers)}"

        // ── Services ────────────────────────────────────────────────────────
        val sftpStatus = sftpController.status()
        b.rowSftp.label.text = getString(R.string.diagnostics_sftp_server)
        b.rowSftp.value.text = when (sftpStatus) {
            is com.example.earthquack.sftp.SftpServerStatus.Running ->
                getString(R.string.services_sftp_running, sftpStatus.port)
            is com.example.earthquack.sftp.SftpServerStatus.Error -> sftpStatus.message
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

        val exitNode = runCatching { exitNodes.currentExitNode()?.name }.getOrNull()
        b.rowExitNode.label.text = getString(R.string.diagnostics_exit_node)
        b.rowExitNode.value.text = exitNode ?: getString(R.string.diagnostics_none)
        lines += "exit node: ${exitNode ?: "-"}"

        val desktop = com.example.earthquack.ServerConfig.getHost(requireContext())
        b.rowDesktop.label.text = getString(R.string.diagnostics_desktop)
        b.rowDesktop.value.text = desktop
        lines += "desktop: $desktop"

        val capabilities = com.example.earthquack.DesktopCapabilityStore.last()
        b.rowCapabilities.label.text = getString(R.string.diagnostics_capabilities)
        b.rowCapabilities.value.text = capabilities?.summary()
            ?: getString(R.string.diagnostics_not_queried)
        lines += "desktop capabilities: ${capabilities?.summary() ?: "not queried"}"

        // ── Remotes ─────────────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            val remotes = withContext(Dispatchers.IO) { storageRepository.remotesOrNull() }
            val names = remotes?.map { it.name } ?: emptyList()
            val health = mutableMapOf<String, RcloneHealthProbe.Health>()
            renderRemotes(remotes.orEmpty(), health)
            storageRepository.probeRemotes(names) { name, result ->
                health[name] = result
                renderRemotes(remotes.orEmpty(), health)
            }
            lines += "remotes:"
            names.forEach { name ->
                val state = health[name]?.state ?: RcloneHealthProbe.State.Unknown
                lines += "  - $name: ${state.name.lowercase()}"
            }
            report = lines.joinToString("\n")
        }
    }

    private fun renderPeers(peersJson: String) {
        val container = binding.cardPeers
        container.removeAllViews()
        val peers = com.example.earthquack.tailscale.TailnetPeer.parseList(peersJson)
        if (peers.isEmpty()) {
            container.addView(row(container, getString(R.string.diagnostics_no_peers), ""))
            return
        }
        peers.forEach { peer ->
            val flags = buildList {
                if (peer.online) add("online") else add("offline")
                if (peer.exitNodeOption) add("exit node")
                if (peer.isExitNode) add("in use")
            }.joinToString(", ")
            container.addView(row(container, peer.name.ifBlank { peer.ip }, "${peer.ip} · $flags"))
        }
    }

    private fun renderRemotes(
        remotes: List<StorageRepository.RemoteSummary>,
        health: Map<String, RcloneHealthProbe.Health>,
    ) {
        val container = binding.cardRemotes
        container.removeAllViews()
        if (remotes.isEmpty()) {
            container.addView(row(container, getString(R.string.state_no_remotes), ""))
            return
        }
        remotes.forEach { remote ->
            val result = health[remote.name]
            val state = result?.state ?: RcloneHealthProbe.State.Unknown
            val route = if (remote.routedViaTailscale) " · via Tailscale" else ""
            val detail = result?.detail?.let { " — ${it.take(80)}" } ?: ""
            container.addView(row(container, remote.name, "$route · ${state.name.lowercase()}$detail"))
        }
    }

    private fun row(parent: ViewGroup, label: String, value: String): View {
        val rowBinding = PartialDetailRowBinding.inflate(layoutInflater, parent, false)
        rowBinding.label.text = label
        rowBinding.value.text = value
        return rowBinding.root
    }

    private fun peerSummary(peersJson: String): String {
        val peers = com.example.earthquack.tailscale.TailnetPeer.parseList(peersJson)
        if (peers.isEmpty()) return "none"
        return peers.joinToString(", ") { "${it.name.ifBlank { it.ip }}${if (it.online) "" else " (offline)"}" }
    }

    private fun copyReport() {
        val text = report.ifBlank { getString(R.string.diagnostics_not_queried) }
        val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("earthQuack diagnostics", text))
        Toast.makeText(requireContext(), R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
