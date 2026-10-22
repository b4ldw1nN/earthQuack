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

    fun looksTailnetHost(host: String): Boolean = TailnetRoute.looksTailnetHost(host)

    suspend fun isTailnetHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (looksTailnetHost(h)) return true
        if (h == "localhost" || h.isEmpty()) return false
        // Bare MagicDNS names ("archii") and our own .ts.net domains resolve
        // inside the tailnet; anything with a public-looking domain does not.
        if ('.' in h && !h.endsWith(".ts.net")) return false
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
        notify(effectiveSocksPort())
    }

    fun removeRclone(backend: RcloneEngine) {
        rcloneBackends -= backend
    }

    /**
     * SOCKS port of the running node.
     *
     * Usually the lifecycle state machine knows it first, but the node may
     * also be started through [TailscaleAuthManager] (login flow), which does
     * not update this engine — the shim itself always knows, so fall back to
     * it. 0 means no node is running and tailnet dials must not be proxied.
     */
    fun effectiveSocksPort(): Int {
        if (socksPort > 0) return socksPort
        return runCatching { Tailscale.socksPort().toInt() }.getOrDefault(0)
    }

    private fun notify(port: Int = effectiveSocksPort()) {
        val jobs = rcloneBackends.toMutableList()
        scope.launch {
            jobs.forEach { backend ->
                runCatching {
                    rcloneSocksSync(backend, if (port > 0) port else null)
                }
            }
        }
    }

    private suspend fun rcloneSocksSync(backend: RcloneEngine, socksPort: Int?) =
        RcloneTailnetSync.apply(backend, socksPort)

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
                // Adopt backends that registered before this engine existed.
                pendingRclone.forEach { engine.rcloneBackends += it }
                pendingRclone.clear()
                if (engine.effectiveSocksPort() > 0) engine.notify()
                return engine
            }
        }

        private val pendingRclone = java.util.concurrent.CopyOnWriteArrayList<RcloneEngine>()

        fun registerRclone(backend: RcloneEngine) {
            val engine = sharedInstance
            if (engine != null) engine.addRclone(backend)
            else pendingRclone += backend
        }

        fun unregisterRclone(backend: RcloneEngine) {
            val engine = sharedInstance
            if (engine != null) engine.removeRclone(backend)
            else pendingRclone -= backend
        }

        /**
         * Node lifecycle hook for flows that do not own the engine's state
         * machine ([TailscaleAuthManager] starts the node during login), so
         * dependent integrations still re-sync once the node can route.
         */
        fun onNodeRunning() {
            val port = runCatching { Tailscale.socksPort().toInt() }.getOrDefault(0)
            val engine = sharedInstance
            Log.i(TAG, "onNodeRunning: port=$port sharedEngine=${engine != null} pending=${pendingRclone.size}")
            if (engine != null) {
                if (pendingRclone.isNotEmpty()) {
                    pendingRclone.forEach { engine.rcloneBackends += it }
                    pendingRclone.clear()
                }
                engine.notify()
            } else if (port > 0 && pendingRclone.isNotEmpty()) {
                // No engine instance exists yet (the login flow never creates
                // one); sync the queued backends standalone instead.
                val backends = pendingRclone.toMutableList()
                pendingRclone.clear()
                CoroutineScope(Dispatchers.Default).launch {
                    backends.forEach { runCatching { RcloneTailnetSync.apply(it, port) } }
                }
            }
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