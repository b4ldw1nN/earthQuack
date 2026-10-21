package com.example.earthquack.ui

import android.util.Log
import com.example.earthquack.ssh.SshConnectResult
import com.example.earthquack.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * TerminalSession — one SSH shell, owned by the process rather than by a screen.
 *
 * A terminal that dies when the user presses Back is a terminal that cannot be
 * left running: you go and check something else, come back, and find the
 * session gone. The session therefore lives here, outside the fragment, and the
 * fragment is only a *view* of it: it attaches, renders, and detaches.
 *
 * The screen shows what the session is doing (connecting, connected, failed)
 * through [phase]; the output is fed into the session's own grid, so output
 * that arrives while nobody is looking is still there when the user comes
 * back.
 */
class TerminalSession(val profileId: String) {


    private companion object {
        const val TAG = "TerminalSession"
    }

    /** What the session is doing. */
    enum class Phase { Idle, Connecting, Connected, Failed }

    /** Told when something changed that the screen should show. */
    interface Listener {
        /** New output arrived: redraw. */
        fun onOutput()

        /** The session's phase changed. */
        fun onPhase(phase: Phase, message: String?)
    }

    /** The screen attached to this session, if any. */
    @Volatile
    var listener: Listener? = null

    /** The terminal emulator: the screen this session's output is drawn from. */
    val grid = TerminalGrid(TerminalFragment.INITIAL_ROWS, TerminalFragment.INITIAL_COLS)

    /** The connection, the shell and the generation that guards both. */
    val state = TerminalState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The failure message, when the phase is [Phase.Failed]. */
    @Volatile
    var failureMessage: String? = null
        private set

    /** Bumped whenever the shell is replaced or torn down. */
    var generation: Int = 0
        private set

    /** Total bytes fed to the parser; diagnostics only. */
    @Volatile
    var bytesReceived: Long = 0L
        private set

    private var job: Job? = null

    /**
     * Records a phase change and tells the attached screen.
     *
     * The flow alone is not enough: the screen reads the phase once when it
     * attaches, and a change that happens while nobody is looking has to be
     * delivered when the screen comes back.
     */
    private fun setPhase(phase: Phase, message: String? = null) {
        _phase.value = phase
        listener?.onPhase(phase, message)
    }

    /** True while a shell is open. */
    val isConnected: Boolean get() = state.isConnected

    /**
     * Connects and opens a shell.
     *
     * [connect] is the blocking connect call, supplied by the caller because
     * it needs a Context. The session runs it on IO and keeps the result.
     */
    fun start(
        profile: com.example.earthquack.ssh.ConnectionProfile,
        connect: suspend (com.example.earthquack.ssh.ConnectionProfile) -> SshConnectResult,
        onFailure: (String) -> Unit,
    ) {
        job?.cancel()
        state.close()
        generation++
        val mine = generation
        failureMessage = null
        setPhase(Phase.Connecting)

        job = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { connect(profile) }
            } catch (e: Throwable) {
                SshConnectResult.Failed(com.example.earthquack.ssh.SshFailure.Unexpected(e.message ?: "failed"))
            }

            if (mine != generation) {
                (result as? SshConnectResult.Connected)?.connection?.close()
                return@launch
            }

            when (result) {
                is SshConnectResult.Connected -> {
                    state.adopt(result.connection)
                    openShell(mine, onFailure)
                }
                is SshConnectResult.Failed -> {
                    failureMessage = result.failure.message
                    setPhase(Phase.Failed, result.failure.message)
                    onFailure(result.failure.message)
                }
            }
        }
    }

    private fun openShell(mine: Int, onFailure: (String) -> Unit) {
        val conn = state.connection ?: return
        scope.launch {
            var channel: SshConnection.ShellChannel? = null
            try {
                channel = withContext(Dispatchers.IO) {
                    conn.openShell(
                        onData = { data, offset, len -> onShellData(data, offset, len) },
                        rows = TerminalFragment.INITIAL_ROWS,
                        cols = TerminalFragment.INITIAL_COLS
                    )
                }
                if (mine != generation) {
                    channel.close()
                    return@launch
                }
                state.adopt(channel)
                failureMessage = null
                setPhase(Phase.Connected)
            } catch (e: Throwable) {
                if (mine != generation) {
                    runCatching { channel?.close() }
                    return@launch
                }
                Log.e(TAG, "could not open a shell", e)
                failureMessage = e.message
                setPhase(Phase.Failed, e.message)
                onFailure(e.message ?: "could not open a shell")
            }
        }
    }

    /** Output from the remote shell, on an sshd I/O thread. */
    private fun onShellData(data: ByteArray, offset: Int, len: Int) {
        bytesReceived += len
        try {
            grid.feed(data, offset, len)
        } catch (e: Throwable) {
            Log.w(TAG, "parser dropped $len bytes", e)
            return
        }
        listener?.onOutput()
    }

    /** Writes [payload] to the shell. Returns false when there is no shell. */
    suspend fun write(payload: ByteArray): Boolean {
        val target = state.shell ?: return false
        if (!target.isOpen) return false
        val mine = generation
        val result = runCatching { target.write(payload) }
        if (result.isFailure) {
            Log.w(TAG, "write failed", result.exceptionOrNull())
            return false
        }
        return mine == generation
    }

    /** Sends a PTY size change, off the main thread. */
    suspend fun resize(rows: Int, cols: Int) {
        val target = state.shell ?: return
        if (!target.isOpen) return
        val mine = generation
        runCatching {
            if (mine == generation) target.resize(rows, cols)
        }.onFailure { Log.w(TAG, "resize to ${rows}x$cols failed", it) }
    }

    /** Closes the session. The screen is told through [phase]. */
    fun stop() {
        job?.cancel()
        job = null
        generation++
        state.close()
        failureMessage = null
        setPhase(Phase.Idle)
    }

    /** True when the session has nothing running. */
    fun isIdle(): Boolean = _phase.value == Phase.Idle
}

/**
 * TerminalSessions — the sessions of this process, by profile.
 *
 * Keyed by profile so two profiles never share a shell, and held for as long
 * as the process lives: a session the user connected stays connected until
 * they disconnect it or the app is killed.
 */
object TerminalSessions {

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    /** The session for [profileId], created on first use. */
    fun of(profileId: String): TerminalSession =
        sessions.getOrPut(profileId) { TerminalSession(profileId) }

    /** The session for [profileId], or null when there is none. */
    fun peek(profileId: String): TerminalSession? = sessions[profileId]

    /** Closes and forgets a session. */
    fun close(profileId: String) {
        sessions.remove(profileId)?.stop()
    }

    /** Closes every session; used when the process is going away. */
    fun closeAll() {
        sessions.values.forEach { runCatching { it.stop() } }
        sessions.clear()
    }
}
