package com.example.earthquack.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivitySubScreenBinding

/**
 * Shared behaviour for the Settings sub-screens (Security, Battery,
 * Permissions).
 *
 * ## Why a base class
 *
 * All three have the same shape: a compact top bar with a back arrow, a
 * scrollable body, and no bottom navigation. Implementing that three times meant
 * three chances to get the back-arrow wiring subtly different -- and, in the
 * previous design, the back arrow genuinely was missing from some screens.
 *
 * Subclasses supply their own body layout and set the title/subtitle. Everything
 * else, including making Back and the toolbar arrow behave identically, lives
 * here.
 */
abstract class SubScreenActivity : AppCompatActivity() {

    protected lateinit var shell: ActivitySubScreenBinding

    /**
     * Inflate the body into the shell.
     *
     * Called from [setContentView] in the subclass, after the shell exists.
     */
    abstract fun inflateBody(inflater: LayoutInflater, container: ViewGroup?)

    /** Screen title. */
    abstract val titleRes: Int

    /** Screen subtitle; 0 for none. */
    open val subtitleRes: Int = 0

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        shell = ActivitySubScreenBinding.inflate(layoutInflater)
        setContentView(shell.root)

        inflateBody(layoutInflater, shell.body)

        shell.appbar.title.setText(titleRes)
        if (subtitleRes != 0) {
            shell.appbar.subtitle.setText(subtitleRes)
            shell.appbar.subtitle.visibility = android.view.View.VISIBLE
        } else {
            shell.appbar.subtitle.visibility = android.view.View.GONE
        }
        // Sub-screens are pushed, so there is always somewhere to go back to.
        shell.appbar.btnBack.visibility = android.view.View.VISIBLE
        shell.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
