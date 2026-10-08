package com.example.earthquack.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.sftp.client.SftpClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers

/** One entry in a remote directory listing. */
data class SftpEntry(
    val name: String,
    /** Absolute path on the remote host. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMillis: Long,
    val isSymbolicLink: Boolean = false,
    val permissions: String? = null
)

/** Progress of a transfer, in bytes. */
data class TransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long   // -1 when the size is not known
) {
    /** 0..1, or null when the total is unknown. Never faked as 0 or 1. */
    val fraction: Float?
        get() = if (totalBytes > 0) (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null
}

/** Raised when a transfer is cancelled by the caller. */
class TransferCancelledException : CancellationException("transfer cancelled")

/**
 * The SFTP client, as this app uses it.
 *
 * ## Independent of the UI
 *
 * Takes no Context, no View, no coroutine scope of its own. Everything
 * suspending here runs on [Dispatchers.IO] and observes cancellation, so the
 * file browser and a background transfer drive it identically.
 *
 * ## Streaming
 *
 * Every transfer moves a fixed buffer at a time ([BUFFER_BYTES]) between the
 * local stream and the remote channel. Nothing is buffered whole: a 4 GB file
 * costs 256 KiB of heap here, and the progress callback is invoked with each
 * chunk so a UI can show a moving bar instead of a spinner.
 *
 * ## Resume
 *
 * [download] and [upload] take an offset, which is what makes resuming a
 * partial transfer possible: the caller asks the server for the size, skips
 * bytes it already has, and passes the offset. [remoteSize] exists so that
 * check does not have to be hand-rolled against the raw client.
 */
class SftpSession(private val client: SftpClient) : AutoCloseable {

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
    }

    val isOpen: Boolean get() = client.isOpen

    /**
     * Lists [path].
     *
     * "." and ".." are returned: OpenSSH clients expect them, and filtering
     * them here would make this client's behaviour differ from every other.
     */
    suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        val handle = client.openDir(path)
        try {
            client.readDir(handle).map { entry ->
                val attrs = entry.attributes
                SftpEntry(
                    name = entry.filename,
                    path = if (path.endsWith("/")) "$path${entry.filename}" else "$path/${entry.filename}",
                    isDirectory = attrs.isDirectory,
                    size = attrs.size,
                    lastModifiedMillis = attrs.modifyTime.toMillis(),
                    isSymbolicLink = attrs.isSymbolicLink,
                    permissions = formatUnixPermissions(attrs.permissions)
                )
            }
        } finally {
            runCatching { client.close(handle) }
        }
    }

    /** Metadata for a single path, or null when it does not exist. */
    suspend fun stat(path: String): SftpEntry? = withContext(Dispatchers.IO) {
        val attrs = try {
            client.stat(path)
        } catch (e: IOException) {
            return@withContext null
        }
        println("[SFTP] stat: path=$path, isDir=${attrs.isDirectory}, modifyTime=${attrs.modifyTime}, isSymlink=${attrs.isSymbolicLink}, perms=${attrs.permissions}")
        SftpEntry(
            name = path.trimEnd('/').substringAfterLast('/'),
            path = path,
            isDirectory = attrs.isDirectory,
            size = attrs.size,
            lastModifiedMillis = attrs.modifyTime?.toMillis() ?: 0,
            isSymbolicLink = attrs.isSymbolicLink,
            permissions = formatUnixPermissions(attrs.permissions)
        )
    }

    /** Size in bytes, or null when the path cannot be stat'ed. */
    suspend fun remoteSize(path: String): Long? = withContext(Dispatchers.IO) {
        try {
            client.stat(path).size
        } catch (e: IOException) {
            null
        }
    }

    /** The server's own canonical form of [path]. */
    suspend fun realPath(path: String): String = withContext(Dispatchers.IO) {
        client.canonicalPath(path)
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        try {
            client.mkdir(path)
        } catch (e: IOException) {
            throw friendly("could not create $path", e)
        }
    }

    /**
     * Deletes [path].
     *
     * Directories are removed recursively. A non-recursive `rmdir` on a
     * non-empty directory fails on OpenSSH servers, so a file browser that
     * cannot delete a folder is a file browser that looks broken.
     */
    suspend fun delete(path: String, recursive: Boolean = false) = withContext(Dispatchers.IO) {
        println("[SFTP] delete: path=$path, recursive=$recursive")
        try {
            if (recursive) {
                removeRecursively(path)
            } else {
                // SFTP uses different commands for files (REMOVE) and directories (RMDIR)
                val attrs = client.stat(path)
                println("[SFTP] delete: attrs.isDirectory=${attrs.isDirectory}")
                if (attrs.isDirectory) {
                    println("[SFTP] delete: calling rmdir")
                    client.rmdir(path)
                    println("[SFTP] delete: rmdir returned")
                } else {
                    client.remove(path)
                }
            }
        } catch (e: IOException) {
            println("[SFTP] delete failed: ${e.message}")
            throw friendly("could not delete $path", e)
        }
    }

    private fun removeRecursively(path: String) {
        val handle = client.openDir(path)
        try {
            client.readDir(handle).forEach { entry ->
                if (entry.filename == "." || entry.filename == "..") return@forEach
                val child = "$path/${entry.filename}"
                if (entry.attributes.isDirectory) removeRecursively(child) else client.remove(child)
            }
        } finally {
            runCatching { client.close(handle) }
        }
        client.remove(path)
    }

    /** Renames, and therefore also moves: SFTP has one operation for both. */
    suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        try {
            client.rename(from, to)
        } catch (e: IOException) {
            throw friendly("could not rename $from", e)
        }
    }

    /**
     * Streams [input] to [remotePath].
     *
     * @param size total bytes, or -1 when unknown. Unknown size means the
     *   progress callback reports a null fraction rather than a made-up one.
     * @param offset bytes already present at the start of [remotePath], for
     *   resuming. Passed straight through to the remote channel.
     */
    suspend fun upload(
        input: InputStream,
        remotePath: String,
        size: Long = -1,
        offset: Long = 0,
        bufferBytes: Int = BUFFER_BYTES,
        onProgress: ((TransferProgress) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        val modes = if (offset > 0) {
            listOf(SftpClient.OpenMode.Write)
        } else {
            listOf(SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate, SftpClient.OpenMode.Create)
        }
        val handle = try {
            client.open(remotePath, modes)
        } catch (e: IOException) {
            throw friendly("could not open $remotePath for writing", e)
        }

        val buffer = ByteArray(bufferBytes)
        var position = offset
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                // sshd's write() returns void: the whole range is either sent or
                // the call throws, so there is no short-write loop to write.
                client.write(handle, position, buffer, 0, read)
                position += read
                onProgress?.invoke(TransferProgress(position - offset, size))
            }
        } finally {
            runCatching { client.close(handle) }
        }
        position - offset
    }

    /**
     * Streams [remotePath] into [output].
     *
     * @param offset first byte to read, for resuming a partial download.
     */
    suspend fun download(
        remotePath: String,
        output: OutputStream,
        offset: Long = 0,
        bufferBytes: Int = BUFFER_BYTES,
        onProgress: ((TransferProgress) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        val handle = try {
            client.open(remotePath, SftpClient.OpenMode.Read)
        } catch (e: IOException) {
            throw friendly("could not open $remotePath for reading", e)
        }

        val total = runCatching { client.stat(remotePath).size }.getOrDefault(-1L)
        val buffer = ByteArray(bufferBytes)
        // AtomicReference<Boolean>, not AtomicBoolean: that is the parameter
        // type sshd's read() takes, and AtomicBoolean does not extend it.
        // Explicitly use Boolean.FALSE to avoid primitive boxing issues
        val eof = java.util.concurrent.atomic.AtomicReference<Boolean>(java.lang.Boolean.FALSE)
        println("[SFTP] download: eof initialized to ${eof.get()}")
        var position = offset
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = client.read(handle, position, buffer, 0, buffer.size, eof)
                if (read <= 0) break
                output.write(buffer, 0, read)
                position += read
                onProgress?.invoke(TransferProgress(position - offset, total - offset))
                // eof.get() may return null (sshd bug?), so compare explicitly to Boolean.TRUE
                if (eof.get() == java.lang.Boolean.TRUE) break
            }
        } finally {
            runCatching { client.close(handle) }
        }
        position - offset
    }

    /**
     * Turns an sshd/SFTP status code into a message worth showing.
     *
     * "Permission denied" and "no such file" are what a user can act on;
     * "SftpException with status 3" is not. The original is kept as the cause
     * so nothing is lost for debugging.
     */
    private fun friendly(message: String, e: IOException): IOException {
        val detail = when {
            e is org.apache.sshd.sftp.common.SftpException ->
                when (val status = e.status) {
                    SftpConstants.SSH_FX_NO_SUCH_FILE -> "no such file"
                    SftpConstants.SSH_FX_PERMISSION_DENIED -> "permission denied"
                    SftpConstants.SSH_FX_FILE_ALREADY_EXISTS -> "already exists"
                    SftpConstants.SSH_FX_FAILURE -> "the server refused the operation"
                    else -> "SFTP status $status"
                }
            else -> e.message ?: e.javaClass.simpleName
        }
        return IOException("$message: $detail", e)
    }

    override fun close() {
        runCatching { client.close() }
    }
}

