package io.earthquack.app.tailscale

import android.content.Context
import io.earthquack.app.ssh.KeystoreSecretStore
import io.earthquack.app.ssh.SecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * AdminDevices — the tailnet's device list, as the admin API reports it.
 *
 * The admin API knows every device by its Tailscale device ID ("nXXXX…"),
 * which is what the expire/delete calls need. The phone does not know its
 * own ID (the node does not report it), but the device list does, and the
 * phone's tailnet address identifies its own entry — so the ID can be found
 * instead of asked for.
 */
object AdminDevices {

    /** A device as the admin API describes it. */
    data class Device(
        val id: String,
        val name: String,
        val os: String,
        val addresses: List<String>,
        val hostname: String?,
        val userId: String?,
        val keyExpiryDisabled: Boolean,
        val blocksIncomingConnections: Boolean,
    ) {
        /** The address a peer would use, preferring IPv4. */
        val primaryAddress: String?
            get() = addresses.firstOrNull { it.contains('.') && !it.contains(':') }
                ?: addresses.firstOrNull()

        fun matchesAddress(address: String): Boolean =
            addresses.any { it == address } || primaryAddress == address
    }

    /** Parses the admin API's `{"devices":[…]}` reply. */
    fun parse(json: String): List<Device> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("devices") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Device(
                id = id,
                name = o.optString("name"),
                os = o.optString("os"),
                addresses = o.optJSONArray("addresses").toStringList(),
                hostname = o.optString("hostname").takeIf { it.isNotBlank() },
                userId = o.optString("userId").takeIf { it.isNotBlank() },
                keyExpiryDisabled = o.optBoolean("keyExpiryDisabled"),
                blocksIncomingConnections = o.optBoolean("blocksIncomingConnections"),
            )
        }
    }

    /**
     * The device that is this phone.
     *
     * Matched on the tailnet address the node reports, so the answer is
     * "this phone's ID" rather than a list the user has to search.
     */
    fun thisDevice(devices: List<Device>, tailnetAddress: String?): Device? {
        if (tailnetAddress.isNullOrBlank()) return null
        return devices.firstOrNull { it.matchesAddress(tailnetAddress) }
            ?: devices.firstOrNull { it.name.equals(tailnetAddress, ignoreCase = true) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

/**
 * AdminKeyStore — the Tailscale admin API key, kept in the keystore.
 *
 * An API key can list every device in the tailnet, so it is a secret worth
 * keeping out of plain preferences. The keystore-backed store already exists
 * for the SSH keys.
 */
class AdminKeyStore(context: Context) {

    private val store: SecretStore = KeystoreSecretStore(context.applicationContext.filesDir)

    fun get(): String =
        runCatching { store.get(ALIAS)?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()

    fun set(key: String) {
        runCatching { store.put(ALIAS, key.toByteArray(Charsets.UTF_8)) }
    }

    fun clear() {
        runCatching { store.delete(ALIAS) }
    }

    private companion object {
        const val ALIAS = "tailscale_admin_api_key"
    }
}
