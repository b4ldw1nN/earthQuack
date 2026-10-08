package com.example.earthquack.sftp.fs

import com.example.earthquack.sftp.AndroidFileSystem
import java.io.File
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchKey
import java.nio.file.WatchEvent
import java.nio.file.WatchService
import java.net.URI

/**
 * A [Path] into an [AndroidFileSystem].
 *
 * ## Purely lexical
 *
 * A `Path` here is a string that has been normalised — no `.`, no `..`, exactly
 * one leading `/`. It performs **no** I/O and no existence check, which is what
 * the [Path] contract requires and what keeps `getParent()` cheap enough to
 * call in a listing loop. Symlink resolution is the backend's job, at the point
 * of use, where it can throw instead of quietly returning something else.
 *
 * ## toFile is refused
 *
 * [toFile] throws. It is the escape hatch out of this abstraction straight into
 * `java.io`, and every path from the protocol code into the protocol code
 * through it would skip the confinement rules. The protocol layer must go
 * through the provider instead, which enforces them.
 */
internal class AndroidPath(
    private val fileSystem: AndroidFileSystemNio,
    /** Normalised, absolute, no trailing slash. The root is exactly "/". */
    val pathString: String
) : Path {

    /** The path split into its non-empty segments. */
    private val segments: List<String> =
        if (pathString == "/") emptyList() else pathString.substring(1).split("/")

    override fun getFileSystem(): FileSystem = fileSystem

    override fun isAbsolute(): Boolean = true

    override fun getRoot(): Path? = if (pathString == "/") null else fileSystem.getPath("/")

    override fun getFileName(): Path? =
        if (segments.isEmpty()) null else fileSystem.getPath("/" + segments.last())

    override fun getParent(): Path? {
        if (segments.isEmpty()) return null
        val parent = segments.dropLast(1)
        return fileSystem.getPath(if (parent.isEmpty()) "/" else "/" + parent.joinToString("/"))
    }

    override fun getNameCount(): Int = segments.size

    override fun getName(index: Int): Path = fileSystem.getPath("/" + segments[index])

    override fun subpath(fromIndex: Int, toIndex: Int): Path {
        if (fromIndex < 0 || toIndex > segments.size || fromIndex >= toIndex) {
            throw IllegalArgumentException("bad subpath range $fromIndex..$toIndex for $pathString")
        }
        return fileSystem.getPath("/" + segments.subList(fromIndex, toIndex).joinToString("/"))
    }

    override fun startsWith(other: Path): Boolean {
        val prefix = other.toString()
        if (prefix == "/") return true
        return pathString == prefix || pathString.startsWith("$prefix/")
    }

    override fun endsWith(other: Path): Boolean {
        val suffix = other.toString().trim('/')
        return segments.lastOrNull() == suffix
    }

    override fun startsWith(other: String): Boolean =
        pathString == other || pathString.startsWith("$other/")

    override fun endsWith(other: String): Boolean = segments.lastOrNull() == other.trim('/')

    /**
     * Absolute-path resolution: [other] wins unless it is relative.
     *
     * Every path produced here is absolute, so this only ever has the one
     * meaningful case. It exists because [Path] declares it abstract, and
     * returning "not supported" from a method sshd might call during a
     * canonicalisation pass would surface as an unexplained SFTP failure.
     */
    override fun resolve(other: Path): Path =
        if (other.isAbsolute) other else getPath(resolveString(other.toString()))

    override fun resolve(other: String): Path =
        if (other.startsWith("/")) getPath(other) else getPath(resolveString(other))

    override fun resolveSibling(other: Path): Path = resolve(other)

    override fun resolveSibling(other: String): Path = resolve(other)

    /** Present because android.jar declares the `Iterable` default as abstract. */
    override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
        private var index = 0
        override fun hasNext(): Boolean = index < segments.size
        override fun next(): Path = getName(index++)
        override fun remove() = throw UnsupportedOperationException()
    }

    override fun relativize(other: Path): Path {
        val mine = segments
        val theirs = if (other.toString() == "/") emptyList() else other.toString().substring(1).split("/")
        var common = 0
        while (common < mine.size && common < theirs.size && mine[common] == theirs[common]) common++
        val up = mine.drop(common).map { ".." }
        val down = theirs.drop(common)
        return getPath((up + down).joinToString("/"))
    }

    /** Joins a relative path onto this one, without touching the filesystem. */
    internal fun resolveString(relative: String): String {
        if (relative.startsWith("/")) return relative
        val joined = if (pathString == "/") relative else "$pathString/$relative"
        return AndroidPath.of(fileSystem, joined).pathString
    }

    private fun getPath(value: String): AndroidPath = AndroidPath.of(fileSystem, value)

    /** Already normal; returns this. */
    override fun normalize(): Path = this

    override fun toAbsolutePath(): Path = this

    @Throws(IOException::class)
    override fun toRealPath(vararg linkOptions: LinkOption): Path =
        fileSystem.getPath(fileSystem.backend.realPath(pathString))

    override fun toUri(): URI = URI(SCHEME, "", pathString, null)

    override fun toFile(): File = throw UnsupportedOperationException(
        "the SFTP filesystem is not a java.io filesystem; go through FileSystemProvider"
    )

    @Throws(IOException::class)
    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey = throw UnsupportedOperationException("watching is not supported")

    @Throws(IOException::class)
    override fun register(
        watcher: WatchService,
        vararg events: WatchEvent.Kind<*>
    ): WatchKey = throw UnsupportedOperationException("watching is not supported")

    override fun compareTo(other: Path): Int = pathString.compareTo(other.toString())

    override fun equals(other: Any?): Boolean =
        other is AndroidPath && other.pathString == pathString

    override fun hashCode(): Int = pathString.hashCode()

    override fun toString(): String = pathString

    companion object {
        const val SCHEME = "android"

        /**
         * Builds a path from raw client input.
         *
         * Normalisation here is textual only (`.` dropped, `..` popped, slashes
         * collapsed) and refuses to climb above the root, exactly as the
         * backend does. Two independent checks on the same rule are deliberate:
         * this one keeps the `Path` objects well-formed, and the backend's
         * catches what reaches it through a route that skipped this.
         */
        fun of(fileSystem: AndroidFileSystemNio, raw: String): AndroidPath {
            val out = mutableListOf<String>()
            for (segment in raw.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> {
                        if (out.isEmpty()) {
                            throw com.example.earthquack.sftp.PathEscapeException(raw)
                        }
                        out.removeAt(out.size - 1)
                    }
                    else -> out.add(segment)
                }
            }
            return AndroidPath(fileSystem, if (out.isEmpty()) "/" else "/" + out.joinToString("/"))
        }

        /** Parses `android:///a/b` back into a path. */
        fun fromUri(fileSystem: AndroidFileSystemNio, uri: URI): AndroidPath {
            val raw = uri.path ?: uri.schemeSpecificPart ?: "/"
            return of(fileSystem, if (raw.startsWith("/")) raw else "/$raw")
        }
    }
}

