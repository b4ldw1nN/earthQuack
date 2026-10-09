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
import kotlinx.coroutines.channels.Channel
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

    private val inputChannel = Channel<ByteArray>(64)
    private var isConnected = false

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

        // Input field: intercepts soft keyboard
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_UNSPECIFIED) {
                val text = binding.inputField.text.toString()
                if (text.isNotEmpty()) {
                    writeToShell(text + "\n")
                    binding.inputField.text.clear()
                }
                true
            } else false
        }

        // Connect button
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnSend.setOnClickListener {
            val text = binding.inputField.text.toString()
            if (text.isNotEmpty()) {
                writeToShell(text + "\n")
                binding.inputField.text.clear()
            }
        }

        // Handle orientation/window resize
        binding.terminalScroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (isConnected) {
                val w = binding.terminalLog.width
                val h = binding.terminalLog.height
                if (w > 0 && h > 0) {
                    val cols = maxOf(1, w / (binding.terminalLog.paint.measureText("M").toInt()))
                    val rows = maxOf(1, h / binding.terminalLog.lineHeight)
                    shell?.resize(rows, cols)
                }
            }
        }

        connect()
    }

    private fun connect() {
        Log.i("TerminalFragment", "connect() called for profile: ${profile?.name}")
        val p = profile ?: return
        lifecycleScope.launch {
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
            try {
                // Open shell with PTY
                val ch = conn.openShell(
                    onData = { data, offset, len ->
                        val text = vtParser.feed(data, offset, len)
                        requireActivity().runOnUiThread {
                            appendToLog(text)
                        }
                    },
                    rows = 24,
                    cols = 80
                )
                shell = ch
                isConnected = true

                requireActivity().runOnUiThread {
                    binding.statusText.text = "Connected"
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    binding.inputField.isEnabled = true
                    binding.inputField.requestFocus()
                }

                // Start reading stdin from the input channel
                lifecycleScope.launch {
                    for (bytes in inputChannel) {
                        ch.stdin.offer(bytes, 0, bytes.size)
                    }
                }
            } catch (e: Throwable) {
                Log.e("TerminalFragment", "Failed to open shell", e)
                val msg = when {
                    e.message?.contains("Closed", ignoreCase = true) == true ->
                        "Shell channel closed by server. The SSH server may be configured to deny shell access (e.g., ForceCommand internal-sftp, PermitTTY no, or user shell set to nologin). Check server's sshd_config and user shell."
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Connection timed out opening shell channel"
                    e.message?.contains("auth", ignoreCase = true) == true ->
                        "Authentication failed"
                    else ->
                        "Terminal error: ${e.message}"
                }
                requireActivity().runOnUiThread {
                    showError(msg)
                }
            }
        }
    }

    private fun writeToShell(text: String) {
        inputChannel.trySend(text.toByteArray(Charsets.UTF_8))
    }

    private fun disconnect() {
        isConnected = false
        inputChannel.close()
        shell?.close()
        connection?.close()
        connection = null
        shell = null
        requireActivity().runOnUiThread {
            binding.statusText.text = "Disconnected"
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
            binding.inputField.isEnabled = false
        }
    }

    private fun appendToLog(spanned: android.text.Spanned) {
        val current = binding.terminalLog.text as SpannableStringBuilder
        current.append(spanned)
        binding.terminalLog.text = current
        binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
    }

    private fun showError(msg: String) {
        binding.statusText.text = "Error: $msg"
        binding.btnConnect.isEnabled = true
    }

    override fun onDestroyView() {
        disconnect()
        _binding = null
        super.onDestroyView()
    }

    companion object {
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

    private enum class State { GROUND, ESC, CSI, OSC, OSC_STRING }

    private var state = State.GROUND
    private val params = mutableListOf<Int>()
    private var paramBuf = ""
    private var intermediate = 0
    private val output = SpannableStringBuilder()

    private var currentFg: Int? = null
    private var currentBg: Int? = null
    private var bold = false
    private var underline = false
    private var reverse = false

    /**
     * Feeds raw bytes, returns styled text ready to append.
     */
    fun feed(data: ByteArray, offset: Int, len: Int): android.text.Spanned {
        val local = SpannableStringBuilder()
        var i = offset
        val end = offset + len
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            i++
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
                    0x37 -> { /* DECSC - save cursor */ }
                    0x38 -> { /* DECRC - restore cursor */ }
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
            }
        }
        return local
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
                    currentFg = null; currentBg = null; bold = false; underline = false; reverse = false
                }
                1 -> bold = true
                2 -> { /* faint */ }
                3 -> { /* italic */ }
                4 -> underline = true
                7 -> reverse = true
                22 -> bold = false
                24 -> underline = false
                27 -> reverse = false
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
        val start = output.length
        output.append(c)
        applyAttributes(start, output.length)
    }

    private fun append(text: String) {
        val start = output.length
        output.append(text)
        applyAttributes(start, output.length)
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