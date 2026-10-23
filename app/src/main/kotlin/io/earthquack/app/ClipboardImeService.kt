package io.earthquack.app

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import io.earthquack.app.tailscale.TailnetRoute
import java.net.URL

/**
 * ClipboardImeService
 * ====================
 * A minimal input method service that forwards typing requests to the system/default
 * view while keeping the background clipboard listener active across all apps.
 *
 * It renders no visible UI (height 0 view), allowing the system keyboard switcher
 * or primary input flow to function seamlessly without blocking user typing.
 */
class ClipboardImeService : InputMethodService() {

    private val TAG = "ClipboardImeService"
    private lateinit var clipboardManager: android.content.ClipboardManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastLocalText: String? = null

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        checkAndPostClipboard()
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        Log.i(TAG, "IME Service created and listener attached")
        
        // Background poll — throttled when screen is off
        serviceScope.launch {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            while (isActive) {
                if (!pm.isInteractive) {
                    delay(30_000)
                    continue
                }
                checkAndPostClipboard()
                delay(10_000)
            }
        }
    }

    override fun onCreateInputView(): View {
        // Return a zero-size view so our IME doesn't hide the screen or block typing
        val dummyView = View(this)
        dummyView.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
        return dummyView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Immediately switch back to the main typing keyboard when an input field is tapped
        switchToPreviousInputMethod()
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun checkAndPostClipboard() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive) return
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (text.isBlank() || text == lastLocalText) return

        lastLocalText = text
        Log.i(TAG, "IME background clip change detected: '$text'")

        serviceScope.launch {
            postToServer(text)
        }
    }

    private fun postToServer(text: String) {
        try {
            val toSend = CryptoUtil.wrapIfNeeded(text, ServerConfig.getAesKey(this@ClipboardImeService), ServerConfig.isAesEnabled(this@ClipboardImeService))
            val url = URL("${ServerConfig.getBaseUrl(this@ClipboardImeService)}/clipboard")
            // Tailnet hosts are only reachable through the app's embedded
            // node; public/LAN hosts get NO_PROXY and dail the normal way.
            val conn = url.openConnection(
                TailnetRoute.socksAddressFor(url.host)?.let {
                    java.net.Proxy(java.net.Proxy.Type.SOCKS, it)
                } ?: java.net.Proxy.NO_PROXY
            ) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val body = JSONObject().apply {
                put("clipboard", toSend)
                put("origin", ORIGIN_PHONE)
            }.toString()

            conn.outputStream.use { os ->
                val input = body.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val code = conn.responseCode
            Log.i(TAG, "IME POST /clipboard response: $code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "IME POST failed: ${e.message}")
        }
    }
}
