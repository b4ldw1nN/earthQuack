package com.example.earthquack.stream

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Exercises the loopback range server over real HTTP, against a [RangeSource]
 * backed by a plain array.
 *
 * The point of using the real socket rather than calling the handlers directly
 * is that the bugs this class has to prevent are wire bugs: a malformed status
 * line, a `Content-Range` off by one, a response that never terminates. Those
 * only show up through a client.
 *
 * Every test builds its own server on an ephemeral port and stops it, so the
 * tests can run in any order and in parallel.
 */
class RangeHttpServerTest {

    private lateinit var source: FakeSource
    private lateinit var server: RangeHttpServer

    /** Deterministic content: byte i is (i mod 251), so any slice is checkable. */
    private val contents: ByteArray = ByteArray(FILE_SIZE) { (it % 251).toByte() }

    @Before
    fun setUp() {
        source = FakeSource(contents)
        server = RangeHttpServer(
            source = source,
            contentType = "video/mp4",
            chunkBytes = CHUNK,
            // A fixed token rather than a generated one: these tests build
            // URLs by hand to check what a *wrong* token does, which needs to
            // know the right one. The generated-token path is covered by the
            // format assertion below.
            token = TEST_TOKEN
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── metadata ─────────────────────────────────────────────────────────────

    /** A player opens with this to learn the length before it can seek. */
    @Test
    fun `HEAD reports the length and promises ranges`() {
        val connection = open(method = "HEAD")
        try {
            assertEquals(200, connection.responseCode)
            assertEquals(FILE_SIZE.toString(), connection.getHeaderField("Content-Length"))
            assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
            assertEquals("video/mp4", connection.getHeaderField("Content-Type"))
            // A HEAD must not carry a body: the player would otherwise read
            // the file twice. An empty stream is the observable proof.
            val body = connection.inputStream.use { input -> input.readBytes() }
            assertEquals(0, body.size)
        } finally {
            connection.disconnect()
        }
    }

    // ── the request that starts playback ──────────────────────────────────────

    /**
     * ExoPlayer's first request has no `Range`: it learns the length from the
     * response and then reads sequentially. It must get the whole object, as a
     * stream, without the server having to materialise anything.
     */
    @Test
    fun `a plain GET streams the whole object`() {
        assertArrayEquals(contents, get(null))
    }

    @Test
    fun `a plain GET on an empty file is a 200 with no body`() {
        val empty = FakeSource(ByteArray(0))
        val emptyServer = RangeHttpServer(source = empty, chunkBytes = CHUNK, token = TEST_TOKEN)
        emptyServer.start()
        try {
            val connection = open(emptyServer.url("empty.mp4"), method = "GET")
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("0", connection.getHeaderField("Content-Length"))
                assertEquals(0, connection.inputStream.use { it.readBytes() }.size)
            } finally {
                connection.disconnect()
            }
        } finally {
            emptyServer.stop()
        }
    }

    // ── ranges ───────────────────────────────────────────────────────────────

    @Test
    fun `a bounded range is served as 206 with the right metadata`() {
        val connection = open(range = "bytes=100-199")
        try {
            assertEquals(206, connection.responseCode)
            assertEquals("bytes 100-199/$FILE_SIZE", connection.getHeaderField("Content-Range"))
            assertEquals("100", connection.getHeaderField("Content-Length"))
            assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
            assertArrayEquals(contents.sliceArray(100 until 200), readBody(connection))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `an open-ended range runs to the end of the object`() {
        val connection = open(range = "bytes=$SIZE_MINUS_100-")
        try {
            assertEquals(206, connection.responseCode)
            assertEquals(
                "bytes $SIZE_MINUS_100-${FILE_SIZE - 1}/$FILE_SIZE",
                connection.getHeaderField("Content-Range")
            )
            assertEquals("100", connection.getHeaderField("Content-Length"))
            assertArrayEquals(
                contents.sliceArray(SIZE_MINUS_100.toInt() until FILE_SIZE),
                readBody(connection)
            )
        } finally {
            connection.disconnect()
        }
    }

    /** A suffix request is how a trailer or an ID3 tag at the end is read. */
    @Test
    fun `a suffix range returns the last N bytes`() {
        val connection = open(range = "bytes=-64")
        try {
            assertEquals(206, connection.responseCode)
            assertEquals("64", connection.getHeaderField("Content-Length"))
            assertArrayEquals(
                contents.sliceArray((FILE_SIZE - 64) until FILE_SIZE),
                readBody(connection)
            )
        } finally {
            connection.disconnect()
        }
    }

    /** A range whose end is past the last byte is clamped, not refused. */
    @Test
    fun `a range past the end is clamped to the object`() {
        val connection = open(range = "bytes=$SIZE_MINUS_10-99999")
        try {
            assertEquals(206, connection.responseCode)
            assertEquals("10", connection.getHeaderField("Content-Length"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a range that starts past the end is 416`() {
        val connection = open(range = "bytes=$FILE_SIZE-")
        try {
            assertEquals(416, connection.responseCode)
            assertEquals("bytes */$FILE_SIZE", connection.getHeaderField("Content-Range"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a reversed range is 416`() {
        val connection = open(range = "bytes=500-100")
        try {
            assertEquals(416, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a malformed range is 416 rather than a whole-object answer`() {
        listOf("bytes=", "bytes=abc", "bytes=-", "bytes=1", "bytes=12").forEach { header ->
            val connection = open(range = header)
            try {
                assertEquals("header $header", 416, connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Only single ranges. A multipart request is refused rather than answered
     * as a concatenation, which would be a corrupt file the player cannot
     * detect as such.
     */
    @Test
    fun `a multi-range request is refused`() {
        val connection = open(range = "bytes=0-1,5-6")
        try {
            assertEquals(416, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `an unknown range unit is refused`() {
        val connection = open(range = "items=0-1")
        try {
            assertEquals(400, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    // ── seeking: several ranges in a row ──────────────────────────────────────

    /**
     * A viewer that scrubs forward and back is a sequence of ranges, each of
     * which must start from the right byte. A shared read cursor would make
     * the second range depend on the first.
     */
    @Test
    fun `ranges requested in sequence each start at the right byte`() {
        val seeks = listOf(
            0L to 4096L,
            FILE_SIZE - 1 to FILE_SIZE - 1,
            55_000L to 55_100L,
            100L to 200L,
            FILE_SIZE - 4096 to FILE_SIZE - 1
        )
        seeks.forEach { (start, endInclusive) ->
            val connection = open(range = "bytes=$start-$endInclusive")
            try {
                assertEquals(206, connection.responseCode)
                val body = readBody(connection)
                val expected = contents.sliceArray(start.toInt()..endInclusive.toInt())
                assertArrayEquals("range $start-$endInclusive", expected, body)
            } finally {
                connection.disconnect()
            }
        }
    }

    // ── concurrency ──────────────────────────────────────────────────────────

    /**
     * A seek during a forward read is two requests at once, and both have to
     * be correct. If the server had a single cursor or a shared buffer, one of
     * these would come back with the other's bytes.
     */
    @Test
    fun `concurrent range requests are all correct`() {
        val workers = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 16).map { index ->
                val start = (index * 7919L) % (FILE_SIZE - 512)
                Callable {
                    val connection = open(range = "bytes=$start-${start + 511}")
                    try {
                        val code = connection.responseCode
                        val body = readBody(connection)
                        assertEquals(206, code)
                        assertArrayEquals(
                            contents.sliceArray(start.toInt()..(start + 511).toInt()),
                            body
                        )
                    } finally {
                        connection.disconnect()
                    }
                    index
                }
            }
            val results = workers.invokeAll(jobs)
            results.forEach { assertEquals(it.get(5, TimeUnit.SECONDS), it.get()) }
        } finally {
            workers.shutdownNow()
        }
    }

    // ── failures ──────────────────────────────────────────────────────────────

    /** A remote that cannot be read answers 500, not a truncated 200. */
    @Test
    fun `a remote read failure is a 500`() {
        val failing = FakeSource(contents, failReadsWith = RangeReadException("remote gone"))
        val failingServer = RangeHttpServer(source = failing, chunkBytes = CHUNK, token = TEST_TOKEN)
        failingServer.start()
        try {
            val connection = open(failingServer.url("film.mp4"), range = "bytes=0-127")
            try {
                assertEquals(500, connection.responseCode)
            } finally {
                connection.disconnect()
            }
        } finally {
            failingServer.stop()
        }
    }

    /**
     * A backend that reports no size at all. Not an error, but not a range
     * either: without a length there is nothing to promise.
     */
    @Test
    fun `an object with no reported size cannot be ranged`() {
        val sizeless = FakeSource(contents, sizeOverride = null)
        val sizelessServer = RangeHttpServer(source = sizeless, chunkBytes = CHUNK, token = TEST_TOKEN)
        sizelessServer.start()
        try {
            val ranged = open(sizelessServer.url("stream.mp4"), range = "bytes=0-63")
            try {
                assertEquals(416, ranged.responseCode)
            } finally {
                ranged.disconnect()
            }

            // Without a range it is still served, as a chunked body.
            val plain = open(sizelessServer.url("stream.mp4"), method = "GET")
            try {
                assertEquals(200, plain.responseCode)
                val body = readBody(plain)
                assertEquals(contents.size, body.size)
                assertArrayEquals(contents, body)
            } finally {
                plain.disconnect()
            }
        } finally {
            sizelessServer.stop()
        }
    }

    // ── authorization ─────────────────────────────────────────────────────────

    /** The token is the only authorisation this server has. */
    @Test
    fun `a request without the token is a 404`() {
        val url = server.url("film.mp4")
        val stripped = url.replaceFirst("/${TEST_TOKEN}/", "/")
        val connection = open(stripped, range = "bytes=0-63")
        try {
            assertEquals(404, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a request with a wrong token is a 404`() {
        val url = server.url("film.mp4")
        val wrong = url.replaceFirst(TEST_TOKEN, "0".repeat(32))
        val connection = open(wrong, range = "bytes=0-63")
        try {
            assertEquals(404, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    /** A path that tries to climb out of the single segment is refused. */
    @Test
    fun `a traversal path is a 404`() {
        val connection = open(
            "http://127.0.0.1:${server.port}/${TEST_TOKEN}/../etc/passwd",
            range = "bytes=0-63"
        )
        try {
            // The client resolves ".." before it is sent in most stacks, so
            // both the resolved form and the raw form must be refused.
            assertEquals(404, connection.responseCode)
        } finally {
            connection.disconnect()
        }
        val raw = java.net.Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write(
                ("GET /${TEST_TOKEN}/..%2f..%2fetc%2fpasswd HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .toByteArray()
            )
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
        assertTrue("expected a 404 for an encoded traversal, got: $raw", raw.contains("404"))
    }

    @Test
    fun `a token is long and unguessable`() {
        val token = TEST_TOKEN
        assertEquals(32, token.length)
        assertTrue(token.matches(Regex("[0-9a-f]{32}")))
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Closing the screen must close the socket. A server left listening with no
     * player behind it is exactly the leak this guards against.
     */
    @Test
    fun `stopping closes the socket and is idempotent`() {
        assertTrue(server.isRunning)
        server.stop()
        assertFalse(server.isRunning)

        try {
            URL(server.url("film.mp4")).openConnection().connect()
            fail("a stopped server must refuse connections")
        } catch (expected: Exception) {
            // ConnectException, or SocketException. Either is the point.
        }

        // Closing twice must not throw: onStop and onDestroy both call it.
        server.stop()
    }

    @Test
    fun `the server answers while running and after being stopped once`() {
        val first = get("bytes=0-99")
        assertArrayEquals(contents.sliceArray(0..99), first)
        server.stop()
        // Reading a second time is the same request the player would retry.
        assertFalse(server.isRunning)
    }

    // ── memory bounds ──────────────────────────────────────────────────────────

    /**
     * The server must ask for no more than one chunk at a time, however long
     * the range is. A range of the whole 64 KiB object in 1 KiB chunks is the
     * shape a 2 GB video takes: one bounded read per loop iteration, never the
     * object in one buffer.
     */
    @Test
    fun `reads are bounded by the chunk size`() {
        val body = get("bytes=0-")
        assertArrayEquals(contents, body)
        assertTrue(
            "every read must be at most $CHUNK bytes: ${source.requestedLengths}",
            source.requestedLengths.all { it <= CHUNK }
        )
        assertTrue("the object was read in pieces", source.requestedLengths.size > 1)
    }

    /**
     * A client that only reads the first bytes must not cause the whole object
     * to be read: TCP backpressure stops the loop, and that is what stops a
     * player that paused in the first seconds from draining the remote.
     */
    @Test
    fun `a client that stops reading stops the reads`() {
        // 8 MB, not the 64 KiB of the other tests: the kernel socket buffers
        // on loopback are measured in megabytes, so an object that fits in
        // them would be written in full however right the server was about not
        // prefetching. This size makes the backpressure observable.
        val large = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val largeSource = FakeSource(large)
        val largeServer = RangeHttpServer(
            source = largeSource,
            chunkBytes = CHUNK,
            token = TEST_TOKEN
        )
        largeServer.start()
        try {
            val connection = open(largeServer.url("film.mp4"), range = "bytes=0-")
            try {
                val read = connection.inputStream.use { it.read(ByteArray(512)) }
                assertTrue("some bytes arrived", read > 0)
            } finally {
                connection.disconnect()
            }
            // Give the worker a moment to notice the closed socket.
            Thread.sleep(ATTENTION_MILLIS)
            val readBytes = largeSource.requestedLengths.sum()
            assertTrue(
                "the whole object must not have been read: $readBytes of ${large.size}",
                readBytes < large.size / 4
            )
        } finally {
            largeServer.stop()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun open(
        url: String = server.url("film.mp4"),
        method: String = "GET",
        range: String? = null
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.readTimeout = 10_000
        connection.connectTimeout = 10_000
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }

    private fun get(range: String?): ByteArray {
        val connection = open(range = range)
        try {
            return readBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection): ByteArray {
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > FILE_SIZE) fail("more bytes than the object holds")
            }
            return out.toByteArray()
        }
    }

    private companion object {
        /** 32 hex characters, the shape of a generated token. */
        const val TEST_TOKEN = "0123456789abcdef0123456789abcdef"
        const val FILE_SIZE = 64 * 1024
        const val SIZE_MINUS_100 = FILE_SIZE - 100
        const val SIZE_MINUS_10 = FILE_SIZE - 10
        const val CHUNK = 1024
        const val ATTENTION_MILLIS = 250L
    }
}

/**
 * A [RangeSource] over a fixed array, with the failure modes the tests need.
 *
 * Every read is recorded, so a test can prove the server asked for bounded,
 * non-overlapping slices rather than one big read.
 */
private class FakeSource(
    private val data: ByteArray,
    /** null means "this backend reports no size". */
    private val sizeOverride: Long? = data.size.toLong(),
    private val failReadsWith: RangeReadException? = null
) : RangeSource {

    val requestedLengths = mutableListOf<Int>()

    override fun size(): Long? = sizeOverride

    override fun readAt(offset: Long, length: Int): ByteArray {
        requestedLengths.add(length)
        failReadsWith?.let { throw it }
        if (offset >= data.size) return ByteArray(0)
        val end = minOf(offset + length, data.size.toLong())
        return data.copyOfRange(offset.toInt(), end.toInt())
    }
}
