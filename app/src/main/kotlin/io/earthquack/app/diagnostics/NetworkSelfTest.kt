package io.earthquack.app.diagnostics

import android.content.Context
import io.earthquack.app.sftp.SftpServerControllerImpl
import io.earthquack.app.sftp.SftpServerStatus
import io.earthquack.app.tailscale.Tailscale
import io.earthquack.app.tailscale.TailscaleLoginState
import io.earthquack.app.tailscale.TailscaleNodeMonitor
import io.earthquack.app.tailscale.TailnetPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object NetworkSelfTest {

    data class Result(
        val name: String,
        val passed: Boolean,
        val detail: String,
    )

    suspend fun runAll(context: Context): List<Result> = withContext(Dispatchers.IO) {
        listOf(
            testSocksProxy(),
            testTailscaleNode(),
            testSftpServer(context)
        )
    }

    private fun testSocksProxy(): Result {
        val port = 1055
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
            }
            Result("SOCKS5 Proxy", true, "127.0.0.1:$port listening and reachable")
        } catch (e: Exception) {
            Result("SOCKS5 Proxy", false, "127.0.0.1:$port offline (${e.message ?: "refused"})")
        }
    }

    private fun testTailscaleNode(): Result {
        val state = TailscaleNodeMonitor.state.value
        if (state !is TailscaleLoginState.Running) {
            return Result("Tailscale Node", false, "Node state: ${state::class.simpleName ?: state.toString()}")
        }
        val peersJson = runCatching { Tailscale.peers() }.getOrDefault("[]")
        val peers = TailnetPeer.parseList(peersJson)
        val onlinePeers = peers.count { it.online }
        return Result("Tailscale Node", true, "Online (${state.nodeIp}) · $onlinePeers/${peers.size} peers reachable")
    }

    private fun testSftpServer(context: Context): Result {
        val controller = SftpServerControllerImpl(context)
        val status = controller.status()
        return when (status) {
            is SftpServerStatus.Running -> {
                val reachable = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", status.port), 1000) }
                    true
                }.getOrDefault(false)
                if (reachable) {
                    Result("SFTP Server", true, "Listening on port ${status.port} (${status.connectedClients} clients)")
                } else {
                    Result("SFTP Server", false, "Engine reports port ${status.port} but socket connect failed")
                }
            }
            is SftpServerStatus.Error -> Result("SFTP Server", false, "Error: ${status.message}")
            SftpServerStatus.Stopped -> Result("SFTP Server", true, "Stopped (inactive)")
            SftpServerStatus.NotImplemented -> Result("SFTP Server", false, "Not implemented")
        }
    }
}
