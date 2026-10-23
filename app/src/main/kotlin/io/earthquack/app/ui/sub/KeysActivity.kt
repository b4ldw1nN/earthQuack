package io.earthquack.app.ui.sub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.earthquack.app.R
import io.earthquack.app.databinding.ActivityKeysBinding
import io.earthquack.app.databinding.ItemKeyEntryBinding
import io.earthquack.app.sftp.AuthorizedKeysStore
import io.earthquack.app.sftp.HostKeyPairing
import io.earthquack.app.sftp.SftpAuthorizedKeys
import io.earthquack.app.sftp.parseAuthorizedKeyLine
import io.earthquack.app.sftp.readHostKeyPublicLine
import io.earthquack.app.ssh.IdentityKeyInfo
import io.earthquack.app.ssh.SshServices
import io.earthquack.app.ui.SubScreenActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Unified management for SSH and SFTP keys.
 *
 * Surfaces client identity keys, SFTP server authorized client keys, and
 * the phone's SFTP host key in one coordinated screen.
 */
class KeysActivity : SubScreenActivity() {

    override val titleRes = R.string.keys_title
    override val subtitleRes = R.string.keys_subtitle

    private lateinit var body: ActivityKeysBinding
    private val keyStore by lazy { SshServices.identityKeys(this) }
    private val authorizedKeysStore by lazy { AuthorizedKeysStore(this) }

