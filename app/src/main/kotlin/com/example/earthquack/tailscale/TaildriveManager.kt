package com.example.earthquack.tailscale

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TaildriveManager(
    private val control: TaildriveControl,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    fun remoteShares(): List<TaildriveShare> =
        TaildriveShare.parseList(control.taildriveShares())

    fun shares(): List<TaildriveShare> = remoteShares()

    suspend fun mountLocal(name: String, localPath: String) {
        withContext(io) {
            try {
                control.taildriveMount(name, localPath)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to mount '$name': ${e.message}", e)
            }
        }
    }

    suspend fun unmountLocal(name: String) {
        withContext(io) {
            try {
                control.taildriveUnmount(name)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to unmount '$name': ${e.message}", e)
            }
        }
    }

    suspend fun openRemoteShare(share: TaildriveShare): String {
        // Taildrive is WebDAV over the tailnet; rclone handles the actual
        // browse once a remote is registered for this URL.
        return "http://${share.peer}/${share.name}${share.path}"
    }

    companion object {
        fun defaultMountDir(): File = File(System.getProperty("java.io.tmpdir"), "taildrive")
    }
}

interface TaildriveControl {
    fun taildriveShares(): String
    fun taildriveMount(name: String, localPath: String)
    fun taildriveUnmount(name: String)
}

object NativeTaildriveControl : TaildriveControl {
    override fun taildriveShares(): String =
        com.example.earthquack.tailscale.Tailscale.taildriveShares()

    override fun taildriveMount(name: String, localPath: String) {
        try {
            com.example.earthquack.tailscale.Tailscale.taildriveMount(name, localPath)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "taildriveMount failed", e)
        }
    }

    override fun taildriveUnmount(name: String) {
        try {
            com.example.earthquack.tailscale.Tailscale.taildriveUnmount(name)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "taildriveUnmount failed", e)
        }
    }
}