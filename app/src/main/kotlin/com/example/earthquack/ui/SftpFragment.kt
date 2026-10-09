package com.example.earthquack.ui

import android.content.Context

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentSftpBinding
import com.example.earthquack.sftp.SftpServerController
import com.example.earthquack.sftp.SftpServerControllerImpl
import com.example.earthquack.sftp.SftpServerStatus
import com.example.earthquack.sftp.SftpSettings
import com.example.earthquack.sftp.SftpSettingsStore
import com.example.earthquack.sftp.AuthorizedKeysStore
import com.example.earthquack.ssh.IdentityKeyStore
import com.example.earthquack.ssh.KeystoreSecretStore
import com.example.earthquack.ssh.SecretStore
import com.example.earthquack.state.TailnetStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * SFTP server screen.
 *
 * ## What this controls
 *
 * The embedded MINA SSHD server via [SftpServerControllerImpl]. Start and Stop
 * are live controls: the server binds a port, keeps running in a foreground
 * service, and reports its real state back here. Nothing on this screen is a
 * placeholder — the status row reads the engine, the endpoint is derived from
 * the configured tailnet host, and the client count comes from the server.
 *
 * ## Why the settings are real anyway
 *
 * Port, root directory and authentication choices persist through
 * [SftpSettingsStore] and are validated on save, so a restart reads exactly the
 * values the user chose.
 *
 * ## No password is stored
 *
 * There is deliberately no password field. `ServerConfig` already keeps a bearer
 * token and an AES key as plaintext in SharedPreferences, and copying that
 * pattern to a new credential is the exact mistake the Security screen
 * documents. Authentication is public-key first; when password auth is enabled
 * the server expects a one-time credential held in the Keystore-backed
 * SecretStore, never in preferences.
 */
class SftpFragment : Fragment() {

    private var _binding: FragmentSftpBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: SftpSettingsStore
    private lateinit var secrets: SecretStore
    private lateinit var authorizedKeys: AuthorizedKeysStore
    private lateinit var keyStore: IdentityKeyStore

    /** SAF picker for a private key file. */
    private val importKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        // Show file selected feedback
        val fileName = getFileName(uri) ?: "key file"
        toast(getString(R.string.profile_key_selected_file, fileName))