    private val importClientKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        importClientKeyFromUri(uri)
    }

    private val importAuthorizedKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        importAuthorizedKeyFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wire()
        render()
    }

    override fun inflateBody(inflater: LayoutInflater, container: ViewGroup?) {
        body = ActivityKeysBinding.inflate(inflater, container, false)
        container?.removeAllViews()
        container?.addView(body.root)
    }

    private fun wire() {
        body.btnGenerateKey.setOnClickListener { generateClientKey() }
        body.btnImportKey.setOnClickListener {
            importClientKeyLauncher.launch(arrayOf("*/*"))
        }
        body.btnAddAuthorizedKey.setOnClickListener {
            importAuthorizedKeyLauncher.launch(arrayOf("text/plain", "*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        renderClientKeys()
        renderAuthorizedKeys()
        renderHostKey()
    }

    private fun renderClientKeys() {
        body.clientKeysContainer.removeAllViews()
        val keys = keyStore.list()
        val authorizedLines = authorizedKeysStore.list().map { it.publicKey }.toSet()

        if (keys.isEmpty()) {
            body.clientKeysContainer.addView(createEmptyView(getString(R.string.keys_empty_client)))
            return
        }

        keys.forEachIndexed { index, info ->
            if (index > 0) {
                body.clientKeysContainer.addView(createDivider())
            }
            val row = ItemKeyEntryBinding.inflate(layoutInflater, body.clientKeysContainer, false)
            row.keyTitle.text = "${info.label} (${info.algorithm})"
            row.keySubtitle.text = info.fingerprint

            row.btnCopyKey.setOnClickListener {
                copyToClipboard("public key", info.publicKeyOpenSsh)
                toast(R.string.profile_key_copied)
            }

            // If this client key is not already authorized on the local SFTP server, offer 1-tap authorize
            val isAlreadyAuthorized = authorizedLines.any { it.contains(info.publicKeyOpenSsh.trim()) || info.publicKeyOpenSsh.contains(it.trim()) }
            if (!isAlreadyAuthorized) {
                row.btnAuthorizeKey.isVisible = true
                row.btnAuthorizeKey.setOnClickListener {
                    authorizedKeysStore.add(info.label, info.publicKeyOpenSsh)
                    toast(R.string.keys_sftp_authorized_success)
                    render()
                }
            } else {
                row.btnAuthorizeKey.isVisible = false
            }

            row.btnDeleteKey.setOnClickListener {
                confirmDelete(info.label) {
                    keyStore.delete(info.alias)
                    render()
                }
            }

            body.clientKeysContainer.addView(row.root)
        }
    }

    private fun renderAuthorizedKeys() {
        body.authorizedKeysContainer.removeAllViews()
        val keys = authorizedKeysStore.list()

        if (keys.isEmpty()) {
            body.authorizedKeysContainer.addView(createEmptyView(getString(R.string.keys_empty_authorized)))
            return
        }

        keys.forEachIndexed { index, entry ->
            if (index > 0) {
                body.authorizedKeysContainer.addView(createDivider())
            }
            val row = ItemKeyEntryBinding.inflate(layoutInflater, body.authorizedKeysContainer, false)
            row.keyTitle.text = entry.label
            row.keySubtitle.text = entry.publicKey.take(64) + "…"
            row.btnAuthorizeKey.isVisible = false

            row.btnCopyKey.setOnClickListener {
                copyToClipboard("authorized key", entry.publicKey)
                toast(R.string.profile_key_copied)
            }

            row.btnDeleteKey.text = getString(R.string.keys_revoke)
            row.btnDeleteKey.setOnClickListener {
                confirmDelete(entry.label) {
                    authorizedKeysStore.remove(entry.publicKey)
                    render()
                }
            }

            body.authorizedKeysContainer.addView(row.root)
        }
    }

    private fun renderHostKey() {
        val publicLine = readHostKeyPublicLine(filesDir)
        if (publicLine == null) {
            body.textHostFingerprint.text = getString(R.string.sftp_host_key_none)
            body.btnCopyKnownHosts.isEnabled = false
            body.btnShareHostKey.isEnabled = false
            return
        }

        val parts = HostKeyPairing.partsOf(publicLine)
        val host = tailnetHost()
        val port = 2222
        val fingerprint = parts?.let {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(it.blob)
            "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        } ?: publicLine.take(48)

        body.textHostFingerprint.text = fingerprint
        body.btnCopyKnownHosts.isEnabled = parts != null
        body.btnShareHostKey.isEnabled = parts != null

        if (parts != null) {
            val knownHosts = HostKeyPairing.knownHostsLine(parts, host, port)
            body.btnCopyKnownHosts.setOnClickListener {
                copyToClipboard("known_hosts", knownHosts)
                toast(R.string.profile_key_copied)
            }

            body.btnShareHostKey.setOnClickListener {
                val shareText = getString(R.string.sftp_host_key_share_body, fingerprint, port)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.sftp_host_key_share)))
            }
        }
    }

    private fun generateClientKey() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { keyStore.generateKey(label = "earthquack") }
            result.onSuccess {
                render()
                toast("Key generated successfully")
            }.onFailure {
                toast(R.string.profile_key_generate_failed)
            }
        }
    }

    private fun importClientKeyFromUri(uri: Uri) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val label = getFileName(uri)?.substringBeforeLast('.') ?: "imported"
        val alias = UUID.randomUUID().toString()

        promptPassphrase { passphrase ->
            lifecycleScope.launch {
                val imported = withContext(Dispatchers.IO) {
                    keyStore.importKey(alias, label, bytes, passphrase?.toCharArray()).getOrNull()
                }
                if (imported == null) {
                    toast(R.string.profile_key_import_failed)
                } else {
                    render()
                    toast(R.string.profile_key_imported)
                }
            }
        }
    }

    private fun importAuthorizedKeyFromUri(uri: Uri) {
        val content = contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: return

        val line = parseAuthorizedKeyLine(content).getOrElse {
            toast(it.message ?: getString(R.string.sftp_key_unreadable))
            return
        }
        val label = getFileName(uri)?.substringBeforeLast('.') ?: line.label
        authorizedKeysStore.add(label, line.toAuthorizedKeyText())
        render()
        toast(getString(R.string.sftp_key_added, label))
    }

    private fun promptPassphrase(onResult: (String?) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Passphrase (leave empty if none)"
            setBackgroundResource(R.drawable.bg_eq_field)
            setTextColor(resources.getColor(R.color.eq_text_primary, theme))
            setHintTextColor(resources.getColor(R.color.eq_text_muted, theme))
            val pad = resources.getDimensionPixelSize(R.dimen.eq_gap_md)
            setPadding(pad, pad, pad, pad)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_key_passphrase_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(null) }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                onResult(if (text.isEmpty()) null else text)
            }
            .show()
    }

    private fun confirmDelete(label: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.keys_delete) + " " + label)
            .setMessage("Are you sure you want to remove this key?")
            .setPositiveButton(R.string.keys_delete) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createEmptyView(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(resources.getColor(R.color.eq_text_muted, theme))
        val pad = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
        setPadding(pad, pad, pad, pad)
    }

    private fun createDivider(): View = View(this).apply {
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.eq_divider)
        ).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
            marginEnd = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
        }
        setBackgroundColor(resources.getColor(R.color.eq_border, theme))
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun tailnetHost(): String =
        runCatching {
            org.json.JSONObject(io.earthquack.app.tailscale.Tailscale.thisDevice()).optString("ip")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "phone"

    private fun getFileName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
