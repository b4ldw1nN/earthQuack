package com.example.earthquack.ui

import android.graphics.Typeface
import android.util.Log
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentTerminalBinding
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.SshConnection
import com.example.earthquack.ssh.SshServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An interactive SSH terminal.
 */
class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private val vtParser = VT100Parser()

    private var profile: ConnectionProfile? = null
    private var connection: SshConnection? = null
    private var shell: SshConnection.ShellChannel? = null

    private var isConnected = false

    /**
     * Bumped whenever the shell is replaced or torn down.
     *
     * A write started for one shell can otherwise land on an sshd I/O thread and
     * return its failure after a reconnect has already swapped in a new shell:
     * the old error would then be reported against a connection that is fine.
     * Each send records the generation it belongs to and only reports against
     * the current one.
     */
    private var shellGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.i("TerminalFragment", "onViewCreated called")
        super.onViewCreated(view, savedInstanceState)

        val profileId = arguments?.getString(ARG_PROFILE_ID)
        if (profileId == null) {
            showError("No profile specified")
            return
        }
        val p = SshServices.profiles(requireContext()).get(profileId)
        if (p == null) {
            showError("Profile not found")
            return
        }
        profile = p

        binding.statusText.text = "Disconnected"
        // binding.subtitleText is not in the binding - using status text only
        // binding.subtitleText.text = p.endpoint

        // Terminal log: monospace, selectable, scrollable
        binding.terminalLog.setTextIsSelectable(true)
        binding.terminalLog.typeface = Typeface.MONOSPACE
        binding.terminalLog.textSize = 13f
        binding.terminalLog.includeFontPadding = false
        binding.inputField.typeface = Typeface.MONOSPACE

        // Input field: intercepts soft keyboard. Both paths end in submitInput(),
        // which owns the "send and clear" contract; neither clears the field
        // itself, because a submission that fails must leave its text behind.
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                submitInput()
                true
            } else false
        }

        // Connect button
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnSend.setOnClickListener {
            submitInput()
            // Dismiss the keyboard: leaving it up covers most of the log,
            // and a terminal is watched while it works rather than typed
            // into exclusively.
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.inputField.windowToken, 0)
        }

        // Handle orientation/window resize
        binding.terminalScroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (isConnected) {
                val w = binding.terminalLog.width
                val h = binding.terminalLog.height
                if (w > 0 && h > 0) {
                    val cols = maxOf(1, w / (binding.terminalLog.paint.measureText("M").toInt()))
                    val rows = maxOf(1, h / binding.terminalLog.lineHeight)
                    resizeShell(rows, cols)
                }
            }
        }

        connect()
    }

    private fun connect() {
        Log.i("TerminalFragment", "connect() called for profile: ${profile?.name}")
        val p = profile ?: return
        lifecycleScope.launch {
            vtParser.reset()
            binding.terminalLog.text = ""
            binding.btnConnect.isEnabled = false
            binding.statusText.text = "Connecting…"

            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                SshServices.connectionFactory(requireContext()).connect(p)
            }

            if (result is com.example.earthquack.ssh.SshConnectResult.Connected) {
                connection = result.connection
                openShell()
            } else {
                val failure = (result as com.example.earthquack.ssh.SshConnectResult.Failed).failure
                showError(failure.message)
                binding.btnConnect.isEnabled = true
            }
        }
    }

    private fun openShell() {
        val conn = connection ?: return
        lifecycleScope.launch {
            var ch: SshConnection.ShellChannel? = null
            try {
                // Opening a channel is blocking I/O: it waits for the server to
                // answer the open request. Doing that on the main thread throws
                // NetworkOnMainThreadException, which sshd's NIO2 write handler
                // surfaces as a write-cycle failure, which closes the session —
                // so the shell dies before it opens and the user sees "the server
                // refused" for a server that would have said yes. This is the
                // only reason the terminal ever failed: TCP, key exchange,
                // authentication and host-key verification all succeeded first.
                ch = withContext(Dispatchers.IO) {
                    conn.openShell(
                        onData = { data, offset, len ->
                            val text = vtParser.feed(data, offset, len)
                            // Posted to the TextView, not to the activity: the
                            // callback runs on an sshd I/O thread that outlives
                            // the screen, and requireActivity() throws once the
                            // fragment is detached.
                            _binding?.terminalLog?.post {
                                if (_binding != null) appendToLog(text)
                            }
                        },
                        rows = 24,
                        cols = 80
                    )
                }
                shell = ch
                isConnected = true
                // A fresh shell means a fresh input path: nothing carries over
                // from a previous session, so a reconnect cannot be handed a
                // transport the last one already closed.
                shellGeneration++

                requireActivity().runOnUiThread {
                    if (view == null) return@runOnUiThread
                    binding.statusText.text = "Connected"
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    binding.inputField.isEnabled = true
                    binding.inputField.requestFocus()
                }
            } catch (e: CancellationException) {
                // The screen went away while the channel was opening: nothing to
                // report, and a half-open channel must not be left behind.
                ch?.close()
                throw e
            } catch (e: Throwable) {
                Log.e("TerminalFragment", "Failed to open shell", e)
                // The reason comes from SshConnection, which knows what actually
                // happened. Guessing again here from keywords produced "the server
                // refused the shell" for what was really a client-side bug.
                val detail = e.cause?.message?.takeIf { it.isNotBlank() } ?: e.message
                requireActivity().runOnUiThread {
                    if (view == null) return@runOnUiThread
                    showError(detail ?: "The terminal could not be opened.")
                }
            }
        }
    }

    /**
     * Reads the input field, sends it, and clears it *only* if the send was
     * accepted.
     *
     * The order matters. Clearing first is what made the old transport look
     * broken: the field emptied, and the send then failed on a queue that
     * disconnect had already closed — with the result discarded, so nothing
     * anywhere recorded that the command never left the device.
     */
    private fun submitInput() {
        val text = binding.inputField.text.toString()
        if (text.isEmpty()) return
        sendToShell(text)
    }

    /**
     * Sends [text] as one command, terminated by the newline the shell needs to
     * run it.
     *
     * The blocking write happens on [Dispatchers.IO]; the main thread only does
     * view work. [target] is captured by value, so a disconnect or reconnect
     * that clears [shell] mid-flight cannot make this write reach a channel the
     * user has moved on from — it either lands on the shell it was meant for, or
     * it fails and says so.
     */
    private fun sendToShell(text: String) {
        val target = shell
        if (target == null || !target.isOpen) {
            // Reporting here rather than dropping: an input field that accepts
            // keystrokes for a shell that is gone is the whole bug.
            Log.w(TAG, "not sending ${text.length} chars — no open shell channel")
            showInputError("The terminal is not connected, so that was not sent.")
            return
        }
        val generation = shellGeneration
        val payload = (text + "\n").toByteArray(Charsets.UTF_8)
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                runCatching { target.write(payload) }
            }
            if (sent.isSuccess) {
                if (_binding != null) binding.inputField.text.clear()
            } else if (generation == shellGeneration && view != null) {
                // Length and class only: the payload is user input and may hold
                // a password typed into a terminal command.
                val error = sent.exceptionOrNull()
                Log.w(TAG, "shell write of ${payload.size} bytes failed: ${error?.javaClass?.simpleName}")
                showInputError(
                    "The terminal did not accept that: ${error?.message ?: "the connection dropped"}"
                )
            }
        }
    }

    /**
     * Sends PTY size changes off the main thread.
     *
     * sshd's sendWindowChange writes a channel request packet immediately. The
     * layout listener runs on Android's main thread, so doing the resize inline
     * can trip NetworkOnMainThreadException and close the shell before input is
     * ever sent.
     */
    private fun resizeShell(rows: Int, cols: Int) {
        val target = shell ?: return
        if (!target.isOpen) return
        val generation = shellGeneration
        lifecycleScope.launch(Dispatchers.IO) {
            val resized = runCatching {
                if (generation == shellGeneration && target.isOpen) {
                    target.resize(rows, cols)
                }
            }
            if (resized.isFailure && generation == shellGeneration) {
                Log.w(TAG, "terminal resize to ${rows}x${cols} failed", resized.exceptionOrNull())
            }
        }
    }

    private fun disconnect() {
        isConnected = false
        // Retire the shell before closing it, so a write that is already on
        // Dispatchers.IO is seen as belonging to the previous session and is
        // not reported against whatever the next one opens.
        shellGeneration++
        val dying = shell
        shell = null
        dying?.close()
        connection?.close()
        connection = null
        // Repeated disconnects are safe: close() is idempotent on both the
        // shell channel and the connection, and every field is already null.
        if (view != null) {
            binding.statusText.text = "Disconnected"
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
            binding.inputField.isEnabled = false
        }
    }

    private fun appendToLog(spanned: CharSequence) {
        val log = binding.terminalLog
        // The TextView's text is not necessarily a SpannableStringBuilder:
        // `setTextIsSelectable(true)` makes it a SpannableString, so casting
        // straight to the builder threw ClassCastException on the first byte of
        // output — which killed the app the moment a shell finally opened.
        // Append onto a builder that is built from whatever is there.
        val current = when (val existing = log.text) {
            is SpannableStringBuilder -> existing
            else -> SpannableStringBuilder(existing)
        }
        current.append(spanned)
        log.text = current
        binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
    }

    private fun showError(msg: String) {
        binding.statusText.text = "Error: $msg"
        binding.btnConnect.isEnabled = true
    }

    /**
     * Reports a send that did not happen.
     *
     * Separate from [showError] on purpose: the field keeps its text, so the
     * difference between "the command ran and produced nothing" and "the command
     * never left the device" has to be visible in the status line.
     */
    private fun showInputError(msg: String) {
        if (_binding == null) return
        binding.statusText.text = "Error: $msg"
    }

    override fun onDestroyView() {
        // The view is going away, so no view may be touched afterwards — and
        // every coroutine in lifecycleScope is cancelled by the framework, which
        // is what stops the last write from reaching a channel nothing is
        // watching any more.
        disconnect()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "TerminalFragment"
        const val ARG_PROFILE_ID = "profile_id"

        fun newInstance(profileId: String): TerminalFragment {
            return TerminalFragment().apply {
                arguments = Bundle().apply { putString(ARG_PROFILE_ID, profileId) }
            }
        }
    }
}

