package io.earthquack.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.earthquack.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Creates a standard horizontal divider view with margins matching the card padding.
 */
fun Fragment.createDivider(): View = requireContext().createDivider()

fun Context.createDivider(): View = View(this).apply {
    layoutParams = ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        resources.getDimensionPixelSize(R.dimen.eq_divider)
    ).apply {
        marginStart = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
        marginEnd = resources.getDimensionPixelSize(R.dimen.eq_card_pad)
    }
    setBackgroundColor(resources.getColor(R.color.eq_border, theme))
}

/**
 * Shows a simple dialog with a dismiss button.
 */
fun Context.showSimpleDialog(message: String) {
    MaterialAlertDialogBuilder(this)
        .setMessage(message)
        .setPositiveButton(R.string.action_dismiss, null)
        .show()
}
