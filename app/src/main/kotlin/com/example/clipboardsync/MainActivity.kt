package com.example.clipboardsync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.clipboardsync.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentStatus = SyncStatus.STOPPED
    private var serviceRunning = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> doStartService() }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STATUS_UPDATE) return
            val statusName = intent.getStringExtra(EXTRA_STATUS) ?: return
            val lastSync   = intent.getStringExtra(EXTRA_LAST_SYNC)
            val status = SyncStatus.entries.firstOrNull { it.name == statusName } ?: return
            updateUi(status, lastSync)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Server Discovery
        binding.textServer.text = "${ServerConfig.getHost(this)}:$SERVER_PORT"
        binding.btnRediscover.setOnClickListener { triggerTailscaleScan() }

        // Battery saver switches
        binding.cbBatterySaver.isChecked = ServerConfig.isBatterySaverEnabled(this)
        binding.cbPauseOnScreenOff.isChecked = ServerConfig.isPauseOnScreenOff(this)
        binding.cbPauseOnScreenOff.isEnabled = binding.cbBatterySaver.isChecked

        binding.cbBatterySaver.setOnCheckedChangeListener { _, checked ->
            ServerConfig.setBatterySaverEnabled(this, checked)
            binding.cbPauseOnScreenOff.isEnabled = checked
            Toast.makeText(this, if (checked) "Battery saver ON" else "Battery saver OFF", Toast.LENGTH_SHORT).show()
        }
        binding.cbPauseOnScreenOff.setOnCheckedChangeListener { _, checked ->
            ServerConfig.setPauseOnScreenOff(this, checked)
        }

        // Security — AES
        binding.cbAesEnabled.isChecked = ServerConfig.isAesEnabled(this)
        binding.editAesKey.setText(ServerConfig.getAesKey(this))
        binding.tilAesKey.isEnabled = binding.cbAesEnabled.isChecked
        binding.editAesKey.isEnabled = binding.cbAesEnabled.isChecked
        binding.btnGenKey.isEnabled = binding.cbAesEnabled.isChecked
        binding.btnCopyKey.isEnabled = binding.cbAesEnabled.isChecked

        binding.cbAesEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && !ServerConfig.hasValidAesKey(this) && binding.editAesKey.text.isNullOrBlank()) {
                val k = CryptoUtil.generateKeyBase64()
                binding.editAesKey.setText(k)
                ServerConfig.setAesKey(this, k)
                Toast.makeText(this, "AES key generated — set same key on Desktop!", Toast.LENGTH_LONG).show()
            }
            ServerConfig.setAesEnabled(this, checked)
            binding.tilAesKey.isEnabled = checked
            binding.editAesKey.isEnabled = checked
            binding.btnGenKey.isEnabled = checked
            binding.btnCopyKey.isEnabled = checked
            if (checked && !CryptoUtil.isValidKeyBase64(binding.editAesKey.text.toString())) {
                binding.tilAesKey.error = "Invalid key — 32 bytes Base64 (44 chars)"
            } else {
                binding.tilAesKey.error = null
            }
        }
        binding.editAesKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val k = binding.editAesKey.text.toString().trim()
                if (k.isNotEmpty() && !CryptoUtil.isValidKeyBase64(k)) {
                    binding.tilAesKey.error = "Invalid — must be 44-char Base64 (32 bytes)"
                } else {
                    binding.tilAesKey.error = null
                    ServerConfig.setAesKey(this, k)
                    if (k.isNotBlank()) Toast.makeText(this, "AES key saved — set CLIPBOARD_AES_KEY on Desktop!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnGenKey.setOnClickListener {
            val k = CryptoUtil.generateKeyBase64()
            binding.editAesKey.setText(k)
            ServerConfig.setAesKey(this, k)
            ServerConfig.setAesEnabled(this, true)
            binding.cbAesEnabled.isChecked = true
            Toast.makeText(this, "New AES key — copy to Desktop env!", Toast.LENGTH_LONG).show()
        }
        binding.btnCopyKey.setOnClickListener {
            val k = binding.editAesKey.text.toString().trim()
            if (k.isBlank()) {
                Toast.makeText(this, "No key to copy", Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AES Key", k))
                Toast.makeText(this, "AES key copied", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggle.setOnClickListener {
            if (serviceRunning) stopSync() else startSync()
        }
        binding.btnPauseResume.setOnClickListener {
            if (currentStatus == SyncStatus.PAUSED) resumeSync() else pauseSync()
        }
        binding.btnOverlayPermission.setOnClickListener {
            openOverlayPermissionSettings()
        }
        binding.btnImeSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        refreshOverlayButton()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        refreshOverlayButton()
        binding.textServer.text = "${ServerConfig.getHost(this)}:$SERVER_PORT"
        checkServiceRunningState()
    }

    @Suppress("DEPRECATION")
    private fun checkServiceRunningState() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val isRunning = am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ClipboardSyncService::class.java.name
        }
        if (isRunning) {
            startService(Intent(this, ClipboardSyncService::class.java).apply { action = "QUERY_STATUS" })
            if (currentStatus == SyncStatus.STOPPED) {
                updateUi(SyncStatus.RUNNING)
            }
        } else {
            updateUi(SyncStatus.STOPPED)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(this)

    private fun refreshOverlayButton() {
        if (canDrawOverlays()) {
            binding.btnOverlayPermission.text = " Draw over apps: Granted"
            binding.btnOverlayPermission.isEnabled = false
        } else {
            binding.btnOverlayPermission.text = "Grant 'Draw over apps' (needed for background sync)"
            binding.btnOverlayPermission.isEnabled = true
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun triggerTailscaleScan() {
        Toast.makeText(this, "Scanning Tailscale network for clipboard services…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val servers = TailscaleDiscovery.discoverAllWorkingServers()
            withContext(Dispatchers.Main) {
                if (servers.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No active clipboard servers found on Tailscale", Toast.LENGTH_LONG).show()
                } else if (servers.size == 1) {
                    val ip = servers[0].tailscaleIpv4[0]
                    val name = if (servers[0].hostname.isNotBlank()) servers[0].hostname else ip
                    Toast.makeText(this@MainActivity, "Discovered active server: $name ($ip)", Toast.LENGTH_LONG).show()
                    applyNewHost(ip)
                } else {
                    showDeviceSelectionDialog(servers) { selectedIp ->
                        applyNewHost(selectedIp)
                    }
                }
            }
        }
    }

    fun showDeviceSelectionDialog(servers: List<TailscaleDiscovery.PeerNode>, onSelected: (String) -> Unit) {
        if (servers.isEmpty()) return
        val items = mutableListOf("0) Sync with All Devices")
        servers.forEachIndexed { i, node ->
            val name = if (node.hostname.isNotBlank()) node.hostname else node.tailscaleIpv4[0]
            items.add("${i + 1}) $name (${node.os}) — ${node.tailscaleIpv4[0]}")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Select Target Tailscale Device")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    val firstIp = servers[0].tailscaleIpv4[0]
                    onSelected(firstIp)
                } else {
                    val chosenIp = servers[which - 1].tailscaleIpv4[0]
                    onSelected(chosenIp)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyNewHost(host: String) {
        val oldHost = ServerConfig.getHost(this)
        val wasRunning = serviceRunning
        ServerConfig.setHost(this, host)
        binding.textServer.text = "$host:$SERVER_PORT"
        if (wasRunning) {
            Toast.makeText(this, "Target changed to $host — restarting service…", Toast.LENGTH_SHORT).show()
            startService(Intent(this, ClipboardSyncService::class.java).apply { action = ACTION_STOP_SYNC })
            binding.root.postDelayed({
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ClipboardSyncService::class.java).apply { action = ACTION_START_SYNC }
                )
                updateUi(SyncStatus.CONNECTING)
            }, 600)
        } else {
            Toast.makeText(this, "Connected to: $host", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSync() {
        if (!canDrawOverlays()) {
            Toast.makeText(
                this,
                "Grant 'Draw over other apps' first so clipboard can be read in the background",
                Toast.LENGTH_LONG
            ).show()
            openOverlayPermissionSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            doStartService()
        }
    }

    private fun doStartService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ClipboardSyncService::class.java).apply { action = ACTION_START_SYNC }
        )
        serviceRunning = true
        updateUi(SyncStatus.CONNECTING)
    }

    private fun stopSync() {
        startService(Intent(this, ClipboardSyncService::class.java).apply { action = ACTION_STOP_SYNC })
        serviceRunning = false
        updateUi(SyncStatus.STOPPED)
    }

    private fun pauseSync() {
        startService(Intent(this, ClipboardSyncService::class.java).apply { action = ACTION_PAUSE_SYNC })
        updateUi(SyncStatus.PAUSED)
    }

    private fun resumeSync() {
        startService(Intent(this, ClipboardSyncService::class.java).apply { action = ACTION_RESUME_SYNC })
        updateUi(SyncStatus.CONNECTING)
    }

    private fun updateUi(status: SyncStatus, lastSync: String? = null) {
        currentStatus  = status
        serviceRunning = status != SyncStatus.STOPPED

        binding.textStatus.text = when (status) {
            SyncStatus.RUNNING    -> "Running"
            SyncStatus.STOPPED    -> "Stopped"
            SyncStatus.CONNECTING -> "Connecting…"
            SyncStatus.PAUSED     -> "Paused"
            SyncStatus.ERROR      -> "Connection error"
        }
        binding.textStatusSub.text = when (status) {
            SyncStatus.RUNNING    -> "Connected to ${ServerConfig.getHost(this)}:${SERVER_PORT}"
            SyncStatus.CONNECTING -> "Connecting to ${ServerConfig.getHost(this)}…"
            SyncStatus.PAUSED     -> "Paused — tap Resume to reconnect"
            SyncStatus.ERROR      -> "Retrying with backoff…"
            SyncStatus.STOPPED    -> "Tap Start to connect to your Desktop laptop"
        }
        binding.dotStatus.setBackgroundResource(when (status) {
            SyncStatus.RUNNING    -> R.drawable.dot_running
            SyncStatus.CONNECTING -> R.drawable.dot_connecting
            SyncStatus.PAUSED     -> R.drawable.dot_paused
            SyncStatus.ERROR      -> R.drawable.dot_error
            SyncStatus.STOPPED    -> R.drawable.dot_stopped
        })
        // Toggle button style
        if (serviceRunning) {
            binding.btnToggle.text = "Stop Sync"
            binding.btnToggle.setIconResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnToggle.text = "Start Sync"
            binding.btnToggle.setIconResource(android.R.drawable.ic_media_play)
        }

        // Pause/Resume button
        if (serviceRunning) {
            binding.btnPauseResume.visibility = android.view.View.VISIBLE
            binding.btnPauseResume.text = if (status == SyncStatus.PAUSED) "Resume" else "Pause"
            binding.btnPauseResume.setIconResource(
                if (status == SyncStatus.PAUSED) android.R.drawable.ic_media_play
                else android.R.drawable.ic_media_pause
            )
        } else {
            binding.btnPauseResume.visibility = android.view.View.GONE
        }

        if (lastSync != null) {
            val label   = if (lastSync.startsWith("→")) "Sent to Desktop:" else "Received from Desktop:"
            val snippet = lastSync.drop(1).trim().let { if (it.length > 80) it.take(80) + "…" else it }
            binding.textLastSync.text = "$label\n$snippet"
            binding.cardLastSync.visibility = android.view.View.VISIBLE
        } else if (status == SyncStatus.STOPPED) {
            binding.textLastSync.text = ""
            binding.cardLastSync.visibility = android.view.View.GONE
        } else if (binding.textLastSync.text.isNotBlank()) {
            binding.cardLastSync.visibility = android.view.View.VISIBLE
        }
    }
}