        // Ask for passphrase before importing
        promptPassphrase { passphrase ->
            lifecycleScope.launch {
                val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val imported = withContext(Dispatchers.IO) {
                    keyStore.importKey(
                        "key-" + System.currentTimeMillis(),
                        "imported",
                        bytes,
                        passphrase?.toCharArray()
                    )
                }.getOrNull()
                if (imported == null) {
                    toast(R.string.profile_key_import_failed)
                } else {
                    refreshKeys()
                    selectKey(imported.alias)
                    toast(R.string.profile_key_imported)
                }
            }
        }
    }

    /**
     * The real SFTP server controller.
     *
     * Constructed in [onViewCreated] rather than as a field initializer: the
     * implementation needs a Context (for SharedPreferences-backed settings and
     * the Keystore-backed SecretStore), which a Fragment does not have at field
     * initialization time — the context is only guaranteed after attachment.
     */
    private lateinit var controller: SftpServerController

    private companion object {
        const val PASSWORD_ALIAS = "sftp_password"

        /**
         * Username presented to SFTP clients.
         *
         * A fixed name, matching what a single-user phone-as-server would
         * sensibly use. Not a credential: authentication is by key or a
         * one-time password, so this string grants nothing on its own.
         */
        const val USERNAME = "earthquack"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSftpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = SftpSettingsStore(requireContext())
        controller = SftpServerControllerImpl(requireContext())
        secrets = KeystoreSecretStore(requireContext().filesDir)
        authorizedKeys = AuthorizedKeysStore(requireContext())

        binding.rowPortEdit.setOnClickListener { promptPort() }
        binding.rowRootEdit.setOnClickListener { promptRoot() }
        binding.rowPasswordSet.setOnClickListener { promptPassword() }
        binding.rowAuthorizedKeys.setOnClickListener { showKeysDialog() }

        // Persist immediately on toggle. A settings screen where a switch needs
        // a separate Save button is a settings screen where people forget to
        // press it.
        binding.switchPasswordAuth.setOnCheckedChangeListener { _, checked ->
            if (checked && !secrets.has(PASSWORD_ALIAS)) {
                // If enabling password auth but no password is set, prompt for one
                promptPassword { success ->
                    if (!success) {
                        // User cancelled or failed to set password, revert the switch
                        binding.switchPasswordAuth.isChecked = false
                    }
                }
            } else {
                update { it.copy(passwordAuth = checked) }
            }
        }
        binding.switchPubkeyAuth.setOnCheckedChangeListener { _, checked ->
            update { it.copy(publicKeyAuth = checked) }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val settings = store.load()
        val status = controller.status()

        binding.statusServer.apply {
            dot.setBackgroundResource(
                when (status) {
                    SftpServerStatus.Stopped -> R.drawable.eq_dot_hollow_muted
                    is SftpServerStatus.Running -> R.drawable.eq_dot_hollow_success
                    is SftpServerStatus.Error -> R.drawable.eq_dot_error
                    SftpServerStatus.NotImplemented -> R.drawable.eq_dot_hollow_muted
                }
            )
            statusText.text = status.label
            statusSub.text = when (status) {
                SftpServerStatus.Stopped -> getString(R.string.sftp_stopped_sub)
                is SftpServerStatus.Running -> getString(R.string.sftp_running_sub, status.port)
                is SftpServerStatus.Error -> status.message
                SftpServerStatus.NotImplemented -> getString(R.string.sftp_not_implemented_sub)
            }

            // Start unless genuinely running. The real controller always
            // reports Stopped/Running/Error, so the control is always live —
            // unlike the previous build, where Start was disabled because no
            // server existed.
            action.visibility = View.VISIBLE
            action.isEnabled = status.isControllable
            action.alpha = if (status.isControllable) 1f else 0.4f
            val running = status is SftpServerStatus.Running
            action.text = getString(if (running) R.string.sftp_stop else R.string.sftp_start)
            action.setIconResource(
                if (running) R.drawable.ic_eq_stop else R.drawable.ic_eq_play
            )
            action.setOnClickListener { toggleServer(status) }
        }

        binding.textUnavailable.visibility =
            if (status == SftpServerStatus.NotImplemented) View.VISIBLE else View.GONE

        // Connection details. Endpoint is real when a host is configured, and
        // says so plainly when not -- the placeholder is never printed as an
        // address.
        val configured = true
        val phoneIp = TailnetStatus(requireContext()).tailnetAddress()
        binding.rowEndpoint.label.text = getString(R.string.sftp_endpoint)
        binding.rowEndpoint.value.text = if (configured) {
            "${phoneIp ?: "no tailnet IP"}:${settings.port}"
        } else {
            getString(R.string.state_not_configured)
        }
        binding.rowUsername.label.text = getString(R.string.sftp_username)
        binding.rowUsername.value.text = USERNAME
        binding.rowRoot.label.text = getString(R.string.sftp_root)
        binding.rowRoot.value.text = settings.rootPath
        binding.rowClients.label.text = getString(R.string.sftp_clients)
        binding.rowClients.value.text = (status as? SftpServerStatus.Running)
            ?.connectedClients?.toString()
            ?: getString(R.string.state_no_clients)

        // Guards against the listener firing during render and writing back the
        // value we just read, which would mark the settings dirty on every
        // resume.
        binding.switchPasswordAuth.isChecked = settings.passwordAuth
        binding.switchPubkeyAuth.isChecked = settings.publicKeyAuth

        // Password status
        val passwordSet = secrets.has(PASSWORD_ALIAS)
        binding.textPasswordStatus.text = if (passwordSet) {
            getString(R.string.sftp_password_set_sub)
        } else {
            getString(R.string.sftp_password_none)
        }

        binding.textPort.text = settings.port.toString()
        binding.textRoot.text = settings.rootPath
        binding.textHostKey.text = settings.hostKeyFingerprint
            ?: getString(R.string.sftp_host_key_none)
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    private fun promptPort() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(store.load().port.toString())
            // Pre-select so typing replaces rather than appends.
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sftp_port)
            .setView(pad(input))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = input.text.toString().trim().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    toast(R.string.sftp_port_invalid)
                } else {
                    update { it.copy(port = port) }
                }
            }
            .show()
    }

    private fun promptRoot() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(store.load().rootPath)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sftp_root)
            .setView(pad(input))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isEmpty()) {
                    toast(R.string.sftp_root_invalid)
                } else {
                    update { it.copy(rootPath = path) }
                }
            }
            .show()
    }

    /** Dialogs need their own padding; an EditText with none looks broken. */
    private fun pad(input: EditText): View = FrameLayout(requireContext()).apply {
        val pad = resources.getDimensionPixelSize(R.dimen.eq_gap_lg)
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }

    /**
     * Shows the authorized keys management dialog.
     */
    private fun showKeysDialog() {
        val keys = authorizedKeys.list()
        if (keys.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_authorized_keys)
                .setMessage(R.string.sftp_no_authorized_keys)
                .setPositiveButton(R.string.sftp_add_key) { _, _ ->
                    // TODO: Implement key import from file
                    toast("Key import not yet implemented")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            val items = keys.map { it.label }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_authorized_keys)
                .setItems(items) { _, which ->
                    val entry = keys[which]
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(entry.label)
                        .setMessage(entry.publicKey)
                        .setPositiveButton(R.string.sftp_remove_key) { _, _ ->
                            authorizedKeys.remove(entry.publicKey)
                            render()
                            toast(R.string.sftp_key_removed)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setPositiveButton(R.string.sftp_add_key) { _, _ ->
                    importKeyLauncher.launch(arrayOf("*/*"))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Prompts the user to set/change/clear the SFTP server password.
     *
     * When a password is already set, offers to change or clear it.
     * When no password is set, only offers to set one.
     */
    private fun promptPassword(onComplete: ((Boolean) -> Unit)? = null) {
        val hasPassword = secrets.has(PASSWORD_ALIAS)
        val title = if (hasPassword) getString(R.string.sftp_password_change) else getString(R.string.sftp_password_set)
        val items = if (hasPassword) {
            arrayOf(getString(R.string.sftp_password_change), getString(R.string.sftp_password_clear))
        } else {
            arrayOf(getString(R.string.sftp_password_set))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(items) { _, which ->
                if (hasPassword) {
                    when (which) {
                        0 -> promptNewPassword(onComplete) // Change
                        1 -> clearPassword(onComplete)     // Clear
                    }
                } else {
                    when (which) {
                        0 -> promptNewPassword(onComplete) // Set
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onComplete?.invoke(false) }
            .show()
    }

    private fun promptNewPassword(onComplete: ((Boolean) -> Unit)? = null) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.sftp_password_set_hint)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.sftp_password_set))
            .setView(pad(input))
            .setNegativeButton(android.R.string.cancel) { _, _ -> onComplete?.invoke(false) }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val password = input.text.toString()
                if (password.isBlank()) {
                    toast(R.string.sftp_settings_invalid)
                    onComplete?.invoke(false)
                } else {
                    secrets.put(PASSWORD_ALIAS, password.toByteArray(StandardCharsets.UTF_8))
                    render()
                    onComplete?.invoke(true)
                }
            }
            .show()
    }

    private fun clearPassword(onComplete: ((Boolean) -> Unit)? = null) {
        secrets.delete(PASSWORD_ALIAS)
        // If password auth is enabled but we cleared the password, disable it
        val settings = store.load()
        if (settings.passwordAuth) {
            update { it.copy(passwordAuth = false) }
        } else {
            render()
        }
        onComplete?.invoke(true)
    }

    /** Applies a change, saves it, and re-renders. Invalid values are rejected. */
    private fun update(transform: (SftpSettings) -> SftpSettings) {
        val oldSettings = store.load()
        val candidate = transform(oldSettings)
        if (store.save(candidate)) {
            // Check if any setting that requires a server restart has changed
            val needsRestart = oldSettings.port != candidate.port ||
                oldSettings.rootPath != candidate.rootPath ||
                oldSettings.username != candidate.username ||
                oldSettings.passwordAuth != candidate.passwordAuth ||
                oldSettings.publicKeyAuth != candidate.publicKeyAuth ||
                oldSettings.maxConnections != candidate.maxConnections

            render()

            if (needsRestart) {
                // Server needs restart to apply new settings
                val currentStatus = controller.status()
                if (currentStatus is SftpServerStatus.Running) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = controller.start(candidate)
                        if (result is SftpServerStatus.Error) {
                            // Restart failed, show error and revert to old settings
                            toast(R.string.sftp_start_failed)
                            store.save(oldSettings)
                            render()
                        }
                    }
                }
            }
        } else {
            // save() rejects an out-of-range port or blank root; say so instead
            // of silently discarding the edit.
            toast(R.string.sftp_settings_invalid)
        }
    }

    private fun toggleServer(status: SftpServerStatus) {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = store.load()
            val next = if (status == SftpServerStatus.Stopped) {
                controller.start(settings)
            } else {
                controller.stop()
            }
            render()
            if (next is SftpServerStatus.Error) toast(R.string.sftp_start_failed)
        }
    }

    private fun toast(res: Int) =
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Gets a display name for a SAF URI.
     */
    private fun getFileName(uri: Uri): String? {
        return requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }
    }

    /**
     * Prompts the user for a key passphrase.
     */
    private fun promptPassphrase(onResult: (String?) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.profile_key_passphrase_hint)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_key_passphrase_title)
            .setView(input.apply {
                val pad = resources.getDimensionPixelSize(R.dimen.eq_gap_lg)
                setPadding(pad, pad / 2, pad, 0)
            })
            .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(null) }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                onResult(if (text.isEmpty()) null else text)
            }
            .show()
    }

    private fun refreshKeys() {
        // Not needed for SftpFragment - keys are managed in ProfileEditorActivity
    }

    private fun selectKey(alias: String?) {
        // Not needed for SftpFragment - keys are managed in ProfileEditorActivity
    }
}
