package io.earthquack.app.storage

import io.earthquack.app.stream.RangeReadException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers [RcloneRangeSource] against a fake bridge that answers the way the
 * native shim does, so the whole path — parameters, base64, reply validation,
 * error mapping — is exercised without a device or a remote.
 *
 * The bridge records every method it is asked for. That is what makes the
 * central regression test in this class possible: a video must be streamed with
 * bounded reads, and *never* by `operations/copyfile`, which is the whole-file
 * download the browser used to do before the first frame.
 */
class RcloneRangeSourceTest {

    /** Deterministic bytes: byte i is (i mod 251). */
    private val contents = ByteArray(FILE_SIZE) { (it % 251).toByte() }

    // ── reads ──────────────────────────────────────────────────────────────────

    @Test
    fun `a read returns the bytes at the offset`() = runTest {
        val (engine, fake) = fakeEngine()
        val source = RcloneRangeSource(engine, "gdrive:", "films/clip.mp4")

        val first = source.readAt(offset = 0, length = 1024)
        assertArrayEquals(contents.sliceArray(0 until 1024), first)

        val middle = source.readAt(offset = 4096, length = 512)
        assertArrayEquals(contents.sliceArray(4096 until 4608), middle)
    }

    @Test
    fun `a read that runs off the end is short rather than an error`() = runTest {
        val (engine, fake) = fakeEngine()
        val source = RcloneRangeSource(engine, "gdrive:", "clip.mp4")

        val tail = source.readAt(offset = FILE_SIZE - 8L, length = 1024)
        assertEquals(8, tail.size)
        assertArrayEquals(contents.sliceArray((FILE_SIZE - 8) until FILE_SIZE), tail)

        val past = source.readAt(offset = FILE_SIZE + 64L, length = 16)
        assertEquals(0, past.size)
    }

    @Test
    fun `an empty reply is not an error`() = runTest {
        val (engine, fake) = fakeEngine()
        val source = RcloneRangeSource(engine, "gdrive:", "clip.mp4")

        // Reading exactly the last byte leaves the next read with nothing to
        // return; that is the normal end of the object.
        assertEquals(1, source.readAt(FILE_SIZE - 1L, 1).size)
        assertEquals(0, source.readAt(FILE_SIZE.toLong(), 1).size)
    }

    /** Names with spaces and Unicode must survive the parameter round trip. */
    @Test
    fun `a remote name with spaces and unicode is passed through`() = runTest {
        val (engine, fake) = fakeEngine()
        val remote = "films/München — swapped (final) [4K].mp4"
        val source = RcloneRangeSource(engine, "my drive:", remote)

        source.readAt(0, 16)

        assertEquals(remote, fake.lastParams.getString("srcRemote"))
        assertEquals("my drive:", fake.lastParams.getString("srcFs"))
    }

    /** The exact offset and length must reach the remote, unmodified. */
    @Test
    fun `the read request carries the offset and length it was given`() = runTest {
        val (engine, fake) = fakeEngine()
        val source = RcloneRangeSource(engine, "gdrive:", "clip.mp4")

        source.readAt(offset = 12345L, length = 4096)

        val params = fake.lastParams
        assertEquals(12345L, params.getLong("offset"))
        assertEquals(4096L, params.getLong("length"))
        assertEquals("gdrive:", params.getString("srcFs"))
        assertEquals("clip.mp4", params.getString("srcRemote"))
    }

    /**
     * The shim caps one read at 8 MiB so a malformed request cannot allocate
     * without bound. Asking for more is a bug on this side and must not reach
     * the remote.
     */
    @Test
    fun `a read larger than the shim allows is refused before it is sent`() = runTest {
        val (engine, fake) = fakeEngine()
        val source = RcloneRangeSource(engine, "gdrive:", "clip.mp4")

        try {
            source.readAt(offset = 0, length = 64 * 1024 * 1024)
            fail("an oversized read must not reach the remote")
        } catch (e: IllegalArgumentException) {
            // Expected: the require() guard, before any RPC.
        }
        assertTrue(fake.requestedMethods.isEmpty())
    }

