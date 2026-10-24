package io.earthquack.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.earthquack.app.MainActivity
import io.earthquack.app.R
import io.earthquack.app.databinding.FragmentStorageBinding
import io.earthquack.app.databinding.ItemRemoteRowBinding
import io.earthquack.app.state.StorageRepository
import io.earthquack.app.storage.RcloneHealthProbe
import io.earthquack.app.tailscale.TailscaleLoginState
import io.earthquack.app.tailscale.TailscaleNodeMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Storage: internal capacity, shared-storage access, and configured rclone
 * remotes.
 *
 * ## What is real
 *
 * - The meter is [android.os.StatFs] against the app's own files directory.
 *   If the platform reports no total, the whole card is hidden rather than
 *   showing a bar that cannot mean anything.
 * - The remote list is `config/listremotes` through the native rclone layer.
 *
 * ## What is deliberately absent
 *
 * The mockup showed each remote with its own status line and implied cloud
 * accounts ("Google Drive — Connected", "MEGA — Connected"). Reporting a
 * remote as "Connected" would require an `about` RPC per remote against live
 * credentials, which can fail, can prompt, and costs a network round trip on
 * every screen entry. So a remote is shown by name and type, and the row does
 * not claim a connection state it has not verified.
 */
class StorageFragment : Fragment() {

    private var _binding: FragmentStorageBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: StorageRepository
    private lateinit var importer: RcloneConfigImporter

    /**
     * Rows of the remote list, by remote name, so a probe result can update
     * the row it belongs to.
     */
    private val remoteRows = mutableMapOf<String, ItemRemoteRowBinding>()

    /** The type/route part of each row's label, without the health suffix. */
    private val remoteBaseLabels = mutableMapOf<String, String>()

    /** The last probe result per remote, for the row menu. */
    private val remoteHealth = mutableMapOf<String, RcloneHealthProbe.Health>()

    /**
     * When the last probe ran, and whether the node was up when it did.
     *
     * Probing is one network round-trip per remote, and a cloud account can
     * hold hundreds of files at its root. Doing that on every visit to the
     * screen is slow and wasteful, so a result is reused until the node
     * changes state or the answer is old enough to be worth refreshing.
     */
    private var lastProbeAt = 0L
    private var lastProbeWithNode = false

    private fun shouldProbe(): Boolean {
        val nodeUp = TailscaleNodeMonitor.state.value is TailscaleLoginState.Running
        val age = System.currentTimeMillis() - lastProbeAt
        return nodeUp != lastProbeWithNode || age > PROBE_MAX_AGE_MS
    }

