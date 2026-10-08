package com.example.earthquack.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityRemoteFormBinding
import com.example.earthquack.databinding.ItemRemoteFieldBinding
import com.example.earthquack.storage.ProviderInfo
import com.example.earthquack.storage.ProviderSetting
import com.example.earthquack.storage.RcloneConfigManager
import com.example.earthquack.storage.RcloneEngine
import com.example.earthquack.storage.RcloneRemoteManager
import kotlinx.coroutines.launch

/**
 * Step 2: the settings form for one rclone backend.
 *
 * ## Why the form is generated
 *
 * Backends take completely different keys: an SFTP remote wants `host` and
 * `user`, Drive wants a client id, MEGA wants a user and password. Writing a
 * form per backend is unmaintainable across ~40 of them and would inevitably
 * disagree with `config/providers`. So the fields are built at runtime from
 * [ProviderInfo.settings], and rclone is the single source of truth for what a
 * backend needs.
 *
 * ## Secrets
 *
 * A setting rclone marks `Sensitive` is rendered as a password field with an
 * explicit reveal control, and its value is never logged or placed in a content
 * description. rclone stores it in `rclone.conf` in app-private storage; see
 * `RcloneConfigManager`.
 *
 * ## nonInteractive
 *
 * Always true. An embedded app cannot service the browser-based OAuth flow
 * rclone would otherwise try to start, and a half-started flow is worse than a
 * clean "saved but not signed in" — see the note shown on the form.
 */
class RemoteFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteFormBinding

    private var provider: ProviderInfo? = null

    /** Field bindings by setting name, so values can be collected on submit. */
    private val fields = mutableMapOf<String, ItemRemoteFieldBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()

        binding.appbar.title.text = type
        binding.appbar.subtitle.text = getString(R.string.add_remote_subtitle)
        binding.appbar.subtitle.visibility = View.VISIBLE
        binding.appbar.btnBack.visibility = View.VISIBLE
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnCreate.setOnClickListener { create() }
        // Default the section name to the backend name; still editable, since
        // "drive" and "drive-backup" are both useful.
        binding.editRemoteName.setText(type)

        loadProvider(type)
    }

    private fun loadProvider(type: String) {
        binding.formFields.removeAllViews()
        fields.clear()

        lifecycleScope.launch {
            val info = runCatching {
                val cfg = RcloneConfigManager(applicationContext)
                cfg.ensureReady()
                val engine = RcloneEngine().also { it.initialize(cfg.configPath) }
                RcloneRemoteManager(engine).providers().firstOrNull { it.name == type }
            }.getOrNull()

            if (info == null) {
                Toast.makeText(
                    this@RemoteFormActivity,
                    getString(R.string.add_remote_error, type),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            provider = info
            buildForm(info)
        }
    }

    private fun buildForm(info: ProviderInfo) {
        val inflater = layoutInflater
        info.settings.forEach { setting ->
            // Advanced settings are hidden by default; showing all ~15 keys of
            // every backend at once is the same mistake the old settings screen
            // made.
            if (setting.advanced()) return@forEach

            val row = ItemRemoteFieldBinding.inflate(inflater, binding.formFields, false)
            bindSetting(row, setting)
            fields[setting.name] = row
            binding.formFields.addView(row.root)
        }

        // Nothing generated means everything this backend needs is optional.
        if (fields.isEmpty()) {
            val note = ItemRemoteFieldBinding.inflate(inflater, binding.formFields, false)
            note.fieldLabel.text = getString(R.string.remote_no_settings)
            note.fieldLabel.setTextColor(getColor(R.color.eq_text_muted))
            note.fieldInputWrap.visibility = View.GONE
            note.fieldSwitch.visibility = View.GONE
            binding.formFields.addView(note.root)
        }

        binding.cardOauthNote.visibility =
            if (info.isOAuthBased()) View.VISIBLE else View.GONE
    }

    private fun bindSetting(row: ItemRemoteFieldBinding, setting: ProviderSetting) {
        row.fieldLabel.text = setting.name
        row.fieldBadge.visibility = if (setting.required) View.VISIBLE else View.GONE
        row.fieldHelp.text = setting.help
        row.fieldHelp.visibility = if (setting.help.isBlank()) View.GONE else View.VISIBLE

        // Booleans get a switch; everything else a text field.
        if (setting.type.equals("Bool", ignoreCase = true)) {
            row.fieldInputWrap.visibility = View.GONE
            row.fieldSwitch.visibility = View.VISIBLE
            row.fieldSwitch.text = setting.name
            return
        }

        row.fieldSwitch.visibility = View.GONE
        row.fieldInput.visibility = View.VISIBLE

        // Numeric types get a numeric keyboard; it is a small correctness win
        // (rclone rejects a non-numeric port) even though rclone still validates.
        val numeric = setting.type.equals("Int", ignoreCase = true) ||
            setting.type.equals("Int64", ignoreCase = true) ||
            setting.type.equals("Float", ignoreCase = true) ||
            setting.type.equals("Size", ignoreCase = true)

        row.fieldInput.inputType = when {
            // rclone's obscured values are stored base64-encoded. Sending them
            // as plain text still works because rclone re-obscures on write, but
            // password type keeps them off the keyboard's learning dictionary.
            setting.isSecret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (setting.isSecret) {
            row.fieldInput.transformationMethod = PasswordTransformationMethod.getInstance()
            row.fieldReveal.visibility = View.VISIBLE
            row.fieldReveal.setOnClickListener { toggleReveal(row) }
        }

        // The help text goes in the hint only when it is safe to show, which for
        // a secret field means never: a hint can be captured by a screenshot or
        // an accessibility service.
        if (setting.isSecret) {
            row.fieldInput.hint = getString(R.string.remote_secret_hint)
        }
    }

    private fun toggleReveal(row: ItemRemoteFieldBinding) {
        val revealed = row.fieldInput.transformationMethod == null
        row.fieldInput.transformationMethod =
            if (revealed) PasswordTransformationMethod.getInstance() else null
        row.fieldReveal.setImageResource(
            if (revealed) R.drawable.ic_eq_eye else R.drawable.ic_eq_eye_off
        )
        row.fieldReveal.contentDescription =
            getString(if (revealed) R.string.action_show else R.string.action_hide)
        row.fieldInput.setSelection(row.fieldInput.text?.length ?: 0)
    }

    private fun create() {
        val info = provider ?: return

        val name = binding.editRemoteName.text.toString().trim()
        if (name.isBlank() || name.contains('[') || name.contains(']')) {
            showError(getString(R.string.remote_name_invalid))
            return
        }

        // Required settings must be present. Caught here so the user gets a
        // specific message naming the field, rather than rclone's generic
        // 400 about a missing key.
        info.settings.filter { it.required }.forEach { required ->
            if (fields.containsKey(required.name) &&
                readValue(required) .isBlank()
            ) {
                showError(getString(R.string.remote_field_missing, required.name))
                return
            }
        }

        val params = mutableMapOf<String, String>()
        info.settings.forEach { setting ->
            if (setting.advanced()) return@forEach
            val value = readValue(setting)
            // Empty optional settings are omitted entirely. Sending "" for a
            // field rclone treats as "unset" makes it believe a value was
            // supplied, which produces surprising validation errors.
            if (value.isNotBlank()) params[setting.name] = value
        }

        showError(null)
        binding.btnCreate.isEnabled = false

        lifecycleScope.launch {
            runCatching {
                val cfg = RcloneConfigManager(applicationContext)
                cfg.ensureReady()
                val engine = RcloneEngine().also { it.initialize(cfg.configPath) }
                // nonInteractive: an embedded app cannot run rclone's browser
                // OAuth flow. See the class comment.
                RcloneRemoteManager(engine).createRemote(name, info.name, params, true)
            }.onSuccess {
                Toast.makeText(
                    this@RemoteFormActivity,
                    getString(R.string.remote_created, name),
                    Toast.LENGTH_LONG
                ).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { error ->
                binding.btnCreate.isEnabled = true
                // rclone's own message; it is far more useful than anything
                // invented here. Never log the params -- they contain secrets.
                showError(getString(R.string.remote_create_failed, error.message ?: ""))
            }
        }
    }

    private fun readValue(setting: ProviderSetting): String {
        val row = fields[setting.name] ?: return ""
        if (setting.type.equals("Bool", ignoreCase = true)) {
            return row.fieldSwitch.isChecked.toString()
        }
        return row.fieldInput.text?.toString()?.trim().orEmpty()
    }

    private fun showError(message: String?) {
        binding.textFormError.text = message.orEmpty()
        binding.textFormError.visibility =
            if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val EXTRA_TYPE = "remote_type"

        fun intent(context: Context, type: String): Intent =
            Intent(context, RemoteFormActivity::class.java)
                .putExtra(EXTRA_TYPE, type)
    }
}

/** Advanced keys rclone marks, so the form can hide them by default. */
private fun ProviderSetting.advanced(): Boolean = isAdvanced

/** Backends whose sign-in cannot complete inside this app. */
private fun ProviderInfo.isOAuthBased(): Boolean =
    name.lowercase() in setOf("drive", "onedrive", "dropbox", "box", "pcloud", "sharepoint")
