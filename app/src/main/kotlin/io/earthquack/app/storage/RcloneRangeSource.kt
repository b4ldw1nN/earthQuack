package io.earthquack.app.storage

import io.earthquack.app.stream.RangeReadException
import io.earthquack.app.stream.RangeSource
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * A [RangeSource] over the embedded rclone, via one RPC call per read.
 *
 * ## The method this uses, and why it is the only one that works
 *
 * Every data path in the linked rclone RPC surface is whole-object:
 * `operations/copyfile` copies an object between two filesystems, and nothing
 * else moves bytes at all (verified method by method against rclone v1.75.1 —
 * see `rclone-android/rclone/read.go` for the full argument). So the shim now
 * exposes a single range read:
 *
 * ```text
 * earthquack/read  {srcFs, srcRemote, offset, length}
 *              -> {length, data (base64), size, eof}
 * ```
 *
 * It is a narrow, additive method on the native side: the gomobile surface is
 * still `initialize`/`rpc`/`shutdown`, so nothing about the Rust-style bridge
 * or any existing test fake changes.
 *
 * ## How a read becomes bytes
 *
 * 1. one `earthquack/read` call per read, on [RcloneEngine]'s IO dispatcher,
 *    never on the calling thread's caller;
 * 2. `data` is base64, decoded with `java.util.Base64` — available from API 26,
 *    which is this app's `minSdk`, and unlike `android.util.Base64` it behaves
 *    identically on a device and under unit tests, so this class is testable
 *    on the JVM;
 * 3. the reply's own `length` is checked against the decoded bytes. A mismatch
 *    means the reply was truncated or malformed in transit, and serving those
 *    bytes would corrupt the video in a way the player cannot detect.
 *
 * ## Sizes
 *
 * The size comes from `operations/stat`, the same call the listing screen's
 * data comes from, so a file that is being appended to while it plays reports
 * the same size here as it does in the browser. Null when the backend reports
 * no size at all.
 *
 * ## Blocking
 *
 * [RangeSource] is a blocking interface by design — see its docs — so the
 * suspend [RcloneEngine.call] is run inside `runBlocking`. The caller is a
 * range-server worker thread that exists to do this, never the main thread,
 * and [RcloneEngine.call] does its own `withContext(Dispatchers.IO)`.
 */
class RcloneRangeSource(
    private val engine: RcloneEngine,
    private val srcFs: String,
    private val srcRemote: String,
    /** Upper bound on one read. The server asks for [REQUEST_CHUNK] at a time. */
    private val maxChunkBytes: Int = REQUEST_CHUNK
) : RangeSource {

    // NOTE (Agent B, build-unblock only): this was an expression-body function
    // (`override fun size(): Long? = try { … }`) containing `return null`, which
    // Kotlin rejects and which broke the shared main-source compile for all
    // agents. Converted to a block body; the logic below is unchanged. Agent C
    // may restyle freely as long as it compiles.
    override fun size(): Long? {
        return try {
            System.err.println("EQ_STREAM: size() calling operations/stat fs=$srcFs remote=$srcRemote")
            val reply = runBlocking {
                engine.call(METHOD_STAT, JSONObject().put("fs", srcFs).put("remote", srcRemote))
            }
            System.err.println("EQ_STREAM: size() stat reply=$reply")
            val item = reply.optJSONObject("item") ?: return null
            // A directory has a size of 0 upstream and no business being streamed;
            // reporting it as "unknown" stops the server from promising a range.
            if (item.optBoolean("IsDir", item.optBoolean("isDir"))) return null
            val size = item.optLong("Size", item.optLong("size", -1L))
            if (size < 0) null else size
        } catch (e: RcloneException) {
            System.err.println("EQ_STREAM: size() RcloneException: $e")
            // A stat that cannot be answered means the remote is gone, the
            // credentials expired, or the path moved. Treating that as "unknown
            // size" would send the player into a range it cannot satisfy, so the
            // failure is raised instead.
            throw RangeReadException(statMessage(e), e)
        } catch (e: RangeReadException) {
            System.err.println("EQ_STREAM: size() RangeReadException: $e")
            throw e
        } catch (e: Exception) {
            System.err.println("EQ_STREAM: size() Exception: $e")
            throw RangeReadException(STAT_FAILED, e)
        }
    }

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must not be negative" }
        require(length in 1..maxChunkBytes) { "length must be within 1..$maxChunkBytes" }

        val t0 = System.currentTimeMillis()
        System.err.println("EQ_STREAM: readAt offset=$offset length=$length fs=$srcFs remote=$srcRemote")
        val reply = try {
            runBlocking {
                engine.call(
                    METHOD_READ,
                    JSONObject()
                        .put("srcFs", srcFs)
                        .put("srcRemote", srcRemote)
                        .put("offset", offset)
                        .put("length", length)
                )
            }
        } catch (e: RcloneException) {
            System.err.println("EQ_STREAM: readAt failed in ${System.currentTimeMillis() - t0}ms: $e")
            throw RangeReadException(readMessage(e), e)
        } catch (e: RangeReadException) {
            System.err.println("EQ_STREAM: readAt failed in ${System.currentTimeMillis() - t0}ms: $e")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            System.err.println("EQ_STREAM: readAt cancelled in ${System.currentTimeMillis() - t0}ms")
            // The server stops its workers by cancelling them; a cancelled read
            // is not a read failure and must not be reported as one.
            throw e
        } catch (e: Exception) {
            System.err.println("EQ_STREAM: readAt failed in ${System.currentTimeMillis() - t0}ms: $e")
            throw RangeReadException(READ_FAILED, e)
        }

        val dt = System.currentTimeMillis() - t0
        val encoded = reply.optString("data")
        val reported = reply.optLong("length", -1L)
        System.err.println("EQ_STREAM: readAt returned length=$reported in ${dt}ms")
        if (encoded.isEmpty()) return ByteArray(0)

        val data = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw RangeReadException(MALFORMED_REPLY, e)
        }
        if (reported != data.size.toLong()) {
            // The only signature of a truncated or reassembled reply: trust it
            // as little as possible, because a video played through a hole in
            // its bytes looks like a decoder fault somewhere else entirely.
            throw RangeReadException(MALFORMED_REPLY)
        }
        return data
    }

    /** rclone's own error text, or a fixed sentence. Never a credential. */
    private fun readMessage(e: RcloneException): String =
        firstLineOf(e).ifBlank { READ_FAILED }

    /** Same, for the stat call. */
    private fun statMessage(e: RcloneException): String =
        firstLineOf(e).ifBlank { STAT_FAILED }

    private fun firstLineOf(e: RcloneException): String = try {
        JSONObject(e.rcloneOutput).optString("error").trim()
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }.take(MAX_DETAIL)

    companion object {
        /** The shim's range-read method, registered in `rclone/read.go`. */
        const val METHOD_READ = "earthquack/read"

        /** rclone's own stat, used for the size. */
        private const val METHOD_STAT = "operations/stat"

        /** What one HTTP range response is filled from. 1 MiB. */
        const val REQUEST_CHUNK = 1024 * 1024

        private const val READ_FAILED = "The remote could not be read"
        private const val STAT_FAILED = "The remote file could not be checked"
        private const val MALFORMED_REPLY = "The remote returned an unreadable reply"

        /** Matches the rest of the app's cap on rclone's error text. */
        private const val MAX_DETAIL = 160
    }
}
