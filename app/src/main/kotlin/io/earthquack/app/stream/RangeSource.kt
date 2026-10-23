package io.earthquack.app.stream

/**
 * Thrown when a read from the remote failed.
 *
 * Carries a message that is safe to show the user: it is either this class's
 * own sentence, or rclone's own error text (which names the remote and the
 * operation, never a credential). [cause] is kept for the log, and the
 * [message] is what reaches the screen.
 */
class RangeReadException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Random access to one remote file, one bounded read at a time.
 *
 * The whole streaming pipeline is written against this interface rather than
 * against rclone, because "give me 256 KB at offset 1 MB" is the only thing a
 * range server asks of a remote. That keeps the server testable on the JVM
 * against a plain array, and it means a second implementation — an SFTP
 * client, a cached local copy — would not touch the HTTP layer at all.
 *
 * Implementations must be **blocking** and must be safe to call from several
 * threads at once: the range server issues one call per HTTP request, and a
 * player readily has a probe request and a seek request in flight together.
 * Each call has to be independent — no shared cursor — or a seek would corrupt
 * whatever else is reading.
 */
interface RangeSource {

    /**
     * The size of the file in bytes, or null when the backend reports none.
     *
     * A null size means the object cannot be served as a range: without a
     * length there is no `Content-Range` to compute and no way to tell a valid
     * range from an unsatisfiable one. Callers treat that as "not streamable"
     * rather than guessing.
     */
    fun size(): Long?

    /**
     * Reads up to [length] bytes starting at [offset].
     *
     * @return the bytes read. May be shorter than requested when it reached
     *   the end of the file; empty only when [offset] is at or past the end.
     * @throws RangeReadException when the remote read failed, including when
     *   the backend cannot honour a range at all.
     */
    @Throws(RangeReadException::class)
    fun readAt(offset: Long, length: Int): ByteArray
}
