package com.example.earthquack.ui

import android.view.View
import com.example.earthquack.AppStatus
import com.example.earthquack.R

/**
 * Renders the app-wide status banner.
 *
 * The banner is the same widget everywhere it appears, so how a message looks
 * and how its action behaves is decided in one place rather than per screen.
 */
object StatusBanner {

    /**
     * Shows [message] in the banner, or hides the banner when it is null.
     *
     * @param banner the banner root (the included layout's root view)
     * @param text the message text view
     * @param action the optional action button
     */
    fun render(
        banner: View,
        text: android.widget.TextView,
        action: android.widget.Button,
        message: AppStatus.Message?,
    ) {
        if (message == null) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        text.text = message.text

        val tint = when (message.severity) {
            AppStatus.Severity.Info -> R.color.eq_primary
            AppStatus.Severity.Warning -> R.color.eq_warning
            AppStatus.Severity.Error -> R.color.eq_error
        }
        text.setTextColor(androidx.core.content.ContextCompat.getColor(banner.context, tint))
        banner.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(
                banner.context,
                when (message.severity) {
                    AppStatus.Severity.Info -> R.color.eq_primary_dim
                    AppStatus.Severity.Warning -> R.color.eq_warning_dim
                    AppStatus.Severity.Error -> R.color.eq_error_dim
                },
            )
        )

        val label = message.actionLabel
        val actionRunnable = message.action
        if (label != null && actionRunnable != null) {
            action.visibility = View.VISIBLE
            action.text = label
            action.setOnClickListener { actionRunnable() }
        } else {
            action.visibility = View.GONE
            action.setOnClickListener(null)
        }
    }
}
