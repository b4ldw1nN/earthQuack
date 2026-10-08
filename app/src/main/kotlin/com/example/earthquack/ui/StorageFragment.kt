package com.example.earthquack.ui

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
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.MainActivity
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentStorageBinding
import com.example.earthquack.databinding.ItemRemoteRowBinding
import com.example.earthquack.state.StorageRepository
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
        binding.btnRemotesRetry.setOnClickListener { loadRemotes() }
        binding.btnGrantStorage.setOnClickListener { openAllFilesAccessSettings() }

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
                }
            }
        }
    }

    private fun renderRemotes(remotes: List<StorageRepository.RemoteSummary>) {
        val container = binding.cardRemotes
        container.removeAllViews()
        container.visibility = View.VISIBLE

        remotes.forEachIndexed { index, remote ->
            val row = ItemRemoteRowBinding.inflate(layoutInflater, container, false)
            row.remoteName.text = remote.name
            // Type when the config declares one, otherwise say so. Guessing a
            // backend from the section name would be fabrication.
            row.remoteStatus.text = remote.type
                ?.let { getString(R.string.storage_remote_status, it) }
                ?: getString(R.string.storage_remote_type_unknown)

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
                container.addView(divider())
            }
        }
    }

    private fun divider(): View = View(requireContext()).apply {
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.eq_divider)
        ).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
            marginEnd = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
        }
        setBackgroundColor(resources.getColor(R.color.eq_border, requireContext().theme))
    }

    private fun showRemoteMenu(
        remote: StorageRepository.RemoteSummary,
        anchor: View
    ) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_BROWSE, 0, R.string.action_browse)
            menu.add(0, MENU_DELETE, 1, R.string.action_delete_remote)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_BROWSE -> browseRemote(remote)
                    MENU_DELETE -> confirmDelete(remote)
                }
                true
            }
            show()
        }
    }

    /** Opens the browser at an absolute path on this device. */
    private fun openLocal(absolutePath: String) {
        startActivity(
            com.example.earthquack.FileBrowserActivity.intent(
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
            com.example.earthquack.FileBrowserActivity.intent(
                requireContext(),
                com.example.earthquack.FileBrowserActivity.sharedStorageFs(requireContext())
            )
        )
    }

    /** Opens the browser at [remote]. */
    private fun browseRemote(remote: StorageRepository.RemoteSummary) {
        startActivity(
            com.example.earthquack.FileBrowserActivity.intent(requireContext(), "${remote.name}:")
        )
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
                val cfg = com.example.earthquack.storage.RcloneConfigManager(requireContext())
                cfg.ensureReady()
                val engine = com.example.earthquack.storage.RcloneEngine()
                    .also { it.initialize(cfg.configPath) }
                com.example.earthquack.storage.RcloneRemoteManager(engine)
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
        const val MENU_BROWSE = 1
        const val MENU_DELETE = 2
    }
}
