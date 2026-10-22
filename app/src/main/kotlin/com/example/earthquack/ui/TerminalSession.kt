package com.example.earthquack.ui

import android.util.Log
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.SshConnectResult
import com.example.earthquack.ssh.SshConnection
import com.termux.terminal.TerminalSession as TermuxSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * TerminalState — keeps the connection, the shell channel, and generation guards.
 */
class TerminalState {

    var shellGeneration = 0
        private set

    @Volatile
    private var shellRef: SshConnection.ShellChannel? = null

    @Volatile
    private var connectionRef: SshConnection? = null

    val isConnected: Boolean get() = connectionRef?.isOpen == true

    val shell: SshConnection.ShellChannel? get() = shellRef

    val connection: SshConnection? get() = connectionRef

    fun adopt(connection: SshConnection) {
        shellRef = null
        connectionRef = connection
        shellGeneration++
    }

    fun adopt(shell: SshConnection.ShellChannel) {
        shellRef = shell
        shellGeneration++
    }

    fun close() {
        shellGeneration++
        val dyingShell = shellRef
        shellRef = null
        val conn = connectionRef
        connectionRef = null
        dyingShell?.close()
        conn?.close()
    }
}

/**
 * TerminalSession — bridges an SSH shell to Termux's terminal emulator and view.
 *
 * Owned by the process rather than by a screen: navigating Back detaches the
 * view, but the shell and emulator remain active until explicitly disconnected.
 */
class TerminalSession(val profileId: String) {

    private companion object {
        const val TAG = "TerminalSession"
    }

    enum class Phase { Idle, Connecting, Connected, Failed }

    interface Listener {
        fun onOutput()
        fun onPhase(phase: Phase, message: String?)
        fun onCopyText(text: String)
        fun onPasteText()
    }

    @Volatile
    var listener: Listener? = null

    val state = TerminalState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    @Volatile
    var failureMessage: String? = null
        private set

    var generation: Int = 0
        private set

    @Volatile
    var bytesReceived: Long = 0L
        private set

    private var job: Job? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TermuxSession?) {
            listener?.onOutput()
        }

        override fun onTitleChanged(changedSession: TermuxSession?) {}

        override fun onSessionFinished(finishedSession: TermuxSession?) {
            setPhase(Phase.Idle)
        }

        override fun onCopyTextToClipboard(session: TermuxSession?, text: String?) {
            listener?.onCopyText(text ?: "")
        }

        override fun onPasteTextFromClipboard(session: TermuxSession?) {
            listener?.onPasteText()
        }

        override fun onBell(session: TermuxSession?) {}

        override fun onColorsChanged(session: TermuxSession?) {}

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "Stack trace", e) }
    }

    /** Termux terminal session handling ANSI/VT100 emulation and screen buffer. */
    val termSession = TermuxSession(
        2000,
        sessionClient,
        object : TermuxSession.SessionCallback {
            override fun onWrite(data: ByteArray, offset: Int, count: Int) {
                val bytes = data.copyOfRange(offset, offset + count)
                scope.launch {
                    write(bytes)
                }
            }

            override fun onResize(columns: Int, rows: Int) {
                scope.launch {
                    resize(rows, columns)
                }
            }
        }
    )

    private fun setPhase(phase: Phase, message: String? = null) {
        _phase.value = phase
        listener?.onPhase(phase, message)
    }

    val isConnected: Boolean get() = state.isConnected

    fun start(
        profile: ConnectionProfile,
        connect: suspend (ConnectionProfile) -> SshConnectResult,
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
                val em = termSession.emulator
                val rows = em?.mRows ?: TerminalFragment.INITIAL_ROWS
                val cols = em?.mColumns ?: TerminalFragment.INITIAL_COLS
                channel = withContext(Dispatchers.IO) {
                    conn.openShell(
                        onData = { data: ByteArray, offset: Int, len: Int -> onShellData(data, offset, len) },
                        rows = rows,
                        cols = cols
                    )
                }
                if (mine != generation) {
                    channel?.close()
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

    private fun onShellData(data: ByteArray, offset: Int, len: Int) {
        bytesReceived += len
        try {
            termSession.appendRemoteData(data, offset, len)
        } catch (e: Throwable) {
            Log.w(TAG, "terminal emulator dropped $len bytes", e)
        }
    }

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

    suspend fun resize(rows: Int, cols: Int) {
        val target = state.shell ?: return
        if (!target.isOpen) return
        val mine = generation
        runCatching {
            if (mine == generation) target.resize(rows, cols)
        }.onFailure { Log.w(TAG, "resize to ${rows}x$cols failed", it) }
    }

    fun stop() {
        job?.cancel()
        job = null
        generation++
        state.close()
        failureMessage = null
        termSession.finishIfRunning()
        setPhase(Phase.Idle)
    }

    fun isIdle(): Boolean = _phase.value == Phase.Idle
}

object TerminalSessions {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    fun of(profileId: String): TerminalSession =
        sessions.getOrPut(profileId) { TerminalSession(profileId) }

    fun peek(profileId: String): TerminalSession? = sessions[profileId]

    fun close(profileId: String) {
        sessions.remove(profileId)?.stop()
    }

    fun closeAll() {
        sessions.values.forEach { runCatching { it.stop() } }
        sessions.clear()
    }
}
