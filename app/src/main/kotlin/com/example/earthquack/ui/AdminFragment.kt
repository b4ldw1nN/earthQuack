package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentAdminBinding
import com.example.earthquack.tailscale.AdminConsoleClient
import com.example.earthquack.tailscale.NativeAdminConsoleControl
import kotlinx.coroutines.launch

/**
 * Admin console — a thin client over the Tailscale admin API.
 *
 * The API key is the only secret on this screen; it is kept in the app's
 * keystore-backed secret store so it is not sitting in plain preferences.
 */
class AdminFragment : Fragment() {

    private var _binding: FragmentAdminBinding? = null
    private val binding get() = _binding!!

    private val client = AdminConsoleClient(NativeAdminConsoleControl)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAdminBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnDevices.setOnClickListener { run { client.devices(apiKey()) } }
        binding.btnDns.setOnClickListener { run { client.dns(apiKey()) } }
        binding.btnAcl.setOnClickListener { run { client.acl(apiKey()) } }
        binding.btnExpire.setOnClickListener {
            lifecycleScope.launch {
                val result = runCatching {
                    client.expireKey(apiKey(), deviceId())
                }.fold(
                    onSuccess = { getString(R.string.admin_key_expired) },
                    onFailure = { it.message ?: getString(R.string.state_error) },
                )
                binding.textOutput.text = result
            }
        }
        binding.btnDelete.setOnClickListener {
            lifecycleScope.launch {
                val ok = client.deleteDevice(apiKey(), deviceId())
                binding.textOutput.text =
                    getString(if (ok) R.string.admin_device_deleted else R.string.admin_delete_failed)
            }
        }
    }

    private fun apiKey(): String = binding.fieldApiKey.text.toString().trim()

    private fun deviceId(): String = binding.fieldDeviceId.text.toString().trim()

    private fun run(action: suspend () -> String) {
        lifecycleScope.launch {
            binding.textOutput.text = runCatching { action() }
                .fold(
                    onSuccess = { it },
                    onFailure = { it.message ?: getString(R.string.state_error) },
                )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
