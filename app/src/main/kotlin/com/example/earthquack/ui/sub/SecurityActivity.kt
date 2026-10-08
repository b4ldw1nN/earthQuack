package com.example.earthquack.ui.sub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import com.example.earthquack.CryptoUtil
import com.example.earthquack.R
import com.example.earthquack.ServerConfig
import com.example.earthquack.databinding.ActivitySecurityBinding
import com.example.earthquack.databinding.ItemStatusCheckBinding
import com.example.earthquack.ui.SubScreenActivity

/**
 * Encryption and authentication controls.
 *
 * Moved here from the old single-screen settings page. The behaviour is the same
 * as it was -- [ServerConfig] still reads and writes the same preferences -- so
 * nothing about the actual encryption changes with this move. What changes is
 * that the credentials are now behind a dedicated screen, masked by default,
 * and accompanied by an honest note about how they are stored.
 *
 * ## Secrets in this screen
 *
 * - Both fields are password inputs with an explicit reveal control. Nothing is
 *   rendered in the clear by default.
 * - No value is logged from here. The previous code logged sync payloads, and
 *   the clipboard history store suppresses logging of possibly-sensitive content
 *   for the same reason.
 * - Neither value is placed in a content description, so it cannot be picked up
 *   by an accessibility service reading the view tree.
 *
 * ## The storage warning is not decorative
 *
 * `ServerConfig` stores the AES key and bearer token as plaintext in
 * SharedPreferences. They are app-private, so no other app can read them, but
 * they are not encrypted at rest and are not protected by the Keystore. The
 * screen says exactly that rather than implying a guarantee it does not provide.
 */
class SecurityActivity : SubScreenActivity() {

    override val titleRes = R.string.security_title
    override val subtitleRes = R.string.security_subtitle

    private lateinit var body: ActivitySecurityBinding