/**
 * The SFTP status codes this app maps to readable text.
 *
 * Values are from draft-ietf-secsh-filexfer-02. Duplicated rather than imported
 * so the mapping above stays readable in one place.
 */
object SftpConstants {
    const val SSH_FX_OK = 0
    const val SSH_FX_EOF = 1
    const val SSH_FX_NO_SUCH_FILE = 2
    const val SSH_FX_PERMISSION_DENIED = 3
    const val SSH_FX_FAILURE = 4
    const val SSH_FX_FILE_ALREADY_EXISTS = 11
}

/**
 * Renders an SFTP permission word as `rwxr-xr-x`.
 *
 * SFTP sends permissions as a plain int, unlike the local `PosixFilePermission`
 * set a JVM file API returns, so there is nothing to convert from — the bits
 * just need labelling. A client UI shows this next to a name, and `0755` alone
 * is less use to a person deciding whether a file is writable.
 */
fun formatUnixPermissions(permissions: Int?): String? {
    if (permissions == null) return null
    val sb = StringBuilder(9)
    val rwx = "rwxrwxrwx"
    for (shift in intArrayOf(6, 3, 0)) {
        for (bit in 2 downTo 0) {
            sb.append(rwx[(bit * 3) + (shift / 3)])
        }
    }
    return sb.toString()
}