    // ── the no-whole-file-download guarantee ─────────────────────────────────────

    /**
     * The core guarantee of the streaming feature: a video is read in bounded
     * pieces, and the whole-object copy method the browser used before is never
     * called. If this regresses, playback silently becomes a full download
     * again — which is exactly what the requirement forbids.
     */
    @Test
    fun `streaming never asks for a whole-file copy`() = runTest {
        val (engine, fake) = fakeEngine()
        val source = RcloneRangeSource(engine, "gdrive:", "films/clip.mp4")

        source.size()
        var offset = 0L
        while (offset < FILE_SIZE) {
            source.readAt(offset, 256 * 1024)
            offset += 256 * 1024
        }

        assertFalse(
            "operations/copyfile must never be called to stream a video",
            fake.requestedMethods.contains("operations/copyfile")
        )
        assertFalse(
            "sync/* must never be called either",
            fake.requestedMethods.any { it.startsWith("sync/") }
        )
        assertEquals(
            setOf("operations/stat", "earthquack/read"),
            fake.requestedMethods.toSet()
        )
    }

    // ── size ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the size comes from a stat, not from a read`() = runTest {
        val (engine, fake) = fakeEngine()
        val source = RcloneRangeSource(engine, "gdrive:", "clip.mp4")

        assertEquals(FILE_SIZE.toLong(), source.size())
        assertEquals("operations/stat", fake.requestedMethods.first())
    }

    @Test
    fun `a remote with no reported size has a null size`() = runTest {
        val (engine, fake) = fakeEngine(size = null)
        val source = RcloneRangeSource(engine, "gdrive:", "clip.mp4")

        assertNull(source.size())
    }

    @Test
    fun `a directory cannot be streamed`() = runTest {
        val (engine, fake) = fakeEngine(isDir = true)
        val source = RcloneRangeSource(engine, "gdrive:", "a directory")

        assertNull(source.size())
    }

    // ── failures ──────────────────────────────────────────────────────────────────

    @Test
    fun `an rclone error becomes a readable failure`() = runTest {
        val (engine, fake) = fakeEngine(
            readFailure = RcloneResult(
                500,
                JSONObject().put("error", "directory not found").toString()
            )
        )
        val source = RcloneRangeSource(engine, "gdrive:", "gone.mp4")

        // If no exception is thrown the fail() below reports it; the message is
        // rclone's own words, which name the remote and never a credential.
        try {
            source.readAt(0, 4)
            fail("a failed read must throw")
        } catch (e: RangeReadException) {
            assertTrue(e.message!!.contains("directory not found"))
        }
    }

    /** A backend that cannot honour a seek fails: that is the fallback signal. */
    @Test
    fun `a backend with no range support reports a readable failure`() = runTest {
        val (engine, fake) = fakeEngine(
            readFailure = RcloneResult(
                500,
                JSONObject()
                    .put("error", "can't seek - backend doesn't support seeking")
                    .toString()
            )
        )
        val source = RcloneRangeSource(engine, "sftp:laptop", "films/clip.mp4")

        try {
            source.readAt(0, 4)
            fail("a failing read must throw")
        } catch (e: RangeReadException) {
            assertTrue(e.message!!.contains("seek"))
        }
    }

    @Test
    fun `a failed stat is a failure rather than an unknown size`() = runTest {
        val (engine, fake) = fakeEngine(
            statFailure = RcloneResult(
                404,
                JSONObject().put("error", "didn't find section in config file").toString()
            )
        )
        val source = RcloneRangeSource(engine, "gdrive:", "gone.mp4")

        try {
            source.size()
            fail("a remote that cannot be reached must not report a size")
        } catch (e: RangeReadException) {
            assertTrue(e.message!!.contains("config file"))
        }
    }

    /**
     * A reply whose `length` does not match its payload is corrupt in transit:
     * serving those bytes would put a hole in the video that looks like a
     * decoder fault somewhere else.
     */
    @Test
    fun `a reply whose length does not match its payload is rejected`() = runTest {
        val (engine, fake) = fakeEngine()
        fake.claimLength = 64
        val source = RcloneRangeSource(engine, "gdrive:", "clip.mp4")

        try {
            source.readAt(0, 16)
            fail("a mismatched reply must not be served")
        } catch (e: RangeReadException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // ── fakes ───────────────────────────────────────────────────────────────────

    /**
     * Stands in for the native shim's `earthquack/read`, slicing the same
     * [contents] the Go side would read.
     *
     * @param size what the fake remote reports as its size, or null when the
     *   backend reports none.
     * @param isDir whether the entry is a directory.
     * @param statFailure/[readFailure] the envelope rclone returns for a
     *   failed call; the engine turns it into an [RcloneException] with it
     *   preserved, exactly as production does.
     */
    private fun fakeEngine(
        size: Long? = FILE_SIZE.toLong(),
        isDir: Boolean = false,
        statFailure: RcloneResult? = null,
        readFailure: RcloneResult? = null
    ): Pair<RcloneEngine, FakeRangeBridge> {
        val bridge = FakeRangeBridge()
        bridge.size = size
        bridge.isDir = isDir
        bridge.statFailure = statFailure
        bridge.readFailure = readFailure
        return RcloneEngine(bridge).apply { initialize("") } to bridge
    }

    /**
     * A [RcloneBridge] that answers `operations/stat` and `earthquack/read`
     * from a fixed array, and records what it was asked.
     *
     * It lives at the bridge level, not the engine level, so the real
     * [RcloneEngine] parameter marshalling, status handling and error envelope
     * are all part of what is under test.
     */
    private inner class FakeRangeBridge : RcloneBridge {
        var size: Long? = FILE_SIZE.toLong()
        var isDir: Boolean = false
        var statFailure: RcloneResult? = null
        var readFailure: RcloneResult? = null

        /** When set, `length` is reported as this instead of the real size. */
        var claimLength: Int? = null

        val requestedMethods = mutableListOf<String>()
        var lastParams: JSONObject = JSONObject()

        override fun initialize(configPath: String) = Unit

        override fun rpc(method: String, input: String): RcloneResult {
            requestedMethods.add(method)
            val params = JSONObject(input)
            lastParams = params
            return when (method) {
                "operations/stat" -> statFailure ?: RcloneResult(
                    200,
                    JSONObject()
                        .put(
                            "item",
                            JSONObject()
                                .put("IsDir", isDir)
                                .put("Size", size ?: -1L)
                                .put("Name", "clip.mp4")
                        )
                        .toString()
                )

                "earthquack/read" -> readFailure ?: readReply(params)
                else -> RcloneResult(404, JSONObject().put("error", method).toString())
            }
        }

        override fun shutdown() = Unit

        private fun readReply(params: JSONObject): RcloneResult {
            val offset = params.getLong("offset")
            val length = params.getLong("length").toInt()
            if (offset >= contents.size) {
                return RcloneResult(
                    200,
                    JSONObject()
                        .put("length", 0)
                        .put("data", "")
                        .put("size", size ?: -1L)
                        .put("eof", true)
                        .toString()
                )
            }
            val end = minOf(offset + length, contents.size.toLong())
            val slice = contents.copyOfRange(offset.toInt(), end.toInt())
            return RcloneResult(
                200,
                JSONObject()
                    .put("length", (claimLength ?: slice.size).toLong())
                    .put("data", Base64.getEncoder().encodeToString(slice))
                    .put("size", size ?: -1L)
                    .put("eof", end >= contents.size)
                    .toString()
            )
        }
    }

    private companion object {
        const val FILE_SIZE = 24 * 1024
    }
}
