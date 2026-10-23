package io.earthquack.app.stream

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback-only HTTP server that serves one [RangeSource] as a seekable file.
 *
 * ```text
 * ExoPlayer (media3) ──HTTP 127.0.0.1:ephemeral──▶ RangeHttpServer ──▶ RangeSource
 *                       Range requests            206 + Content-Range      bounded reads
 * ```
 *
 * ## Why this exists instead of downloading the file
 *
 * The file browser used to `operations/copyfile` a whole remote video into
 * cache and hand that local file to a `VideoView`, so opening a 2 GB film cost
 * 2 GB of transfer and as much storage before the first frame. A range server
 * costs the first buffer and then whatever the viewer actually watches.
 *
 * ## Security posture
 *
 *  - **Binds to `127.0.0.1` with an ephemeral port, never `0.0.0.0`.** The
 *    socket is unreachable from any other interface, so it is not exposed on
 *    Wi-Fi, a tailnet interface, or a VPN.
 *  - **A 128-bit per-session token in the URL path.** Only this app's player
 *    knows it; a stray request without it gets the same 404 as a bad path.
 *  - **The path is validated**: it must be exactly `/<token>/<one-segment>`,
 *    so no traversal, no query parameters, no second path level.
 *  - **The token is never logged**, and the URL never leaves this object. It
 *    is not written to storage, notifications, or the share sheet.
 *
 * ## Threading
 *
 * One accept thread, a fixed pool of workers, and at most
 * [maxConcurrentRequests] in flight. A read is per-call and independent, so a
 * seek and a forward read genuinely run in parallel: there is no cursor to
 * corrupt. Responses send `Connection: close`, so each request is a fresh
 * connection and there is no keep-alive state to get wrong.
 *
 * ## Memory
 *
 * Every response streams from the remote in [chunkBytes] chunks through one
 * buffer per request. Nothing is reassembled, so a 4 GB film costs 256 KiB
 * plus whatever the OS socket buffer holds.
 */