/**
 * Minimal VT100/ANSI parser → Spanned.
 */
class VT100Parser {

    private enum class State { GROUND, ESC, CSI, OSC, OSC_STRING, CHARSET }

    private var state = State.GROUND
    private val params = mutableListOf<Int>()
    private var paramBuf = ""
    private var intermediate = 0
    private val output = SpannableStringBuilder()

    /**
     * A multi-byte sequence split across two reads, held until it completes.
     *
     * sshd hands over whatever arrived in one TCP read, which can end in the
     * middle of a UTF-8 sequence. Decoding per read would then turn the second
     * half into two replacement characters — gibberish in the log, with no
     * fault on the wire.
     */
    private var pendingUtf8: ByteArray? = null

    private var currentFg: Int? = null
    private var currentBg: Int? = null
    private var bold = false
    private var underline = false
    private var reverse = false
    private var concealed = false

    fun reset() {
        state = State.GROUND
        params.clear()
        paramBuf = ""
        intermediate = 0
        output.clear()
        pendingUtf8 = null
        currentFg = null
        currentBg = null
        bold = false
        underline = false
        reverse = false
        concealed = false
    }

    /**
     * Feeds raw bytes, returns styled text ready to append.
     *
     * The result is the slice of the parser's own accumulator that this call
     * produced, from [pendingStart] to its new length — never a fresh empty
     * builder. The previous version returned a `local` buffer that nothing ever
     * wrote to, while every helper appended to `output`, so the terminal
     * parsed and stored all of a shell's output and then displayed none of it.
     * The connection stayed established and the screen stayed blank, which is
     * exactly what was observed.
     */
    fun feed(data: ByteArray, offset: Int, len: Int): android.text.Spanned {
        val pendingStart = output.length
        var i = offset
        val end = offset + len
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            i++

            if (state == State.GROUND && handleGroundByte(b)) {
                continue
            }

            when (state) {
                State.GROUND -> when (b) {
                    0x1B -> state = State.ESC // ESC
                    0x07 -> { /* BEL */ }
                    0x08 -> backspace()
                    0x0A -> newline() // LF
                    0x0D -> { /* CR - ignored */ }
                    0x0C -> clearScreen() // FF
                    0x09 -> append('\t') // TAB
                    else -> append(b.toChar())
                }
                State.ESC -> when (b) {
                    0x5B -> { state = State.CSI; params.clear(); paramBuf = ""; intermediate = 0 } // [
                    0x5D -> { state = State.OSC; paramBuf = "" } // ]
                    0x28, 0x29, 0x2A, 0x2B, 0x2D, 0x2E, 0x2F -> state = State.CHARSET
                    0x37 -> state = State.GROUND // DECSC - save cursor
                    0x38 -> state = State.GROUND // DECRC - restore cursor
                    0x63 -> { reset() } // RIS
                    else -> { state = State.GROUND; append(b.toChar()) }
                }
                State.CSI -> when (b) {
                    in 0x30..0x39 -> paramBuf += b.toChar() // digit
                    0x3B -> { // ;
                        params.add(paramBuf.toIntOrNull() ?: 0)
                        paramBuf = ""
                    }
                    in 0x3A..0x3F -> intermediate = b // : ; < = > ?
                    in 0x40..0x7E -> { // @ A..Z [ \ ] ^ _ ` a..z { | } ~
                        params.add(paramBuf.toIntOrNull() ?: 0)
                        handleCSI(b.toChar())
                        state = State.GROUND
                    }
                    else -> { state = State.GROUND }
                }
                State.OSC -> when (b) {
                    0x1B -> state = State.OSC_STRING // ESC inside OSC
                    0x07 -> { state = State.GROUND; paramBuf = "" } // BEL terminates OSC
                    in 0x08..0x0C, in 0x0E..0x1F -> { /* ignore control */ }
                    else -> paramBuf += b.toChar()
                }
                State.OSC_STRING -> when (b) {
                    0x5C -> { state = State.GROUND; paramBuf = "" } // ST
                    0x07 -> { state = State.GROUND; paramBuf = "" } // BEL
                    else -> state = State.OSC
                }
                State.CHARSET -> state = State.GROUND
            }
        }
        // Return only what this call produced. A substring of `output` carries the
        // spans set while those bytes were parsed, because the spans are
        // recorded over exactly these positions.
        return if (output.length > pendingStart) {
            SpannableStringBuilder(output, pendingStart, output.length)
        } else {
            SpannableStringBuilder()
        }
    }

    private fun handleCSI(finalChar: Char) {
        val cmd = finalChar
        val p = params
        val first = p.firstOrNull() ?: 1
        when (cmd) {
            'm' -> handleSGR(p) // SGR
            'H', 'f' -> { /* CUP/HVP - cursor position */ }
            'J' -> when (first) { 2 -> clearScreen(); else -> { } } // ED
            'K' -> { /* EL - erase in line */ }
            'A' -> { /* CUU - cursor up */ }
            'B' -> { /* CUD - cursor down */ }
            'C' -> { /* CUF - cursor forward */ }
            'D' -> { /* CUB - cursor back */ }
            'h', 'l' -> { /* SM/RM - set/reset mode */ }
            'c' -> { /* DA - device attributes */ }
            'n' -> { /* DSR - device status report */ }
            else -> { /* unhandled */ }
        }
    }

    private fun handleSGR(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            when (val p = params[i].toInt()) {
                0 -> { // reset
                    currentFg = null; currentBg = null; bold = false; underline = false; reverse = false; concealed = false
                }
                1 -> bold = true
                2 -> { /* faint */ }
                3 -> { /* italic */ }
                4 -> underline = true
                7 -> reverse = true
                8 -> concealed = true
                22 -> bold = false
                24 -> underline = false
                27 -> reverse = false
                28 -> concealed = false
                in 30..37 -> currentFg = p - 30 // standard fg
                39 -> currentFg = null
                in 40..47 -> currentBg = p - 40 // standard bg
                49 -> currentBg = null
                in 90..97 -> currentFg = p - 90 + 8 // bright fg
                in 100..107 -> currentBg = p - 100 + 8 // bright bg
                38 -> { // extended fg
                    if (i + 1 < params.size && params[i + 1] == 5) {
                        currentFg = params[i + 2]; i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2) {
                        // RGB - not implemented
                        i += 4
                    }
                }
                48 -> { // extended bg
                    if (i + 1 < params.size && params[i + 1] == 5) {
                        currentBg = params[i + 2]; i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2) {
                        i += 4
                    }
                }
            }
            i++
        }
    }

    // Output helpers

    private fun append(c: Char) {
        if (concealed) return
        val start = output.length
        output.append(c)
        applyAttributes(start, output.length)
    }

    private fun append(text: String) {
        if (concealed) return
        val start = output.length
        output.append(text)
        applyAttributes(start, output.length)
    }

    /**
     * Handles printable/control bytes while in GROUND state.
     *
     * Returns true when the byte was consumed. UTF-8 is accumulated byte by byte
     * so a prompt marker, emoji, or non-Latin filename split across TCP reads is
     * decoded as one character instead of mojibake.
     */
    private fun handleGroundByte(b: Int): Boolean {
        val carried = pendingUtf8
        if (carried != null) {
            if (isUtf8Continuation(b)) {
                val bytes = carried + byteArrayOf(b.toByte())
                if (bytes.size == utf8SequenceLength(bytes[0].toInt() and 0xFF)) {
                    pendingUtf8 = null
                    append(decodeUtf8(bytes))
                } else {
                    pendingUtf8 = bytes
                }
                return true
            }

            pendingUtf8 = null
            append(decodeUtf8(carried))
        }

        return when (b) {
            0x9B -> {
                state = State.CSI
                params.clear()
                paramBuf = ""
                intermediate = 0
                true
            }
            0x9D -> {
                state = State.OSC
                paramBuf = ""
                true
            }
            0x1B -> {
                state = State.ESC
                true
            }
            0x07 -> true // BEL
            0x08 -> {
                backspace()
                true
            }
            0x0A -> {
                newline()
                true
            }
            0x0D -> true // CR
            0x0C -> {
                clearScreen()
                true
            }
            0x09 -> {
                append('\t')
                true
            }
            in 0x00..0x1F -> true
            in 0x20..0x7E -> {
                append(b.toChar())
                true
            }
            else -> {
                if (isUtf8Lead(b)) {
                    pendingUtf8 = byteArrayOf(b.toByte())
                } else {
                    append("\uFFFD")
                }
                true
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8)

    private fun isUtf8Lead(b: Int): Boolean =
        b in 0xC2..0xF4

    private fun isUtf8Continuation(b: Int): Boolean =
        b in 0x80..0xBF

    private fun utf8SequenceLength(lead: Int): Int = when (lead) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 1
    }

    private fun applyAttributes(start: Int, end: Int) {
        if (start >= end) return
        val fg = currentFg?.let { ansiToColor(it, true) }
        val bg = currentBg?.let { ansiToColor(it, false) }
        if (fg != null) output.setSpan(ForegroundColorSpan(fg), start, end, android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        if (bg != null) output.setSpan(BackgroundColorSpan(bg), start, end, android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        if (bold) output.setSpan(StyleSpan(Typeface.BOLD), start, end, android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        if (underline) output.setSpan(StyleSpan(Typeface.ITALIC), start, end, android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        if (reverse) {
            // swap fg/bg for reverse
        }
    }

    private fun ansiToColor(idx: Int, isFg: Boolean): Int {
        val colors = if (isFg) {
            intArrayOf(
                0xFF1E1E1E.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(), // 0-3
                0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(), // 4-7
                0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(), // 8-11
                0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt()  // 12-15
            )
        } else {
            intArrayOf(
                0xFF000000.toInt(), 0xFF800000.toInt(), 0xFF008000.toInt(), 0xFF808000.toInt(),
                0xFF000080.toInt(), 0xFF800080.toInt(), 0xFF008080.toInt(), 0xFFC0C0C0.toInt(),
                0xFF808080.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
                0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
            )
        }
        return colors[idx.coerceIn(0, colors.size - 1)]
    }

    private fun backspace() {
        if (output.length > 0) output.delete(output.length - 1, output.length)
    }

    private fun newline() {
        append('\n')
    }

    private fun clearScreen() {
        output.clear()
    }
}

/**
 * Wrapper that passes all input through to the shell channel.
 */
class TerminalInputConnection(view: View, private val onCommit: (String) -> Unit) :
    InputConnectionWrapper(view.onCreateInputConnection(EditorInfo()), false) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.let { onCommit(it.toString()) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        return super.sendKeyEvent(event)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && afterLength == 0) {
            onCommit("\u0008")
            return true
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }
}
