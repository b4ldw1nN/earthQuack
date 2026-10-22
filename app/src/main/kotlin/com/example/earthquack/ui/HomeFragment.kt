package com.example.earthquack.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.earthquack.ACTION_START_SYNC
import com.example.earthquack.ACTION_STOP_SYNC
import com.example.earthquack.FileBrowserActivity
import com.example.earthquack.MainActivity
import com.example.earthquack.R
import com.example.earthquack.ServerConfig
import com.example.earthquack.databinding.FragmentHomeBinding
import com.example.earthquack.sftp.SftpServerControllerImpl
import com.example.earthquack.sftp.SftpServerService
import com.example.earthquack.sftp.SftpServerStatus
import com.example.earthquack.state.StorageRepository
import com.example.earthquack.state.SyncStateLabel
import com.example.earthquack.state.SystemStatus
import com.example.earthquack.state.SystemStatusProvider
import com.example.earthquack.tailscale.Tailscale
import com.example.earthquack.tailscale.TailscaleLoginState
import com.example.earthquack.tailscale.TailscaleNodeMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The dashboard.
 *
 * ## The rule this screen exists to demonstrate
 *
 * Every value shown is real. Where the mockup had a plausible literal --
 * "Connected", "archii", "100.x.x.x", "3 remotes", "12 ms" -- this screen shows
 * the measured value or an honest "Not configured" / "No remotes". A dashboard
 * whose numbers are placeholders trains the user to distrust it, which defeats
 * the point of having a dashboard.
 *
 * Specifically, the mockup's "Device: archii / 100.x.x.x / 12 ms" is only ever
 * partially knowable: EarthQuack stores a *host address* it syncs to, but it
 * does not ask Tailscale for a device name or a latency figure, so those rows
 * are omitted rather than filled with something plausible. See
 * [ConnectionRow] for the mapping.
 *
 * ## Refresh policy
 *
 * Refreshed on resume (permissions and service state can change while the app
 * is backgrounded) and whenever the service broadcasts a status change. The
 * rclone remote count is fetched off the main thread and only on refresh, not
 * polled -- it crosses the JNI boundary and there is no reason to pay for it
 * more often than the user can perceive.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var statusProvider: SystemStatusProvider
    private lateinit var storageRepository: StorageRepository

    /**
     * The real SFTP server controller, so the tile shows the server's actual
     * state. The old placeholder controller could only ever say "not
     * available", which made a running server look dead.
     */
    private lateinit var sftpController: SftpServerControllerImpl

    /** Receives the SFTP server's status broadcasts. */
    private var sftpStatusReceiver: BroadcastReceiver? = null

    /** Host of the parent activity, used to observe service status broadcasts. */
    private val mainActivity: MainActivity? get() = activity as? MainActivity

    /**
     * Registered here rather than at the point of use: the platform requires it
     * before STARTED, and constructing a launcher inside a click handler throws
     * `IllegalStateException` at runtime.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { refresh() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        statusProvider = SystemStatusProvider(context)
        storageRepository = StorageRepository(context)
        sftpController = SftpServerControllerImpl(context)

        wireActions()

        // Observe service status broadcasts so the card updates the moment the
        // service changes state, without the user pulling to refresh.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainActivity?.syncState?.collect { refresh() }
            }
        }

        // The node's own state decides the "This device" row, and the SFTP
        // server broadcasts whenever it starts or stops. Both are observed so
        // the dashboard never shows a stale value.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TailscaleNodeMonitor.state.collect { refresh() }
            }
        }
        sftpStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == SftpServerService.ACTION_STATUS_UPDATE) refresh()
            }
        }
        val filter = IntentFilter(SftpServerService.ACTION_STATUS_UPDATE)
        androidx.core.content.ContextCompat.registerReceiver(
            requireContext(), sftpStatusReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.example.earthquack.AppStatus.message.collect { message ->
                    StatusBanner.render(
                        binding.statusBanner.root,
                        binding.statusBanner.bannerText,
                        binding.statusBanner.bannerAction,
                        message,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun wireActions() {
        binding.cardConnection.setOnClickListener { mainActivity?.openConnections() }
        binding.tileClipboard.setOnClickListener { mainActivity?.openClipboard() }
        binding.tileSftp.setOnClickListener { mainActivity?.openSftp() }
        // The Storage tile leads to the file browser, not the Storage tab.
        // "Storage" and "Browse Files" appearing as separate actions on the same
        // screen made the distinction unclear; the tab is reachable from the
        // bottom bar anyway.
        binding.tileStorage.setOnClickListener {
            startActivity(FileBrowserActivity.intent(requireContext()))
        }
        binding.tileTransfers.setOnClickListener { mainActivity?.openStorage() }
        // The buttons show short labels for space; the full label is still
        // announced by accessibility services.
        binding.btnBrowseFiles.contentDescription = getString(R.string.home_browse_files)
        binding.btnSendClipboard.contentDescription = getString(R.string.home_send_clipboard)
        binding.btnStartSftp.contentDescription = getString(R.string.home_start_sftp)

        binding.btnBrowseFiles.setOnClickListener {
            startActivity(FileBrowserActivity.intent(requireContext()))
        }
        binding.btnSendClipboard.setOnClickListener {
            mainActivity?.openClipboard()
        }
        binding.btnStartSftp.setOnClickListener { mainActivity?.openSftp() }
        binding.btnToggleSync.setOnClickListener { toggleSync() }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            // The status read asks ActivityManager whether the service is
            // alive: a binder round-trip that must not sit on the main
            // thread while the user is waiting for a screen to appear.
            val status = withContext(Dispatchers.IO) {
                statusProvider.read(mainActivity?.syncState?.value ?: SyncStateLabel.UNKNOWN)
            }
            if (_binding == null) return@launch
            renderConnection(status)
            renderServiceTiles(status)
            renderToggle(status)
            renderLastSync()
            // Off the main thread: crosses JNI into rclone.
            val remotes = storageRepository.remotesOrNull()
            _binding?.let { b ->
                b.textStorageState.text = when {
                    remotes == null -> getString(R.string.state_unavailable)
                    remotes.isEmpty() -> getString(R.string.state_no_remotes)
                    else -> resources.getQuantityString(
                        R.plurals.remote_count, remotes.size, remotes.size
                    )
                }
            }
        }
    }

    /**
     * The connectivity card.
     *
     * Rows are hidden rather than filled when unknown. Showing "Device —" next
     * to an unconfigured install implies a device exists and failed to report,
     * which is a different (and wrong) diagnosis than "you have not set this
     * up yet".
     */
    private fun renderConnection(status: SystemStatus) {
        val (dotRes, stateText, subText) = when {
            status.isConnected -> Triple(
                R.drawable.eq_dot_hollow_success,
                getString(R.string.state_connected),
                getString(R.string.subtitle_connected_via_tailscale)
            )
            status.syncState == SyncStateLabel.CONNECTING && status.isServiceRunning -> Triple(
                R.drawable.eq_dot_hollow_muted,
                getString(R.string.state_connecting),
                getString(R.string.subtitle_connecting_to, status.host.orEmpty())
            )
            status.syncState == SyncStateLabel.PAUSED -> Triple(
                R.drawable.eq_dot_hollow_muted,
                getString(R.string.state_paused),
                getString(R.string.subtitle_paused)
            )
            status.syncState == SyncStateLabel.ERROR -> Triple(
                R.drawable.eq_dot_hollow_muted,
                getString(R.string.state_error),
                getString(R.string.subtitle_retrying)
            )
            status.isServiceRunning -> Triple(
                R.drawable.eq_dot_hollow_muted,
                getString(R.string.state_starting),
                getString(R.string.state_not_configured)
            )
            // Nothing running.
            else -> Triple(
                R.drawable.eq_dot_hollow_muted,
                getString(R.string.state_disconnected),
                if (status.isConfigured) {
                    getString(R.string.subtitle_tap_start)
                } else {
                    getString(R.string.subtitle_not_configured)
                }
            )
        }

        binding.dotConnection.setBackgroundResource(dotRes)
        binding.textConnectionState.text = stateText
        binding.textConnectionSub.text = subText

        // Endpoint rows. "Device" is this phone's own tailnet address — the
        // app's identity on the tailnet — while the desktop is named
        // separately, because the two are easy to confuse and the desktop
        // address is only meaningful once one is configured.
        val thisDevice = thisDeviceTailnetIp()
        if (status.isConfigured || thisDevice != null) {
            binding.dividerConnection.visibility = View.VISIBLE
            binding.rowsConnection.visibility = View.VISIBLE
            binding.rowHost.label.text = getString(R.string.home_row_this_device)
            binding.rowHost.value.text = thisDevice ?: getString(R.string.home_not_on_tailnet)
            binding.rowDesktop.label.text = getString(R.string.home_row_desktop)
            binding.rowDesktop.value.text = status.host.orEmpty()
            binding.rowPort.label.text = getString(R.string.home_row_port)
            binding.rowPort.value.text = status.port.toString()
        } else {
            binding.dividerConnection.visibility = View.GONE
            binding.rowsConnection.visibility = View.GONE
        }
    }

    /**
     * This phone's tailnet address, from the app's own node.
     *
     * The node is the app's identity on the tailnet, so its address is what
     * a peer needs to reach the services this app serves. Null when the node
     * is not running.
     */
    private fun thisDeviceTailnetIp(): String? {
        val fromState = (TailscaleNodeMonitor.state.value as? TailscaleLoginState.Running)?.nodeIp
        if (!fromState.isNullOrBlank()) return fromState
        return runCatching {
            org.json.JSONObject(Tailscale.thisDevice()).optString("ip")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun renderServiceTiles(status: SystemStatus) {
        // Clipboard tile reflects the real service state.
        val clipboardRunning = status.syncState == SyncStateLabel.RUNNING && status.isServiceRunning
        binding.textClipboardState.text = when {
            clipboardRunning -> getString(R.string.state_running)
            status.syncState == SyncStateLabel.PAUSED -> getString(R.string.state_paused)
            status.isServiceRunning -> getString(R.string.state_connecting)
            else -> getString(R.string.state_stopped)
        }
        binding.textClipboardState.setTextColor(
            resources.getColor(
                if (clipboardRunning) R.color.eq_success else R.color.eq_text_muted,
                requireContext().theme
            )
        )

        // SFTP tile reflects the real server: the engine knows whether it is
        // listening and on which port, so the tile can say so instead of
        // claiming the feature is unavailable.
        val sftpStatus = sftpController.status()
        binding.textSftpState.text = when (sftpStatus) {
            is SftpServerStatus.Running -> getString(R.string.services_sftp_running, sftpStatus.port)
            is SftpServerStatus.Error -> sftpStatus.message
            else -> getString(R.string.state_stopped)
        }

        // Transfers: there is no transfer registry yet, so the honest value is
        // always "no active transfers" -- not a made-up completed/failed pair.
        binding.textTransfersState.text = getString(R.string.state_no_active_transfers)
    }

    private fun renderToggle(status: SystemStatus) {
        val running = status.isServiceRunning
        binding.textToggleSync.text =
            getString(if (running) R.string.action_stop_sync else R.string.action_start_sync)
        binding.iconToggleSync.setImageResource(
            if (running) R.drawable.ic_eq_stop else R.drawable.ic_eq_play
        )
        binding.iconToggleSync.imageTintList = resources.getColorStateList(
            if (running) R.color.eq_error else R.color.eq_primary,
            requireContext().theme
        )
    }

    private fun renderLastSync() {
        // Reading the history is a file read and a JSON parse: off the main
        // thread, like everything else here.
        viewLifecycleOwner.lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                com.example.earthquack.state.ClipboardHistoryStore(requireContext()).latest()
            }
            val b = _binding ?: return@launch
            if (entry == null) {
                b.cardLastSync.visibility = View.GONE
                return@launch
            }
            b.cardLastSync.visibility = View.VISIBLE
            val direction = when (entry.direction) {
                com.example.earthquack.state.ClipboardEntry.Direction.SENT ->
                    getString(R.string.direction_sent)
                com.example.earthquack.state.ClipboardEntry.Direction.RECEIVED ->
                    getString(R.string.direction_received)
            }
            b.textLastSync.text = "$direction: ${preview(entry.text)}"
        }
    }

    /**
     * A short, honest preview of the last value.
     *
     * Ciphertext is shown as such: a value the app could not decrypt is not
     * text, and printing "AES:..." at the user is just noise. The value is
     * still stored; only the preview changes.
     */
    private fun preview(text: String): String {
        if (text.startsWith(com.example.earthquack.CryptoUtil.PREFIX)) return getString(R.string.clipboard_encrypted_preview)
        return text
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    /**
     * Start or stop the clipboard service.
     *
     * Deliberately thin: it starts the existing service with the existing
     * action strings. The permission gate stays where it always was, because a
     * background clipboard service without the overlay permission silently does
     * nothing, and telling the user to grant it is more useful than an error.
     */
    private fun toggleSync() {
        val context = requireContext()
        val status = statusProvider.read(mainActivity?.syncState?.value ?: SyncStateLabel.UNKNOWN)

        if (status.isServiceRunning) {
            context.startService(
                android.content.Intent(context, com.example.earthquack.EarthQuackService::class.java)
                    .setAction(ACTION_STOP_SYNC)
            )
            return
        }

        if (!statusProvider.canDrawOverlays()) {
            Toast.makeText(
                context,
                getString(R.string.toast_needs_overlay),
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
            return
        }

        if (!statusProvider.hasNotificationPermission()) {
            requestNotificationPermissionThenStart()
            return
        }

        androidx.core.content.ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, com.example.earthquack.EarthQuackService::class.java)
                .setAction(ACTION_START_SYNC)
        )
    }

    /**
     * Requests notification permission, then starts the service regardless.
     *
     * The launcher is registered in [onCreate], not here: `registerForActivityResult`
     * must run before the fragment reaches STARTED, and creating it inside a click
     * handler throws.
     *
     * Starting regardless of the answer is deliberate. A foreground service needs
     * a notification, so refusing the permission does not make starting pointless
     * -- and the service degrades more gracefully than the alternative, which is
     * a running service the user never knew about.
     */
    private fun requestNotificationPermissionThenStart() {
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        androidx.core.content.ContextCompat.startForegroundService(
            requireContext(),
            android.content.Intent(
                requireContext(),
                com.example.earthquack.EarthQuackService::class.java
            ).setAction(ACTION_START_SYNC)
        )
    }

    override fun onDestroyView() {
        sftpStatusReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        sftpStatusReceiver = null
        super.onDestroyView()
        _binding = null
    }
}
