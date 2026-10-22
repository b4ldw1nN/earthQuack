package com.example.earthquack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentTerminalBinding
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.SshServices
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

/**
 * An interactive SSH terminal powered by Termux's TerminalView & TerminalEmulator.
 */
class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private var session: TerminalSession? = null
    private var profile: ConnectionProfile? = null

    private var ctrlActive = false
    private var altActive = false

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

        val termView = binding.terminalView
        termView.setTextSize((13 * resources.displayMetrics.scaledDensity).toInt())
        termView.setTypeface(Typeface.MONOSPACE)

        termView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale

            override fun onSingleTapUp(e: MotionEvent?) {
                termView.requestFocus()
                val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                imm?.showSoftInput(termView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, s: com.termux.terminal.TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onLongPress(event: MotionEvent?): Boolean = false

            override fun readControlKey(): Boolean {
                val state = ctrlActive
                if (state) {
                    ctrlActive = false
                    updateModifierButtons()
                }
                return state
            }

            override fun readAltKey(): Boolean {
                val state = altActive
                if (state) {
                    altActive = false
                    updateModifierButtons()
                }
                return state
            }

            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: com.termux.terminal.TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
            override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
            override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
            override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, ex: Exception?) { Log.e(tag, message, ex) }
            override fun logStackTrace(tag: String?, ex: Exception?) { Log.e(tag, "Stack trace", ex) }
        })

        session = TerminalSessions.of(resolved.id)
        session?.listener = object : TerminalSession.Listener {
            override fun onOutput() {
                // Instantly update TerminalView on the UI thread when new characters arrive
                _binding?.terminalView?.post {
                    _binding?.terminalView?.onScreenUpdated()
                }
            }

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

            override fun onCopyText(text: String) {
                val clipManager = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                clipManager?.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }

            override fun onPasteText() {
                val clipManager = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                val text = clipManager?.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    session?.termSession?.write(text)
                }
            }
        }

        termView.attachSession(session!!.termSession)

        wireButtons()
        wireExtraKeys()
        applyKeyboardInset()

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
        }
    }

    private fun wireButtons() {
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
    }

    private fun wireExtraKeys() {
        binding.keyEsc.setOnClickListener {
            session?.termSession?.write(byteArrayOf(27), 0, 1)
        }
        binding.keyTab.setOnClickListener {
            session?.termSession?.write(byteArrayOf(9), 0, 1)
        }
        binding.keyCtrl.setOnClickListener {
            ctrlActive = !ctrlActive
            updateModifierButtons()
        }
        binding.keyAlt.setOnClickListener {
            altActive = !altActive
            updateModifierButtons()
        }
        binding.keyCtrlC.setOnClickListener {
            session?.termSession?.write(byteArrayOf(3), 0, 1)
        }
        binding.keyLeft.setOnClickListener {
            val seq = byteArrayOf(27, '['.code.toByte(), 'D'.code.toByte())
            session?.termSession?.write(seq, 0, seq.size)
        }
        binding.keyDown.setOnClickListener {
            val seq = byteArrayOf(27, '['.code.toByte(), 'B'.code.toByte())
            session?.termSession?.write(seq, 0, seq.size)
        }
        binding.keyUp.setOnClickListener {
            val seq = byteArrayOf(27, '['.code.toByte(), 'A'.code.toByte())
            session?.termSession?.write(seq, 0, seq.size)
        }
        binding.keyRight.setOnClickListener {
            val seq = byteArrayOf(27, '['.code.toByte(), 'C'.code.toByte())
            session?.termSession?.write(seq, 0, seq.size)
        }
    }

    private fun updateModifierButtons() {
        val b = _binding ?: return
        val activeColor = ContextCompat.getColor(requireContext(), R.color.eq_primary)
        val normalColor = Color.parseColor("#21262D")
        b.keyCtrl.setBackgroundColor(if (ctrlActive) activeColor else normalColor)
        b.keyAlt.setBackgroundColor(if (altActive) activeColor else normalColor)
    }

    private fun applyKeyboardInset() {
        val root = _binding?.root ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val bottom = if (ime.bottom > 0) ime.bottom else navBars.bottom
            val bar = _binding?.extraKeysBar
            if (bar != null) {
                val lp = bar.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom
                    bar.layoutParams = lp
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun connect() {
        val target = profile ?: return
        val held = session ?: TerminalSessions.of(target.id).also { session = it }
        binding.btnConnect.isEnabled = false
        binding.btnDisconnect.isEnabled = false
        showStatus(getString(R.string.terminal_state_connecting))

        held.start(
            profile = target,
            connect = { p ->
                SshServices.connectionFactory(requireContext()).connect(p)
            },
            onFailure = { message -> showError(message) },
        )
    }

    private fun disconnect() {
        binding.btnDisconnect.isEnabled = false
        session?.stop()
        showStatus(getString(R.string.terminal_state_disconnected))
        binding.btnConnect.isEnabled = true
    }

    private fun markConnected() {
        if (_binding == null) return
        binding.statusText.text = getString(R.string.terminal_state_connected)
        binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.eq_success))
        binding.btnConnect.isEnabled = false
        binding.btnDisconnect.isEnabled = true
        binding.terminalView.requestFocus()
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showStatus(text: String) {
        if (_binding == null) return
        binding.statusText.text = text
        binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.eq_terminal_muted))
    }

    private fun showError(message: String) {
        if (_binding == null) return
        binding.statusText.text = message
        binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.eq_error))
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        session?.listener = null
        _binding = null
    }

    companion object {
        private const val TAG = "TerminalFragment"
        const val ARG_PROFILE_ID = "profile_id"
        const val INITIAL_ROWS = 24
        const val INITIAL_COLS = 80

        fun newInstance(profileId: String): TerminalFragment =
            TerminalFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROFILE_ID, profileId)
                }
            }
    }
}
