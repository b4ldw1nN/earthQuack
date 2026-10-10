package com.example.earthquack.ui.sub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.ServerConfig
import com.example.earthquack.databinding.ActivityFilesCacheBinding
import com.example.earthquack.storage.DownloadCache
import com.example.earthquack.storage.DownloadCache.Companion.rootFor
import com.example.earthquack.ui.SubScreenActivity
import com.example.earthquack.ui.formatSize
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long downloaded files are kept, and how to empty the cache now.
 *
 * ## Why this is a screen and not a switch on the Files screen
 *
 * It is a *policy* — "delete a downloaded file after N days" — that affects a
 * gigabyte of storage. A switch on the screen it applies to would be one tap
 * away from being flipped, and the consequence is invisible until the user
 * wonders where their space went. The screen states the consequence and
 * measures the current cost, which is what makes the choice informed.
 *
 * ## What is automatic and what is not
 *
 * The retention choice is automatic: files older than it are removed without
 * the user doing anything, every time the Files screen opens. The Delete
 * button is the manual half — it empties the cache immediately, which is the
 * one action that cannot be undone. That asymmetry is why the button asks
 * first and the retention does not.
 */
class FilesCacheActivity : SubScreenActivity() {

    override val titleRes = R.string.files_cache_title

    private lateinit var body: ActivityFilesCacheBinding

    /** The cache this screen manages. */
    private lateinit var cache: DownloadCache

    override fun inflateBody(inflater: android.view.LayoutInflater, container: ViewGroup?) {
        body = ActivityFilesCacheBinding.inflate(inflater, container ?: return, false)
        container.addView(body.root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        cache = DownloadCache(rootFor(cacheDir))
        super.onCreate(savedInstanceState)

        buildRetentionOptions()
        body.btnClearCache.setOnClickListener { confirmClear() }
        refreshFigures()
    }

    /**
     * Builds one radio button per retention choice.
     *
     * From the same constant the preference is read with, so the checked state
     * and the stored value cannot disagree. The choices are deliberately in
     * ascending order with "Off" first, because the question is "how long",
     * and a list that starts at "Off" makes "keep nothing" an obvious option
     * rather than a hidden one.
     */
    private fun buildRetentionOptions() {
        val selected = ServerConfig.getDownloadCacheDays(this)
        for (days in ServerConfig.DOWNLOAD_CACHE_CHOICES) {
            val button = RadioButton(this).apply {
                text = labelFor(days)
                textSize = 15f
                isChecked = days == selected
                setOnClickListener { setRetention(days) }
            }
            body.retentionGroup.addView(button)
        }
    }

    private fun labelFor(days: Int): String = when (days) {
        0 -> getString(R.string.files_cache_choice_off)
        1 -> getString(R.string.files_cache_choice_day)
        else -> getString(R.string.files_cache_choice_days, days)
    }

    /** Stores [days] and immediately applies it. */
    private fun setRetention(days: Int) {
        ServerConfig.setDownloadCacheDays(this, days)
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) { cache.prune(days) }
            refreshFigures()
            // Only worth saying when something actually went: a retention
            // change that removed nothing is not an event.
            if (removed > 0) {
                Toast.makeText(
                    this@FilesCacheActivity,
                    getString(R.string.files_cache_pruned, removed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Empties the cache, after asking. */
    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_cache_clear_confirm_title)
            .setMessage(R.string.files_cache_clear_confirm)
            .setNegativeButton(R.string.action_dismiss, null)
            .setPositiveButton(R.string.files_cache_clear) { _, _ ->
                lifecycleScope.launch {
                    val removed = withContext(Dispatchers.IO) { cache.clear() }
                    refreshFigures()
                    Toast.makeText(
                        this@FilesCacheActivity,
                        getString(R.string.files_cache_cleared, removed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun refreshFigures() {
        lifecycleScope.launch {
            val (files, bytes) = withContext(Dispatchers.IO) {
                cache.count() to cache.size()
            }
            body.cacheSize.text = when (files) {
                0 -> getString(R.string.files_cache_empty)
                else -> getString(R.string.files_cache_used, formatSize(bytes), files)
            }
        }
    }

}
