package com.example.earthquack.sftp.fs

import com.example.earthquack.sftp.AndroidRandomAccess
import com.example.earthquack.sftp.PathEscapeException
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileStoreAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.nio.file.spi.FileSystemProvider

/**
 * The [FileSystemProvider] that gives sshd's SFTP subsystem a `java.nio.file`
 * view of an [com.example.earthquack.sftp.AndroidFileSystem].
 *
 * ## Why a provider at all
 *
 * MINA SSHD's SFTP server works in terms of `java.nio.file.Path`. Rather than
 * hand it the device's real filesystem — which would expose everything the
 * process can see, including app sandboxes — this translates each `Path` into a
 * call on the storage abstraction. The result is that the protocol code has no
 * way to name a file outside the served root: the only way to reach storage is
 * a method on the backend, and the backend refuses.
 *
 * ## Supported surface
 *
 * Everything the SFTP subsystem actually calls. Anything else throws
 * `UnsupportedOperationException`, which sshd maps to
 * `SSH_FX_OP_UNSUPPORTED` — an honest "this server does not do that", which is
 * the correct answer for e.g. POSIX ACLs on shared storage that cannot store
 * them anyway.
 */
internal class AndroidFileSystemProvider(
    private val fileSystem: AndroidFileSystemNio
) : FileSystemProvider() {

    private val backend get() = fileSystem.backend

    override fun getScheme(): String = AndroidPath.SCHEME

    // ── lifecycle: one view per session, handed out by the factory ───────────

    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): java.nio.file.FileSystem =
        throw UnsupportedOperationException("this filesystem is created by the server, not by URI")

    override fun getFileSystem(uri: URI): java.nio.file.FileSystem = fileSystem

    override fun newFileSystem(
        path: java.nio.file.Path,
        env: MutableMap<String, *>
    ): java.nio.file.FileSystem =
        throw UnsupportedOperationException("this filesystem is created by the server")

    override fun getPath(uri: URI): java.nio.file.Path = AndroidPath.fromUri(fileSystem, uri)

    // ── files ────────────────────────────────────────────────────────────────

    override fun newByteChannel(
        path: java.nio.file.Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        val target = pathOf(path)
        val write = options.contains(StandardOpenOption.WRITE) ||
            options.contains(StandardOpenOption.APPEND) ||
            options.contains(StandardOpenOption.CREATE) ||
            options.contains(StandardOpenOption.TRUNCATE_EXISTING)
        val truncate = options.contains(StandardOpenOption.TRUNCATE_EXISTING)
        val exists = backend.exists(target)

        println("[PROVIDER] newByteChannel: target=$target, options=$options, write=$write, truncate=$truncate, exists=$exists")

        if (!exists && !write) throw NoSuchFileException(target)
        if (exists && !write && !backend.isDirectory(target) && options.contains(StandardOpenOption.DELETE_ON_CLOSE)) {
            throw UnsupportedOperationException("DELETE_ON_CLOSE is not supported")
        }

        val handle: AndroidRandomAccess = try {
            backend.openRandomAccess(target, write, truncate && !options.contains(StandardOpenOption.APPEND))
        } catch (e: PathEscapeException) {
            throw AccessDeniedException(target, null, e.message)
        } catch (e: NoSuchFileException) {
            throw e
        } catch (e: IOException) {
            throw translate(e, target)
        }
        return AndroidSeekableChannel(handle, target)
    }

    override fun newDirectoryStream(
        dir: java.nio.file.Path,
        filter: DirectoryStream.Filter<in java.nio.file.Path>
    ): DirectoryStream<java.nio.file.Path> {
        val target = pathOf(dir)
        if (!backend.exists(target)) throw NoSuchFileException(target)
        if (!backend.isDirectory(target)) throw NotDirectoryExceptionShim(target)
        val entries = backend.list(target)
        return object : DirectoryStream<java.nio.file.Path> {
            private val iterator = entries.iterator()
            override fun iterator(): MutableIterator<java.nio.file.Path> =
                object : MutableIterator<java.nio.file.Path> {
                    private var next: java.nio.file.Path? = null

                    private fun advance() {
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            // "." and ".." are emitted by sshd itself; sending
                            // them here as well would double every listing.
                            if (entry.name == "." || entry.name == "..") continue
                            val child = fileSystem.getPath(entry.path)
                            if (filter.accept(child)) {
                                next = child
                                return
                            }
                        }
                        next = null
                    }

                    override fun hasNext(): Boolean {
                        if (next == null) advance()
                        return next != null
                    }

                    override fun next(): java.nio.file.Path {
                        if (!hasNext()) throw java.util.NoSuchElementException()
                        return next!!
                    }

                    override fun remove() = throw UnsupportedOperationException()
                }

            override fun close() = Unit
        }
    }

    override fun createDirectory(
        dir: java.nio.file.Path,
        vararg attrs: FileAttribute<*>
    ) {
        val target = pathOf(dir)
        if (backend.exists(target)) throw FileAlreadyExistsException(target)
        try {
            backend.mkdir(target)
        } catch (e: PathEscapeException) {
            throw AccessDeniedException(target, null, e.message)
        } catch (e: IOException) {
            throw translate(e, target)
        }
    }

    override fun delete(path: java.nio.file.Path) {
        val target = pathOf(path)
        println("[PROVIDER] delete: target=$target")
        if (!backend.exists(target)) throw NoSuchFileException(target)
        if (backend.isDirectory(target)) {
            println("[PROVIDER] delete: is directory, listing...")
            val entries = backend.list(target)
            println("[PROVIDER] delete: entries=${entries.map { it.name }}")
            // Deliberately not recursive: SFTP's rmdir is specified to fail on a
            // non-empty directory, and quietly emptying one because the client
            // asked would be a data-loss surprise.
            throw DirectoryNotEmptyException(target)
        }
        try {
            backend.delete(target, recursive = false)
        } catch (e: PathEscapeException) {
            throw AccessDeniedException(target, null, e.message)
        } catch (e: IOException) {
            throw translate(e, target)
        }
    }

    override fun copy(
        source: java.nio.file.Path,
        target: java.nio.file.Path,
        options: Array<out CopyOption>
    ) {
        val from = pathOf(source)
        val to = pathOf(target)
        if (!backend.exists(from)) throw NoSuchFileException(from)
        if (backend.isDirectory(from)) throw java.nio.file.FileSystemException(from, to, "is a directory")
        if (backend.exists(to) && !options.contains(StandardCopyOption.REPLACE_EXISTING)) throw FileAlreadyExistsException(to)
        backend.openRead(from).use { input ->
            backend.openWrite(to, append = false).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
            }
        }
    }

    override fun move(
        source: java.nio.file.Path,
        target: java.nio.file.Path,
        options: Array<out CopyOption>
    ) {
        val from = pathOf(source)
        val to = pathOf(target)
        if (options.contains(StandardCopyOption.ATOMIC_MOVE)) {
            // A rename inside one filesystem is atomic on this platform; the
            // option is satisfied by doing the ordinary thing rather than by
            // being rejected.
        }
        if (backend.exists(to) && options.contains(StandardCopyOption.REPLACE_EXISTING)) {
            backend.delete(to, recursive = true)
        }
        try {
            backend.rename(from, to)
        } catch (e: PathEscapeException) {
            throw AccessDeniedException(from, to, e.message)
        } catch (e: IOException) {
            throw translate(e, from)
        }
    }

    // ── queries ──────────────────────────────────────────────────────────────

    override fun isSameFile(path: java.nio.file.Path, path2: java.nio.file.Path): Boolean =
        backend.realPath(pathOf(path)) == backend.realPath(pathOf(path2))

    override fun isHidden(path: java.nio.file.Path): Boolean =
        path.fileName?.toString()?.startsWith(".") == true

    override fun getFileStore(path: java.nio.file.Path): FileStore =
        AndroidFileStore(pathOf(path))

    override fun checkAccess(path: java.nio.file.Path, vararg modes: AccessMode) {
        val target = pathOf(path)
        val entry = backend.stat(target) ?: throw NoSuchFileException(target)
        val permissions = entry.permissions
        val ownerRead = permissions and OWNER_READ != 0
        val ownerWrite = permissions and OWNER_WRITE != 0
        val ownerExec = permissions and OWNER_EXEC != 0
        for (mode in modes) {
            val allowed = when (mode) {
                AccessMode.READ -> ownerRead
                AccessMode.WRITE -> ownerWrite
                AccessMode.EXECUTE -> ownerExec
                else -> true
            }
            if (!allowed) throw AccessDeniedException(target, null, "not permitted")
        }
    }

    override fun <V : FileAttributeView?> getFileAttributeView(
        path: java.nio.file.Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? = null

    @Suppress("UNCHECKED_CAST")
    override fun <A : BasicFileAttributes?> readAttributes(
        path: java.nio.file.Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        val target = pathOf(path)
        val entry = backend.stat(target) ?: throw NoSuchFileException(target)
        val attributes = AndroidFileAttributes(entry)
        if (type == BasicFileAttributes::class.java || type == PosixFileAttributes::class.java) {
            return attributes as A
        }
        if (type == FileTime::class.java) return attributes.lastModifiedTime() as A
        if (type == UserPrincipal::class.java || type == GroupPrincipal::class.java) {
            return attributes.owner() as A
        }
        throw UnsupportedOperationException("attribute view ${type.name} is not supported")
    }

    override fun readAttributes(
        path: java.nio.file.Path,
        attributes: String,
        vararg options: LinkOption
    ): MutableMap<String, Any> {
        val target = pathOf(path)
        val entry = backend.stat(target) ?: throw NoSuchFileException(target)
        val attrs = AndroidFileAttributes(entry)

        // The string form is "view:name,name,...". sshd asks for a list in one
        // call, so every requested name has to be answered or the whole call
        // fails — which is how a listing ends up reporting OP_UNSUPPORTED.
        val view = attributes.substringBefore(':')
        val requested = attributes.substringAfter(':', "")
            .split(',')
            .filter { it.isNotBlank() && it != "*" }
        if (requested.isEmpty()) {
            return mutableMapOf()
        }

        val out = mutableMapOf<String, Any>()
        requested.forEach { name ->
            when (name) {
                "size" -> out["size"] = attrs.size()
                "isRegularFile" -> out["isRegularFile"] = attrs.isRegularFile()
                "isDirectory" -> out["isDirectory"] = attrs.isDirectory()
                "isSymbolicLink" -> out["isSymbolicLink"] = attrs.isSymbolicLink()
                "isOther" -> out["isOther"] = attrs.isOther()
                "lastModifiedTime" -> out["lastModifiedTime"] = attrs.lastModifiedTime()
                "lastAccessTime" -> out["lastAccessTime"] = attrs.lastAccessTime()
                "creationTime" -> out["creationTime"] = attrs.creationTime()
                "key" -> out["key"] = attrs.fileKey() ?: AndroidPath.SCHEME
                "permissions" -> out["permissions"] = attrs.permissions()
                "owner" -> out["owner"] = attrs.owner()
                "group" -> out["group"] = attrs.group()
                "mode" -> out["mode"] = entry.permissions
                // Unknown names are skipped rather than failing the call: a
                // client that asks for an attribute this filesystem does not
                // have should get the ones it does have, not an error.
                else -> Unit
            }
        }
        return out
    }

    override fun setAttribute(
        path: java.nio.file.Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        val target = pathOf(path)
        if (attribute.substringAfter(':') == "lastModifiedTime" && value is FileTime) {
            backend.setLastModified(target, value.toMillis())
            return
        }
        // Everything else — permissions, owner, times Android does not expose —
        // is accepted and ignored rather than failed, because OpenSSH clients
        // (and rclone) routinely send a chmod or a utimes alongside an upload
        // and treat its failure as a failed transfer.
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun pathOf(path: java.nio.file.Path): String {
        if (path !is AndroidPath) {
            throw java.nio.file.ProviderMismatchException("expected an AndroidPath, got $path")
        }
        return path.pathString
    }

    /** Maps a backend failure onto the NIO exception sshd expects. */
    private fun translate(e: IOException, target: String): IOException = when (e) {
        is PathEscapeException -> AccessDeniedException(target, null, e.message)
        else -> java.nio.file.FileSystemException(target, null, e.message)
    }

    private companion object {
        const val OWNER_READ = 0b100_000_000
        const val OWNER_WRITE = 0b010_000_000
        const val OWNER_EXEC = 0b001_000_000
    }
}

/**
 * A [FileStore] for the served root.
 *
 * Exists because sshd asks for one while building attributes, and a server
 * that throws here answers every listing with OP_UNSUPPORTED. The numbers are
 * the platform's own, read from the directory, so they are true rather than
 * invented.
 */
private class AndroidFileStore(private val path: String) : FileStore() {

    override fun name(): String = "android"

    override fun type(): String = "root"

    override fun isReadOnly(): Boolean = false

    override fun getTotalSpace(): Long = runCatching { java.io.File(path).totalSpace }.getOrDefault(0L)

    override fun getUsableSpace(): Long = runCatching { java.io.File(path).usableSpace }.getOrDefault(0L)

    override fun getUnallocatedSpace(): Long = getUsableSpace()

    override fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean = false

    override fun supportsFileAttributeView(name: String): Boolean = false

    override fun <V : FileStoreAttributeView?> getFileStoreAttributeView(type: Class<V>): V? = null

    override fun getAttribute(attribute: String): Any? = null

    override fun toString(): String = name()
}

/** `NotDirectoryException` without the name clash inside this file. */
private fun NotDirectoryExceptionShim(path: String) = java.nio.file.NotDirectoryException(path)

/**
 * [SeekableByteChannel] over [AndroidRandomAccess].
 *
 * Only the operations SFTP needs are implemented. Byte-range locking is not:
 * Android storage does not provide it, and sshd answers `SSH_FXP_LOCK` with
 * "unsupported" rather than pretending, which is what OpenSSH does on a
 * filesystem without locks.
 */
private class AndroidSeekableChannel(
    private val handle: AndroidRandomAccess,
    private val path: String
) : SeekableByteChannel {

    private var position = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        val length = dst.remaining()
        if (length == 0) return 0
        val buffer = ByteArray(length)
        val read = handle.read(position, buffer, 0, length)
        if (read <= 0) return -1
        dst.put(buffer, 0, read)
        position += read
        return read
    }

    override fun write(src: ByteBuffer): Int {
        val length = src.remaining()
        if (length == 0) return 0
        val buffer = ByteArray(length)
        src.get(buffer)
        handle.write(position, buffer, 0, length)
        position += length
        return length
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        if (newPosition < 0) throw IllegalArgumentException("negative position")
        position = newPosition
        return this
    }

    override fun size(): Long = handle.size()

    override fun truncate(size: Long): SeekableByteChannel {
        handle.truncate(size)
        if (position > size) position = size
        return this
    }

    override fun isOpen(): Boolean = open

    override fun close() {
        if (!open) return
        open = false
        handle.close()
    }

}

