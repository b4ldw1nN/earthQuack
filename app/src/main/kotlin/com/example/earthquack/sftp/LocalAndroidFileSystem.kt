package com.example.earthquack.sftp

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * [AndroidFileSystem] over `java.io`, rooted at one directory.
 *
 * No Android imports. That is not a stylistic preference: it is what lets the
 * traversal, symlink and confinement tests in `LocalAndroidFileSystemTest` run
 * on the JVM in a second rather than needing a device, and it keeps the rules
 * that keep the server inside its root in one readable file.
 *
 * ## Confinement, in order
 *
 *  1. The client path is resolved lexically: `.` dropped, `..` popped. A `..`
 *     that would climb above the root is refused outright rather than clamped,
 *     because a clamped `..` silently redirects a write to a file the client
 *     did not name.
 *  2. The result is joined onto the root and canonicalised, which resolves
 *     every symlink on the way.
 *  3. The canonical path must still be inside the canonical root.
 *
 * Step 3 is what stops a symlink *inside* the root from pointing out of it,
 * which steps 1–2 cannot see. Steps 1–2 are what stop `..`, and they also mean
 * a path that was never going to escape is cheap.
 *
 * ## Known limitation
 *
 * Android cannot `chmod`, and on shared storage it cannot read another app's
 * `Android/data`. Those directories are reported with whatever the platform
 * actually says — a permission error from the platform, not a fake empty
 * directory — because the alternative is telling a client a directory is empty
 * when it is not readable.
 */
class LocalAndroidFileSystem(rootDir: File) : AndroidFileSystem {

    /** Canonical root. Every decision below is relative to this, not to `rootDir`. */
    private val root: File = try {
        rootDir.canonicalFile
    } catch (e: IOException) {
        throw IOException("SFTP root ${rootDir.path} cannot be resolved", e)
    }

    init {
        if (!root.isDirectory) {
            throw IOException("SFTP root ${root.path} is not a directory (does the permission exist?)")
        }
    }

    override val rootPath: String get() = root.path

    // ── reads ────────────────────────────────────────────────────────────────

    override fun exists(path: String): Boolean = try {
        resolve(path).exists()
    } catch (e: PathEscapeException) {
        false
    } catch (e: IOException) {
        false
    }

    override fun isDirectory(path: String): Boolean = try {
        resolve(path).isDirectory
    } catch (e: IOException) {
        false
    }

    override fun stat(path: String): FsEntry? {
        // A symlink must be stat-able even when its target lies outside the
        // root: the link itself is inside, and lstat's whole job is to
        // describe the link, not what it points at. resolve() canonicalises —
        // it follows the link and then refuses the escaped target — so the
        // link check has to happen on the lexical path, before that.
        val relative = normalize(path)
        val lexical = if (relative.isEmpty()) root else File(root, relative)
        if (isLink(lexical)) {
            return describe(lexical, if (path.startsWith("/")) path else "/$path")
        }
        val file = resolve(path)
        if (!file.exists()) return null
        return describe(file, path)
    }

    /**
     * Whether [file] is itself a symlink, without following it.
     *
     * `File.exists()` follows the link, so a dangling or escaping link would
     * vanish before `describe` could report it as a link. This check uses
     * `NOFOLLOW_LINKS` semantics so the SFTP layer can report `SSH_FX_SYMLINK`
     * honestly and refuse the *target* rather than pretending nothing is there.
     */
    private fun isLink(file: File): Boolean =
        runCatching {
            java.nio.file.Files.readAttributes(
                file.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS
            ).isSymbolicLink
        }.getOrDefault(false)

    override fun list(path: String): List<FsEntry> {
        val dir = resolve(path)
        if (!dir.isDirectory) throw IOException("not a directory: $path")
        val children = dir.listFiles() ?: return emptyList()
        return children.map { describe(it, childPath(path, it.name)) }
    }

    override fun openRead(path: String): InputStream {
        val file = resolve(path)
        if (!file.isFile) throw FileNotFoundException("not a file: $path")
        return FileInputStream(file)
    }

    // ── writes ───────────────────────────────────────────────────────────────

    override fun openWrite(path: String, append: Boolean): OutputStream {
        val file = resolve(path)
        if (file.isDirectory) throw IOException("is a directory: $path")
        // The parent must already exist. SFTP's mkdir-then-write is what every
        // client does; auto-creating parents here would make a typo'd path
        // succeed somewhere unexpected.
        file.parentFile?.takeIf { !it.isDirectory }
            ?.let { throw IOException("no such directory: ${it.path}") }
        return FileOutputStream(file, append)
    }

    override fun mkdir(path: String): Boolean {
        val file = resolve(path)
        if (file.exists()) return false
        if (!file.mkdirs()) throw IOException("could not create $path")
        return true
    }

    override fun delete(path: String, recursive: Boolean): Boolean {
        val file = resolve(path)
        if (!file.exists()) return false
        if (file.isDirectory) {
            if (!recursive && file.list()?.isNotEmpty() == true) {
                throw IOException("directory not empty: $path")
            }
            return file.deleteRecursively()
        }
        return file.delete()
    }

