package com.example.earthquack.tailscale

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.example.earthquack.tailscale.TailscaleEngine

/**
 * Browser-login flow for the embedded Tailscale node.
 *
 * No auth keys, no admin console, no copy-paste: the user taps "Log in",
 * a Chrome Custom Tab opens the URL tsnet produced, and the node joins the
 * tailnet on its own once the browser side is done. Subsequent runs find a
 * persisted node key in the state directory and never show any UI.
 *
 * ```
 * startLogin()
 *   → Tailscale.start(stateDir, hostname)   (Go shim, sync JNI)
 *   → {"state":"NeedsLogin","login_url":"https://login.tailscale.com/..."}
 *   → openLoginTab(url)   Custom Tab; caller dismisses it when state hits Running
 *   → poll Status() every 2s until BackendState == Running
 * ```
 *
 * The manager owns no Android UI beyond launching the Custom Tab; it exposes
 * a [StateFlow] of [TailscaleLoginState] for whatever screen hosts the login
 * button to collect, and reports when the tab can be dismissed via
 * [TailscaleLoginState.Running].
 *
 * ## Testability
 *
 * All gomobile calls go through [TailscaleNode] and all JSON parsing through
 * the pure functions [parseStartResult]/[parseStatusResult], so the whole
 * state machine runs on the JVM under test with a fake node.
 */
class TailscaleAuthManager(
    private val store: TailscaleNodeConfig,
    private val node: TailscaleNode = NativeTailscaleNode,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Poll interval while waiting for the control plane. Roadmap says 2s. */
    private val pollIntervalMs: Long = 2_000,
    /** Give up waiting for the user to finish in the browser after this. */
    private val loginTimeoutMs: Long = DEFAULT_LOGIN_TIMEOUT_MS,
) {

    private val _state = MutableStateFlow<TailscaleLoginState>(TailscaleLoginState.Idle)
    val state: StateFlow<TailscaleLoginState> = _state.asStateFlow()

    /** True once a login Custom Tab has been opened and not yet dismissed. */
    var loginTabOpen: Boolean = false
        private set

    /**
     * Start the node and drive the login state machine until the node is
     * Running (either already enrolled, or after the user logs in).
     *
     * Safe to call again after a failure; concurrent calls collapse into the
     * single in-flight transition guarded by [TailscaleLoginState.Starting].
     */
    suspend fun startLogin() {
        if (_state.value is TailscaleLoginState.Starting) return
        _state.value = TailscaleLoginState.Starting

        val started = withContext(io) {
            runCatching {
                node.start(store.stateDirPath(), store.advertisedHostname())
            }
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                Log.e(TAG, "tsnet start failed", e)
                _state.value = TailscaleLoginState.Error(e.message ?: "start failed")
                return
            },
        )

        when (val parsed = parseStartResult(started)) {
            is TailscaleLoginState.Running -> {
                _state.value = parsed
                Log.i(TAG, "startLogin: node already ${parsed.nodeIp}, socks=${parsed.socksPort}")
                TailscaleEngine.onNodeRunning()
            }
            is TailscaleLoginState.Error -> _state.value = parsed
            is TailscaleLoginState.NeedsLogin -> awaitLogin(parsed.loginUrl)
            // Start() reports Stopped/Starting only if the shim is in an
            // unexpected state; poll briefly and let the loop sort it out.
            else -> {
                Log.i(TAG, "startLogin: shim reports ${started.take(120)}; entering poll")
                awaitLogin("")
            }
        }
    }

    /**
     * Poll tsnet every [pollIntervalMs] until the node leaves NeedsLogin.
     *
     * The auth URL often arrives a beat after the initial Start() — control
     * has not been contacted yet — so an empty [initialUrl] is fine: the
     * first non-empty URL observed is surfaced to the UI, which opens the
     * Custom Tab then.
     */
    private suspend fun awaitLogin(initialUrl: String) {
        var loginUrl = initialUrl
        if (loginUrl.isNotEmpty()) _state.value = TailscaleLoginState.NeedsLogin(loginUrl)

        val finished = withTimeoutOrNull(loginTimeoutMs) {
            while (true) {
                delay(pollIntervalMs)
                val status = withContext(io) { runCatching { node.status() }.getOrDefault("{}") }
                val parsed = parseStatusResult(status)
                if (parsed != _state.value) {
                    Log.i(TAG, "node state now: $parsed")
                    if (parsed is TailscaleLoginState.Starting) _state.value = parsed
                }
                when (parsed) {
                    is TailscaleLoginState.Running -> {
                        _state.value = parsed
                        // Node can route now; wake rclone backends and other
                        // dependents that registered while it was down.
                        TailscaleEngine.onNodeRunning()
                        return@withTimeoutOrNull true
                    }
                    is TailscaleLoginState.Error -> {
                        _state.value = parsed
                        return@withTimeoutOrNull true
                    }
                    is TailscaleLoginState.NeedsLogin -> {
                        if (parsed.loginUrl.isNotEmpty() && parsed.loginUrl != loginUrl) {
                            loginUrl = parsed.loginUrl
                            _state.value = parsed
                        }
                    }
                    else -> Unit // Starting/Stopped mid-flight; keep polling.
                }
            }
            true
        }

        if (finished == null) {
            _state.value = TailscaleLoginState.Error("login timed out — tap Log in to retry")
        }
    }

    /**
     * Open the tsnet login URL in a Chrome Custom Tab.
     *
     * Custom Tabs is preferred over a plain VIEW intent because it keeps the
     * user in a browser-like flow that Tailscale's login page is designed
     * for, and comes back to the app cleanly. If no browser can host the tab
     * (extremely stripped images), fall back to a plain browser intent
     * rather than failing the whole login.
     */
    fun openLoginTab(context: Context, url: String) {
        if (url.isBlank()) return
        val uri = Uri.parse(url)
        val appContext = context.applicationContext
        try {
            val tab = CustomTabsIntent.Builder().build()
            tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(appContext, uri)
            loginTabOpen = true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no Custom Tabs host; falling back to VIEW intent")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { err ->
                Log.e(TAG, "no browser available for login URL", err)
                _state.value = TailscaleLoginState.Error("no browser available to complete login")
                return
            }
            loginTabOpen = true
        }
    }

    /** Called by the UI when it dismisses the login tab (state → Running/Error). */
    fun onLoginTabDismissed() {
        loginTabOpen = false
    }

    /** Reset to Idle so the flow can be retried from a clean slate. */
    fun reset() {
        _state.value = TailscaleLoginState.Idle
    }

    companion object {
        private const val TAG = "TailscaleAuth"

        /** 5 minutes: longer than any real login, short enough to notice a stall. */
        const val DEFAULT_LOGIN_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * Parse the JSON returned by the Go shim's Start().
         *
         * Start() returns statusLocked() on every path, so the shape is
         * always `{"state":..., "node_ip":..., "login_url":..., "socks_port":N}`
         * plus an optional `"error"` on the failure paths.
         */
        fun parseStartResult(raw: String): TailscaleLoginState = parseStatusResult(raw)

        /**
         * Parse a Status() snapshot into a login state.
         *
         * `state` is tsnet's BackendState verbatim ("Running", "NeedsLogin",
         * "Stopped", "Starting", "NoState") or the shim's own "Error".
         */
        fun parseStatusResult(raw: String): TailscaleLoginState {
            val json = runCatching { JSONObject(raw) }.getOrElse {
                return TailscaleLoginState.Error("malformed status from tailscale shim")
            }
            return when (val s = json.optString("state", "")) {
                "Running" -> TailscaleLoginState.Running(
                    nodeIp = json.optString("node_ip"),
                    socksPort = json.optInt("socks_port", 0),
                )
                "NeedsLogin" -> TailscaleLoginState.NeedsLogin(
                    loginUrl = json.optString("login_url"),
                )
                "Error" -> TailscaleLoginState.Error(
                    json.optString("error").ifEmpty { "unknown tailscale error" },
                )
                else -> when (s) {
                    "", "Stopped", "Starting", "NoState" -> TailscaleLoginState.Starting
                    else -> TailscaleLoginState.Error("unexpected backend state: $s")
                }
            }
        }
    }
}

