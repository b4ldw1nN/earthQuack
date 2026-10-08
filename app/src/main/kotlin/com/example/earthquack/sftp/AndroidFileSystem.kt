package com.example.earthquack.sftp

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One entry returned by [AndroidFileSystem].
 *
 * [permissions] is a POSIX mode word including the file-type bits, because that
 * is what SFTP reports and what the NIO layer has to hand back. It is derived
 * from the platform rather than invented, so a client sees the same numbers it
 * would from OpenSSH's own sftp-server.
 */
data class FsEntry(
    val name: String,
    /** Path as the client sees it: absolute, no trailing slash, "/" for the root. */
    val path: String,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val size: Long,
    val lastModifiedMillis: Long,
    val permissions: Int
)

/**
 * Raised when a client asks for something outside the served root.
 *
 * Its own type rather than a generic [IOException] so the SFTP layer can map it
 * to `SSH_FX_PERMISSION_DENIED` (which is what a client expects for "no") and
 * can log it as a distinct, interesting event rather than as a generic failure.
 */
class PathEscapeException(path: String) :
    IOException("path is outside the served root: ${path.replace(Regex("\n"), " ")}")

/**
 * The storage the SFTP server exposes.
 *
 * ## Why this exists
 *
 * Android's storage rules and SFTP's path rules have nothing to do with each
 * other. The protocol layer thinks in absolute POSIX paths with `.`/`..`,
 * symlinks and symbolic links to directories; Android has scoped storage,
 * per-app sandboxes and directories (`Android/data`) that cannot be listed at
 * all even with "all files access". Binding the protocol to `java.io.File`
 * would make every one of those decisions inside the SFTP server, and would
 * make the server impossible to test without a device.
 *
 * So this is the seam. [LocalAndroidFileSystem] is the only implementation, and
 * it is a plain `java.io` class with no Android imports at all — which is why
 * the traversal tests run on the JVM.
 *
 * ## Path rules every implementation must enforce
 *
 *  - `/` is the served root, never the device root.
 *  - `.` is a no-op and `..` may only walk upwards within the root.
 *  - A path that resolves outside the root — including through a symlink —
 *    fails with [PathEscapeException]. It is never silently clamped, because a
 *    silently clamped `..` looks to a client like a successful write to the
 *    wrong file.
 */
interface AndroidFileSystem {

    /** The real absolute path of the served root, for display only. */
    val rootPath: String

    /** Whether [path] exists. Never throws for a missing path. */
    fun exists(path: String): Boolean

    /** Whether [path] is a directory. False for a missing path. */
    fun isDirectory(path: String): Boolean

    /** Metadata for [path], or null when it does not exist. */
    fun stat(path: String): FsEntry?

    /** Directory contents, unsorted, excluding `.` and `..`. */
    fun list(path: String): List<FsEntry>

    /** Opens [path] for reading, positioned at the start. */
    fun openRead(path: String): InputStream

    /** Opens [path] for writing. [append] keeps existing content. */
    fun openWrite(path: String, append: Boolean): OutputStream

    /** Creates a directory. Returns false when it already existed. */
    fun mkdir(path: String): Boolean

    /** Deletes [path]. With [recursive], a directory and its contents. */
    fun delete(path: String, recursive: Boolean): Boolean

    /** Renames or moves. Must fail rather than crossing the root. */
    fun rename(from: String, to: String): Boolean

    /** Updates modification time. Returns false when unsupported. */
    fun setLastModified(path: String, millis: Long): Boolean

    /** The canonical form of [path] as the client sees it. */
    fun realPath(path: String): String

    /**
     * Opens a seekable handle, as SFTP's partial-write and resume paths need.
     *
     * Throws [UnsupportedOperationException] by default: an implementation
     * that cannot seek must say so rather than hand back a handle that lies
     * about its position, because the failure would then surface as silently
     * corrupted files on the other machine.
     */
    fun openRandomAccess(path: String, write: Boolean, truncate: Boolean): AndroidRandomAccess =
        throw UnsupportedOperationException("random access is not supported by this filesystem")
}

/**
 * Random-access handle used by the NIO layer.
 *
 * SFTP is a random-access protocol: a client rewinds a partially uploaded file
 * and writes at an offset. Streaming-only abstractions would force that whole
 * file through memory, so the abstraction carries this instead of pretending
 * [AndroidFileSystem.openRead] can do the job.
 */
interface AndroidRandomAccess {
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    fun write(position: Long, buffer: ByteArray, offset: Int, length: Int)
    fun size(): Long
    fun truncate(length: Long)
    fun close()
}
