package com.example.earthquack.tailscale

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import com.example.earthquack.storage.RcloneEngine

/** Alias kept for roadmap naming; [TailscaleLoginState] is the canonical sealed class. */
typealias TailscaleState = TailscaleLoginState

/** A backend that wants to react to the node's network state changes. */
fun interface TailscaleNetworkHook {
    suspend fun onNetworkChanged(running: Boolean, socksPort: Int)
}

/** What sshd dials when the profile's host belongs to this node's tailnet. */
fun interface TailnetDialRouter {
    suspend fun relayFor(host: String, port: Int): Socks5Relay?
}

class TailscaleEngine(
    private val config: TailscaleNodeConfig,
    private val node: TailscaleNode = NativeTailscaleNode,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val listeners: List<TailscaleNetworkHook> = emptyList(),
    private val resolveHost: (String) -> String = { Tailscale.resolve(it) },
) {
    private val _state = MutableStateFlow<TailscaleLoginState>(TailscaleLoginState.Stopped)
    val state: StateFlow<TailscaleLoginState> = _state.asStateFlow()

    private val rcloneBackends = CopyOnWriteArrayList<RcloneEngine>()
    @Volatile private var socksPort: Int = 0
    @Volatile private var startJob: Job? = null
    private var engineRef: AtomicReference<RcloneEngine?> = AtomicReference(null)

    fun start() {
        if (!config.isEnrolled()) {
            Log.i(TAG, "embedded tailscale: not enrolled — nothing to start")
            return
        }
        synchronized(this) {
            if (_state.value is TailscaleLoginState.Starting ||
                _state.value is TailscaleLoginState.Running
            ) return
            _state.value = TailscaleLoginState.Starting
            startJob = scope.launch {
                val result = runCatching {
                    withContext(io) {
                        node.start(config.stateDirPath(), config.advertisedHostname())
                    }
                }
                val parsed = result.fold(
                    onSuccess = { TailscaleAuthManager.parseStartResult(it) },
                    onFailure = { TailscaleLoginState.Error(it.message ?: "tsnet start failed") },
                )
                when (parsed) {
                    is TailscaleLoginState.Running -> {
                        socksPort = parsed.socksPort
                        _state.value = parsed
                        notify(socksPort)
                    }
                    else -> {
                        _state.value = parsed
                        notify(0)
                    }
                }
            }
        }
    }

    suspend fun start(context: Context) {
        start()
    }

    suspend fun stop() {
        val pending = synchronized(this) { startJob }
        pending?.join()
        synchronized(this) {
            startJob = null
            _state.value = TailscaleLoginState.Stopped
            socksPort = 0
        }
        if (state.value !is TailscaleLoginState.Stopped) {
            withContext(io) { runCatching { node.stop() } }
        }
        notify(0)
    }

    suspend fun restart() {
        stop()
        start()
    }

    fun socksProxy(): InetSocketAddress? =
        (state.value as? TailscaleLoginState.Running)?.let { InetSocketAddress("127.0.0.1", socksPort) }

    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val sp = socksProxy()
            return if (sp != null) listOf(Proxy(Proxy.Type.SOCKS, sp)) else listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI, sa: SocketAddress, e: IOException) {}
    }

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (_state.value is TailscaleLoginState.Running) {
                val resolved = runCatching { resolveHost(hostname) }.getOrDefault("")
                if (resolved.isNotEmpty()) return listOf(InetAddress.getByName(resolved))
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    suspend fun relayFor(host: String, port: Int): Socks5Relay? {
        if (state.value !is TailscaleLoginState.Running) return null
        if (!isTailnetHost(host)) return null
        if (socksPort <= 0) return null
        return runCatching { Socks5Relay.start("127.0.0.1", socksPort, host, port) }
            .getOrNull()
    }

    fun looksTailnetHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.endsWith(".ts.net")) return true
        val parts = h.split(".")
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        return nums[0] == 100 && nums[1] in 64..127
    }

    suspend fun isTailnetHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (looksTailnetHost(h)) return true
        if ('.' !in h || h == "localhost") return false
        val resolved = runCatching { withContext(io) { resolveHost(h) } }.getOrDefault("")
        if (resolved.isEmpty()) return false
        return looksTailnetHost(resolved)
    }

    fun nodeIp(): String? = (state.value as? TailscaleLoginState.Running)?.nodeIp

    fun peers(): List<TailnetPeer> = runCatching {
        TailnetPeer.parseList(Tailscale.peers())
    }.getOrDefault(emptyList())

    suspend fun resolve(hostname: String): String? = withContext(io) {
        runCatching { resolveHost(hostname) }.getOrDefault("").ifEmpty { null }
    }

    fun addRclone(backend: RcloneEngine) {
        rcloneBackends += backend
        notify(socksPort)
    }

    fun removeRclone(backend: RcloneEngine) {
        rcloneBackends -= backend
    }

    private fun notify(port: Int) {
        val running = port > 0
        val jobs = rcloneBackends.toMutableList()
        scope.launch {
            jobs.forEach { backend ->
                runCatching {
                    rcloneSocksSync(backend, if (running) port else null)
                }
            }
        }
    }

    private suspend fun rcloneSocksSync(backend: RcloneEngine, socksPort: Int?) {
        try {
            val dump = runCatching { backend.call("config/dump") }.getOrElse {
                Log.w(TAG, "rclone sync: config/dump failed", it)
                return
            }
            val remotes = dump.optJSONObject("remotes")
            if (remotes != null) {
                // rclone's config/dump returns remotes as an object keyed by name.
                val keys = remotes.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val params = remotes.optJSONObject(name) ?: continue
                    val type = params.optString("type", "")
                    val host = params.optString("host", "")
                    if (type == "sftp" && withContext(io) { isTailnetHost(host) }) {
                        val wanted = if (socksPort == null) "" else "socks5://127.0.0.1:$socksPort"
                        val current = params.optString("socks_proxy", "")
                        if (current != wanted) {
                            backend.call(
                                "config/update",
                                org.json.JSONObject().put("name", name).put(
                                    "parameters", org.json.JSONObject().put("socks_proxy", wanted)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "rclone sync failed", e)
        }
    }

    companion object {
        private const val TAG = "TailscaleEngine"
        @Volatile
        private var sharedInstance: TailscaleEngine? = null

        fun shared(context: Context): TailscaleEngine {
            val existing = sharedInstance
            if (existing != null) return existing
            synchronized(this) {
                val existing2 = sharedInstance
                if (existing2 != null) return existing2
                val store = TailscaleStateStore(context.applicationContext)
                val engine = TailscaleEngine(
                    config = store,
                    listeners = emptyList(),
                    resolveHost = { Tailscale.resolve(it) },
                )
                sharedInstance = engine
                engine.rcloneBackends.forEach { engine.addRclone(it) }
                return engine
            }
        }

        fun registerRclone(backend: RcloneEngine) {
            sharedInstance?.addRclone(backend)
        }

        fun unregisterRclone(backend: RcloneEngine) {
            sharedInstance?.removeRclone(backend)
        }

        // Roadmap-style (object) entry points, delegating to the shared instance.
        suspend fun start(context: Context) {
            shared(context).start()
        }

        suspend fun stop() {
            sharedInstance?.stop()
        }

        fun socksProxy(): InetSocketAddress? = sharedInstance?.socksProxy()

        fun nodeIp(): String? = sharedInstance?.nodeIp()

        fun peers(): List<TailnetPeer> = sharedInstance?.peers() ?: emptyList()

        suspend fun resolve(hostname: String): String? = sharedInstance?.resolve(hostname)
    }
}

/** Wire SOCKS5 into OkHttp. */
fun buildHttpClient(proxy: java.net.InetSocketAddress?): OkHttpClient =
    OkHttpClient.Builder()
        .apply { if (proxy != null) proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, proxy)) }
        .build()

// ─── consumers (defined here so the tailscale package can expose its API) ───

/** The OGH/SOCKS5 proxy router that SSH connections use. */
fun interface TailscaleSocksRouter {
    suspend fun relayFor(host: String, port: Int): Socks5Relay?
}