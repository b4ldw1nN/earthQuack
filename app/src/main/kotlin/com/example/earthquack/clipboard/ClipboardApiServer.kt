package com.example.earthquack.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.earthquack.CryptoUtil
import com.example.earthquack.ServerConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * ClipboardApiServer — this phone's clipboard, served over HTTP.
 *
 * The desktop's clipboard bridge normally talks to a server that runs on the
 * desktop. This is the mirror image: the phone serves its own clipboard, and
 * the desktop talks to the phone. That removes the desktop daemon from the
 * clipboard path entirely — the desktop only needs the phone's tailnet
 * address.
 *
 * The contract is deliberately the same as the desktop's server, so the
 * desktop client needs no changes beyond pointing at a different host:
 *
 *   GET  /health        → {"status":"ok"}
 *   GET  /capabilities  → what this server supports
 *   GET  /clipboard     → {"clipboard":…, "origin":"phone", "version":n}
 *   POST /clipboard     → {"clipboard":…, "origin":…} → sets the phone's clipboard
 *   GET  /events        → SSE stream of clipboard changes
 *
 * Written on a plain [ServerSocket] on purpose: the app must not grow a
 * server dependency for four endpoints, and the traffic is local to the
 * device and the tailnet.
 */
class ClipboardApiServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
) {

    private val version = AtomicLong(0)
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private var serverSocket: ServerSocket? = null
    private var running = false

    /** Last text this server pushed out, so it can answer GET /clipboard. */
    @Volatile
    private var lastText: String = ""
    private var lastOrigin: String = "phone"

    /** Starts the server. Returns false when the port is taken. */
    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket(port)
            socket.reuseAddress = true
            serverSocket = socket
            running = true
            Thread({ acceptLoop(socket) }, "clipboard-api").apply { isDaemon = true }.start()
            Log.i(TAG, "clipboard API listening on :$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "clipboard API could not start on :$port — ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    /** Pushes a new clipboard value to every SSE listener. */
    fun publish(text: String, origin: String) {
        lastText = text
        lastOrigin = origin
        version.incrementAndGet()
        broadcast(eventPayload())
    }

    /** The payload a client receives: the text, wrapped when AES is on. */
    private fun eventPayload(): String {
        val wrapped = CryptoUtil.wrapIfNeeded(
            lastText,
            ServerConfig.getAesKey(context),
            ServerConfig.isAesEnabled(context),
        )
        return """{"clipboard":"${escape(wrapped)}","origin":"$lastOrigin","version":${version.get()}}"""
    }

    private fun broadcast(payload: String) {
        val frame = "event: clipboard\ndata: $payload\n\n".toByteArray()
        clients.forEach { out ->
            runCatching { out.write(frame); out.flush() }
                .onFailure { clients.remove(out) }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                Thread({ handle(client) }, "clipboard-api-conn").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            var authorization: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                if (name == "authorization") authorization = value
            }

            if (!authorized(authorization)) {
                writeJson(output, 401, """{"error":"unauthorized"}""")
                return
            }

            when {
                method == "GET" && path == "/health" ->
                    writeJson(output, 200, """{"status":"ok","service":"earthquack"}""")

                method == "GET" && path == "/capabilities" ->
                    writeJson(
                        output, 200,
                        """{"service":"earthquack","version":"${appVersion()}","aes_enabled":${ServerConfig.isAesEnabled(context)},"aes_key_fingerprint":"${fingerprint()}","clipboard_port":$port,"endpoints":["clipboard","events","health","capabilities"]}"""
                    )

                method == "GET" && path == "/clipboard" ->
                    writeJson(output, 200, eventPayload())

                method == "GET" && path == "/events" -> {
                    output.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n").toByteArray()
                    )
                    output.flush()
                    clients.add(output)
                    // Keepalive, so proxies do not drop an idle stream.
                    Thread {
                        while (running && clients.contains(output)) {
                            try {
                                Thread.sleep(15_000)
                                output.write(": keepalive\n\n".toByteArray())
                                output.flush()
                            } catch (e: Exception) {
                                break
                            }
                        }
                        clients.remove(output)
                    }.apply { isDaemon = true }.start()
                }

                method == "POST" && path == "/clipboard" -> {
                    val body = if (contentLength > 0) {
                        val buffer = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(buffer, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        String(buffer, 0, read)
                    } else ""

                    val text = runCatching {
                        val json = org.json.JSONObject(body)
                        val raw = json.optString("clipboard")
                        CryptoUtil.unwrapIfNeeded(
                            raw,
                            ServerConfig.getAesKey(context),
                            ServerConfig.isAesEnabled(context),
                        )
                    }.getOrElse {
                        writeJson(output, 400, """{"error":"bad request"}""")
                        return
                    }
                    val origin = runCatching { org.json.JSONObject(body).optString("origin", "desktop") }
                        .getOrDefault("desktop")
                    applyToPhoneClipboard(text)
                    publish(text, origin)
                    writeJson(output, 200, """{"version":${version.get()}}""")
                }

                else -> writeJson(output, 404, """{"error":"not found"}""")
            }
        }
    }

    /** Puts the incoming text into the phone's system clipboard. */
    private fun applyToPhoneClipboard(text: String) {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText("earthQuack", text))
        }.onFailure { Log.w(TAG, "could not set the phone clipboard: ${it.message}") }
    }

    private fun authorized(header: String?): Boolean {
        val expected = ServerConfig.getAuthToken(context)
        if (expected.isBlank()) return true
        return header == "Bearer $expected"
    }

    private fun writeJson(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            else -> "404 Not Found"
        }
        output.write(
            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        output.write(bytes)
        output.flush()
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("")

    private fun fingerprint(): String = runCatching {
        com.example.earthquack.DesktopCapabilities.fingerprint(ServerConfig.getAesKey(context))
    }.getOrDefault("")

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    companion object {
        private const val TAG = "ClipboardApiServer"
        const val DEFAULT_PORT = 8875

        @Volatile
        private var instance: ClipboardApiServer? = null

        /**
         * The app's clipboard API, started on first use.
         *
         * One per process: the desktop addresses the phone by its tailnet
         * address and port, so a second server on the same port could only
         * confuse things.
         */
        fun get(context: Context): ClipboardApiServer {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ClipboardApiServer(context.applicationContext).also {
                    it.start()
                    instance = it
                }
            }
        }
    }
}
