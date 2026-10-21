package com.example.earthquack.tailscale

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TailscaleNodeMonitor — one embedded node, one state, observed app-wide.
 *
 * The node belongs to this app: it has its own tailnet IP and its own SOCKS
 * proxy, and it is up whenever the app is up. Whether the *desktop* binary
 * happens to be running is a different question — the tailnet is connected
 * either way, so the UI must not report "disconnected" just because the
 * laptop service is down.
 *
 * Previously every entry point (activity, service, fragment) built its own
 * [TailscaleAuthManager] and started the node again, so the state was
 * scattered and duplicated. This monitor owns the single manager and
 * republishes its state for every screen to observe.
 */
object TailscaleNodeMonitor {

    private const val TAG = "TailscaleNodeMonitor"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<TailscaleLoginState>(TailscaleLoginState.Idle)
    val state: StateFlow<TailscaleLoginState> = _state.asStateFlow()

    @Volatile
    private var manager: TailscaleAuthManager? = null

    /** True when the node is up and can route tailnet traffic. */
    val isRunning: Boolean get() = _state.value is TailscaleLoginState.Running

    /** The node's tailnet IP while running, else null. */
    val nodeIp: String? get() = (_state.value as? TailscaleLoginState.Running)?.nodeIp

    /**
     * Starts the embedded node, or joins the one already running.
     *
     * Safe to call from any entry point and any number of times: only the
     * first call does the work, later calls just observe the same node.
     */
    fun start(context: Context) {
        if (manager != null) return
        synchronized(this) {
            if (manager != null) return
            val authManager = TailscaleAuthManager(TailscaleStateStore(context.applicationContext))
            manager = authManager
            scope.launch {
                authManager.state.collect { _state.value = it }
            }
            scope.launch {
                runCatching { authManager.startLogin() }
                    .onFailure { Log.w(TAG, "node start failed", it) }
            }
        }
    }

    /**
     * Re-runs the login flow (used by the "Log in" button). The node is
     * already up in most cases, so this mostly refreshes the state.
     */
    fun login(context: Context) {
        val authManager = manager ?: run {
            start(context)
            return
        }
        scope.launch {
            runCatching { authManager.startLogin() }
                .onFailure { Log.w(TAG, "login failed", it) }
        }
    }

    /** Opens the tsnet login page in a browser tab, when one is pending. */
    fun openLoginTab(context: Context) {
        val url = (_state.value as? TailscaleLoginState.NeedsLogin)?.loginUrl ?: return
        manager?.openLoginTab(context, url)
    }
}
