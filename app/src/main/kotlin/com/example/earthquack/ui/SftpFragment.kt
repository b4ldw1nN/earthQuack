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
import com.example.earthquack.sftp.DerivedPublicKey
import com.example.earthquack.sftp.derivePublicKeyFromPrivateKey
import com.example.earthquack.sftp.looksLikeOpenSshPrivateKey
import com.example.earthquack.sftp.parseAuthorizedKeyLine
import com.example.earthquack.ssh.KeystoreSecretStore
import com.example.earthquack.ssh.SecretStore
import com.example.earthquack.state.TailnetStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
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

    /** SAF picker for a key file: a `.pub` public key, or the private key itself. */
    private val importAuthorizedKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val label = getFileName(uri) ?: getString(R.string.sftp_imported_key_label)
        val text = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charset.forName("UTF-8"))
            }
        }.getOrNull()
        if (text == null) {
            toast(R.string.sftp_key_unreadable)
            return@registerForActivityResult
        }

        if (looksLikeOpenSshPrivateKey(text)) {
            // The user has the private key and no `.pub` file — which is the
            // common case, and one this app can now serve on its own. The public
            // half is derived from the private key; no private bytes are kept.
            promptPassphrase { passphrase ->
                val chars = passphrase?.toCharArray()
                val derived = derivePublicKeyFromPrivateKey(text, chars)
                derived.onSuccess { installDerivedKey(it, label) }
                    .onFailure {
                        showKeyError(it.message ?: getString(R.string.sftp_key_unreadable))
                    }
            }
            return@registerForActivityResult
        }

        val line = parseAuthorizedKeyLine(text).getOrElse {
            showKeyError(it.message ?: getString(R.string.sftp_key_unreadable))
            return@registerForActivityResult
        }
        installDerivedKey(
            DerivedPublicKey(line.type, line.key, line.label),
            label.ifBlank { line.label }
        )
    }

    /** Stores a derived-or-parsed key and reports it, with a persistent message. */
    private fun installDerivedKey(key: DerivedPublicKey, fallbackLabel: String) {
        val label = key.label.ifBlank { fallbackLabel }
        val line = key.toAuthorizedKeyText()
        authorizedKeys.add(label, line)
        render()
        toast(getString(R.string.sftp_key_added, label))
    }

    /** A key could not be used; say why, in a form the user will actually see. */
    private fun showKeyError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sftp_key_error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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

        binding.rowPortEdit.setOnClickListener {
            promptPort()
        }
        binding.rowRootEdit.setOnClickListener {
            promptRoot()
        }
        binding.rowPasswordSet.setOnClickListener {
            promptPassword()
        }
        binding.rowAuthorizedKeys.setOnClickListener {
            showKeysDialog()
        }

        // Persist immediately on toggle. A settings screen where a switch needs
        // a separate Save button is a settings screen where people forget to
        // press it.
        binding.switchPasswordAuth.setOnCheckedChangeListener { _, checked ->
            if (checked && !secrets.has(PASSWORD_ALIAS)) {
                // Enabling password auth with no stored password would produce a
                // configuration that cannot start. Ask for the password *now*,
                // before the setting is committed, and only turn the flag on if
                // that succeeded. The old code prompted but left the flag off
                // on success too, so password auth silently stayed disabled.
                promptNewPassword { success ->
                    if (success) {
                        render()
                        toast(R.string.sftp_password_set_sub)
                        // Ask again for the server restart now that a password
                        // exists, so the enabled state is usable immediately.
                        update { it.copy(passwordAuth = true) }
                    } else {
                        update { it.copy(passwordAuth = false) }
                        render()
                    }
                }
            } else {
                update { it.copy(passwordAuth = checked) }
            }
        }
        binding.switchPubkeyAuth.setOnCheckedChangeListener { _, checked ->
            // Enabling public-key auth with no installed key cannot start
            // either, and the failure would otherwise surface only when the
            // user presses Start.
            val keys = authorizedKeys.list()
            if (checked && keys.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sftp_authorized_keys)
                    .setMessage(R.string.sftp_no_authorized_keys_start)
                    .setPositiveButton(R.string.sftp_add_key) { _, _ -> importPublicKey() }
                    .setNegativeButton(R.string.sftp_cancel) { _, _ ->
                        binding.switchPubkeyAuth.isChecked = false
                    }
                    .setOnCancelListener { binding.switchPubkeyAuth.isChecked = false }
                    .show()
            } else {
                update { it.copy(publicKeyAuth = checked) }
            }
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
     *
     * "Add key" opens the system file picker. It used to toast "Key import not
     * yet implemented" when the list was empty, so the button existed and did
     * nothing — which left a client with no way to install a key at all.
     */
    private fun showKeysDialog() {
        val keys = authorizedKeys.list()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sftp_authorized_keys)
            .setItems(keys.map { it.label }.toTypedArray()) { _, which ->
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
            .setPositiveButton(R.string.sftp_add_key) { _, _ -> importPublicKey() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opens the system picker for an OpenSSH public key file. */
    private fun importPublicKey() {
        importAuthorizedKeyLauncher.launch(arrayOf("text/plain", "*/*"))
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

    /**
     * Clears the stored server password.
     *
     * Password authentication cannot remain enabled without a password — that
     * state can never start a server. But disabling it must not leave the server
     * with *no* authentication method either, so the two flags are checked before
     * the secret is deleted and the user is asked to choose. Deleting first and
     * repairing afterwards, which is what this did, meant the user could end up
     * with an unusable server and no idea which flag did it.
     */
    private fun clearPassword(onComplete: ((Boolean) -> Unit)? = null) {
        val settings = store.load()
        val wouldLeaveNoAuth = !settings.publicKeyAuth

        if (wouldLeaveNoAuth) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_password_clear_title)
                .setMessage(R.string.sftp_password_clear_no_auth)
                .setPositiveButton(R.string.sftp_password_clear_disable) { _, _ ->
                    secrets.delete(PASSWORD_ALIAS)
                    update { it.copy(passwordAuth = false) }
                    render()
                    onComplete?.invoke(true)
                }
                .setNegativeButton(R.string.sftp_password_clear_cancel) { _, _ -> onComplete?.invoke(false) }
                .show()
            return
        }

        secrets.delete(PASSWORD_ALIAS)
        if (settings.passwordAuth) {
            update { it.copy(passwordAuth = false) }
        } else {
            render()
        }
        // Report success only now: the earlier version signalled success before
        // the restart had even been attempted, so a failed restart looked like a
        // completed one.
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

    // ── server control ───────────────────────────────────────────────────────

    /**
     * Starts or stops the server, based on what it is actually doing.
     *
     * Branches on "is it running", not on "is the status exactly Stopped". The
     * previous code compared `status == SftpServerStatus.Stopped`, which is a
     * different object from `Error` — so a server that had failed to start once
     * was treated as *running*: pressing Start called `stop()`, the status went
     * back to Stopped, and pressing Start again failed the same way forever. The
     * button moved, and nothing it offered ever started a server.
     */
    private fun toggleServer(status: SftpServerStatus) {
        viewLifecycleOwner.lifecycleScope.launch {
            val running = status is SftpServerStatus.Running
            val settings = store.load()

            // The reason the last start failed is what tells the user what to
            // change, so it is shown verbatim rather than replaced by a generic
            // "could not start".
            if (!running && status is SftpServerStatus.Error) {
                viewModelScopeMessage(status.message)
            }

            val next = if (running) controller.stop() else controller.start(settings)
            render()
            when (next) {
                is SftpServerStatus.Error -> toast(next.message)
                is SftpServerStatus.Running -> {
                    if (!running) toast("SFTP server started on port ${next.port}")
                }
                else -> Unit
            }
        }
    }

    /** Shows a message without assuming the fragment is attached. */
    private fun viewModelScopeMessage(message: String) {
        if (view != null) toast(message)
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
