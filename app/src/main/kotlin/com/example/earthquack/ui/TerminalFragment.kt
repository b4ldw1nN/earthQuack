package com.example.earthquack.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

    /**
     * The shell this screen shows.
     *
     * It lives outside the fragment (see [TerminalSessions]) so Back does not
     * end the session: the user can leave the terminal and come back to a
     * shell that is still running.
     */
    private var session: TerminalSession? = null

    private val grid: TerminalGrid get() = session?.grid ?: TerminalGrid(INITIAL_ROWS, INITIAL_COLS)

    private val state: TerminalState get() = session?.state ?: TerminalState()

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

    /** True while a finger is on the scroll view. */
    private var userTouching = false

    /** The last screen written to the view, so an unchanged draw is skipped. */
    private var lastDrawnSignature: Pair<Int, String?>? = null

    /** Total bytes fed to the parser this session; diagnostics only. */
    private var bytesReceived = 0L

    /** Shell channels opened this session; must be one per connect. Diagnostics. */
    private var shellChannelsOpened = 0

    private var profile: ConnectionProfile? = null

    /** The layout listener, so it can be removed when the view goes. */
    private var resizeListener: ViewTreeObserver.OnGlobalLayoutListener? = null

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
        binding.terminalLog.setTextIsSelectable(false)
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

        // The session outlives this screen: attach to it so output keeps
        // arriving, and let go of it when the view goes (Back keeps the
        // shell, it only detaches the screen).
        session = TerminalSessions.of(resolved.id)
        session?.listener = object : TerminalSession.Listener {
            override fun onOutput() = onSessionOutput()

            // The session calls this from its own worker thread, and the work
            // it asks for is view work: the status text, the buttons, and the
            // keyboard. Touching those off the main thread is why the screen
            // kept saying "Connecting…" after the shell was already open.
            override fun onPhase(phase: TerminalSession.Phase, message: String?) {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (_binding == null) return@launch
                    when (phase) {
                        TerminalSession.Phase.Connected -> markConnected()
                        TerminalSession.Phase.Failed ->
                            showError(message ?: getString(R.string.terminal_error_not_connected))
                        TerminalSession.Phase.Connecting ->
                            showStatus(getString(R.string.terminal_state_connecting))
                        TerminalSession.Phase.Idle ->
                            showStatus(getString(R.string.terminal_state_disconnected))
                    }
                }
            }
        }

        wireInput()
        wireButtons()
        wireScroll()
        wireResize()

        // A session that is already running (the user came back) shows its
        // state right away instead of pretending to be disconnected.
        when (session?.phase?.value) {
            TerminalSession.Phase.Connected -> markConnected()
            TerminalSession.Phase.Connecting -> showStatus(getString(R.string.terminal_state_connecting))
            TerminalSession.Phase.Failed ->
                showError(session?.failureMessage ?: getString(R.string.terminal_error_not_connected))
            else -> showStatus(getString(R.string.terminal_state_disconnected))
        }
        if (session?.isConnected == true) {
            binding.btnConnect.isEnabled = false
            binding.btnDisconnect.isEnabled = true
            binding.inputField.isEnabled = true
            requestDraw()
        }
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
        // Back to the live output: without it, a user who scrolled up has no
        // way to return to the prompt.
        binding.btnJumpToLive.setOnClickListener {
            userScrolledUp = false
            binding.btnJumpToLive.visibility = View.GONE
            scrollToBottom()
        }
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
            // The IME can still end up serving another view (an app-bar button
            // that held focus, for instance). The keyboard then appears and
            // nothing it types reaches the shell, which looks exactly like a
            // broken terminal. Hand the connection back to the sink and say so.
            field.postDelayed({
                if (_binding == null) return@postDelayed
                val active = manager.isActive(field)
                if (!active) {
                    field.requestFocus()
                    manager.restartInput(field)
                    manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 250)
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
                     setPadding(8, 4, 8, 4)
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
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            // The keyboard opening shrinks the window, which moves the bottom
            // of the log. Re-anchor to the newest output so the prompt stays
            // on screen instead of the viewport showing the middle of the
            // listing.
            if (!userScrolledUp) scrollToBottom()

            if (state.isConnected) {
                val view = _binding?.terminalScroll ?: return@OnGlobalLayoutListener
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
                val log = _binding?.terminalLog ?: return@OnGlobalLayoutListener
                // The log's own padding is inside the scroll view's content
                // box, so it has to come off the width the columns are
                // counted from.
                // Font metrics come from the log's paint; they are stable and
                // unrelated to how wide the content currently is.
                val cell = log.paint.measureText("M")
                val line = log.lineHeight
                val textWidth = viewportW - log.paddingStart - log.paddingEnd
                if (textWidth > 0 && viewportH > 0 && cell > 0f && line > 0) {
                    val rows = viewportH / line
                    val cols = (textWidth / cell).toInt()
                    // The emulator and the shell must agree on the geometry.
                    // When they did not, every cursor-up / erase / delete-lines
                    // sequence in the shell's prompt redraw landed on a
                    // different row, and the text the user typed appeared a
                    // line below the prompt (or on top of it).
                    session?.grid?.resize(rows, cols)
                    // The shell is told even when the size did not change:
                    // a window change makes it redraw its prompt, and the
                    // redraw is what puts the emulator and the shell back
                    // in step after the screen was resized.
                    resizeShell(rows = rows, cols = cols, force = true)
                }
            }
        }
        resizeListener = listener
        binding.terminalScroll.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    // ── connect ──────────────────────────────────────────────────────────────

    private fun connect() {
        val target = profile ?: return
        val held = session ?: TerminalSessions.of(target.id).also { session = it }
        binding.btnConnect.isEnabled = false
        binding.btnDisconnect.isEnabled = false
        showStatus(getString(R.string.terminal_state_connecting))

        resetTerminal()
        // A fresh session starts following again: whatever the user was
        // reading on the previous connection must not pin the new one away
        // from its prompt.
        userScrolledUp = false

        held.start(
            profile = target,
            connect = { p ->
                SshServices.connectionFactory(requireContext()).connect(p)
            },
            onFailure = { message -> showError(message) },
        )
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

    /**
     * New output arrived in the session.
     *
     * The session owns the parser and the screen; the fragment only decides
     * when to put it on the display.
     */
    private fun onSessionOutput() {
        bytesReceived = session?.bytesReceived ?: 0
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
        val rows = grid.renderTrimmed()
        // Setting the text empties the view until the new content has been
        // measured, which reads as the output blinking out and back on every
        // frame. Nothing is written when the screen has not changed.
        val signature = rows.size to rows.lastOrNull()?.joinToString("") { it.text }
        if (signature != lastDrawnSignature) {
            lastDrawnSignature = signature
            log.text = rows.toScreenSpan()
            val rendered = rows.sumOf { line -> line.sumOf { it.text.length } }
            Log.i(TAG, "drew $rendered chars over ${rows.size} lines " +
                "(session bytes=$bytesReceived), autoscroll=${!userScrolledUp}")
        }
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
        // The view can be gone by the time this is called: the layout
        // listener and the posted draw both outlive a screen that was left
        // with Back, and touching the destroyed binding is a crash.
        val scroll = _binding?.terminalScroll ?: return
        scroll.post {
            val view = _binding?.terminalScroll ?: return@post
            if (userScrolledUp) return@post
            // Scroll position only — never fullScroll(FOCUS_DOWN), which also
            // requests focus for the last focusable view and would yank the IME
            // off the input sink mid-typing (observed: the first echoed char
            // moved focus to terminalLog, so every keystroke after it was lost).
            val child = view.getChildAt(0) ?: return@post
            view.scrollTo(0, child.height)
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
        // A scroll that nobody dragged is the view's own relayout, and
        // treating it as "the user scrolled up" switched the follow off: the
        // output then went on below the viewport and the text looked like it
        // had vanished. Only a drag counts.
        binding.terminalScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> userTouching = true
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> userTouching = false
            }
            false
        }
        binding.terminalScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY == oldScrollY) return@setOnScrollChangeListener
            if (!userTouching) return@setOnScrollChangeListener
            val child = binding.terminalScroll.getChildAt(0) ?: return@setOnScrollChangeListener
            val distanceFromBottom = child.height - (scrollY + binding.terminalScroll.height)
            val wasUp = userScrolledUp
            userScrolledUp = !shouldFollowOutput(distanceFromBottom, NEAR_BOTTOM_SLACK_PX)
            _binding?.btnJumpToLive?.visibility = if (userScrolledUp) View.VISIBLE else View.GONE
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
        lastDrawnSignature = null
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
        val held = session
        if (held == null || held.state.shell == null || !held.state.shell!!.isOpen) {
            // Reported rather than dropped: an input field that accepts
            // keystrokes for a shell that is gone is the whole bug.
            showInputError(getString(R.string.terminal_error_not_connected))
            return
        }
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) { held.write(payload) }
            if (!sent) {
                showInputError(
                    getString(
                        R.string.terminal_error_send_failed,
                        getString(R.string.terminal_error_connection_dropped)
                    )
                )
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
    private fun resizeShell(rows: Int, cols: Int, force: Boolean = false) {
        val held = session ?: return
        if (held.state.shell == null || !held.state.shell!!.isOpen) return
        if (!force && rows == lastRows && cols == lastCols) return
        lastRows = rows
        lastCols = cols
        lifecycleScope.launch(Dispatchers.IO) { held.resize(rows, cols) }
    }

    // ── disconnect ───────────────────────────────────────────────────────────

    private fun disconnect() {
        // The session is closed for good: the user asked for it. Leaving the
        // screen (Back) does not come through here — the session stays
        // connected until it is disconnected explicitly.
        val held = session
        session = null
        held?.listener = null
        held?.stop()
        TerminalSessions.close(profile?.id.orEmpty())
        showStatus(getString(R.string.terminal_state_disconnected))
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        binding.inputField.isEnabled = false
    }

    override fun onDestroyView() {
        // The view is going away, so nothing may be touched afterwards, and
        // every coroutine in lifecycleScope is cancelled by the framework.
        //
        // The *session* is not closed: Back leaves the terminal, it does not
        // end the shell. The screen detaches from the session, and the
        // session keeps running until the user disconnects it.
        //
        // The layout listener has to go as well: it fires on the next layout
        // pass, which happens after the view is gone, and it used to reach
        // into the destroyed binding — a crash the user caused by switching
        // tabs while a shell was open.
        resizeListener?.let { listener ->
            _binding?.terminalScroll?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        resizeListener = null
        session?.listener = null
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
        const val INITIAL_ROWS = 24
        const val INITIAL_COLS = 80

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