    /**
     * SAF picker for an existing rclone.conf.
     *
     * Registered here rather than constructed on click: `registerForActivityResult`
     * must run before STARTED, and building one inside a listener throws at
     * runtime. Using the document picker means no storage permission is
     * required to read the chosen file.
     */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { confirmImport(it) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = StorageRepository(requireContext())

        importer = RcloneConfigImporter(requireContext())
        // Local storage rows open the browser at that directory. Shared storage
        // previously had no route into the browser at all, which is why "cannot
        // access shared storage" looked like a permission problem when it was
        // only a missing link.
        binding.rowInternal.setOnClickListener { openLocal(requireContext().filesDir.absolutePath) }
        binding.rowShared.setOnClickListener { openSharedStorage() }
        binding.btnImportRemote.setOnClickListener {
            // Some file providers do not report a MIME type for a plain INI
            // file, so */* is accepted and the importer validates the content.
            importLauncher.launch(arrayOf("*/*", "text/plain"))
        }
        binding.btnAddRemote.setOnClickListener {
            startActivity(AddRemoteActivity.intent(requireContext()))
        }
        binding.btnEditConfig.setOnClickListener {
            startActivity(
                android.content.Intent(requireContext(), RcloneConfigEditorActivity::class.java)
            )
        }
        binding.btnRemotesRetry.setOnClickListener { loadRemotes() }
        binding.btnGrantStorage.setOnClickListener { openAllFilesAccessSettings() }

        // A tailnet remote can only be reached once the node is up, and the
        // node comes up on its own schedule. Probing before that says
        // "unreachable" for a remote that is about to work, so the list is
        // refreshed when the node reports Running.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TailscaleNodeMonitor.state.collect { state ->
                    if (state is TailscaleLoginState.Running) loadRemotes()
                }
            }
        }

        renderLocalStorage()
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted all-files access in system settings while we
        // were backgrounded, so this has to be re-read rather than cached.
        renderLocalStorage()
        loadRemotes()
    }

    // ── Local ────────────────────────────────────────────────────────────────

    private fun renderLocalStorage() {
        val usage = repository.internalUsage()

        if (usage.isKnown) {
            binding.cardUsage.visibility = View.VISIBLE
            binding.usageBar.progress = (usage.usedFraction * 1000).toInt()
            binding.textUsage.text = getString(
                R.string.storage_usage_format,
                formatBytes(usage.usedBytes),
                formatBytes(usage.totalBytes)
            )
            binding.textInternalFree.text = getString(
                R.string.storage_free_format,
                formatBytes(usage.freeBytes)
            )
        } else {
            // No total means no meaningful fraction. Hide rather than mislead.
            binding.cardUsage.visibility = View.GONE
            binding.textInternalFree.text = getString(R.string.state_unknown)
        }

        val sharedReadable = repository.sharedStorageReadable()
        binding.textSharedState.text = if (sharedReadable) {
            getString(R.string.storage_shared_granted)
        } else {
            getString(R.string.storage_shared_not_granted)
        }
        binding.btnGrantStorage.visibility = if (sharedReadable) View.GONE else View.VISIBLE
    }

    private fun openAllFilesAccessSettings() {
        // MANAGE_EXTERNAL_STORAGE has no runtime-request dialog; the only route
        // is the settings page. ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
        // scopes to this app, which is what we want.
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${requireContext().packageName}")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    // ── rclone remotes ───────────────────────────────────────────────────────

    private fun loadRemotes() {
        binding.remotesMessage.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            // Crosses JNI into rclone, so it must stay off the main thread.
            val remotes = repository.remotesOrNull()
            val b = _binding ?: return@launch

            when {
                remotes == null -> {
                    // Could not ask. Distinct from "asked and got none".
                    b.cardRemotes.visibility = View.GONE
                    b.remotesMessage.visibility = View.VISIBLE
                    b.textRemotesMessage.text = getString(R.string.storage_remotes_error)
                    b.btnRemotesRetry.visibility = View.VISIBLE
                }

                remotes.isEmpty() -> {
                    // The normal fresh-install state, not an error.
                    b.cardRemotes.visibility = View.GONE
                    b.remotesMessage.visibility = View.VISIBLE
                    b.textRemotesMessage.text = getString(R.string.state_no_remotes)
                    b.btnRemotesRetry.visibility = View.GONE
                }

                else -> {
                    b.remotesMessage.visibility = View.GONE
                    b.btnRemotesRetry.visibility = View.GONE
                    renderRemotes(remotes)
                    probeRemotes(remotes.map { it.name })
                }
            }
        }
    }

    /**
     * Probes each remote and updates its row as the answers arrive.
     *
     * Probing is slow (a network round-trip per remote) and may never finish
     * for a remote that hangs, so each remote's row is updated on its own
     * rather than waiting for the whole set.
     */
    private fun probeRemotes(names: List<String>) {
        if (names.isEmpty()) return
        if (!shouldProbe()) {
            // Fresh enough: show what we already know.
            applyCachedHealth(names)
            return
        }
        lastProbeAt = System.currentTimeMillis()
        lastProbeWithNode = TailscaleNodeMonitor.state.value is TailscaleLoginState.Running
        viewLifecycleOwner.lifecycleScope.launch {
            repository.probeRemotes(names) { name, result ->
                if (_binding == null) return@probeRemotes
                val ctx = context ?: return@probeRemotes
                remoteHealth[name] = result
                val row = remoteRows[name] ?: return@probeRemotes
                row.remoteStatus.text = remoteStatusLabel(remoteBaseLabels[name].orEmpty(), result)
                row.remoteStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        ctx,
                        when (result.state) {
                            RcloneHealthProbe.State.Ok -> R.color.eq_success
                            RcloneHealthProbe.State.Unknown -> R.color.eq_text_muted
                            else -> R.color.eq_warning
                        }
                    )
                )
            }
        }
    }

    /** Re-applies the last known health to the rows, without probing again. */
    private fun applyCachedHealth(names: List<String>) {
        names.forEach { name ->
            val result = remoteHealth[name] ?: return@forEach
            val row = remoteRows[name] ?: return@forEach
            row.remoteStatus.text = remoteStatusLabel(remoteBaseLabels[name].orEmpty(), result)
        }
    }

    /**
     * Combines the type/route prefix with the probe result.
     *
     * The prefix is kept because "sftp · via Tailscale" says how it is
     * reached, while the health says whether it answers; the two are
     * different facts and both are worth showing.
     */
    private fun remoteStatusLabel(prefix: String, health: RcloneHealthProbe.Health): String {
        val suffix = when (health.state) {
            RcloneHealthProbe.State.Ok -> getString(R.string.storage_remote_ok)
            RcloneHealthProbe.State.NeedsAuth -> getString(R.string.storage_remote_needs_auth)
            RcloneHealthProbe.State.Unreachable -> getString(R.string.storage_remote_unreachable)
            RcloneHealthProbe.State.Failed -> getString(R.string.storage_remote_failed)
            RcloneHealthProbe.State.NotSupported -> getString(R.string.storage_remote_not_supported)
            RcloneHealthProbe.State.NotSetUp -> getString(R.string.storage_remote_not_set_up)
            RcloneHealthProbe.State.Unknown -> return prefix
        }
        return getString(R.string.storage_remote_status_with_health, prefix, suffix)
    }

    private fun renderRemotes(remotes: List<StorageRepository.RemoteSummary>) {
        val container = binding.cardRemotes
        container.removeAllViews()
        container.visibility = View.VISIBLE
        remoteRows.clear()
        remoteBaseLabels.clear()

        remotes.forEachIndexed { index, remote ->
            val row = ItemRemoteRowBinding.inflate(layoutInflater, container, false)
            remoteRows[remote.name] = row
            row.remoteName.text = remote.name
            // Type when the config declares one, otherwise say so. Guessing a
            // backend from the section name would be fabrication. Tailnet
            // remotes carry their route too: they are dialled through this
            // app's own Tailscale node, which is what makes them reachable
            // without a system VPN.
            val typeLabel = remote.type
                ?.let { getString(R.string.storage_remote_status, it) }
                ?: getString(R.string.storage_remote_type_unknown)
            val baseLabel = if (remote.routedViaTailscale) {
                getString(R.string.storage_remote_via_tailscale, typeLabel)
            } else {
                typeLabel
            }
            remoteBaseLabels[remote.name] = baseLabel
            row.remoteStatus.text = baseLabel

            row.remoteIcon.setImageResource(iconForType(remote.type))
            row.remoteIcon.imageTintList = resources.getColorStateList(
                tintForType(remote.type), requireContext().theme
            )

            // The whole row opens the remote. Previously only the overflow was
            // wired, so tapping a remote's name did nothing at all and the rows
            // looked inert -- the single most confusing thing on this screen.
            row.root.setOnClickListener { browseRemote(remote) }
            row.remoteMore.setOnClickListener { showRemoteMenu(remote, row.remoteMore) }

            container.addView(row.root)
            if (index < remotes.lastIndex) {
                container.addView(createDivider())
            }
        }
    }

    private fun showRemoteMenu(
        remote: StorageRepository.RemoteSummary,
        anchor: View
    ) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_BROWSE, 0, R.string.action_browse)
            if (!remote.type.isNullOrBlank()) {
                menu.add(0, MENU_EDIT, 3, R.string.action_edit_remote)
            }
            // Only offered when the remote is a crypt remote whose encrypted
            // folder does not exist yet: that is the one setup step the app
            // can do for the user.
            if (remoteHealth[remote.name]?.state == RcloneHealthProbe.State.NotSetUp) {
                menu.add(0, MENU_CREATE_FOLDER, 1, R.string.storage_remote_create_folder)
            }
            menu.add(0, MENU_DELETE, 2, R.string.action_delete_remote)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_BROWSE -> browseRemote(remote)
                    MENU_EDIT -> editRemote(remote)
                    MENU_CREATE_FOLDER -> createCryptFolder(remote)
                    MENU_DELETE -> confirmDelete(remote)
                }
                true
            }
            show()
        }
    }

    /**
     * Creates the missing folder behind a crypt remote, then re-probes it.
     */
    private fun createCryptFolder(remote: StorageRepository.RemoteSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) {
                repository.createCryptFolder(remote.name)
            }
            Toast.makeText(
                requireContext(),
                getString(if (created) R.string.storage_remote_folder_created else R.string.storage_remote_folder_failed),
                Toast.LENGTH_SHORT,
            ).show()
            loadRemotes()
        }
    }

    /** Opens the browser at an absolute path on this device. */
    private fun openLocal(absolutePath: String) {
        startActivity(
            io.earthquack.app.FileBrowserActivity.intent(
                requireContext(),
                ":local:$absolutePath"
            )
        )
    }

    /**
     * Opens shared storage, taking the user to grant access first if needed.
     */
    private fun openSharedStorage() {
        if (!repository.sharedStorageReadable()) {
            openAllFilesAccessSettings()
            return
        }
        // Delegates to the browser's own definition so the two cannot disagree
        // about what "shared storage" means.
        startActivity(
            io.earthquack.app.FileBrowserActivity.intent(
                requireContext(),
                io.earthquack.app.FileBrowserActivity.sharedStorageFs(requireContext())
            )
        )
    }

    /** Opens the browser at [remote]. */
    private fun browseRemote(remote: StorageRepository.RemoteSummary) {
        startActivity(
            io.earthquack.app.FileBrowserActivity.intent(requireContext(), "${remote.name}:")
        )
    }

    /** Opens the editor for an existing remote. */
    private fun editRemote(remote: StorageRepository.RemoteSummary) {
        val type = remote.type ?: return
        startActivity(RemoteFormActivity.editIntent(requireContext(), type, remote.name))
    }

    /**
     * Deletes a remote, after confirmation.
     *
     * Confirmation is required: this removes stored credentials for that remote
     * from `rclone.conf` and cannot be undone from the UI.
     */
    private fun confirmDelete(remote: StorageRepository.RemoteSummary) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_delete_remote_title, remote.name))
            .setMessage(R.string.dialog_delete_remote_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete_remote) { _, _ -> deleteRemote(remote) }
            .show()
    }

    private fun deleteRemote(remote: StorageRepository.RemoteSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val cfg = io.earthquack.app.storage.RcloneConfigManager(requireContext())
                cfg.ensureReady()
                val engine = withContext(Dispatchers.IO) {
                    io.earthquack.app.storage.RcloneEngines.getBlocking(cfg.configPath)
                }
                io.earthquack.app.storage.RcloneRemoteManager(engine)
                    .deleteRemote(remote.name)
            }.onSuccess {
                loadRemotes()
            }.onFailure { error ->
                _binding?.remotesMessage?.let { container ->
                    container.visibility = View.VISIBLE
                    binding.textRemotesMessage.text =
                        getString(R.string.storage_delete_failed, error.message ?: "")
                    binding.btnRemotesRetry.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Importing overwrites config the user may have set up in-app, so it is
     * confirmed first. A backup is taken regardless -- see
     * [RcloneConfigImporter] -- but confirmation is still the honest thing to do
     * when the result is not obviously reversible from the UI.
     */
    private fun confirmImport(uri: android.net.Uri) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.storage_import_confirm_title)
            .setMessage(R.string.storage_import_confirm_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.storage_import_remote) { _, _ -> performImport(uri) }
            .show()
    }

    private fun performImport(uri: android.net.Uri) {
        // Content-resolver reads can be slow for a large or remote-backed
        // provider, so the import is not done on the main thread.
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { importer.importFrom(uri) }
            if (result == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.storage_import_failed,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val backupNote = if (result.backedUp) {
                    getString(R.string.storage_import_backup)
                } else {
                    ""
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.storage_import_ok, result.count, backupNote),
                    Toast.LENGTH_LONG
                ).show()
            }
            loadRemotes()
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    /**
     * Binary units, because that is what Android's storage settings report and
     * what users compare against.
     *
     * One decimal above 1 KB only: "42.6 GB" is useful, "42.6347 GB" is noise.
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble() / 1024
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }

    private fun iconForType(type: String?): Int = when (type?.lowercase()) {
        "local" -> R.drawable.ic_eq_folder
        "sftp" -> R.drawable.ic_eq_transfer
        "drive" -> R.drawable.ic_eq_cloud
        "mega" -> R.drawable.ic_eq_cloud
        "onedrive" -> R.drawable.ic_eq_cloud
        else -> R.drawable.ic_eq_storage
    }

    private fun tintForType(type: String?): Int = when (type?.lowercase()) {
        "local" -> R.color.eq_provider_local
        "sftp" -> R.color.eq_provider_sftp
        "drive" -> R.color.eq_provider_drive
        "mega" -> R.color.eq_provider_mega
        "onedrive", "dropbox" -> R.color.eq_provider_onedrive
        else -> R.color.eq_text_muted
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** How long a probe result is trusted before it is refreshed. */
        const val PROBE_MAX_AGE_MS = 120_000L

        const val MENU_BROWSE = 1
        const val MENU_CREATE_FOLDER = 2
        const val MENU_DELETE = 3
        const val MENU_EDIT = 4
    }
}
