package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.earthquack.MainActivity
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentServicesBinding
import com.example.earthquack.sftp.SftpServerStatus
import com.example.earthquack.sftp.UnavailableSftpController
import com.example.earthquack.state.SyncStateLabel
import com.example.earthquack.state.SystemStatusProvider

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
    private val sftpController = UnavailableSftpController()

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
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val status = statusProvider.read(mainActivity?.syncState?.value ?: SyncStateLabel.UNKNOWN)

        // Tailscale: described by the sync connection, because that is what
        // actually exercises the tailnet.
        binding.rowTailscale.rowSubtitle.text = when {
            status.isConnected -> getString(R.string.services_tailscale_connected)
            status.syncState == SyncStateLabel.CONNECTING -> getString(R.string.state_connecting)
            !status.isConfigured -> getString(R.string.subtitle_not_configured)
            else -> getString(R.string.state_disconnected)
        }

        // SFTP: from the controller. Currently NotImplemented, which is reported
        // as such rather than as "Stopped".
        binding.rowSftp.rowSubtitle.text = sftpController.status().label

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
