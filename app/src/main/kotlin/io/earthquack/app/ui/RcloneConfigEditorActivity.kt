package io.earthquack.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.earthquack.app.R
import io.earthquack.app.databinding.ActivityRcloneConfigEditorBinding
import io.earthquack.app.storage.RcloneConfigManager
import io.earthquack.app.storage.RcloneEngine
import io.earthquack.app.storage.RcloneEngines
import io.earthquack.app.storage.RcloneRemoteManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The raw `rclone.conf`, editable.
 *
 * ## Why a raw editor, when there is a form
 *
 * The per-remote form ([AddRemoteActivity]) can express every remote, but it
 * cannot express what the user sometimes needs to *un*-express: a combine
 * remote's upstream list naming a remote that was later deleted, a value
 * rclone accepts that the form has no field for. The honest fix for a broken
 * config is editing the config, not forcing a re-import from a computer.
 *
 * ## Safety, matching [RcloneConfigImporter]'s trust model
 *
 * - **Backup before write.** The previous file is copied beside it first, so
 *   a bad edit is one tap away from being undone; the backup is the same
 *   app-private, owner-only location as the config itself.
 * - **Verify after write.** A fresh engine reads the new file back and lists
 *   remotes — rclone's own INI reader is the validator. A file rclone refuses
 *   leaves the *old* file in place, the backup is offered for restore, and
 *   nothing is left half-saved.
 * - **Size-capped**, so an absurd paste cannot expand storage or feed rclone
 *   a megabyte of junk.
 * - **Never logged.** The text goes nowhere near logcat; this class does not
 *   log its contents at all, at any level.
 */
class RcloneConfigEditorActivity : AppCompatActivity() {

    private companion object {
        /** Same cap as the importer: one config, comfortably. */
        const val MAX_CONFIG_BYTES = 512 * 1024L
        const val BACKUP_SUFFIX = ".bak"
    }

    private lateinit var binding: ActivityRcloneConfigEditorBinding
    private lateinit var configManager: RcloneConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRcloneConfigEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = RcloneConfigManager(this)

        binding.appbar.btnBack.setOnClickListener { finish() }
        binding.appbar.title.setText(R.string.config_editor_title)
        binding.appbar.subtitle.visibility = View.GONE

        binding.editConfig.movementMethod = ScrollingMovementMethod()
        binding.btnSave.setOnClickListener { confirmSave() }
        binding.btnRestore.setOnClickListener { restoreBackup() }

        loadConfig()
    }

    private fun loadConfig() {
        val file = configManager.configFile
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                when {
                    file.exists() -> file.readText()
                    else -> null
                }
            }
            if (text == null) {
                binding.editConfig.setText("")
                showStatus(getString(R.string.config_editor_empty))
                binding.btnSave.isEnabled = false
            } else {
                binding.editConfig.setText(text)
            }
        }
    }

    /**
     * Confirms before writing.
     *
     * The dialog states the consequence plainly — every remote is re-read
     * from this file — and names the backup, so the save feels like the
     * recoverable act it is.
     */
    private fun confirmSave() {
        val edited = binding.editConfig.text?.toString() ?: return
        if (edited.length > MAX_CONFIG_BYTES) {
            Toast.makeText(this, R.string.config_editor_too_big, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.config_editor_save_title)
            .setMessage(R.string.config_editor_save_body)
            .setNegativeButton(R.string.action_dismiss, null)
            .setPositiveButton(R.string.config_editor_save) { _, _ -> save(edited) }
            .show()
    }

    private fun save(edited: String) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val file = configManager.configFile
                file.parentFile?.mkdirs()
                val backup = File(file.absolutePath + BACKUP_SUFFIX)
                if (file.exists()) {
                    // Backup first: a write that fails halfway must not be
                    // able to take the only copy with it.
                    file.copyTo(backup, overwrite = true)
                    configManager.repairPermissions(backup)
                }
                file.writeText(edited)
                configManager.repairPermissions(file)
                // The shared engine holds the old config in memory; drop it
                // so what follows reads the file that was just written.
                io.earthquack.app.storage.RcloneEngines.reset()
                // The validator is rclone itself: a fresh engine re-reads the
                // file with rclone's own INI parser.
                val engine = RcloneEngines.getBlocking(configManager.configPath)
                try {
                    RcloneRemoteManager(engine).listRemotes()
                    true
                } catch (e: io.earthquack.app.storage.RcloneException) {
                    // The validator is rclone's own INI reader: a file it
                    // refuses is not a file to leave behind.
                    false
                } finally {
                    engine.shutdown()
                }
            }
            if (outcome) {
                setResult(RESULT_OK)
                Toast.makeText(this@RcloneConfigEditorActivity, R.string.config_editor_saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // The new file is in place but unreadable: say so, and put the
                // recovery one tap away.
                showStatus(getString(R.string.config_editor_rejected))
                binding.btnRestore.visibility = View.VISIBLE
            }
        }
    }

    private fun restoreBackup() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val file = configManager.configFile
                val backup = File(file.absolutePath + BACKUP_SUFFIX)
                if (!backup.exists()) return@withContext false
                backup.copyTo(file, overwrite = true)
                configManager.repairPermissions(file)
                val engine = RcloneEngines.getBlocking(configManager.configPath)
                try {
                    RcloneRemoteManager(engine).listRemotes()
                    true
                } catch (e: io.earthquack.app.storage.RcloneException) {
                    // The validator is rclone's own INI reader: a file it
                    // refuses is not a file to leave behind.
                    false
                } finally {
                    engine.shutdown()
                }
            }
            if (ok) {
                binding.btnRestore.visibility = View.GONE
                binding.editConfig.setText(withContext(Dispatchers.IO) { configManager.configFile.readText() })
                showStatus(getString(R.string.config_editor_restored))
            } else {
                showStatus(getString(R.string.config_editor_restore_failed))
            }
        }
    }

    private fun showStatus(message: String) {
        binding.textStatus.text = message
        binding.textStatus.visibility = View.VISIBLE
    }
}
