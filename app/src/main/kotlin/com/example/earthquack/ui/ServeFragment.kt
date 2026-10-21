package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentServeBinding
import com.example.earthquack.databinding.PartialDetailRowBinding
import com.example.earthquack.sftp.SftpServerControllerImpl
import com.example.earthquack.sftp.SftpServerStatus
import com.example.earthquack.tailscale.NativeServeControl
import com.example.earthquack.tailscale.ServeEntry
import com.example.earthquack.tailscale.ServeManager
import com.example.earthquack.tailscale.ServeStore
import com.example.earthquack.tailscale.TailscaleNodeMonitor
import kotlinx.coroutines.launch

/**
 * Serve / Funnel — what this phone publishes to the tailnet.
 *
 * The rows are the services that can be published; the card below lists what
 * is published right now. The choice survives a node restart, because the
 * intent is the user's, not the node's.
 */
class ServeFragment : Fragment() {

    private var _binding: FragmentServeBinding? = null
    private val binding get() = _binding!!

    private lateinit var manager: ServeManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentServeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        manager = ServeManager(NativeServeControl, store = ServeStore(requireContext()))

        binding.rowSftp.label.text = getString(R.string.serve_sftp)
        binding.rowClipboard.label.text = getString(R.string.serve_clipboard_api)

        // The SFTP server publishes itself when it starts; the clipboard API
        // is this phone's own server and is published from here.
        binding.rowClipboard.root.setOnClickListener { toggle(8875, 8875) }

        binding.btnStopAll.setOnClickListener {
            lifecycleScope.launch {
                runCatching { manager.stopAll() }
                    .onFailure { toast(it.message) }
                refresh()
            }
        }
        binding.btnFunnel.setOnClickListener {
            lifecycleScope.launch {
                val published = manager.activeServes()
                runCatching {
                    if (published.any { it.funnel }) {
                        published.filter { it.funnel }.forEach { manager.funnelDisable(it.tailnetPort) }
                    } else {
                        val target = published.firstOrNull()
                            ?: throw IllegalStateException(getString(R.string.serve_nothing_published))
                        manager.funnelEnable(target.tailnetPort)
                    }
                }.onFailure { toast(it.message) }
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggle(tailnetPort: Int, localPort: Int) {
        lifecycleScope.launch {
            runCatching {
                if (manager.activeServes().any { it.tailnetPort == tailnetPort }) {
                    manager.stop(tailnetPort)
                } else {
                    manager.serveHttp(localPort, tailnetPort)
                }
            }.onFailure { toast(it.message) }
            refresh()
        }
    }

    private fun refresh() {
        val b = _binding ?: return
        val active = manager.activeServes()

        b.rowSftp.value.text = sftpState()
        b.rowClipboard.value.text = stateFor(active, 8875)

        b.cardPublished.removeAllViews()
        if (active.isEmpty()) {
            b.cardPublished.addView(row(getString(R.string.serve_none_published), ""))
        } else {
            active.forEach { entry ->
                b.cardPublished.addView(row(describe(entry), getString(R.string.serve_local_port, entry.localPort)))
            }
        }

        b.btnFunnel.setText(
            if (active.any { it.funnel }) R.string.serve_funnel_disable else R.string.serve_funnel_enable
        )
        b.btnFunnel.isEnabled = active.isNotEmpty()
    }

    private fun sftpState(): String {
        val status = SftpServerControllerImpl(requireContext()).status()
        return when (status) {
            is SftpServerStatus.Running -> getString(R.string.serve_sftp_running, status.port)
            is SftpServerStatus.Error -> status.message
            else -> getString(R.string.serve_sftp_stopped)
        }
    }

    private fun stateFor(active: List<ServeEntry>, tailnetPort: Int): String =
        active.firstOrNull { it.tailnetPort == tailnetPort }
            ?.let { getString(R.string.serve_published_on, it.tailnetPort) }
            ?: getString(R.string.serve_not_published)

    private fun describe(entry: ServeEntry): String = buildString {
        append(getString(R.string.serve_tailnet_port, entry.tailnetPort))
        if (entry.tls) append(" · TLS")
        if (entry.funnel) append(" · Funnel")
    }

    private fun row(label: String, value: String): View {
        val rowBinding = PartialDetailRowBinding.inflate(layoutInflater, binding.cardPublished, false)
        rowBinding.label.text = label
        rowBinding.value.text = value
        return rowBinding.root
    }

    private fun toast(message: String?) {
        Toast.makeText(requireContext(), message ?: getString(R.string.state_error), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
