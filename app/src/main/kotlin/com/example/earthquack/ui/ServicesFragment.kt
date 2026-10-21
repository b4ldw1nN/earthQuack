package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.earthquack.MainActivity
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentServicesBinding
import com.example.earthquack.sftp.SftpServerControllerImpl
import com.example.earthquack.state.SyncStateLabel
import com.example.earthquack.state.SystemStatusProvider
import com.example.earthquack.tailscale.TailscaleLoginState
import com.example.earthquack.tailscale.TailscaleNodeMonitor
import kotlinx.coroutines.launch

/**
 * Services hub: Tailscale, SFTP and Clipboard.
 *
 * Each row's subtitle carries the live state rather than a static description,
 * so this screen answers "what is actually running" at a glance. That is the
 * whole reason it exists as a separate tab: the old layout buried the same
 * information behind a single toggle.
 */
class ServicesFragment : Fragment() {

    private var _binding: FragmentServicesBinding? = null
    private val binding get() = _binding!!

    private lateinit var statusProvider: SystemStatusProvider

    /**
     * The real SFTP server controller, for the SFTP row's subtitle.
     *
     * Constructed in [onViewCreated]: it needs a Context, which a Fragment does
     * not have at field-initializer time.
     */
    private lateinit var sftpController: SftpServerControllerImpl

    private val mainActivity: MainActivity? get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusProvider = SystemStatusProvider(requireContext())
        sftpController = SftpServerControllerImpl(requireContext())

        // Static labels and icons; the subtitles are set in refresh().
        binding.rowTailscale.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_tailnet)
            rowTitle.setText(R.string.services_tailscale)
            rowSubtitle.setText(R.string.services_tailscale_sub)
            root.setOnClickListener { mainActivity?.openConnections() }
        }
        binding.rowSftp.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_transfer)
            rowTitle.setText(R.string.services_sftp)
            root.setOnClickListener { mainActivity?.openSftp() }
        }
        binding.rowClipboard.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_clipboard)
            rowTitle.setText(R.string.services_clipboard)
            root.setOnClickListener { mainActivity?.openClipboard() }
        }
        binding.rowProfiles.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_computer)
            rowTitle.setText(R.string.services_profiles)
            root.setOnClickListener { mainActivity?.openProfiles() }
        }

        observeNodeState()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Keeps the Tailscale row live: the node's state changes on its own
     * schedule (it comes up with the app, and can drop if the network
     * changes), so the row follows the node rather than the screen.
     */
    private fun observeNodeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TailscaleNodeMonitor.state.collect { refresh() }
            }
        }
    }

    private fun refresh() {
        val status = statusProvider.read(mainActivity?.syncState?.value ?: SyncStateLabel.UNKNOWN)
        val nodeState = TailscaleNodeMonitor.state.value

        // Tailscale: the state of this app's own node. The tailnet is
        // connected whenever the node is running — it does not depend on
        // the desktop binary being up, which is a separate service shown
        // on its own row.
        binding.rowTailscale.rowSubtitle.text = when (val node = nodeState) {
            is TailscaleLoginState.Running ->
                getString(R.string.services_tailscale_connected, node.nodeIp)
            is TailscaleLoginState.Starting -> getString(R.string.state_connecting)
            is TailscaleLoginState.NeedsLogin -> getString(R.string.state_connecting)
            else -> getString(R.string.state_disconnected)
        }

        // SFTP: the server's real state, from the engine. Running shows the
        // bound port because that is what a client needs; Stopped says Stopped.
        binding.rowSftp.rowSubtitle.text = when (val s = sftpController.status()) {
            is com.example.earthquack.sftp.SftpServerStatus.Running ->
                getString(R.string.services_sftp_running, s.port)
            is com.example.earthquack.sftp.SftpServerStatus.Error -> s.message
            else -> getString(R.string.services_sftp_sub)
        }

        // Clipboard: the service's own state.
        binding.rowClipboard.rowSubtitle.text = when {
            status.syncState == SyncStateLabel.RUNNING && status.isServiceRunning ->
                getString(R.string.state_running)
            status.syncState == SyncStateLabel.PAUSED -> getString(R.string.state_paused)
            status.isServiceRunning -> getString(R.string.state_connecting)
            else -> getString(R.string.state_stopped)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
