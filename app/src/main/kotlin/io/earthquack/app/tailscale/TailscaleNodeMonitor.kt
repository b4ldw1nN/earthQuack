package io.earthquack.app.tailscale

import android.content.Context
import android.util.Log
import io.earthquack.app.AppStatus
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
            pendingContext = context.applicationContext
            val authManager = TailscaleAuthManager(TailscaleStateStore(context.applicationContext))
            manager = authManager
            scope.launch {
                authManager.state.collect { state ->
                    val wasRunning = _state.value is TailscaleLoginState.Running
                    _state.value = state
                    publishNodeStatus(state)
                    if (state is TailscaleLoginState.Running && !wasRunning) {
                        // The node restarted: re-publish the ports the user
                        // asked for, which died with the old node.
                        runCatching {
                            ServeManager(NativeServeControl, store = ServeStore(context.applicationContext)).restore()
                        }
                        // Start SFTP server if user configured it to auto-start with Tailscale
                        runCatching {
                            val sftpStore = io.earthquack.app.sftp.SftpSettingsStore(context.applicationContext)
                            val sftpSettings = sftpStore.load()
                            if (sftpSettings.autoStartWithTailscale && (sftpSettings.passwordAuth || sftpSettings.publicKeyAuth)) {
                                val sftpController = io.earthquack.app.sftp.SftpServerControllerImpl(context.applicationContext)
                                if (sftpController.status() !is io.earthquack.app.sftp.SftpServerStatus.Running) {
                                    sftpController.start(sftpSettings)
                                }
                            }
                        }
                    }
                }
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

    /**
     * Turns the node's state into a message the user can act on.
     *
     * The node failing is invisible otherwise: the app keeps running, the
     * screens keep showing their normal content, and every tailnet feature
     * quietly stops working. Publishing the state here is what makes that
     * visible without hunting for it.
     */
    private fun publishNodeStatus(state: TailscaleLoginState) {
        val message = when (state) {
            is TailscaleLoginState.Running -> null // healthy: nothing to say
            is TailscaleLoginState.NeedsLogin -> AppStatus.Message(
                text = "Tailscale needs you to sign in again. Until then, tailnet " +
                    "features (SFTP, tailnet remotes, the desktop link) cannot work.",
                severity = AppStatus.Severity.Error,
                actionLabel = "Sign in",
                action = { pendingContext?.let { openLoginTab(it) } },
            )
            is TailscaleLoginState.Error -> AppStatus.Message(
                text = "Tailscale stopped working: ${state.message}",
                severity = AppStatus.Severity.Error,
                actionLabel = "Retry",
                action = { pendingContext?.let { login(it) } },
            )
            is TailscaleLoginState.Stopped -> AppStatus.Message(
                text = "Tailscale is stopped. Tailnet features are unavailable " +
                    "until it is running again.",
                severity = AppStatus.Severity.Warning,
                actionLabel = "Start",
                action = { pendingContext?.let { start(it) } },
            )
            else -> null // Idle / Starting: transient, nothing worth a banner
        }
        AppStatus.publish(AppStatus.SOURCE_NODE, message)
    }

    /** Context kept so the banner's actions can act without one being passed in. */
    private var pendingContext: Context? = null
}
