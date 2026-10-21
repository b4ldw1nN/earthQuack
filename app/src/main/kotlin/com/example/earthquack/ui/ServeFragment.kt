package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.tailscale.NativeServeControl
import com.example.earthquack.tailscale.ServeManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ServeFragment : Fragment() {
    private val manager = ServeManager(NativeServeControl)
    private lateinit var status: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun button(label: String, action: suspend () -> Unit): MaterialButton =
            MaterialButton(ctx).apply {
                text = label
                setOnClickListener { lifecycleScope.launch { runCatching { action() }.onFailure { status.text = it.message }; refresh() } }
            }

        root.addView(button("Expose Clipboard API (:8875 -> :80)") { manager.exposeClipboardApi() })
        root.addView(button("Expose SFTP (:2222 -> :22)") { manager.exposeSftp() })
        root.addView(button("Stop All") { manager.stopAll() })
        root.addView(button("Funnel Enable :80") { manager.funnelEnable(80) })
        root.addView(button("Funnel Disable :80") { manager.funnelDisable(80) })

        status = TextView(ctx).apply { setPadding(0, 24, 0, 0) }
        root.addView(status)
        refresh()
        scroll.addView(root)
        return scroll
    }

    private fun refresh() {
        status.text = "Active serves:\n" + manager.activeServes().joinToString("\n") {
            "${it.localPort} -> ${it.tailnetPort}${if (it.tls) " (TLS)" else ""}${if (it.funnel) " (funnel)" else ""}"
        }.ifBlank { "None" }
    }
}