    /** Guards the switch listeners while we write values into the views. */
    private var bindingUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        wire()
    }

    override fun inflateBody(inflater: LayoutInflater, container: ViewGroup?) {
        body = ActivitySecurityBinding.inflate(inflater, container, false)
        container?.removeAllViews()
        container?.addView(body.root)
    }

    private fun wire() {
        body.switchEncryption.setOnCheckedChangeListener { _, checked ->
            if (bindingUp) return@setOnCheckedChangeListener
            if (checked) {
                // Turning encryption on with no key would leave sync silently
                // unencrypted-but-"enabled". Generate one instead.
                if (!ServerConfig.hasValidAesKey(this)) {
                    val key = CryptoUtil.generateKeyBase64()
                    body.editKey.setText(key)
                    ServerConfig.setAesKey(this, key)
                }
            } else {
                // Keep the stored key so re-enabling does not require regenerating
                // and re-synchronising it with the desktop.
                setFieldsEnabled(false)
            }
            ServerConfig.setAesEnabled(this, checked)
            setFieldsEnabled(checked)
            validateKey()
            renderStatus()
        }

        // Save on focus loss rather than on every keystroke: writing to
        // SharedPreferences per character is wasteful, and a key is only complete
        // once the user is done with it.
        body.editKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitKey()
        }
        body.editToken.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                ServerConfig.setAuthToken(this, body.editToken.text.toString())
            }
        }

        body.btnGenerate.setOnClickListener {
            val key = CryptoUtil.generateKeyBase64()
            body.editKey.setText(key)
            ServerConfig.setAesKey(this, key)
            ServerConfig.setAesEnabled(this, true)
            body.switchEncryption.isChecked = true
            setFieldsEnabled(true)
            validateKey()
            renderStatus()
            toast(getString(R.string.security_key_generated))
        }

        body.btnCopyKey.setOnClickListener {
            val key = body.editKey.text.toString().trim()
            if (key.isBlank()) {
                toast(getString(R.string.security_no_key))
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("AES Key", key))
            toast(getString(R.string.security_key_copied))
        }

        body.btnRevealKey.setOnClickListener { toggleReveal(body.editKey, body.btnRevealKey) }
        body.btnRevealToken.setOnClickListener {
            toggleReveal(body.editToken, body.btnRevealToken)
        }
    }

    private fun render() {
        bindingUp = true
        val enabled = ServerConfig.isAesEnabled(this)
        body.switchEncryption.isChecked = enabled
        body.editKey.setText(ServerConfig.getAesKey(this))
        body.editToken.setText(ServerConfig.getAuthToken(this))
        bindingUp = false

        setFieldsEnabled(enabled)
        validateKey()
        renderStatus()
    }

    /**
     * Greys out the key controls when encryption is off.
     *
     * The value stays visible so switching encryption back on does not lose it,
     * but the buttons are disabled because acting on a key that is not in use is
     * misleading.
     */
    private fun setFieldsEnabled(enabled: Boolean) {
        body.editKey.isEnabled = enabled
        body.btnGenerate.isEnabled = enabled
        body.btnCopyKey.isEnabled = enabled
        body.btnRevealKey.isEnabled = enabled
        body.btnGenerate.alpha = if (enabled) 1f else 0.4f
        body.btnCopyKey.alpha = if (enabled) 1f else 0.4f
    }

    private fun commitKey() {
        val key = body.editKey.text.toString().trim()
        if (key.isNotEmpty() && !CryptoUtil.isValidKeyBase64(key)) {
            // Do not persist an invalid key: sync would then fail at encrypt
            // time with an error the user cannot connect back to this screen.
            showKeyError(getString(R.string.security_key_invalid))
            return
        }
        ServerConfig.setAesKey(this, key)
        validateKey()
        renderStatus()
    }

    private fun validateKey() {
        val key = body.editKey.text.toString().trim()
        val valid = key.isNotEmpty() && CryptoUtil.isValidKeyBase64(key)
        if (ServerConfig.isAesEnabled(this) && !valid && key.isNotEmpty()) {
            showKeyError(getString(R.string.security_key_invalid))
        } else {
            body.textKeyError.visibility = android.view.View.GONE
        }
        renderStatus()
    }

    private fun showKeyError(message: String) {
        body.textKeyError.text = message
        body.textKeyError.visibility = android.view.View.VISIBLE
    }

    private fun toggleReveal(field: android.widget.EditText, button: android.widget.ImageButton) {
        val revealed = field.transformationMethod == null
        field.transformationMethod =
            if (revealed) PasswordTransformationMethod.getInstance() else null
        button.setImageResource(if (revealed) R.drawable.ic_eq_eye else R.drawable.ic_eq_eye_off)
        button.contentDescription = getString(
            if (revealed) R.string.action_show else R.string.action_hide
        )
        // Cursor jumps to the start when the transformation changes; restore it
        // so typing continues where the user left off.
        field.setSelection(field.text?.length ?: 0)
    }

    /**
     * The status list.
     *
     * Each row carries a passing or failing icon, not a permanent green tick.
     * The earlier version used one green check for every line, so "Encryption
     * disabled" and "Not ready" were both displayed with a checkmark, which
     * contradicted the text beside it.
     *
     * Claims are derived from the current state rather than asserted, so a line
     * can never claim readiness the settings do not support.
     */
    private fun renderStatus() {
        val container = body.cardStatus
        container.removeAllViews()

        val enabled = ServerConfig.isAesEnabled(this)
        val key = body.editKey.text.toString().trim()
        val keyValid = key.isNotEmpty() && CryptoUtil.isValidKeyBase64(key)

        addStatus(
            container,
            ok = enabled,
            text = getString(
                if (enabled) R.string.security_enc_enabled
                else R.string.security_enc_disabled
            )
        )
        addStatus(
            container,
            ok = keyValid,
            text = getString(
                if (keyValid) R.string.security_key_valid
                else R.string.security_key_missing
            )
        )
        addStatus(
            container,
            ok = enabled && keyValid,
            text = getString(
                if (enabled && keyValid) R.string.security_ready
                else R.string.security_not_ready
            )
        )
    }

    /**
     * One status line, coloured by [ok].
     *
     * Icon AND text AND colour, so the state does not depend on colour alone.
     */
    private fun addStatus(container: android.view.ViewGroup, ok: Boolean, text: String) {
        val row = ItemStatusCheckBinding.inflate(layoutInflater, container, false)
        row.checkText.text = text
        row.checkIcon.setImageResource(
            if (ok) R.drawable.ic_eq_check else R.drawable.ic_eq_error
        )
        row.checkIcon.imageTintList = resources.getColorStateList(
            if (ok) R.color.eq_success else R.color.eq_error,
            theme
        )
        container.addView(row.root)
    }


    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onPause() {
        // Commit anything typed but not yet blurred, so navigating away does not
        // silently drop it.
        commitKey()
        ServerConfig.setAuthToken(this, body.editToken.text.toString())
        super.onPause()
    }
}
