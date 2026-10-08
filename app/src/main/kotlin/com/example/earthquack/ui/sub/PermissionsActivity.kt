package com.example.earthquack.ui.sub

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityPermissionsBinding
import com.example.earthquack.databinding.ItemPermissionRowBinding
import com.example.earthquack.state.PermissionKind
import com.example.earthquack.state.SystemStatusProvider
import com.example.earthquack.ui.SubScreenActivity

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

        val kinds = mutableListOf<PermissionKind>()
        if (!statusProvider.hasNotificationPermission()) kinds += PermissionKind.NOTIFICATIONS
        if (!statusProvider.canDrawOverlays()) kinds += PermissionKind.OVERLAY

        // When nothing is missing the card would be an empty bordered box, which
        // reads as a rendering failure rather than as "you are all set".
        val allGranted = kinds.isEmpty()
        body.cardPermissions.visibility =
            if (allGranted) android.view.View.GONE else android.view.View.VISIBLE
        body.textAllGranted.visibility =
            if (allGranted) android.view.View.VISIBLE else android.view.View.GONE

        kinds.forEachIndexed { index, kind ->
            val row = ItemPermissionRowBinding.inflate(layoutInflater, container, false)
            row.permName.text = kind.label
            row.permWhy.text = kind.why
            row.permState.text = getString(R.string.permissions_not_granted)
            row.permState.setTextColor(getColor(R.color.eq_warning))
            row.permIcon.setImageResource(
                if (kind == PermissionKind.OVERLAY) R.drawable.ic_eq_open_new
                else R.drawable.ic_eq_info
            )
            row.btnGrant.setOnClickListener { grant(kind) }
            container.addView(row.root)

            if (index < kinds.lastIndex) container.addView(divider())
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
