package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.tailscale.AdminConsoleClient
import com.example.earthquack.tailscale.NativeAdminConsoleControl
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AdminFragment : Fragment() {
    private val client = AdminConsoleClient(NativeAdminConsoleControl)
    private lateinit var apiKeyInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var output: TextView

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

        apiKeyInput = EditText(ctx).apply { hint = "Admin API key" }
        deviceIdInput = EditText(ctx).apply { hint = "Device ID (for actions)" }
        root.addView(apiKeyInput)
        root.addView(deviceIdInput)

        fun button(label: String, action: suspend () -> String?): MaterialButton =
            MaterialButton(ctx).apply {
                text = label
                setOnClickListener {
                    lifecycleScope.launch {
                        val key = apiKeyInput.text.toString()
                        val result = runCatching { action() }.getOrElse { it.message ?: "error" }
                        output.text = result ?: ""
                    }
                }
            }

        root.addView(button("List Devices") { client.devices(apiKeyInput.text.toString()) })
        root.addView(button("DNS Config") { client.dns(apiKeyInput.text.toString()) })
        root.addView(button("ACL Policy") { client.acl(apiKeyInput.text.toString()) })
        root.addView(MaterialButton(ctx).apply {
            text = "Expire Key"
            setOnClickListener {
                lifecycleScope.launch {
                    runCatching { client.expireKey(apiKeyInput.text.toString(), deviceIdInput.text.toString()) }
                        .onSuccess { output.text = "Key expired" }
                        .onFailure { output.text = it.message }
                }
            }
        })
        root.addView(MaterialButton(ctx).apply {
            text = "Delete Device"
            setOnClickListener {
                lifecycleScope.launch {
                    val ok = client.deleteDevice(apiKeyInput.text.toString(), deviceIdInput.text.toString())
                    output.text = if (ok) "Device deleted" else "Delete failed"
                }
            }
        })

        output = TextView(ctx).apply { setPadding(0, 24, 0, 0) }
        root.addView(output)
        scroll.addView(root)
        return scroll
    }
}