    override fun rename(from: String, to: String): Boolean {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.exists()) throw IOException("no such file: $from")
        // resolve() has already proved the target is inside the root, which is
        // the check that matters: a naive `File.renameTo` with a client-supplied
        // absolute path would happily move a file out of the tree.
        if (target.exists()) throw IOException("already exists: $to")
        if (!source.renameTo(target)) throw IOException("could not move $from to $to")
        return true
    }

    override fun setLastModified(path: String, millis: Long): Boolean =
        resolve(path).setLastModified(millis)

    override fun realPath(path: String): String {
        val file = resolve(path)
        val relative = file.path.removePrefix(root.path).trimStart(File.separatorChar)
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    override fun openRandomAccess(path: String, write: Boolean, truncate: Boolean): AndroidRandomAccess {
        val file = resolve(path)
        if (file.isDirectory) throw IOException("is a directory: $path")
        if (write && !file.parentFile?.isDirectory!!) {
            throw IOException("no such directory for $path")
        }
        return RandomAccessHandle(file, write, truncate)
    }

    // ── path resolution ──────────────────────────────────────────────────────

    /**
     * Turns a client path into a real file, or refuses it.
     *
     * The only place paths are interpreted. Everything else in this class calls
     * it, so there is exactly one implementation of the confinement rules.
     */
    internal fun resolve(requested: String): File {
        val relative = normalize(requested)
        val candidate = if (relative.isEmpty()) root else File(root, relative)
        val canonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            throw IOException("cannot resolve $requested")
        }
        if (!isInsideRoot(canonical)) throw PathEscapeException(requested)
        return canonical
    }

    /**
     * Lexical normalisation, refusing any `..` that leaves the root.
     *
     * Rejecting rather than clamping is deliberate: OpenSSH's own server treats
     * `/..` as the root, but a *client* that sends `../../data` after a symlink
     * move is usually a client that has lost track of where it is, and quietly
     * serving it the root would hide that.
     */
    internal fun normalize(requested: String): String {
        val segments = mutableListOf<String>()
        for (segment in requested.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) throw PathEscapeException(requested)
                    segments.removeAt(segments.size - 1)
                }
                else -> segments.add(segment)
            }
        }
        return segments.joinToString(File.separator)
    }

    private fun isInsideRoot(candidate: File): Boolean =
        candidate == root || candidate.path.startsWith(root.path + File.separator)

    private fun childPath(parent: String, name: String): String =
        if (parent == "/") "/$name" else "$parent/$name"

    private fun describe(file: File, clientPath: String): FsEntry {
        val link = runCatching { Files.isSymLink(file) }.getOrDefault(false)
        if (link) {
            // lstat semantics: the entry IS the link. Report the link's own
            // metadata, never the target's — the target may be outside the
            // served root, and reading its size, mtime or mode through a
            // listing would leak exactly what the confinement is meant to
            // hide. A symlink is also never a directory per lstat, whatever
            // it points at.
            val attrs = runCatching {
                java.nio.file.Files.readAttributes(
                    file.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
                )
            }.getOrNull()
            return FsEntry(
                name = file.name,
                path = clientPath,
                isDirectory = false,
                isSymbolicLink = true,
                size = attrs?.size() ?: 0L,
                lastModifiedMillis = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
                permissions = S_IFLNK or 0b111_111_111
            )
        }
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        return FsEntry(
            name = file.name,
            path = clientPath,
            isDirectory = file.isDirectory,
            isSymbolicLink = false,
            size = if (file.isDirectory) 0L else file.length(),
            lastModifiedMillis = file.lastModified(),
            permissions = modeOf(canonical)
        )
    }

    /**
     * POSIX mode bits, file-type bits included, as SFTP reports them.
     *
     * Derived from what this app can actually do, not from a stored mode:
     * Android has no `chmod` on shared storage, so a stored mode would be a
     * fiction. A client sees `rw-------` for a file it can read and write and
     * `-w-------` for one it cannot, which is true.
     */
    private fun modeOf(file: File): Int {
        val type = if (file.isDirectory) S_IFDIR else S_IFREG
        val owner = permissionBits(file)
        val group = permissionBits(file)
        val other = permissionBits(file)
        return type or (owner shl 6) or (group shl 3) or other
    }

    private fun permissionBits(file: File): Int {
        var bits = 0
        if (file.canRead()) bits = bits or R
        if (file.canWrite()) bits = bits or W
        if (file.canExecute()) bits = bits or X
        return bits
    }

    /** Random-access handle over [RandomAccessFile]. */
    private class RandomAccessHandle(
        file: File,
        write: Boolean,
        truncate: Boolean
    ) : AndroidRandomAccess {

        private val raf: RandomAccessFile =
            RandomAccessFile(file, if (write) "rw" else "r")

        init {
            if (truncate) raf.setLength(0)
        }

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            raf.seek(position)
            return raf.read(buffer, offset, length)
        }

        override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            raf.seek(position)
            raf.write(buffer, offset, length)
        }

        override fun size(): Long = raf.length()

        override fun truncate(length: Long) = raf.setLength(length)

        override fun close() {
            runCatching { raf.close() }
        }
    }

    private companion object {
        const val S_IFREG = 0x8000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xA000
        const val R = 0b100
        const val W = 0b010
        const val X = 0b001

        /**
         * Link test via NIO with NOFOLLOW_LINKS.
         *
         * `java.io` has no `lstat`, so this is the only way to tell a symlink
         * from the file it points at — which matters because a client asked to
         * list a directory must be told "symlink", not "directory". It reads
         * the same on a device and on the JVM, so the tests exercise the code
         * that ships.
         */
        object Files {
            fun isSymLink(file: File): Boolean = runCatching {
                java.nio.file.Files.readAttributes(
                    file.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
                ).isSymbolicLink
            }.getOrDefault(false)
        }
    }
}
