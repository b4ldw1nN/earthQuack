package com.example.earthquack.tailscale

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServeManager(
    private val control: ServeControl,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val active = mutableListOf<ServeEntry>()

    suspend fun serveHttp(localPort: Int, tailnetPort: Int) {
        withContext(io) {
            try {
                control.serveHTTP(localPort, tailnetPort)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to serve :$localPort on :$tailnetPort: ${e.message}", e)
            }
        }
        active += ServeEntry(localPort, tailnetPort, tls = false)
    }

    suspend fun serveTls(localPort: Int, tailnetPort: Int) {
        withContext(io) {
            try {
                control.serveTLS(localPort, tailnetPort)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to serve TLS :$localPort on :$tailnetPort: ${e.message}", e)
            }
        }
        active += ServeEntry(localPort, tailnetPort, tls = true)
    }

    suspend fun exposeClipboardApi() = serveHttp(8875, 80)

    suspend fun exposeSftp() = serveHttp(2222, 22)

    suspend fun stop(tailnetPort: Int) {
        withContext(io) {
            try {
                control.serveStop(tailnetPort)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to stop serve on :$tailnetPort: ${e.message}", e)
            }
        }
        active.removeAll { it.tailnetPort == tailnetPort }
    }

    suspend fun stopAll() {
        active.toList().forEach { runCatching { stop(it.tailnetPort) } }
        active.clear()
    }

    fun activeServes(): List<ServeEntry> = active.toList()

    suspend fun funnelEnable(tailnetPort: Int) {
        withContext(io) {
            try {
                control.funnelEnable(tailnetPort)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to enable funnel on :$tailnetPort: ${e.message}", e)
            }
        }
        val idx = active.indexOfFirst { it.tailnetPort == tailnetPort }
        if (idx >= 0) active[idx] = active[idx].copy(funnel = true)
    }

    suspend fun funnelDisable(tailnetPort: Int) {
        withContext(io) {
            try {
                control.funnelDisable(tailnetPort)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to disable funnel on :$tailnetPort: ${e.message}", e)
            }
        }
        val idx = active.indexOfFirst { it.tailnetPort == tailnetPort }
        if (idx >= 0) active[idx] = active[idx].copy(funnel = false)
    }
}

interface ServeControl {
    fun serveHTTP(localPort: Int, tailnetPort: Int)
    fun serveTLS(localPort: Int, tailnetPort: Int)
    fun serveStop(tailnetPort: Int)
    fun funnelEnable(tailnetPort: Int)
    fun funnelDisable(tailnetPort: Int)
}

object NativeServeControl : ServeControl {
    override fun serveHTTP(localPort: Int, tailnetPort: Int) {
        try {
            com.example.earthquack.tailscale.Tailscale.serveHTTP(localPort.toLong(), tailnetPort.toLong())
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "serveHTTP failed", e)
        }
    }

    override fun serveTLS(localPort: Int, tailnetPort: Int) {
        try {
            com.example.earthquack.tailscale.Tailscale.serveTLS(localPort.toLong(), tailnetPort.toLong())
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "serveTLS failed", e)
        }
    }

    override fun serveStop(tailnetPort: Int) {
        try {
            com.example.earthquack.tailscale.Tailscale.serveStop(tailnetPort.toLong())
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "serveStop failed", e)
        }
    }

    override fun funnelEnable(tailnetPort: Int) {
        try {
            com.example.earthquack.tailscale.Tailscale.funnelEnable(tailnetPort.toLong())
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "funnelEnable failed", e)
        }
    }

    override fun funnelDisable(tailnetPort: Int) {
        try {
            com.example.earthquack.tailscale.Tailscale.funnelDisable(tailnetPort.toLong())
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "funnelDisable failed", e)
        }
    }
}