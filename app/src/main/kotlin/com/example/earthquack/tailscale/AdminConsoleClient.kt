package com.example.earthquack.tailscale

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class AdminConsoleClient(
    private val control: AdminConsoleControl = NativeAdminConsoleControl,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun devices(apiKey: String): String = withContext(io) {
        control.adminDevices(apiKey)
    }

    suspend fun expireKey(apiKey: String, deviceId: String) {
        withContext(io) {
            control.adminExpireKey(apiKey, deviceId)
        }
    }

    suspend fun dns(apiKey: String): String = withContext(io) {
        control.adminDns(apiKey)
    }

    suspend fun acl(apiKey: String): String = withContext(io) {
        control.adminAcl(apiKey)
    }

    suspend fun deleteDevice(apiKey: String, deviceId: String): Boolean = withContext(io) {
        runCatching {
            val conn = URL("https://api.tailscale.com/api/v2/device/$deviceId").openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString(":$apiKey".toByteArray(), android.util.Base64.NO_WRAP))
            conn.responseCode in 200..299
        }.getOrDefault(false)
    }
}

interface AdminConsoleControl {
    fun adminDevices(apiKey: String): String
    fun adminExpireKey(apiKey: String, deviceId: String)
    fun adminDns(apiKey: String): String
    fun adminAcl(apiKey: String): String
}

object NativeAdminConsoleControl : AdminConsoleControl {
    override fun adminDevices(apiKey: String): String =
        com.example.earthquack.tailscale.Tailscale.adminDevices(apiKey)

    override fun adminExpireKey(apiKey: String, deviceId: String) {
        try {
            com.example.earthquack.tailscale.Tailscale.adminExpireKey(apiKey, deviceId)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "adminExpireKey failed", e)
        }
    }

    override fun adminDns(apiKey: String): String =
        com.example.earthquack.tailscale.Tailscale.adminDNS(apiKey)

    override fun adminAcl(apiKey: String): String =
        com.example.earthquack.tailscale.Tailscale.adminACL(apiKey)
}