/**
 * [FileSystem] view of an [AndroidFileSystem].
 *
 * One instance per SFTP session, created by the factory, because sshd closes
 * it when the session ends. It holds no per-session state beyond that, and the
 * [AndroidFileSystem] behind it is shared — closing the view must never close
 * the storage.
 */
internal class AndroidFileSystemNio(
    /** The storage being served. Shared across sessions. */
    val backend: AndroidFileSystem
) : FileSystem() {

    private var providerRef: AndroidFileSystemProvider? = null

    /** Created on demand: the provider needs this instance, so it cannot be a constructor argument. */
    val providerInstance: AndroidFileSystemProvider
        get() = providerRef ?: AndroidFileSystemProvider(this).also { providerRef = it }

    override fun provider(): java.nio.file.spi.FileSystemProvider = providerInstance

    override fun getSeparator(): String = "/"

    override fun close() {
        // Nothing to release: the backend outlives every session. Overriding
        // this to throw would break sshd's normal teardown path.
    }

    override fun isOpen(): Boolean = true

    override fun getPath(first: String?, vararg more: String?): Path {
        val joined = buildString {
            append(first ?: "/")
            more.forEach { append('/').append(it) }
        }
        return AndroidPath.of(this, joined)
    }

    override fun isReadOnly(): Boolean = false

    /**
     * Glob matching by hand.
     *
     * Android's `FileSystem` declares this abstract and `PathMatcher` is not
     * optional, but no SFTP operation uses it. Supporting `*` and `?` over the
     * final segment is enough to be correct for the callers that could reach
     * it, and is far better than an `UnsupportedOperationException` from an
     * abstract method.
     */
    override fun getPathMatcher(pattern: String): PathMatcher {
        val regex = buildString {
            append("^")
            pattern.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append("$")
        }
        val compiled = Regex(regex)
        return PathMatcher { path -> compiled.matches(path.fileName?.toString() ?: "") }
    }

    override fun getFileStores(): MutableIterable<java.nio.file.FileStore> =
        throw UnsupportedOperationException("file stores are not reported by this filesystem")

    override fun getUserPrincipalLookupService(): java.nio.file.attribute.UserPrincipalLookupService =
        throw UnsupportedOperationException("no user principal service")

    override fun supportedFileAttributeViews(): Set<String> = setOf("basic", "posix")

    override fun newWatchService(): WatchService =
        throw UnsupportedOperationException("watching is not supported")

    override fun getRootDirectories(): MutableIterable<Path> =
        mutableListOf(getPath("/"))

    override fun toString(): String = "${AndroidPath.SCHEME}://${backend.rootPath}"
}
