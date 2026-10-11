package com.example.earthquack.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * The keyboard sink for a Termux-style terminal.
 *
 * The terminal is a scrolling log of what the PTY echoes back, so this field is
 * invisible and holds no visible text of its own — the characters the user
 * types appear in the terminal because the remote shell echoes them, exactly as
 * on any real terminal.
 *
 * That rules out the "type a whole line, then press send" model: an interactive
 * shell must see each keystroke the instant it is pressed, so Backspace, Ctrl+C
 * and Tab act on the line as it is being built. So every change the keyboard
 * makes is mirrored to the shell *immediately* as raw bytes, and this field is
 * kept only as an invisible mirror of the shell's own line buffer.
 *
 * The subtlety that makes this work across keyboards is that the IME must be
 * allowed to insert normally. An earlier version swallowed each commit and left
 * the editable empty; the keyboard's cursor then ran ahead of the (empty) field
 * and it stopped accepting input after a single character. Here the text lands
 * in the editable like any EditText, a [TextWatcher] diffs the change and
 * forwards exactly those bytes, and the field is cleared only on Enter.
 *
 * Enter is a carriage return (0x0D) and Backspace/Delete is 0x7F — the bytes a
 * PTY line discipline acts on — not a newline or a literal space.
 */
class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    /** Receives the raw bytes for every keystroke; writes go to the shell. */
    var onBytes: ((ByteArray) -> Unit)? = null

    // Where the last text change began, how much old text it replaced, and how
    // much new text it inserted. Captured in onTextChanged, read in
    // afterTextChanged where the new content is available.
    private var changeStart = 0
    private var changeBefore = 0
    private var changeCount = 0

    // True while this class is mutating the editable itself (clearing on
    // Enter), so the watcher can tell "the user typed" apart from "we just
    // emptied the mirror" and never forward our own edits back to the shell.
    private var selfEdit = false

    init {
        // This field is the IME's only legitimate target. Without both flags it
        // is easy for a sibling (the terminal log TextView) to be picked as the
        // served view, and then every keystroke lands there instead of reaching
        // the shell. showSoftInputOnFocus makes raising the keyboard automatic
        // on focus so a single tap is enough.
        isFocusableInTouchMode = true
        setShowSoftInputOnFocus(true)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                changeStart = start
                changeBefore = before
                changeCount = count
            }

            override fun afterTextChanged(s: Editable?) {
                if (selfEdit) return
                val text = s?.toString().orEmpty()
                val inserted = text.substring(
                    changeStart,
                    (changeStart + changeCount).coerceAtMost(text.length)
                )
                if (changeBefore > 0) {
                    onBytes?.invoke(ByteArray(changeBefore) { BACKSPACE_BYTE })
                }
                if (inserted.isNotEmpty()) {
                    forward(inserted)
                }
            }
        })
    }

    /** Forwards [text] to the shell, mapping a committed newline to CR. */
    private fun forward(text: String) {
        val sink = onBytes ?: return
        if (text == "\n") {
            sink(byteArrayOf(ENTER_BYTE))
        } else {
            sink(text.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Empties the invisible mirror without forwarding anything.
     *
     * Called after Enter so the next command starts fresh, and after a control
     * key (Ctrl+C, Ctrl+D) that resets the shell's line buffer, so the mirror
     * cannot drift out of step with what the shell is actually holding.
     */
    fun clearMirror() {
        if (text.isNullOrEmpty()) return
        selfEdit = true
        setText("")
        setSelection(0)
        selfEdit = false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // EditText's connection is never null in practice, but the platform
        // signature is @Nullable so Kotlin needs the assertion.
        val base = super.onCreateInputConnection(outAttrs)!!
        // No fullscreen/extract editor: it would open its own large text box
        // and swallow the raw key events this relies on.
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(base)
    }

    private inner class TerminalInputConnection(
        target: InputConnection
    ) : InputConnectionWrapper(target, false) {

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Soft keyboards model Backspace as "delete the char before the
            // cursor". The mirror is usually empty by the time this lands, so
            // forward the Backspace byte to the shell rather than mutating an
            // empty field.
            if (beforeLength > 0 && afterLength == 0) {
                onBytes?.invoke(ByteArray(beforeLength) { BACKSPACE_BYTE })
                return true
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            val e = event ?: return super.sendKeyEvent(event)
            if (e.action == KeyEvent.ACTION_DOWN) {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onBytes?.invoke(byteArrayOf(ENTER_BYTE))
                        clearMirror()
                        return true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        onBytes?.invoke(byteArrayOf(BACKSPACE_BYTE))
                        return true
                    }
                }
            }
            return super.sendKeyEvent(e)
        }
    }

    private companion object {
        /** Carriage return — the byte the Enter key sends to a PTY. */
        const val ENTER_BYTE: Byte = 0x0D

        /** DEL — the byte Backspace/Delete send to a PTY. */
        const val BACKSPACE_BYTE: Byte = 0x7F
    }
}
