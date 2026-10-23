package io.earthquack.app.ui

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
import io.earthquack.app.R
import io.earthquack.app.databinding.ActivityRemoteFormBinding
import io.earthquack.app.databinding.ItemRemoteFieldBinding
import io.earthquack.app.storage.ProviderInfo
import io.earthquack.app.storage.ProviderSetting
import io.earthquack.app.storage.RcloneConfigManager
import io.earthquack.app.storage.RcloneEngine
import io.earthquack.app.storage.RcloneEngines
import io.earthquack.app.storage.RcloneRemoteManager
import kotlinx.coroutines.launch
import org.json.JSONObject

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
 * A setting rclone marks `IsPassword` or password/token type is rendered as a
 * password field with an explicit reveal control, and its value is never logged
 * or placed in a content description. rclone stores it in `rclone.conf` in
 * app-private storage; see `RcloneConfigManager`.
 */
class RemoteFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteFormBinding

    private var provider: ProviderInfo? = null
    private var editingName: String? = null
    private var isEdit: Boolean = false
    private var showAdvanced: Boolean = false

    /** Field bindings by setting name, so values can be collected on submit. */
    private val fields = mutableMapOf<String, ItemRemoteFieldBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        editingName = intent.getStringExtra(EXTRA_NAME)
        isEdit = intent.getBooleanExtra(EXTRA_EDIT, false)

        binding.appbar.title.text = if (isEdit && !editingName.isNullOrBlank()) {
            getString(R.string.remote_edit_title, editingName)
        } else {
            type
        }
        binding.appbar.subtitle.text = getString(R.string.add_remote_subtitle)
        binding.appbar.subtitle.visibility = View.VISIBLE
        binding.appbar.btnBack.visibility = View.VISIBLE
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        if (isEdit) {
            binding.btnCreate.text = getString(R.string.action_save_remote)
        }
        binding.btnCreate.setOnClickListener { create() }

        if (isEdit && !editingName.isNullOrBlank()) {
            binding.editRemoteName.setText(editingName)
            binding.editRemoteName.isEnabled = false
        } else {
            binding.editRemoteName.setText(type)
        }

        binding.btnToggleAdvanced.setOnClickListener {
            toggleAdvanced()
        }

        loadProvider(type)
    }

    private fun loadProvider(type: String) {
        binding.formFields.removeAllViews()
        binding.formFieldsAdvanced.removeAllViews()
        fields.clear()

        lifecycleScope.launch {
            val info = runCatching {
                val cfg = RcloneConfigManager(applicationContext)
                cfg.ensureReady()
                val engine = RcloneEngines.getBlocking(cfg.configPath)
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
            if (isEdit && !editingName.isNullOrBlank()) {
                prefillExisting(editingName!!)
            }
        }
    }

    private fun buildForm(info: ProviderInfo) {
        val inflater = layoutInflater
        val basicSettings = info.settings.filter { !it.isAdvanced }
        val advancedSettings = info.settings.filter { it.isAdvanced }

        basicSettings.forEach { setting ->
            val row = ItemRemoteFieldBinding.inflate(inflater, binding.formFields, false)
            bindSetting(row, setting)
            fields[setting.name] = row
            binding.formFields.addView(row.root)
        }

        advancedSettings.forEach { setting ->
            val row = ItemRemoteFieldBinding.inflate(inflater, binding.formFieldsAdvanced, false)
            bindSetting(row, setting)
            fields[setting.name] = row
            binding.formFieldsAdvanced.addView(row.root)
        }

        if (advancedSettings.isNotEmpty()) {
            binding.btnToggleAdvanced.visibility = View.VISIBLE
            updateAdvancedButtonText(advancedSettings.size)
        } else {
            binding.btnToggleAdvanced.visibility = View.GONE
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

    private fun toggleAdvanced() {
        showAdvanced = !showAdvanced
        binding.formFieldsAdvanced.visibility = if (showAdvanced) View.VISIBLE else View.GONE
        val advancedCount = provider?.settings?.count { it.isAdvanced } ?: 0
        updateAdvancedButtonText(advancedCount)
    }

    private fun updateAdvancedButtonText(advancedCount: Int) {
        binding.btnToggleAdvanced.text = if (showAdvanced) {
            getString(R.string.remote_hide_advanced)
        } else {
            getString(R.string.remote_show_advanced, advancedCount)
        }
    }

    private fun bindSetting(row: ItemRemoteFieldBinding, setting: ProviderSetting) {
        row.fieldLabel.text = setting.name
        row.fieldBadge.visibility = if (setting.required) View.VISIBLE else View.GONE

        // Help text with examples summary if available
        val helpBuilder = StringBuilder()
        if (setting.help.isNotBlank()) {
            helpBuilder.append(setting.help.trim())
        }
        if (setting.examples.isNotEmpty()) {
            val exSummary = setting.examples.take(4).joinToString(", ") { it.value }
            if (helpBuilder.isNotEmpty()) helpBuilder.append("\n")
            helpBuilder.append("Examples: ").append(exSummary)
        }
        row.fieldHelp.text = helpBuilder.toString()
        row.fieldHelp.visibility = if (helpBuilder.isBlank()) View.GONE else View.VISIBLE

        // Booleans get a switch; everything else a text field.
        if (setting.type.equals("Bool", ignoreCase = true)) {
            row.fieldInputWrap.visibility = View.GONE
            row.fieldSwitch.visibility = View.VISIBLE
            row.fieldSwitch.text = setting.name
            if (setting.defaultStr.equals("true", ignoreCase = true)) {
                row.fieldSwitch.isChecked = true
            }
            return
        }

        row.fieldSwitch.visibility = View.GONE
        row.fieldInput.visibility = View.VISIBLE
        row.fieldInputWrap.visibility = View.VISIBLE

        val numeric = setting.type.equals("Int", ignoreCase = true) ||
            setting.type.equals("Int64", ignoreCase = true) ||
            setting.type.equals("Float", ignoreCase = true) ||
            setting.type.equals("Size", ignoreCase = true)

        row.fieldInput.inputType = when {
            setting.isSecret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }

        if (setting.isSecret) {
            row.fieldInput.transformationMethod = PasswordTransformationMethod.getInstance()
            row.fieldReveal.visibility = View.VISIBLE
            row.fieldReveal.setOnClickListener { toggleReveal(row) }
            row.fieldInput.hint = getString(R.string.remote_secret_hint)
        } else {
            row.fieldInput.transformationMethod = null
            row.fieldReveal.visibility = View.GONE
            if (setting.defaultStr.isNotBlank()) {
                row.fieldInput.hint = setting.defaultStr
            } else if (setting.examples.isNotEmpty()) {
                row.fieldInput.hint = setting.examples.first().value
            }
        }
    }

    private fun prefillExisting(name: String) {
        lifecycleScope.launch {
            val params = runCatching {
                val cfg = RcloneConfigManager(applicationContext)
                cfg.ensureReady()
                val engine = RcloneEngines.getBlocking(cfg.configPath)
                val reply = engine.call("config/dump")
                reply.optJSONObject(name)
            }.getOrNull() ?: return@launch
            val info = provider ?: return@launch

            var hasActiveAdvanced = false
            info.settings.forEach { setting ->
                val row = fields[setting.name] ?: return@forEach
                if (!params.has(setting.name)) return@forEach
                val v = params.opt(setting.name)
                if (setting.type.equals("Bool", ignoreCase = true)) {
                    val isChecked = params.optBoolean(setting.name)
                    row.fieldSwitch.isChecked = isChecked
                    if (setting.isAdvanced && isChecked) hasActiveAdvanced = true
                } else {
                    val str = v?.toString().orEmpty()
                    row.fieldInput.setText(str)
                    if (setting.isAdvanced && str.isNotBlank()) hasActiveAdvanced = true
                }
            }

            if (hasActiveAdvanced && !showAdvanced) {
                toggleAdvanced()
            }
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

        // Required settings must be present.
        info.settings.filter { it.required }.forEach { required ->
            if (fields.containsKey(required.name) && readValue(required).isBlank()) {
                showError(getString(R.string.remote_field_missing, required.name))
                return
            }
        }

        // Collect all parameters (basic + advanced)
        val params = mutableMapOf<String, String>()
        info.settings.forEach { setting ->
            val value = readValue(setting)
            if (value.isNotBlank()) {
                params[setting.name] = value
            }
        }

        showError(null)
        binding.btnCreate.isEnabled = false

        lifecycleScope.launch {
            val saveResult = runCatching {
                val cfg = RcloneConfigManager(applicationContext)
                cfg.ensureReady()
                val engine = RcloneEngines.getBlocking(cfg.configPath)
                if (isEdit && !editingName.isNullOrBlank()) {
                    RcloneRemoteManager(engine).updateRemote(name, params, true)
                } else {
                    RcloneRemoteManager(engine).createRemote(name, info.name, params, true)
                }
            }

            if (saveResult.isFailure) {
                binding.btnCreate.isEnabled = true
                showError(getString(R.string.remote_create_failed, saveResult.exceptionOrNull()?.message ?: ""))
                return@launch
            }

            // Attempt test connectivity listing root, but never delete the saved remote on error.
            val verifyResult = runCatching {
                val cfg = RcloneConfigManager(applicationContext)
                val testEngine = RcloneEngines.getBlocking(cfg.configPath)
                testEngine.call(
                    "operations/list",
                    JSONObject()
                        .put("fs", "$name:")
                        .put("remote", "")
                        .put("opt", JSONObject().put("recurse", false))
                )
            }

            binding.btnCreate.isEnabled = true
            if (verifyResult.isSuccess) {
                Toast.makeText(
                    this@RemoteFormActivity,
                    getString(R.string.remote_created, name),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val warnMsg = verifyResult.exceptionOrNull()?.message ?: "not reachable"
                Toast.makeText(
                    this@RemoteFormActivity,
                    getString(R.string.remote_saved_unverified, name, warnMsg),
                    Toast.LENGTH_LONG
                ).show()
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun readValue(setting: ProviderSetting): String {
        val row = fields[setting.name] ?: return ""
        if (setting.type.equals("Bool", ignoreCase = true)) {
            val isChecked = row.fieldSwitch.isChecked
            val isDefaultTrue = setting.defaultStr.equals("true", ignoreCase = true)
            // If it's a bool and was toggled or true, return its value
            return if (isChecked != isDefaultTrue || isChecked) isChecked.toString() else ""
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
        private const val EXTRA_NAME = "remote_name"
        private const val EXTRA_EDIT = "edit_mode"

        fun intent(context: Context, type: String): Intent =
            Intent(context, RemoteFormActivity::class.java)
                .putExtra(EXTRA_TYPE, type)

        fun editIntent(context: Context, type: String, name: String): Intent =
            Intent(context, RemoteFormActivity::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_EDIT, true)
    }
}

/** Backends whose sign-in cannot complete entirely inside an embedded app without browser. */
private fun ProviderInfo.isOAuthBased(): Boolean =
    name.lowercase() in setOf("drive", "onedrive", "dropbox", "box", "pcloud", "sharepoint", "yandex", "zoho")
