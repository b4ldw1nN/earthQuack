package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentAdminBinding
import com.example.earthquack.databinding.ItemAdminDeviceBinding
import com.example.earthquack.tailscale.AdminConsoleClient
import com.example.earthquack.tailscale.AdminDevices
import com.example.earthquack.tailscale.AdminKeyStore
import com.example.earthquack.tailscale.NativeAdminConsoleControl
import com.example.earthquack.tailscale.TailscaleLoginState
import com.example.earthquack.tailscale.TailscaleNodeMonitor
import kotlinx.coroutines.launch

/**
 * Admin console — a thin client over the Tailscale admin API.
 *
 * The API key is the only secret here; it is kept in the keystore-backed
 * secret store, not in plain preferences.
 *
 * The device ID is not asked for either: the admin API lists every device in
 * the tailnet with its ID, and the phone recognises its own entry by the
 * tailnet address the node reports. Tapping any device in the list puts its
 * ID in the field, which is what the expire-key and delete calls need.
 */
class AdminFragment : Fragment() {

    private var _binding: FragmentAdminBinding? = null
    private val binding get() = _binding!!

    private val client = AdminConsoleClient(NativeAdminConsoleControl)
    private lateinit var keyStore: AdminKeyStore

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
        keyStore = AdminKeyStore(requireContext())
        binding.fieldApiKey.setText(keyStore.get())

        binding.btnDevices.setOnClickListener { loadDevices() }
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
        binding.btnSaveKey.setOnClickListener {
            keyStore.set(apiKey())
            Toast.makeText(requireContext(), R.string.admin_key_saved, Toast.LENGTH_SHORT).show()
        }

        // The device list needs a key; with one saved, fill in this phone's
        // own device ID straight away.
        if (keyStore.get().isNotBlank()) loadDevices()
    }

    /**
     * Fetches the device list, renders it, and fills in this phone's ID.
     */
    private fun loadDevices() {
        val key = apiKey()
        if (key.isBlank()) {
            binding.textOutput.text = getString(R.string.admin_key_needed)
            return
        }
        keyStore.set(key)
        lifecycleScope.launch {
            val result = runCatching { client.devices(key) }
            val json = result.getOrElse {
                binding.textOutput.text = it.message ?: getString(R.string.state_error)
                return@launch
            }
            val devices = AdminDevices.parse(json)
            binding.textOutput.text = getString(R.string.admin_devices_count, devices.size)
            renderDevices(devices)
        }
    }

    private fun renderDevices(devices: List<AdminDevices.Device>) {
        val container = binding.listDevices
        container.removeAllViews()
        val ownAddress = (TailscaleNodeMonitor.state.value as? TailscaleLoginState.Running)?.nodeIp
        val own = AdminDevices.thisDevice(devices, ownAddress)
        if (own != null) {
            // This phone's own entry: fill it in, so the field is never a
            // puzzle for the user.
            binding.fieldDeviceId.setText(own.id)
            binding.textOutput.text = getString(R.string.admin_this_device, own.name.ifBlank { own.id }, own.id)
        }
        devices.forEach { device ->
            val row = ItemAdminDeviceBinding.inflate(layoutInflater, container, false)
            row.deviceName.text = device.name.ifBlank { device.id }
            row.deviceDetail.text = listOfNotNull(
                device.os.takeIf { it.isNotBlank() },
                device.primaryAddress,
                device.id.takeIf { device.id != device.name },
            ).joinToString(" · ")
            if (device.id == own?.id) {
                row.deviceDetail.text = getString(R.string.admin_device_is_this_phone) + " · " + row.deviceDetail.text
            }
            row.root.setOnClickListener {
                binding.fieldDeviceId.setText(device.id)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.admin_device_selected, device.name.ifBlank { device.id }),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            container.addView(row.root)
        }
        if (devices.isEmpty()) {
            binding.textOutput.text = getString(R.string.admin_no_devices)
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
