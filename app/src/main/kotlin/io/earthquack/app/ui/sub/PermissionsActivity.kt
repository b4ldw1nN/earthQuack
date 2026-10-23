package io.earthquack.app.ui.sub

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import io.earthquack.app.R
import io.earthquack.app.databinding.ActivityPermissionsBinding
import io.earthquack.app.databinding.ItemPermissionRowBinding
import io.earthquack.app.state.PermissionKind
import androidx.core.view.isVisible
import io.earthquack.app.state.SystemStatusProvider
import io.earthquack.app.ui.SubScreenActivity

/**
 * The permissions EarthQuack actually requests, and nothing else.
 *
 * The app declares more in the manifest (storage, media, wake lock), but those
 * are either not runtime permissions or not needed for the clipboard service to
 * work. Listing a permission the user will never be asked for is how a
 * permissions screen starts looking like a wall of red rows.
 *
 * Each row states the consequence of denying it, because "Not granted" on its
 * own does not tell the user why they should care.
 */
class PermissionsActivity : SubScreenActivity() {

    override val titleRes = R.string.settings_permissions
    override val subtitleRes = R.string.settings_permissions_sub

    private lateinit var body: ActivityPermissionsBinding
    private lateinit var statusProvider: SystemStatusProvider

    /** Which permission the in-flight request was for. */
    private var pending: PermissionKind? = null

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusProvider = SystemStatusProvider(this)
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted the overlay in system settings while this
        // screen was hidden, so state is re-read every time rather than cached.
        if (::body.isInitialized) render()
    }

    override fun inflateBody(inflater: LayoutInflater, container: ViewGroup?) {
        body = ActivityPermissionsBinding.inflate(inflater, container, false)
        container?.removeAllViews()
        container?.addView(body.root)
    }

    private fun render() {
        val container = body.cardPermissions
        container.removeAllViews()

        // Both permissions are always listed, granted or not.
        //
        // The screen used to show only what was missing, which made it a
        // to-do list rather than a status page: a row the user had just
        // granted vanished, leaving no way to see what the app actually has,
        // and an all-granted device got an empty card that read as a broken
        // screen. State is now per row — the row stays, the button leaves.
        PermissionKind.entries.forEachIndexed { index, kind ->
            val granted = kind.isGranted()
            val row = ItemPermissionRowBinding.inflate(layoutInflater, container, false)
            row.permName.text = kind.label
            row.permWhy.text = kind.why
            row.permState.text = getString(
                if (granted) R.string.permissions_granted else R.string.permissions_not_granted
            )
            row.permState.setTextColor(
                getColor(if (granted) R.color.eq_success else R.color.eq_warning)
            )
            row.permIcon.setImageResource(
                when (kind) {
                    PermissionKind.OVERLAY -> R.drawable.ic_eq_open_new
                    PermissionKind.SHARED_STORAGE -> R.drawable.ic_eq_storage
                    else -> R.drawable.ic_eq_info
                }
            )
            // Nothing to grant means nothing to tap: a button that only
            // re-opens the system screen for a granted permission is a trap.
            row.btnGrant.isVisible = !granted
            if (!granted) row.btnGrant.setOnClickListener { grant(kind) }
            container.addView(row.root)

            if (index < PermissionKind.entries.lastIndex) container.addView(divider())
        }
    }

    private fun PermissionKind.isGranted(): Boolean = when (this) {
        PermissionKind.NOTIFICATIONS -> statusProvider.hasNotificationPermission()
        PermissionKind.OVERLAY -> statusProvider.canDrawOverlays()
        PermissionKind.SHARED_STORAGE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                getExternalFilesDir(null)?.canRead() ?: false
            }
    }

    /**
     * Requests or opens the right screen for [kind].
     *
     * The two differ: notifications are a runtime permission and can be asked
     * for in-app, while "draw over other apps" is a special access setting with
     * no dialog and must be sent to system settings.
     */
    private fun grant(kind: PermissionKind) {
        when (kind) {
            PermissionKind.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pending = kind
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            PermissionKind.OVERLAY -> {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }

            // All-files access is special access too: no dialog, only the
            // system page. Scoped to this package, with the app's details
            // screen as the fallback for ROMs that do not offer the scoped
            // page (several OEM skins do not).
            PermissionKind.SHARED_STORAGE -> {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            }
        }
    }

    private fun divider(): android.view.View = android.view.View(this).apply {
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.eq_divider)
        ).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
            marginEnd = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
        }
        setBackgroundColor(getColor(R.color.eq_border))
    }
}
