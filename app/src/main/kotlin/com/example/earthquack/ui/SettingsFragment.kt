package com.example.earthquack.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.earthquack.R
import com.example.earthquack.ServerConfig
import com.example.earthquack.databinding.FragmentSettingsBinding
import com.example.earthquack.sftp.AuthorizedKeysStore
import com.example.earthquack.ssh.SshServices
import com.example.earthquack.state.SystemStatusProvider
import com.example.earthquack.storage.DownloadCache
import com.example.earthquack.ui.sub.BatteryActivity
import com.example.earthquack.ui.sub.FilesCacheActivity
import com.example.earthquack.ui.sub.KeysActivity
import com.example.earthquack.ui.sub.PermissionsActivity
import com.example.earthquack.ui.sub.SecurityActivity
import com.example.earthquack.ui.sub.TransfersActivity

/**
 * Settings hub.
 *
 * Five rows, each opening a focused screen. The point is that the previous
 * "giant settings page" is gone: encryption keys, bearer tokens, battery
 * toggles and permission prompts were all on one scrolling surface, which is why
 * it felt like an options dump rather than a settings screen.
 *
 * Icon tints are set per row here, which is what gives each entry its own
 * colour without needing a separate drawable per icon.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Transfers sit first: the queue is the one screen users open to
        // answer "is my download done", and burying it below security and
        // battery rows made it a destination you had to already know.
        binding.rowTransfers.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_transfer)
            rowIcon.imageTintList = tint(R.color.eq_primary)
            rowTitle.setText(R.string.settings_transfers)
            rowSubtitle.setText(R.string.settings_transfers_sub)
            root.setOnClickListener { open(TransfersActivity::class.java) }
        }

        binding.rowSecurity.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_lock)
            rowIcon.imageTintList = tint(R.color.eq_error)
            rowTitle.setText(R.string.settings_security)
            root.setOnClickListener { open(SecurityActivity::class.java) }
        }

        binding.rowKeys.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_key)
            rowIcon.imageTintList = tint(R.color.eq_primary)
            rowTitle.setText(R.string.settings_keys)
            root.setOnClickListener { open(KeysActivity::class.java) }
        }

        binding.rowBattery.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_battery)
            rowIcon.imageTintList = tint(R.color.eq_success)
            rowTitle.setText(R.string.settings_battery)
            root.setOnClickListener { open(BatteryActivity::class.java) }
        }

        binding.rowPermissions.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_shield)
            rowIcon.imageTintList = tint(R.color.eq_primary)
            rowTitle.setText(R.string.settings_permissions)
            root.setOnClickListener { open(PermissionsActivity::class.java) }
        }

        binding.rowFilesCache.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_storage)
            rowIcon.imageTintList = tint(R.color.eq_primary)
            rowTitle.setText(R.string.settings_files_cache)
            root.setOnClickListener { open(FilesCacheActivity::class.java) }
        }

        binding.rowAppearance.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_palette)
            rowIcon.imageTintList = tint(R.color.eq_provider_mega)
            rowTitle.setText(R.string.settings_appearance)
            rowSubtitle.setText(R.string.settings_appearance_sub)
            root.setOnClickListener { showAppearanceNote() }
        }

        binding.rowAbout.apply {
            rowIcon.setImageResource(R.drawable.ic_eq_info)
            rowIcon.imageTintList = tint(R.color.eq_provider_mega)
            rowTitle.setText(R.string.settings_about)
            rowSubtitle.setText(R.string.settings_about_sub)
            root.setOnClickListener { showAbout() }
        }

        renderStatuses()
    }

    private fun tint(colorRes: Int) =
        resources.getColorStateList(colorRes, requireContext().theme)

    private fun open(target: Class<*>) {
        startActivity(Intent(requireContext(), target))
    }

    private fun showAppearanceNote() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_appearance)
            .setMessage(R.string.settings_appearance_note)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * About.
     *
     * Includes the project's licence deliberately: EarthQuack ships rclone
     * (MIT) and is MIT itself, and an About screen that omits that is hiding
     * something the user is entitled to know.
     */
    private fun showAbout() {
        val version = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "—"

        val message = buildString {
            append(getString(R.string.about_version_format, version))
            append("\n\n")
            append(getString(R.string.about_license, "MIT"))
            append("\n")
            // Named explicitly rather than as a generic "third-party licences"
            // link: the embedded rclone is large enough that its presence is
            // material information, not a footnote.
            append(getString(R.string.about_rclone, "rclone (MIT)"))
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_about)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.about_licenses) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/rclone/rclone/blob/master/COPYING")
                        )
                    )
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        renderStatuses()
    }

    private fun renderStatuses() {
        if (_binding == null) return
        val context = context ?: return

        // Battery optimization
        val statusProvider = SystemStatusProvider(context)
        binding.rowBattery.rowSubtitle.text = if (statusProvider.isIgnoringBatteryOptimisations()) {
            "Unrestricted (Runs in background)"
        } else {
            "Optimized (Restricted)"
        }

        // Permissions
        val missing = statusProvider.read().missingPermissions
        binding.rowPermissions.rowSubtitle.text = if (missing.isEmpty()) {
            "All required permissions granted"
        } else {
            "${missing.size} permission${if (missing.size > 1) "s" else ""} needed"
        }

        // Security / Encryption
        val aes = ServerConfig.isAesEnabled(context)
        binding.rowSecurity.rowSubtitle.text = "Clipboard encryption: ${if (aes) "Active" else "Disabled"}"

        // SSH & SFTP Keys
        val clientCount = runCatching { SshServices.identityKeys(context).list().size }.getOrDefault(0)
        val authCount = runCatching { AuthorizedKeysStore(context).list().size }.getOrDefault(0)
        binding.rowKeys.rowSubtitle.text = "$clientCount client ${if (clientCount == 1) "key" else "keys"} · $authCount authorized"

        // Files cache size
        val cacheBytes = runCatching {
            DownloadCache.rootFor(context.cacheDir).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }.getOrDefault(0L)
        binding.rowFilesCache.rowSubtitle.text = "Cache: ${formatSize(cacheBytes)}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