/** Node lifecycle states surfaced to the UI. */
sealed class TailscaleLoginState {
    /** Nothing has been attempted yet. */
    object Idle : TailscaleLoginState()

    /** Node stopped / never started. */
    object Stopped : TailscaleLoginState()

    /** Start() has been invoked (or the node is mid-transition); waiting. */
    object Starting : TailscaleLoginState()

    /**
     * The node needs a browser login. [loginUrl] may be empty on the very
     * first poll — the UI opens the tab once it turns non-empty.
     */
    data class NeedsLogin(val loginUrl: String) : TailscaleLoginState()

    /** The node is on the tailnet. [nodeIp] is its 100.x address. */
    data class Running(val nodeIp: String, val socksPort: Int) : TailscaleLoginState()

    /** Start or login failed; the message is safe to show to the user. */
    data class Error(val message: String) : TailscaleLoginState()
}

/**
 * What the auth manager needs to know about how the node is configured.
 * [TailscaleStateStore] is the production implementation; tests fake this.
 */
interface TailscaleNodeConfig {
    /** Absolute path of tsnet's state directory. */
    fun stateDirPath(): String

    /** Hostname this device advertises in the tailnet. */
    fun advertisedHostname(): String

    /** True when the device has ever enrolled (a tsnet profile exists). */
    fun isEnrolled(): Boolean = true
}

/**
 * The subset of the gomobile Tailscale shim this manager drives.
 * Abstracted so the state machine is JVM-testable with a fake.
 */
interface TailscaleNode {
    fun start(stateDir: String, hostname: String): String
    fun status(): String
    fun stop()
}

/** Production [TailscaleNode]: the gomobile binding from the merged AAR. */
object NativeTailscaleNode : TailscaleNode {
    override fun start(stateDir: String, hostname: String): String =
        // Same libgojni.so as rclone; gomobile attaches the calling thread on
        // demand, so any thread is safe — but it is not free, hence callers
        // dispatch onto IO.
        com.example.earthquack.tailscale.Tailscale.start(stateDir, hostname)

    override fun status(): String = com.example.earthquack.tailscale.Tailscale.status()

    override fun stop() {
        com.example.earthquack.tailscale.Tailscale.stop()
    }
}
