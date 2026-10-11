package com.example.earthquack.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentTerminalBinding
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.SshConnectResult
import com.example.earthquack.ssh.SshConnection
import com.example.earthquack.ssh.SshFailure
import com.example.earthquack.ssh.SshServices
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An interactive SSH terminal.
 *
 * ## What this is, and what it is not
 *
 * A **scrolling log** of what the remote shell wrote, plus an input field to
 * send it. It is not a terminal emulator: there is no addressable screen, so
 * full-screen programs (vim, htop, less) draw badly and are explicitly out of
 * scope. [TerminalScreenBuffer]'s class comment lists exactly which sequences
 * are honoured and which are only consumed.
 *
 * ## Lifecycle
 *
 * [TerminalState] carries the shell, the connection and the generation that
 * guards both, and every path that tears a session down goes through it. Three
 * things can end a session — the user, a server closing the socket, and the
 * host activity finishing while the view still exists — and all three must
 * produce the same end state, or the next session inherits a channel the last
 * one already closed.
 */
class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private val grid = TerminalGrid(INITIAL_ROWS, INITIAL_COLS)

    private val state = TerminalState()

    /**
     * The coroutine building this session, and the generation it belongs to.
     *
     * Tracked because the blocking native call cannot be interrupted by a
     * coroutine cancel: pressing Disconnect during a connect returns the user
     * to "Disconnected" immediately, the `withContext(Dispatchers.IO)` block
     * keeps running, and without this a session nobody is watching has been
     * created. The generation is what makes the late result discard itself.
     */
    private var sessionJob: Job? = null
    private var connectGeneration = 0

    /**
     * True while output is waiting to be drawn.
     *
     * The output callback runs on an sshd I/O thread at the shell's speed, not
     * the display's. Coalescing what arrives inside one frame into one draw is
     * the difference between a busy `yes` scrolling readably and one that burns
     * the UI thread's frame budget on `setText` alone.
     */
    private val drawPending = AtomicBoolean(false)

    /** The last geometry a window-change was actually sent for. */
    private var lastRows = 0
    private var lastCols = 0

    /**
     * True once the user has scrolled up away from the newest output.
     *
     * While it is set, [draw] stops snapping the viewport to the bottom: forcing
     * `fullScroll(FOCUS_DOWN)` on every frame was what made the terminal look
     * like it was running away — the viewport was yanked down on each startup
     * newline regardless of where the user had scrolled to, leaving blank space
     * above the prompt. It is cleared again the moment the user returns to the
     * bottom, so live output resumes following.
     */
    private var userScrolledUp = false

    /** Total bytes fed to the parser this session; diagnostics only. */
    private var bytesReceived = 0L

    /** Shell channels opened this session; must be one per connect. Diagnostics. */
    private var shellChannelsOpened = 0

    private var profile: ConnectionProfile? = null

    private val drawRunnable = Runnable { draw() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.i(TAG, "onViewCreated")

        val profileId = arguments?.getString(ARG_PROFILE_ID)
        if (profileId == null) {
            showError(getString(R.string.terminal_error_no_profile))
            return
        }
        val resolved = SshServices.profiles(requireContext()).get(profileId)
        if (resolved == null) {
            showError(getString(R.string.terminal_error_profile_missing))
            return
        }
        profile = resolved

        binding.statusText.text = getString(R.string.terminal_state_disconnected)

        // Terminal log: monospace, selectable, scrollable.
        binding.terminalLog.setTextIsSelectable(true)
        binding.terminalLog.typeface = Typeface.MONOSPACE
        binding.terminalLog.textSize = 13f
        binding.terminalLog.includeFontPadding = false
        binding.inputField.typeface = Typeface.MONOSPACE

        // The grid answers a device-status report (`\e[6n`) by writing the bytes
        // back to the shell. Prompts such as fish use it to measure glyph width
        // and stall for a timeout when it goes unanswered, so answering keeps
        // startup snappy. The write goes through send(), which resolves the
        // current shell, so it is a no-op once disconnected.
        grid.onDeviceReport = { report -> send(report) { } }

        wireInput()
        wireButtons()
        wireScroll()
        wireResize()
        // No auto-connect: the terminal opens in a disconnected state and the
        // user connects explicitly with the Connect button. This matches the
        // layout's initial state (Disconnected status, Connect enabled,
        // Disconnect and input disabled).
    }

    private fun wireInput() {
        // Termux-style: the TerminalInputView forwards every keystroke straight
        // to the shell. There is no "type then press send" — printable text,
        // Enter (as CR), and Backspace each become the raw bytes the PTY
        // expects, and the characters show up in the terminal because the
        // remote echoes them, not because we insert them into the field.
        (binding.inputField as? TerminalInputView)?.onBytes = { bytes ->
            send(bytes) { /* nothing to clear: the field stays empty */ }
        }
        // A single tap on the terminal focuses the input and raises the
        // keyboard. The field is transparent and has no send button, so without
        // this the user would have to find the invisible field at the bottom to
        // type at all.
        binding.terminalLog.setOnClickListener { focusInputAndShowKeyboard() }
        binding.terminalScroll.setOnClickListener { focusInputAndShowKeyboard() }
    }

    /** Focuses the input sink and raises the soft keyboard. */
    private fun focusInputAndShowKeyboard() {
        val field = binding.inputField
        if (!field.isEnabled) return
        field.requestFocusFromTouch()
        // The keyboard's own adjustResize triggers a relayout that can hand the
        // IME back to a sibling, so rebind *after* layout settles rather than
        // once, synchronously. requestFocusFromTouch wins the touch-mode focus
        // the terminal log would otherwise claim.
        field.post {
            if (_binding == null) return@post
            val manager = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager ?: return@post
            if (!field.hasFocus()) field.requestFocus()
            // restartInput forces the IME to (re)attach to this field. Without
            // it, a keyboard that is already showing stays bound to whatever had
            // focus before (observed: bound to the terminalLog TextView), and
            // showSoftInput is then a no-op — so keystrokes never reach the sink
            // until the user taps again.
            manager.restartInput(field)
            manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun wireButtons() {
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        // Control keys. A soft keyboard has no Ctrl key, so without these an
        // interactive program can be neither interrupted nor sent a Tab: no
        // Ctrl+C exists for the shell to react to, no matter how politely the
        // UI reports that it is connected.
        CONTROL_KEYS.forEach { (label, bytes) ->
            binding.controlKeys.addView(
                com.google.android.material.button.MaterialButton(requireContext()).apply {
                    text = label
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setOnClickListener { sendControl(label, bytes) }
                }
            )
        }
    }

    /**
     * Watches the terminal's size and keeps the remote PTY in step.
     *
     * The listener fires on every layout pass — the keyboard opening, the scroll
     * view settling, the rotation animation — so the comparison in
     * [resizeShell] is load-bearing rather than an optimisation.
     */
    private fun wireResize() {
        binding.terminalScroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (state.isConnected) {
                val view = binding.terminalScroll
                // Measure the fixed viewport box, never the growing content
                // TextView. terminalLog wraps its content: when the remote
                // redraws a prompt padded to the column count we last sent, the
                // unbreakable prompt line makes the TextView measure wider than
                // the screen, and feeding that width straight back as more
                // columns asked the shell for a few more columns each pass — an
                // unbounded feedback loop (observed: cols climbing 327→432…
                // forever) that flooded the PTY with window-change requests and
                // dragged the prompt down the screen. The scroll view's own box
                // is the real viewport and does not grow with content.
                val viewportW = view.width - view.paddingLeft - view.paddingRight
                val viewportH = view.height - view.paddingTop - view.paddingBottom
                val log = binding.terminalLog
                // Font metrics come from the log's paint; they are stable and
                // unrelated to how wide the content currently is.
                val cell = log.paint.measureText("M")
                val line = log.lineHeight
                if (viewportW > 0 && viewportH > 0 && cell > 0f && line > 0) {
                    resizeShell(rows = viewportH / line, cols = (viewportW / cell).toInt())
                }
            }
        }
    }

    // ── connect ──────────────────────────────────────────────────────────────

    private fun connect() {
        val target = profile ?: return
        // A second connect while one is running would leak the first session:
        // nothing waits on it and nothing closes it.
        sessionJob?.cancel()
        // Anything still open is replaced, not stacked. Reconnecting without
        // this left the previous session alive until GC.
        state.close()
        val generation = ++connectGeneration
        binding.btnConnect.isEnabled = false
        binding.btnDisconnect.isEnabled = false
        showStatus(getString(R.string.terminal_state_connecting))

        sessionJob = lifecycleScope.launch {
            resetTerminal()
            // A fresh session starts following again: whatever the user was
            // reading on the previous connection must not pin the new one away
            // from its prompt.
            userScrolledUp = false

            val result = try {
                withContext(Dispatchers.IO) {
                    SshServices.connectionFactory(requireContext()).connect(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The factory returns failures as data; reaching here means the
                // failure was raised before it could, which is still something
                // the user should see rather than a silent "Disconnected".
                Log.e(TAG, "connect raised", e)
                SshConnectResult.Failed(SshFailure.Unexpected(causeOf(e)))
            }

            // The user disconnected, or pressed Connect again, while this was
            // in flight. The session is real but nobody wants it.
            if (generation != connectGeneration) {
                (result as? SshConnectResult.Connected)?.connection?.close()
                return@launch
            }

            when (result) {
                is SshConnectResult.Connected -> {
                    state.adopt(result.connection)
                    openShell(generation)
                }
                is SshConnectResult.Failed -> {
                    showError(result.failure.message)
                }
            }
        }
    }

    /**
     * Opens a shell on the current connection.
     *
     * The channel open is blocking I/O: it waits for the server to answer, and
     * doing it on the main thread throws [android.os.NetworkOnMainThreadException]
     * which sshd's NIO2 write handler surfaces as a write-cycle failure that
     * closes the session — so the shell dies before it opens. Everything here
     * runs on IO; only the view work comes back to the main thread.
     */
    private fun openShell(generation: Int) {
        val conn = state.connection ?: return
        sessionJob = lifecycleScope.launch {
            var channel: SshConnection.ShellChannel? = null
            try {
                channel = withContext(Dispatchers.IO) {
                    shellChannelsOpened++
                    Log.i(TAG, "opening shell channel #$shellChannelsOpened " +
                        "(generation $generation)")
                    conn.openShell(
                        // The handler is registered before the open returns: the
                        // server can write before the caller has touched the
                        // channel, and dropping the first bytes would look like
                        // a shell that produced nothing.
                        onData = { data, offset, len -> onShellData(data, offset, len) },
                        rows = INITIAL_ROWS,
                        cols = INITIAL_COLS
                    )
                }
                if (generation != connectGeneration) {
                    // Disconnected while the channel was opening.
                    channel.close()
                    return@launch
                }
                state.adopt(channel)
                markConnected()
            } catch (e: CancellationException) {
                // The screen went away mid-open: nothing to report, and the
                // half-open channel must not be left behind.
                channel?.close()
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "could not open a shell", e)
                showError(describeShellFailure(e))
            }
        }
    }

    /**
     * Output from the remote shell, on an sshd I/O thread.
     *
     * Everything here is bounded: the buffer caps what it retains, the display
     * caps what it holds, and the draw is coalesced. The view is reached through
     * the view's own handler, so a callback that outlives the screen cannot
     * touch a destroyed one.
     */

    /** Throttle for the periodic byte-flow log: one line at most this often. */
    private var lastByteFlowLogAt = 0L

    /** How many startup feeds we've dumped (first few only). */
    private var feedDumpCount = 0

    private fun onShellData(data: ByteArray, offset: Int, len: Int) {
        bytesReceived += len
        // Throttled byte-flow heartbeat: proves whether the remote keeps
        // sending (a runaway session) or output stopped (a UI-only loop).
        // Content is never logged — only counts and timing.
        val now = SystemClock.elapsedRealtime()
        if (now - lastByteFlowLogAt >= 500L) {
            lastByteFlowLogAt = now
            Log.i(TAG, "shell data: session bytes=$bytesReceived, this feed=$len")
        }
        // One-shot escaped dump of the first feed. This is the pre-command
        // shell banner/prompt only (no user input yet), so it carries no
        // secrets; it is the only way to tell genuine remote newlines from
        // newlines the parser manufactures out of escape sequences.
        if (feedDumpCount < 5) {
            feedDumpCount++
            val sb = StringBuilder(len * 4)
            var i = offset
            val end = offset + len
            while (i < end) {
                val b = data[i].toInt() and 0xFF
                i++
                when {
                    b == 0x1B -> sb.append("\\e")
                    b == 0x0A -> sb.append("\\n")
                    b == 0x0D -> sb.append("\\r")
                    b == 0x09 -> sb.append("\\t")
                    b in 0x20..0x7E -> sb.append(b.toChar())
                    else -> sb.append(String.format("\\x%02x", b))
                }
            }
            Log.i(TAG, "feed #$feedDumpCount (len=$len): $sb")
        }
        // Feed the fixed-grid emulator. It owns cursor addressing, carriage
        // returns, erases and scrollback internally, so there is no per-frame
        // correction dance here — one feed, one coalesced draw.
        try {
            grid.feed(data, offset, len)
        } catch (e: Throwable) {
            // A parser failure must not take the session down with it. Losing a
            // frame of output is recoverable; a dead terminal is not.
            Log.w(TAG, "parser dropped $len bytes", e)
            return
        }
        requestDraw()
    }

    private fun requestDraw() {
        if (drawPending.compareAndSet(false, true)) {
            _binding?.terminalLog?.post(drawRunnable)
        }
        // If one post is already in flight the runnable runs once and takes
        // everything, so nothing is dropped and nothing is drawn twice.
    }

    /**
     * Puts the accumulated text on screen.
     *
     * One `setText` per frame rather than per byte: a TextView rebuilds its
     * layout from the whole of its text, so a busy shell used to make every
     * frame slower than the last.
     *
     * The scroll is *conditional*. Snapping to the bottom on every frame — the
     * old `fullScroll(FOCUS_DOWN)` — fought the user: scrolling up to read
     * something was undone by the next newline, and with the startup blank lines
     * the viewport looked like it was sliding down on its own. It now follows
     * only when the user is already at the bottom, and leaves their position
     * alone otherwise.
     */
    private fun draw() {
        drawPending.set(false)
        val log = _binding?.terminalLog ?: return
        // One setText per frame from the whole grid, trailing blank rows trimmed
        // so the prompt is the last line and auto-follow pins it to the bottom.
        val rows = grid.renderTrimmed()
        log.text = rows.toScreenSpan()
        val rendered = rows.sumOf { line -> line.sumOf { it.text.length } }
        Log.i(TAG, "drew $rendered chars over ${rows.size} lines " +
            "(session bytes=$bytesReceived), autoscroll=${!userScrolledUp}")
        scrollToBottom()
    }

    /**
     * Scrolls to the newest output, but only when the user is already there.
     *
     * [userScrolledUp] is maintained by the scroll listener wired in
     * [wireScroll], so a deliberate scroll up is respected and a return to the
     * bottom re-arms following. `post` defers the scroll until after the new
     * text has been laid out, otherwise the child is not yet tall enough and the
     * scroll clamps short of the bottom.
     */
    private fun scrollToBottom() {
        if (userScrolledUp) return
        binding.terminalScroll.post {
            if (userScrolledUp) return@post
            // Scroll position only — never fullScroll(FOCUS_DOWN), which also
            // requests focus for the last focusable view and would yank the IME
            // off the input sink mid-typing (observed: the first echoed char
            // moved focus to terminalLog, so every keystroke after it was lost).
            val child = binding.terminalScroll.getChildAt(0) ?: return@post
            binding.terminalScroll.scrollTo(0, child.height)
        }
    }

    /**
     * Tracks whether the user has scrolled away from the newest output.
     *
     * A small slack absorbs the sub-pixel settling of a NestedScrollView so a
     * user resting at the bottom is not treated as having scrolled up; only a
     * deliberate move up sets [userScrolledUp] and stops [draw] from following.
     */
    private fun wireScroll() {
        binding.terminalScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY == oldScrollY) return@setOnScrollChangeListener
            val child = binding.terminalScroll.getChildAt(0) ?: return@setOnScrollChangeListener
            val distanceFromBottom = child.height - (scrollY + binding.terminalScroll.height)
            val wasUp = userScrolledUp
            userScrolledUp = !shouldFollowOutput(distanceFromBottom, NEAR_BOTTOM_SLACK_PX)
            if (wasUp != userScrolledUp) {
                Log.d(TAG, "scroll ${scrollY}px, ${distanceFromBottom}px from bottom -> " +
                    "follow=${!userScrolledUp}")
            }
        }
    }

    private fun markConnected() {
        showStatus(getString(R.string.terminal_state_connected))
        binding.btnConnect.isEnabled = false
        binding.btnDisconnect.isEnabled = true
        binding.inputField.isEnabled = true
        // Raise the keyboard the moment the shell is live, so the user can type
        // immediately without hunting for the transparent field at the bottom.
        focusInputAndShowKeyboard()
    }

    private fun resetTerminal() {
        grid.reset()
        _binding?.terminalLog?.text = ""
        bytesReceived = 0
        feedDumpCount = 0
    }

    // ── input ────────────────────────────────────────────────────────────────

    /**
     * Sends a control byte.
     *
     * The bytes go out through the same write path as a command, so they share
     * its ordering and its failure handling; only the source differs.
     */
    private fun sendControl(label: String, bytes: ByteArray) {
        Log.i(TAG, "sending control key $label")
        // A control key such as Ctrl+C resets the shell's line buffer, so the
        // invisible mirror must be emptied too or the next keystroke's diff
        // would be computed against text the shell no longer holds.
        (binding.inputField as? TerminalInputView)?.clearMirror()
        send(bytes) { /* nothing further to clear */ }
    }

    /**
     * Writes [payload] to the shell, off the main thread.
     *
     * The channel and the generation are both captured by value, so a
     * disconnect or reconnect that lands mid-flight cannot make this write
     * either reach a channel the user has moved on from, or report the old
     * failure against a healthy new session.
     */
    private fun send(payload: ByteArray, onSent: () -> Unit) {
        val target = state.shell
        if (target == null || !target.isOpen) {
            // Reported rather than dropped: an input field that accepts
            // keystrokes for a shell that is gone is the whole bug.
            showInputError(getString(R.string.terminal_error_not_connected))
            return
        }
        val generation = state.shellGeneration
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) { runCatching { target.write(payload) } }
            if (sent.isFailure) {
                if (generation == state.shellGeneration) {
                    val error = sent.exceptionOrNull()
                    showInputError(
                        getString(
                            R.string.terminal_error_send_failed,
                            error?.message ?: getString(R.string.terminal_error_connection_dropped)
                        )
                    )
                }
                return@launch
            }
            // The field is cleared only once the bytes were taken. Clearing
            // first is what made the previous transport look silently broken:
            // the field emptied, the send then failed, and nothing anywhere
            // recorded that the command never left the device.
            if (_binding != null) onSent()
        }
    }

    /**
     * Sends a PTY size change, off the main thread, and only when it changed.
     *
     * sshd's `sendWindowChange` writes a channel request immediately, so doing
     * it inline from a layout listener can throw
     * [android.os.NetworkOnMainThreadException] and close the shell.
     *
     * The comparison is the other half: the layout listener fires on every
     * pass, and sending an identical window-change for each one is a burst of
     * channel requests that some servers answer slowly, which is exactly the
     * "resizing the terminal kills the shell" report.
     */
    private fun resizeShell(rows: Int, cols: Int) {
        val target = state.shell ?: return
        if (!target.isOpen) return
        if (rows == lastRows && cols == lastCols) return
        lastRows = rows
        lastCols = cols
        val generation = state.shellGeneration
        lifecycleScope.launch(Dispatchers.IO) {
            val applied = runCatching {
                if (generation == state.shellGeneration && target.isOpen) {
                    target.resize(rows, cols)
                    true
                } else {
                    false
                }
            }
            if (applied.isFailure) {
                Log.w(TAG, "resize to ${rows}x$cols failed", applied.exceptionOrNull())
            } else if (applied.getOrNull() == true) {
                Log.i(TAG, "resized to ${rows}x$cols")
            }
        }
    }

    // ── disconnect ───────────────────────────────────────────────────────────

    private fun disconnect() {
        // The connect coroutine is cancelled before the state is cleared, so
        // its result is discarded when it lands rather than opening a session
        // after the user has already left.
        sessionJob?.cancel()
        sessionJob = null
        connectGeneration++
        state.close()
        showStatus(getString(R.string.terminal_state_disconnected))
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        binding.inputField.isEnabled = false
    }

    override fun onDestroyView() {
        // The view is going away, so nothing may be touched afterwards, and
        // every coroutine in lifecycleScope is cancelled by the framework. That
        // is what stops the last write from reaching a channel nothing is
        // watching, and what stops a connect that lands after the screen is
        // gone from opening a session nobody closes.
        sessionJob?.cancel()
        sessionJob = null
        state.close()
        _binding = null
        super.onDestroyView()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun showStatus(message: String) {
        if (_binding == null) return
        binding.statusText.text = message
    }

    /** Reports a failure in connecting or opening the shell. */
    private fun showError(message: String) {
        showStatus(getString(R.string.terminal_state_error, message))
        binding.btnConnect.isEnabled = true
    }

    /**
     * Reports a send that did not happen.
     *
     * Separate from [showError] because the field keeps its text: the
     * difference between "the command ran and produced nothing" and "the
     * command never left the device" has to be visible.
     */
    private fun showInputError(message: String) {
        showStatus(getString(R.string.terminal_state_error, message))
    }

    /**
     * Turns a failed shell-open into something the user can act on.
     *
     * A refused shell is not an authentication failure and not a broken
     * connection: the session is authenticated and open, the server simply
     * declines this one channel type. Saying so is the point — the same session
     * may still serve SFTP, and telling the user to check their password sends
     * them looking in exactly the wrong place.
     */
    private fun describeShellFailure(error: Throwable): String {
        val detail = (generateSequence(error) { it.cause }.take(6))
            .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
            .joinToString(": ")
            .take(MAX_DETAIL)
        return when {
            detail.contains("admin", true) ||
                detail.contains("prohibited", true) ||
                detail.contains("ForceCommand", true) ||
                detail.contains("shell request", true) ||
                detail.contains("shell channel", true) ->
                getString(R.string.terminal_error_shell_refused)
            detail.contains("pty", true) || detail.contains("pseudo-terminal", true) ->
                getString(R.string.terminal_error_pty_refused)
            detail.contains("timeout", true) ->
                getString(R.string.terminal_error_shell_timeout)
            detail.isBlank() -> getString(R.string.terminal_error_shell_failed)
            else -> getString(R.string.terminal_error_shell_failed_detail, detail)
        }
    }

    /** The deepest message in a throwable, for a failure sshd wrapped. */
    private fun causeOf(e: Throwable): String {
        var current: Throwable? = e
        repeat(8) {
            current ?: return@repeat
            val message = current.message?.takeIf { m -> m.isNotBlank() }
            if (message != null) return message
            current = current.cause
        }
        return e.javaClass.simpleName
    }

    companion object {
        const val TAG = "TerminalFragment"
        const val ARG_PROFILE_ID = "profile_id"

        /** The shell's geometry before the first layout pass tells us the real one. */
        private const val INITIAL_ROWS = 24
        private const val INITIAL_COLS = 80

        /**
         * How much styled text the TextView holds.
         *
         * The parser's own cap is larger; this one bounds layout cost, because a
         * TextView rebuilds its layout from the whole of its text on every
         * setText. Without it a long-running command makes the UI progressively
         * less responsive rather than merely scrolling fast.
         */
        private const val DISPLAY_CAP = 48 * 1024

        /** sshd's error text is not bounded; a status row is not a log file. */
        private const val MAX_DETAIL = 160

        /**
         * How close to the bottom (px) still counts as "following".
         *
         * A NestedScrollView settles a fraction of a pixel short of the true
         * bottom, so an exact `== 0` test would flicker the follow state on
         * every frame. A line or two of slack absorbs that without letting a
         * deliberate scroll up be mistaken for resting at the bottom.
         */
        private const val NEAR_BOTTOM_SLACK_PX = 24

        /**
         * Control keys, in the order they are shown.
         *
         * Ctrl+C is first because it is the one without which an interactive
         * program cannot be stopped at all — the shell never sees it, so there
         * is nothing to interrupt and no error to explain.
         */
        val CONTROL_KEYS: LinkedHashMap<String, ByteArray> = linkedMapOf(
            "C-c" to byteArrayOf(3),
            "C-d" to byteArrayOf(4),
            "C-z" to byteArrayOf(26),
            "Tab" to byteArrayOf(9),
            "Esc" to byteArrayOf(27),
            "↑" to byteArrayOf(27, '['.code.toByte(), 'A'.code.toByte()),
            "↓" to byteArrayOf(27, '['.code.toByte(), 'B'.code.toByte())
        )

        fun newInstance(profileId: String): TerminalFragment =
            TerminalFragment().apply {
                arguments = Bundle().apply { putString(ARG_PROFILE_ID, profileId) }
            }
    }
}