class RangeHttpServer(
    private val source: RangeSource,
    private val contentType: String = DEFAULT_CONTENT_TYPE,
    private val chunkBytes: Int = 1024 * 1024,
    private val maxConcurrentRequests: Int = MAX_CONCURRENT,
    private val token: String = newToken(),
    private val sizeBytes: Long? = null,
) {

    /** True once [start] has bound a socket and the accept loop is running. */
    val isRunning: Boolean get() = running.get()

    /** The ephemeral port bound to, or 0 before [start]. */
    @Volatile
    var port: Int = 0
        private set

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val servedRequests = AtomicLong(0L)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers = Executors.newFixedThreadPool(WORKER_THREADS) { run ->
        Thread(run, WORKER_NAME).apply { isDaemon = true }
    }
    /** Bounds in-flight requests; a player has a probe and a seek at most. */
    private val permits = Semaphore(maxConcurrentRequests, true)
    private val lock = Any()
    private val clients = mutableSetOf<Socket>()

    /** Per-session token bytes, compared without a timing oracle. */
    private val tokenBytes: ByteArray = token.toByteArray(Charsets.UTF_8)

    /**
     * Binds the loopback socket and starts accepting connections.
     *
     * @return the port the server is listening on.
     * @throws IOException when the loopback socket cannot be bound.
     */
    fun start(): Int {
        check(!running.get()) { "start() called twice" }
        // 127.0.0.1, not InetAddress.getLocalName() or null: this must be the
        // loopback interface specifically, so a later refactor cannot widen it
        // to every interface on the device.
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        stopped.set(false)
        acceptThread = Thread(::acceptLoop, ACCEPT_NAME).apply {
            isDaemon = true
        }.also { it.start() }
        Log.i(TAG, "range server listening on 127.0.0.1:$port")
        return port
    }

    /**
     * The URL the player should open. Contains the session token, so it is
     * never logged and never persisted.
     */
    fun url(fileName: String): String = "http://$LOOPBACK:$port/$token/${encodeSegment(fileName)}"

    /** Requests served so far. Diagnostics only. */
    fun requestCount(): Long = servedRequests.get()

    /**
     * Stops accepting, closes every client socket and releases the workers.
     *
     * Idempotent: a second call does nothing, so an activity's `onStop` and a
     * player's release can both call it without guarding.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        Log.i(TAG, "range server stopping after ${servedRequests.get()} requests")
        running.set(false)

        synchronized(lock) {
            // A worker streaming into a socket that nothing reads anymore holds
            // its permit and its thread, so the sockets are closed rather than
            // left for the client to tear down.
            clients.toList().forEach { socket ->
                try {
                    socket.close()
                } catch (_: IOException) {
                    // Already gone.
                }
            }
            clients.clear()
        }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Closing twice is the same as closing once.
        }
        workers.shutdownNow()
        // The accept thread is inside accept(); closing the socket above
        // unblocks it. Joined with a timeout so a stuck thread cannot hang
        // the caller — it is a daemon thread and dies with the process.
        acceptThread?.let { thread ->
            runCatching { thread.join(JOIN_MILLIS) }
        }
        acceptThread = null
        Log.i(TAG, "range server stopped")
    }

    /** Reads one request head per connection, then hands it to a worker. */
    private fun acceptLoop() {
        val listener = serverSocket ?: return
        while (running.get() && !listener.isClosed) {
            val client = try {
                listener.accept()
            } catch (_: IOException) {
                // Expected on stop(): the socket is closed underneath us.
                return
            }
            synchronized(lock) { clients.add(client) }
            val handler = Runnable {
                try {
                    serve(client)
                } catch (_: Throwable) {
                    // A failure in one request must never take the accept
                    // loop, or the next seek, down with it.
                } finally {
                    synchronized(lock) { clients.remove(client) }
                    close(client)
                }
            }
            try {
                workers.execute(handler)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Executor already shut down: this connection is useless.
                synchronized(lock) { clients.remove(client) }
                close(client)
            }
        }
    }

    private fun serve(client: Socket) {
        // A request head must arrive promptly; a player that opens a socket and
        // then says nothing holds a worker otherwise.
        client.soTimeout = HEAD_TIMEOUT_MILLIS
        val input = client.getInputStream()
        val out = BufferedOutputStream(client.getOutputStream(), STREAM_BUFFER)

        val request = try {
            readRequest(input)
        } catch (e: SocketTimeoutException) {
            System.err.println("EQ_STREAM_HTTP: readRequest SocketTimeoutException: $e")
            return
        } catch (e: IOException) {
            System.err.println("EQ_STREAM_HTTP: readRequest IOException: $e")
            return
        } ?: return

        System.err.println("EQ_STREAM_HTTP: incoming req: ${request.method} Range=${request.header("range")}")

        if (request.method != "GET" && request.method != "HEAD") {
            System.err.println("EQ_STREAM_HTTP: method not allowed: ${request.method}")
            writeResponse(out, STATUS_METHOD_NOT_ALLOWED, request.method == "HEAD", close = true)
            return
        }

        if (!pathAllowed(request.target)) {
            System.err.println("EQ_STREAM_HTTP: 404 path not allowed: ${request.target}")
            // Same answer for a bad token and a bad path: an outside caller
            // learns nothing from the difference.
            writeResponse(out, STATUS_NOT_FOUND, request.method == "HEAD", close = true)
            return
        }

        val isHead = request.method == "HEAD"
        val range = request.header("range")
        if (range != null && !range.startsWith(RANGE_UNIT, ignoreCase = true)) {
            System.err.println("EQ_STREAM_HTTP: bad range header: $range")
            // A range unit this server does not speak: refusing it is legal and
            // honest; pretending it was not asked for would serve the whole body.
            writeRangeProblem(out, isHead, STATUS_BAD_REQUEST, null)
            return
        }

        if (!acquirePermit()) {
            System.err.println("EQ_STREAM_HTTP: 503 too busy (no permit)")
            writeResponse(out, TOO_BUSY, isHead, close = true)
            return
        }
        try {
            servedRequests.incrementAndGet()
            when (val size = sourceSize()) {
                is SizeResult.Failed -> {
                    System.err.println("EQ_STREAM_HTTP: 500 remote failed (sourceSize failed)")
                    // The remote could not even be measured. That is not a
                    // "no size" answer: one means a backend that reports none,
                    // the other means the remote is gone or the credentials
                    // expired. Only the second is worth a 500.
                    writeResponse(out, STATUS_REMOTE_FAILED, isHead, close = true)
                }
                is SizeResult.Unknown -> {
                    System.err.println("EQ_STREAM_HTTP: size unknown, serving chunked")
                    serveUnknownLength(out, isHead, range)
                }
                is SizeResult.Known -> {
                    if (range == null) {
                        System.err.println("EQ_STREAM_HTTP: no range header, serving whole file (size=${size.bytes})")
                        serveWholeFile(out, isHead, size.bytes)
                        return
                    }
                    val parsed = parseRange(range, size.bytes)
                    if (parsed == null) {
                        System.err.println("EQ_STREAM_HTTP: unsatisfiable range $range (size=${size.bytes})")
                        writeRangeProblem(out, isHead, STATUS_UNSATISFIABLE, size.bytes)
                        return
                    }
                    System.err.println("EQ_STREAM_HTTP: serving range ${parsed.first}-${parsed.second} of ${size.bytes}")
                    serveRange(out, isHead, parsed.first, parsed.second, size.bytes)
                }
            }
        } catch (e: RangeReadException) {
            System.err.println("EQ_STREAM_HTTP: RangeReadException during serve: $e")
            Log.w(TAG, "remote read failed: ${e.javaClass.simpleName}")
            writeResponse(out, STATUS_REMOTE_FAILED, isHead, close = true)
        } finally {
            permits.release()
        }
    }


    /**
     * HEAD/GET with no `Range`: the whole object, by definition 200.
     *
     * ExoPlayer's first request is exactly this: it learns the length from the
     * response and starts reading sequentially. It does not read the whole
     * body — TCP backpressure stops the reads — so this is not a disguised
     * full download: the player asks for the next bytes only when they are
     * needed.
     */
    private fun serveWholeFile(out: OutputStream, isHead: Boolean, size: Long) {
        val headers = commonHeaders()
            .plus("Content-Length" to size.toString())
        writeHead(out, STATUS_OK, headers)
        out.flush()
        if (isHead) return
        finishStream(out, 0, size - 1)
        out.flush()
    }

    /**
     * A range the backend cannot quantify. Served as a chunked 200, because
     * that is the only truthful answer: no length, no `Content-Range`.
     *
     * The caller is expected to have refused to stream when the size is
     * unknown; this is the fallback for a race between the size check and the
     * first request.
     */
    private fun serveUnknownLength(out: OutputStream, isHead: Boolean, range: String?) {
        if (range != null) {
            // Without a total length, "is this range satisfiable?" has no
            // answer, so the client is told so rather than given a guess.
            writeRangeProblem(out, isHead, STATUS_UNSATISFIABLE, null)
            return
        }
        val headers = commonHeaders()
            .plus("Transfer-Encoding" to "chunked")
        writeHead(out, STATUS_OK, headers)
        if (isHead) {
            out.flush()
            return
        }
        var position = 0L
        while (running.get()) {
            val data = try {
                source.readAt(position, chunkBytes)
            } catch (_: RangeReadException) {
                // The body has started, so the failure cannot become a status
                // line any more: the only thing to do is end the response.
                writeChunk(out, ByteArray(0), last = true)
                out.flush()
                return
            }
            if (data.isEmpty()) break
            writeChunk(out, data, last = false)
            position += data.size
            if (data.size < chunkBytes) break
        }
        writeChunk(out, ByteArray(0), last = true)
        out.flush()
    }

    /** The one case the player actually relies on: a single satisfiable range. */
    private fun serveRange(out: OutputStream, isHead: Boolean, start: Long, end: Long, size: Long) {
        val headers = commonHeaders()
            .plus("Content-Range" to "bytes $start-$end/$size")
            .plus("Content-Length" to (end - start + 1).toString())
        writeHead(out, STATUS_PARTIAL, headers)
        out.flush()
        if (isHead) return
        finishStream(out, start, end)
        out.flush()
    }

    /**
     * Reads `[start, end]` from the remote and writes it out.
     *
     * One buffer, reused for the whole response, so the memory for this
     * response is [chunkBytes] regardless of how long the range is.
     */
    private fun finishStream(out: OutputStream, start: Long, end: Long) {
        if (end < start) return
        var position = start
        while (position <= end) {
            if (!running.get()) return
            val want = minOf(chunkBytes.toLong(), end - position + 1).toInt()
            val data = try {
                source.readAt(position, want)
            } catch (_: RangeReadException) {
                // The status line and the first bytes are already on the wire,
                // so this cannot become a 500: the response simply ends short
                // and the player reports a read error, which it can retry.
                return
            }
            if (data.isEmpty()) return
            try {
                out.write(data)
            } catch (_: IOException) {
                // The player went away mid-seek. Nothing to clean up: the
                // socket is closed by the worker's finally.
                return
            }
            position += data.size
            // Fewer bytes than asked for means the end of the object.
            if (data.size < want) return
        }
    }

    // ── request reading ────────────────────────────────────────────────────

    private data class Request(val method: String, val target: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    }

    @Throws(IOException::class)
    private fun readRequest(input: java.io.InputStream): Request? {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), HEAD_READER)
        var requestLine: String
        // Skip blank lines a client may send before a request (RFC 7230).
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isNotBlank()) {
                requestLine = line
                break
            }
        }
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split <= 0) continue
            headers[line.substring(0, split).trim().lowercase(Locale.US)] =
                line.substring(split + 1).trim()
        }
        return Request(method, target, headers)
    }

    /**
     * Whether [target] is a request this server will answer.
     *
     * Exactly `/<token>/<single-segment>`: the token, then the file, then
     * nothing. No `..`, no second path level, no query string, and a token
     * that does not match is indistinguishable from a missing file.
     */
    private fun pathAllowed(target: String): Boolean {
        val path = target.substringBefore('?')
        if (!path.startsWith("/")) return false
        val segments = path.substring(1).split('/')
        if (segments.size != 2) return false
        val (given, file) = segments
        if (file.isEmpty() || file.contains("..")) return false
        return constantTimeEquals(given)
    }

    private fun constantTimeEquals(given: String): Boolean =
        MessageDigest.isEqual(
            given.toByteArray(Charsets.UTF_8),
            tokenBytes
        )

    // ── response writing ───────────────────────────────────────────────────

    private fun commonHeaders(): MutableMap<String, String> =
        LinkedHashMap<String, String>().apply {
            put("Accept-Ranges", ACCEPT_RANGES)
            put("Content-Type", contentType)
            put("Cache-Control", NO_CACHE)
            put("Connection", CLOSE)
            put("Server", SERVER)
        }

    private fun writeHead(out: OutputStream, status: Int, headers: Map<String, String>) {
        val reason = REASONS[status] ?: "Unknown"
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        headers.forEach { (key, value) ->
            builder.append(key).append(": ").append(value).append("\r\n")
        }
        builder.append("\r\n")
        out.write(builder.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(out: OutputStream, status: Int, headOnly: Boolean, close: Boolean) {
        val body = if (close) REASON_TEXT[status].orEmpty().toByteArray(Charsets.UTF_8) else ByteArray(0)
        val headers = commonHeaders()
        if (body.isNotEmpty()) {
            headers["Content-Length"] = body.size.toString()
            headers["Content-Type"] = "text/plain; charset=utf-8"
        }
        writeHead(out, status, headers)
        if (!headOnly) out.write(body)
        out.flush()
    }

    /**
     * A 4xx that also carries the unsatisfied-range header, which for a known
     * total length is the bytes-star form.
     */
    private fun writeRangeProblem(out: OutputStream, headOnly: Boolean, status: Int, size: Long?) {
        val headers = commonHeaders().apply {
            size?.let { put("Content-Range", "bytes */$it") }
            put("Content-Length", "0")
        }
        writeHead(out, status, headers)
        out.flush()
    }

    /** One chunked-transfer frame. `last` writes the terminating chunk. */
    private fun writeChunk(out: OutputStream, data: ByteArray, last: Boolean) {
        if (last) {
            out.write(ZERO_CHUNK.toByteArray(Charsets.UTF_8))
        } else {
            out.write(Integer.toHexString(data.size).toByteArray(Charsets.UTF_8))
            out.write(CRLF)
            out.write(data)
            out.write(CRLF)
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * The remote's reported size, in the three shapes it can arrive in.
     *
     * "No size" and "could not ask" are deliberately different answers: the
     * first is a backend that does not report a length (a stream upload, for
     * instance) and the second is a remote that is unreachable or whose
     * credentials expired. Collapsing them would turn a dead remote into a
     * working one with no length.
     */
    private sealed interface SizeResult {
        /** The backend reported [bytes]. */
        data class Known(val bytes: Long) : SizeResult

        /** The backend reported no length at all. */
        data object Unknown : SizeResult

        /** Asking the remote failed. */
        data object Failed : SizeResult
    }

    private fun sourceSize(): SizeResult {
        return try {
            if (sizeBytes != null) {
                return SizeResult.Known(sizeBytes)
            }
            when (val size = source.size()) {
                null -> SizeResult.Unknown
                else -> SizeResult.Known(size)
            }
        } catch (_: RangeReadException) {
            SizeResult.Failed
        } catch (_: Exception) {
            SizeResult.Failed
        }
    }

    /**
     * Takes a permit, waiting a bounded time.
     *
     * A timeout rather than no wait: a request that cannot be served promptly
     * gets a clear 503 rather than a hang, and the player then reports a
     * playback error instead of spinning forever.
     */
    private fun acquirePermit(): Boolean =
        permits.tryAcquire(PERMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun close(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed by the peer or by stop().
        }
    }

    /**
     * Parses a single `bytes=first-last` range against a known [size].
     *
     * @return the inclusive `first,last` pair, or null when the range is not
     *   satisfiable. Only single ranges: a multi-range header is refused with
     *   416 rather than answered as a multipart body, which the player never
     *   asks for and which would need a dialect this server does not have.
     */
    private fun parseRange(header: String, size: Long): Pair<Long, Long>? {
        val spec = header.substring(RANGE_UNIT.length).trim()
        if (spec.isEmpty()) return null
        if (spec.contains(',')) return null

        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()

        val start: Long
        val end: Long
        if (first.isEmpty()) {
            // Suffix form: the last N bytes.
            val suffix = last.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = (size - suffix).coerceAtLeast(0)
            end = size - 1
        } else {
            start = first.toLongOrNull() ?: return null
            end = if (last.isEmpty()) size - 1 else last.toLongOrNull() ?: return null
        }
        if (start < 0 || start >= size || end < start) return null
        return start to end.coerceAtMost(size - 1)
    }

    companion object {
        private const val TAG = "RangeHttpServer"

        /** The loopback address. Deliberately a constant, not a lookup. */
        const val LOOPBACK = "127.0.0.1"

        private const val BACKLOG = 8
        private const val WORKER_THREADS = 4
        private const val MAX_CONCURRENT = 4
        private const val ACCEPT_NAME = "eq-range-accept"
        private const val WORKER_NAME = "eq-range-worker"
        private const val STREAM_BUFFER = 64 * 1024
        private const val HEAD_READER = 8 * 1024
        private const val HEAD_TIMEOUT_MILLIS = 10_000
        private const val JOIN_MILLIS = 2_000L
        private const val PERMIT_TIMEOUT_SECONDS = 8L

        const val ACCEPT_RANGES = "bytes"
        private const val RANGE_UNIT = "bytes="
        private const val DEFAULT_CONTENT_TYPE = "video/mp4"
        private const val NO_CACHE = "no-store"
        private const val CLOSE = "close"
        private const val SERVER = "earthquack-range"
        private const val ZERO_CHUNK = "0\r\n\r\n"
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        private const val STATUS_OK = 200
        private const val STATUS_PARTIAL = 206
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_UNSATISFIABLE = 416
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_METHOD_NOT_ALLOWED = 405
        private const val STATUS_REMOTE_FAILED = 500
        private const val TOO_BUSY = 503

        private val REASONS = mapOf(
            STATUS_OK to "OK",
            STATUS_PARTIAL to "Partial Content",
            400 to "Bad Request",
            STATUS_NOT_FOUND to "Not Found",
            STATUS_METHOD_NOT_ALLOWED to "Method Not Allowed",
            STATUS_UNSATISFIABLE to "Range Not Satisfiable",
            STATUS_REMOTE_FAILED to "Remote Read Failed",
            TOO_BUSY to "Too Many Requests"
        )

        /** A short body for the error statuses that do not negotiate. */
        private val REASON_TEXT = mapOf(
            STATUS_NOT_FOUND to "not found",
            STATUS_METHOD_NOT_ALLOWED to "method not allowed",
            TOO_BUSY to "too many concurrent requests",
            STATUS_BAD_REQUEST to "bad request"
        )

        /** 128 bits from the platform CSPRNG, hex encoded. */
        private fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val hex = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                if (value < 0x10) hex.append('0')
                hex.append(Integer.toHexString(value))
            }
            return hex.toString()
        }

        /**
         * Percent-encodes a file name for the URL path.
         *
         * Keeps `A-Za-z0-9._-` (a dot is legal in a path segment and is what a
         * video's extension consists of) and encodes everything else, so a
         * name with spaces, brackets or non-ASCII characters survives intact.
         */
        fun encodeSegment(segment: String): String {
            val out = StringBuilder()
            val bytes = segment.toByteArray(Charsets.UTF_8)
            for (byte in bytes) {
                val char = byte.toInt().toChar()
                if ((char in 'A'..'Z') || (char in 'a'..'z') ||
                    (char in '0'..'9') || char == '.' || char == '-' || char == '_'
                ) {
                    out.append(char)
                } else {
                    out.append('%')
                    val hex = Integer.toHexString(byte.toInt() and 0xFF)
                    if (hex.length == 1) out.append('0')
                    out.append(hex.uppercase(Locale.US))
                }
            }
            return out.toString()
        }
    }
}
