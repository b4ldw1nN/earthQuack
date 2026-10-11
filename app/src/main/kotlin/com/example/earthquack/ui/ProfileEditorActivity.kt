package com.example.earthquack.ui

import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.provider.OpenableColumns
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityProfileEditorBinding
import com.example.earthquack.ssh.AuthMethod
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.HostKeyPolicy
import com.example.earthquack.ssh.IdentityKeyInfo
import com.example.earthquack.ssh.SshServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.earthquack.ui.runProfileTest

/**
 * Add or edit a [ConnectionProfile].
 *
 * ## One profile powers Terminal and Files
 *
 * This screen writes the single record both the terminal and the SFTP browser
 * read. There is deliberately no "SFTP settings" section — an SFTP connection
 * is the same TCP connection, the same account and the same host key as a
 * shell. Duplicating any of that is how the two drift apart.
 *
 * ## Credentials never live here
 *
 * A profile references a key by alias and a password by alias; both secrets
 * stay in the Keystore-backed SecretStore. The password field writes straight
 * to that store and never into the profile object, so the profile is safe to
 * log and safe to show.
 *
 * ## No fabricated state
 *
 * Every field starts from the profile being edited, or empty when adding. The
 * host-key row shows the recorded fingerprint only when one exists; it says
 * "not verified yet" rather than implying a trust that has not happened.
 */
class ProfileEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditorBinding

    private val profileStore by lazy { SshServices.profiles(this) }
    private val keyStore by lazy { SshServices.identityKeys(this) }
    private val secretStore by lazy { SshServices.secretStore(this) }
    private val knownHosts by lazy { SshServices.knownHosts(this) }

    /** The profile being edited, or null when adding. */
    private var existing: ConnectionProfile? = null

    /** Every imported/generated key, for the key picker. */
    private var keys: List<IdentityKeyInfo> = emptyList()

    private var selectedKeyAlias: String? = null
    private var authMethod: AuthMethod = AuthMethod.PUBLIC_KEY

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
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        existing = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { profileStore.get(it) }
        authMethod = existing?.authMethod ?: AuthMethod.PUBLIC_KEY
        selectedKeyAlias = existing?.identityKeyAlias
        restoreState(savedInstanceState)

        binding.appbar.title.setText(
            if (existing == null) R.string.profile_editor_add_title
            else R.string.profile_editor_edit_title
        )
        binding.appbar.subtitle.visibility = View.GONE
        binding.appbar.btnBack.visibility = View.VISIBLE
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.authKeyRadio.setOnClickListener { setAuthMethod(AuthMethod.PUBLIC_KEY) }
        binding.authPasswordRadio.setOnClickListener { setAuthMethod(AuthMethod.PASSWORD) }

        binding.rowKeySelect.setOnClickListener { showKeyPicker() }
        binding.btnGenerateKey.setOnClickListener { generateKey() }
        binding.btnImportKey.setOnClickListener { importKeyLauncher.launch(arrayOf("*/*")) }

        binding.btnSave.setOnClickListener { save() }
        binding.btnTest.setOnClickListener { test() }

        refreshKeys()
        prefill()
    }

    /**
     * Restores the form's editor state after an activity recreation.
     *
     * The imported keys live in the store and survive anything, but *which
     * key the user selected* was a plain field — so a rotation (or any vendor
     * configuration change: a dark-mode, font, or dialog-triggered recreate)
     * reset it to null, and Save then failed validation with "public key
     * authentication needs an imported key". The user's loop — import, rotate,
     * save fails, import again — is exactly the three identical keys in the
     * store with an empty profile list. The chosen alias and auth method are
     * now part of the instance state.
     */
    private fun restoreState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        val savedName = savedInstanceState.getString(STATE_AUTH_METHOD)
        if (savedName != null) {
            runCatching { AuthMethod.valueOf(savedName) }.getOrNull()?.let { authMethod = it }
        }
        selectedKeyAlias = savedInstanceState.getString(STATE_KEY_ALIAS) ?: selectedKeyAlias
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_AUTH_METHOD, authMethod.name)
        outState.putString(STATE_KEY_ALIAS, selectedKeyAlias)
    }

    private fun prefill() {
        val p = existing
        binding.fieldName.setText(p?.name.orEmpty())
        binding.fieldHost.setText(p?.host.orEmpty())
        binding.fieldPort.setText((p?.port ?: ConnectionProfile.DEFAULT_SSH_PORT).toString())
        binding.fieldUsername.setText(p?.username.orEmpty())

        val fingerprint = p?.let { knownHosts.forProfile(it.id)?.fingerprint }
        binding.textHostKey.text = fingerprint ?: getString(R.string.profile_host_key_unverified)
        // Share is offered only for a key that exists: there is nothing to
        // verify until the first connection records one.
        binding.btnShareHostKey.isVisible = fingerprint != null
        if (fingerprint != null) {
            binding.btnShareHostKey.setOnClickListener { shareHostKey(p!!, fingerprint) }
        }

        setAuthMethod(authMethod)
        // The label follows the alias, including the restored one: a selected
        // key the UI does not show is a key the user cannot trust is selected.
        selectKey(selectedKeyAlias)
    }

    private fun setAuthMethod(method: AuthMethod) {
        authMethod = method
        binding.authKeyRadio.isChecked = method == AuthMethod.PUBLIC_KEY
        binding.authPasswordRadio.isChecked = method == AuthMethod.PASSWORD
        binding.keySection.visibility =
            if (method == AuthMethod.PUBLIC_KEY) View.VISIBLE else View.GONE
        binding.passwordSection.visibility =
            if (method == AuthMethod.PASSWORD) View.VISIBLE else View.GONE
    }

    private fun refreshKeys() {
        keys = keyStore.list()
    }

    private fun selectKey(alias: String?) {
        selectedKeyAlias = alias
        val info = keyStore.find(alias ?: return)
        binding.textSelectedKey.text = if (info != null) {
            getString(R.string.profile_key_selected, info.label, info.algorithm)
        } else {
            getString(R.string.profile_key_none)
        }
    }

    private fun showKeyPicker() {
        if (keys.isEmpty()) {
            toast(R.string.profile_no_keys)
            return
        }
        val names = keys.map { "${it.label} · ${it.algorithm}" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_select_key)
            .setItems(names) { _, index -> selectKey(keys[index].alias) }
            .show()
    }

    private fun generateKey() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { keyStore.generateKey(label = "earthquack") }
            result.onSuccess { info ->
                refreshKeys()
                selectKey(info.alias)
                // The public key is what the user installs on the server; the
                // private half never leaves the encrypted store.
                showPublicKey(info.publicKeyOpenSsh)
            }.onFailure {
                toast(R.string.profile_key_generate_failed)
            }
        }
    }

    private fun showPublicKey(line: String) {
        val view = TextView(this).apply {
            text = line
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
            textSize = 11f
        }
        val container = android.widget.FrameLayout(this).apply { addView(view) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_key_public_title)
            .setMessage(getString(R.string.profile_key_public_body))
            .setView(container)
            .setPositiveButton(R.string.action_copy) { _, _ -> copyToClipboard(line) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("public key", text))
        toast(R.string.profile_key_copied)
    }

    /**
     * Shares the profile's recorded host-key fingerprint.
     *
     * The SSH mirror of the SFTP server's share button: the fingerprint is
     * the number that makes a first connection verifiable and a changed key
     * recoverable, and a fingerprint the user cannot move off the phone is
     * one they cannot check against the server.
     */
    private fun shareHostKey(profile: ConnectionProfile, fingerprint: String) {
        val body = getString(
            R.string.profile_host_key_share_body,
            profile.endpoint,
            fingerprint
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, getString(R.string.profile_host_key_share)))
    }

    /**
     * Builds the profile the form currently describes, without saving it.
     *
     * Extracted from [save] so Test can exercise exactly what Save would
     * write — a test of a different object than the saved one would prove
     * nothing. A password typed into the form is written to the encrypted
     * store under the profile's alias first: the backend cannot authenticate
     * with a password that only exists in an EditText, and silently testing
     * the *stored* (possibly absent or old) password instead would report a
     * failure the user did not make.
     *
     * @return null when the form cannot produce a testable profile; the
     *   reason has already been shown as a toast.
     */
    private fun candidateFromForm(): ConnectionProfile? {
        val name = binding.fieldName.text.toString().trim()
        val host = binding.fieldHost.text.toString().trim()
        val port = binding.fieldPort.text.toString().trim().toIntOrNull()
            ?: ConnectionProfile.DEFAULT_SSH_PORT
        val username = binding.fieldUsername.text.toString().trim()

        var passwordAlias: String? = existing?.passwordAlias
        if (authMethod == AuthMethod.PASSWORD) {
            val entered = binding.fieldPassword.text.toString()
            if (entered.isNotEmpty()) {
                // The same alias save() would use, so a Test then Save tests
                // and saves the same credential.
                passwordAlias = "profile-pw-" + (existing?.id ?: System.currentTimeMillis())
                secretStore.put(passwordAlias, entered.toByteArray(Charsets.UTF_8))
                binding.fieldPassword.text?.clear()
            } else if (passwordAlias == null) {
                toast(R.string.profile_password_required)
                return null
            }
        }

        val candidate = ConnectionProfile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            host = host,
            port = port,
            username = username,
            authMethod = authMethod,
            identityKeyAlias = if (authMethod == AuthMethod.PUBLIC_KEY) selectedKeyAlias else null,
            passwordAlias = passwordAlias,
            hostKeyPolicy = existing?.hostKeyPolicy ?: HostKeyPolicy.TOUF
        )
        val problems = candidate.problems()
        if (problems.isNotEmpty()) {
            Toast.makeText(this, problems.first(), Toast.LENGTH_SHORT).show()
            return null
        }
        return candidate
    }

    private fun save() {
        val candidate = candidateFromForm() ?: return

        if (profileStore.upsert(candidate)) {
            setResult(RESULT_OK)
            finish()
        } else {
            toast(R.string.profile_save_failed)
        }
    }

    /**
     * Connects with the form as it stands.
     *
     * Nothing is saved by succeeding — that is Save's job — but an accepted
     * host key *is* recorded, because that is the consent the test dialog
     * asked for. [runProfileTest] shows the fingerprint and refuses to record
     * anything without an explicit accept.
     */
    private fun test() {
        val candidate = candidateFromForm() ?: return
        runProfileTest(this, candidate)
    }

    /**
     * Gets a display name for a SAF URI.
     */
    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }
    }

    /**
     * Prompts the user for a key passphrase.
     */
    /**
     * Asks for the passphrase protecting a private key being imported.
     *
     * The field is built in code, but it uses the same field drawable as the
     * form ([R.drawable.bg_eq_field]): a bare EditText gets the platform
     * default, whose underline spans the whole dialog width and reads as a
     * broken layout artifact rather than a text field.
     */
    private fun promptPassphrase(onResult: (String?) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.profile_key_passphrase_hint)
            setBackgroundResource(R.drawable.bg_eq_field)
            setTextColor(resources.getColor(R.color.eq_text_primary, theme))
            setHintTextColor(resources.getColor(R.color.eq_text_muted, theme))
            textSize = 15f
            val pad = resources.getDimensionPixelSize(R.dimen.eq_gap_md)
            setPadding(pad, pad, pad, pad)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_key_passphrase_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(null) }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                onResult(if (text.isEmpty()) null else text)
            }
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_PROFILE_ID = "profile_id"

        /** Editor fields lost to a recreation unless instance state keeps them. */
        private const val STATE_AUTH_METHOD = "auth_method"
        private const val STATE_KEY_ALIAS = "key_alias"

        /** Opens the editor for a new profile. */
        fun intent(context: android.content.Context): Intent =
            Intent(context, ProfileEditorActivity::class.java)

        /** Opens the editor for an existing profile. */
        fun intent(context: android.content.Context, profileId: String): Intent =
            Intent(context, ProfileEditorActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
    }
}