/**
 * The shell, the connection, and the generation that guards both.
 *
 * ## Why a class and not three fields
 *
 * Every bug in the previous version was an ordering bug. A write launched for
 * one shell landed on an sshd I/O thread and reported its failure after a
 * reconnect had swapped in a new shell, so a healthy connection looked broken.
 * The counter here is what makes a send idempotent against that: each send
 * records the shell it belongs to, and only reports against the current one.
 *
 * This is also the single place a session is closed, so the three ways a session
 * ends — the user disconnecting, the server closing the socket, and the view
 * being destroyed — cannot leave one path half-done.
 */
class TerminalState {

    /** Bumped whenever the shell is replaced or torn down. */
    var shellGeneration = 0
        private set

    @Volatile
    private var shellRef: SshConnection.ShellChannel? = null

    @Volatile
    private var connectionRef: SshConnection? = null

    /** True while the connection is open and could still host a shell. */
    val isConnected: Boolean get() = connectionRef?.isOpen == true

    val shell: SshConnection.ShellChannel? get() = shellRef

    val connection: SshConnection? get() = connectionRef

    /** Adopts a freshly connected session and bumps the generation. */
    fun adopt(connection: SshConnection) {
        shellRef = null
        connectionRef = connection
        shellGeneration++
    }

    /** Adopts a freshly opened shell and bumps the generation. */
    fun adopt(shell: SshConnection.ShellChannel) {
        shellRef = shell
        shellGeneration++
    }

    /** Closes everything. Idempotent, and safe from any thread. */
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
 * The bytes a typed command line becomes on its way to the shell: the text
 * followed by the Enter key.
 *
 * A PTY shell is line-buffered by the tty in cooked mode, so it runs a line
 * only when it receives the Enter key. Sending the bare text — the previous
 * behaviour — left the shell waiting on its input line forever, which is
 * exactly the "I typed `ls` and nothing happened" report. Enter is a carriage
 * return (0x0D): the tty line discipline (ICRNL) turns it into the newline the
 * shell acts on, and it is what Termux and every other PTY client send.
 *
 * Kept as a pure top-level function so the byte layout is unit-testable without
 * an Android shell.
 */
internal fun commandWithEnter(text: String): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return bytes + byteArrayOf(ENTER_BYTE)
}

/** Carriage return — the byte the Enter key sends to a PTY. */
internal const val ENTER_BYTE: Byte = 0x0D
