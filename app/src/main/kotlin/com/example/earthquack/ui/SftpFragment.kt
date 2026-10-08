package com.example.earthquack.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.ServerConfig
import com.example.earthquack.databinding.FragmentSftpBinding
import com.example.earthquack.sftp.SftpServerController
import com.example.earthquack.sftp.SftpServerStatus
import com.example.earthquack.sftp.SftpSettings
import com.example.earthquack.sftp.SftpSettingsStore
import com.example.earthquack.sftp.UnavailableSftpController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * SFTP server screen.
 *
 * ## Status: not implemented, and the screen says so
 *
 * There is no SSH server in this build. This screen does **not** pretend
 * otherwise: the status reads "Not yet available", Start is disabled, and the
 * reason is stated in view. Showing "Stopped" with a working-looking Start button
 * would be a promise the app cannot keep, and a user who trusted it would wait
 * for a server that would never bind a port.
 *
 * ## Why the settings are real anyway
 *
 * Port, root directory and authentication choices persist through
 * [SftpSettingsStore] and are validated on save. That work is not wasted: when
 * the backend lands it reads these values directly, so nothing has to be
 * re-entered or migrated. The screen is finished; only the server is missing.
 *
 * ## No password is stored
 *
 * There is deliberately no password field. `ServerConfig` already keeps a bearer
 * token and an AES key as plaintext in SharedPreferences, and copying that
 * pattern to a new credential is the exact mistake the Security screen
 * documents. When the server arrives it can generate a one-time credential and
 * show it once, or rely on public-key auth, which stores nothing sensitive.
 */
class SftpFragment : Fragment() {

    private var _binding: FragmentSftpBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: SftpSettingsStore

    /**
     * The seam the backend will implement.
     *
     * Swapped in one place when the server exists; no layout or binding in this
     * class changes.
     */
    private val controller: SftpServerController = UnavailableSftpController()

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

        binding.rowPortEdit.setOnClickListener { promptPort() }
        binding.rowRootEdit.setOnClickListener { promptRoot() }

        // Persist immediately on toggle. A settings screen where a switch needs
        // a separate Save button is a settings screen where people forget to
        // press it.
        binding.switchPasswordAuth.setOnCheckedChangeListener { _, checked ->
            update { it.copy(passwordAuth = checked) }
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
            toggle.visibility = View.GONE
            dot.setBackgroundResource(
                when (status) {
                    SftpServerStatus.NotImplemented -> R.drawable.eq_dot_hollow_muted
                    SftpServerStatus.Stopped -> R.drawable.eq_dot_hollow_muted
                    is SftpServerStatus.Running -> R.drawable.eq_dot_hollow_success
                    is SftpServerStatus.Error -> R.drawable.eq_dot_hollow_muted
                }
            )
            statusText.text = status.label
            statusSub.text = when (status) {
                SftpServerStatus.NotImplemented -> getString(R.string.sftp_not_implemented_sub)
                SftpServerStatus.Stopped -> getString(R.string.sftp_stopped_sub)
                is SftpServerStatus.Running -> getString(R.string.sftp_running_sub, status.port)
                is SftpServerStatus.Error -> status.message
            }

            // Disabled, and the reason is on screen. The button is still visible
            // so the screen's purpose is obvious.
            //
            // Start unless genuinely running. Keying this on "is Running" rather
            // than "is Stopped" matters: NotImplemented also means nothing is
            // listening, and the earlier form of this expression showed "Stop"
            // for a server that does not exist.
            action.visibility = View.VISIBLE
            action.isEnabled = status.isControllable
            action.alpha = if (status.isControllable) 1f else 0.4f
            val running = status is SftpServerStatus.Running
            action.text = getString(if (running) R.string.sftp_stop else R.string.sftp_start)
            action.setIconResource(
                if (running) R.drawable.ic_eq_stop else R.drawable.ic_eq_play
            )
            action.setOnClickListener {
                if (status.isControllable) toggleServer(status) else showNotImplementedDialog()
            }
        }

        binding.textUnavailable.visibility =
            if (status == SftpServerStatus.NotImplemented) View.VISIBLE else View.GONE

        // Connection details. Endpoint is real when a host is configured, and
        // says so plainly when not -- the placeholder is never printed as an
        // address.
        val configured = ServerConfig.isConfigured(requireContext())
        binding.rowEndpoint.label.text = getString(R.string.sftp_endpoint)
        binding.rowEndpoint.value.text = if (configured) {
            "${ServerConfig.getHost(requireContext())}:${settings.port}"
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

    /** Applies a change, saves it, and re-renders. Invalid values are rejected. */
    private fun update(transform: (SftpSettings) -> SftpSettings) {
        val candidate = transform(store.load())
        if (store.save(candidate)) {
            render()
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

    private fun showNotImplementedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sftp_not_implemented_title)
            .setMessage(R.string.sftp_not_implemented_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(res: Int) =
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /**
         * Username presented to SFTP clients.
         *
         * A fixed name, matching what a single-user phone-as-server would
         * sensibly use. Not a credential: authentication is by key or a
         * one-time password, so this string grants nothing on its own.
         */
        const val USERNAME = "earthquack"
    }
}