/**
 * The single account this server acts as.
 *
 * One object for owner and group because there is only ever one: the phone.
 * Nothing here is used for access control — authentication decides that, and it
 * happens before a path is ever resolved.
 */
internal object AndroidPrincipal : UserPrincipal, GroupPrincipal {
    override fun getName(): String = "earthquack"
}

/** [PosixFileAttributes] built from an [com.example.earthquack.sftp.FsEntry]. */
internal class AndroidFileAttributes(
    private val entry: com.example.earthquack.sftp.FsEntry
) : PosixFileAttributes {

    override fun lastModifiedTime(): FileTime = FileTime.fromMillis(entry.lastModifiedMillis)

    override fun lastAccessTime(): FileTime = FileTime.fromMillis(entry.lastModifiedMillis)

    override fun creationTime(): FileTime = FileTime.fromMillis(entry.lastModifiedMillis)

    override fun isRegularFile(): Boolean = !entry.isDirectory && !entry.isSymbolicLink

    override fun isDirectory(): Boolean = entry.isDirectory

    override fun isSymbolicLink(): Boolean = entry.isSymbolicLink

    override fun isOther(): Boolean = false

    override fun size(): Long = entry.size

    /**
     * No stable file key.
     *
     * The identity of a file here is its path, and that is exactly what a file
     * key is not for. Returning null is the documented way to say "this
     * filesystem has no keys", which is true rather than merely convenient.
     */
    override fun fileKey(): Any? = null

    override fun permissions(): Set<PosixFilePermission> {
        val mode = entry.permissions
        val out = mutableSetOf<PosixFilePermission>()
        val owner = (mode shr 6) and 0b111
        val group = (mode shr 3) and 0b111
        val others = mode and 0b111

        if (owner and 0b100 != 0) out += PosixFilePermission.OWNER_READ
        if (owner and 0b010 != 0) out += PosixFilePermission.OWNER_WRITE
        if (owner and 0b001 != 0) out += PosixFilePermission.OWNER_EXECUTE
        if (group and 0b100 != 0) out += PosixFilePermission.GROUP_READ
        if (group and 0b010 != 0) out += PosixFilePermission.GROUP_WRITE
        if (group and 0b001 != 0) out += PosixFilePermission.GROUP_EXECUTE
        if (others and 0b100 != 0) out += PosixFilePermission.OTHERS_READ
        if (others and 0b010 != 0) out += PosixFilePermission.OTHERS_WRITE
        if (others and 0b001 != 0) out += PosixFilePermission.OTHERS_EXECUTE
        return out
    }

    override fun owner(): UserPrincipal = AndroidPrincipal

    override fun group(): GroupPrincipal = AndroidPrincipal
}

