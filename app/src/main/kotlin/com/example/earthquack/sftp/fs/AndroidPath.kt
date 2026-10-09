package com.example.earthquack.sftp.fs

import com.example.earthquack.sftp.AndroidFileSystem
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * A [Path] into an [AndroidFileSystem].
 *
 * ## Absolute, plus single-name relative views
 *
 * Every path that names a location is absolute and normalised — no `.`, no
 * `..`, exactly one leading `/` — and does no I/O, which is what the [Path]
 * contract requires and what keeps `getParent()` cheap enough to call in a
 * listing loop.
 *
 * A [Path] can additionally be a *single-name relative view* of an absolute
 * path: the thing `getFileName()`, `getName(i)` and `subpath()` return. That is
 * a distinct state rather than a separate class, because the previous design
 * returned a `NameView` that was not an [AndroidPath] at all. That had three
 * consequences, all of them sshd-visible:
 *
 *  - every provider method rejected it with `ProviderMismatchException`,
 *    because the provider identifies a path by type;
 *  - `getParent()` returned null, so `x.getFileName().getParent()` did not
 *    name the directory the entry was listed from;
 *  - `toAbsolutePath()` resolved against the served root instead of the
 *    entry's own directory, so the name `a.txt` of `/photos/a.txt` came back
 *    as `/a.txt`.
 *
 * Keeping both forms in one class means a relative view is still an
 * [AndroidPath], still resolves to the same absolute location, and can be passed
 * straight to `readAttributes` — while `toString()` still renders the bare name
 * that a directory listing must report (`a.txt`, never `/a.txt`).
 *
 * ## toFile is refused
 *
 * [toFile] throws. It is the escape hatch out of this abstraction straight into
 * `java.io`, and every path from the protocol code into the protocol code
 * through it would skip the confinement rules. The protocol layer must go
 * through the provider instead, which enforces them.
 *
 * ## Symlink resolution
 *
 * Not done here. [AndroidPath] performs no I/O by contract, so it cannot follow
 * a link; resolution belongs to the backend at the point of use, where it can
 * throw instead of quietly returning something else.
 */
internal class AndroidPath(
    private val fileSystem: AndroidFileSystemNio,
    /** Normalised, absolute, no trailing slash. The root is exactly "/". */
    val pathString: String,
    /**
     * The name this path renders as, when it is a relative single-name view.
     *
     * Null for an ordinary absolute path, which renders as [pathString].
     */
    private val displayName: String? = null
) : Path {

    /** The path split into its non-empty segments. */
    private val segments: List<String> =
        if (pathString == "/") emptyList() else pathString.substring(1).split("/")

    /** The absolute path this view names. A relative view resolves to itself. */
    private val absolute: AndroidPath
        get() = if (displayName == null) this else AndroidPath.of(fileSystem, pathString)

    override fun getFileSystem(): FileSystem = fileSystem

    override fun isAbsolute(): Boolean = displayName == null

    override fun getRoot(): Path? = if (displayName != null) null else fileSystem.getPath("/")

    /**
     * The last element, as a relative single-name view.
     *
     * Null only for the root, which has no name. Rendering is the bare name so
     * that a directory listing reports `a.txt` and not `/a.txt`.
     */
    override fun getFileName(): Path? =
        if (displayName != null) this else segments.lastOrNull()
            ?.let { relativeView(it) }

    override fun getParent(): Path? {
        if (displayName != null || segments.isEmpty()) return null
        val parent = segments.dropLast(1)
        return fileSystem.getPath(if (parent.isEmpty()) "/" else "/" + parent.joinToString("/"))
    }

    override fun getNameCount(): Int = if (displayName != null) 1 else segments.size

    override fun getName(index: Int): Path {
        val bounded = requireIndex(index)
        return relativeView(bounded[index])
    }

    override fun subpath(fromIndex: Int, toIndex: Int): Path {
        if (displayName != null) {
            if (fromIndex != 0 || toIndex != 1) {
                throw IllegalArgumentException("bad subpath $fromIndex..$toIndex for $displayName")
            }
            return this
        }
        if (fromIndex < 0 || toIndex > segments.size || fromIndex >= toIndex) {
            throw IllegalArgumentException("bad subpath range $fromIndex..$toIndex for $pathString")
        }
        val selected = segments.subList(fromIndex, toIndex)
        return relativeView(selected.joinToString("/"))
    }

    /** A relative view rendering as [name] but pointing at this path's location. */
    private fun relativeView(name: String): AndroidPath =
        if (displayName == name) this else AndroidPath(fileSystem, pathString, name)

    private fun requireIndex(index: Int): List<String> {
        if (displayName != null) {
            if (index != 0) throw IllegalArgumentException("no segment $index in $displayName")
            return listOf(displayName)
        }
        if (index < 0 || index >= segments.size) {
            throw IllegalArgumentException("no segment $index in $pathString")
        }
        return segments
    }

    override fun startsWith(other: Path): Boolean {
        val prefix = other.toString().trimEnd('/')
        if (prefix.isEmpty()) return true
        return pathString == prefix || pathString.startsWith("$prefix/")
    }

    override fun endsWith(other: Path): Boolean {
        val otherName = other.fileName?.toString() ?: return false
        return segments.lastOrNull() == otherName
    }

    override fun startsWith(other: String): Boolean =
        startsWith(fileSystem.getPath(normaliseText(other)))

    override fun endsWith(other: String): Boolean = segments.lastOrNull() == other.trim('/')

    /** Textual normalisation for a caller-supplied prefix, which need not be clean. */
    private fun normaliseText(raw: String): String {
        val out = mutableListOf<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return if (out.isEmpty()) "/" else "/" + out.joinToString("/")
    }

    /**
     * Resolution against an absolute path.
     *
     * For a relative view the base is the location it was derived from, which is
     * why `photos.getFileName().resolve("b.txt")` is `/photos/b.txt` rather than
     * `/b.txt`.
     */
    override fun resolve(other: Path): Path =
        if (other.isAbsolute) other else resolveString(other.toString())

    override fun resolve(other: String): Path = resolveString(other)

    override fun resolveSibling(other: Path): Path =
        if (other.isAbsolute) other else (getParent() ?: fileSystem.getPath("/")).resolve(other)

    override fun resolveSibling(other: String): Path {
        if (other.startsWith("/")) return fileSystem.getPath(other)
        val parent = getParent() ?: return resolveString(other)
        return parent.resolve(other)
    }

    /** Present because android.jar declares the `Iterable` default as abstract. */
    override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
        private var index = 0
        private val names = if (displayName != null) listOf(displayName) else segments
        override fun hasNext(): Boolean = index < names.size
        override fun next(): Path = relativeView(names[index++])
        override fun remove() = throw UnsupportedOperationException()
    }

    /**
     * The relative path from this one to [other].
     *
     * The result is a *relative* path, which is the whole point of the call: it
     * may legitimately begin with `..`, and building it through the normalising
     * factory would throw [com.example.earthquack.sftp.PathEscapeException] for
     * exactly the case the caller asked for.
     */
    override fun relativize(other: Path): Path {
        if (displayName != null) {
            throw IllegalArgumentException("cannot relativize from the relative path $displayName")
        }
        val target = other.toString()
        if (target == pathString) return AndroidPath.relative(fileSystem, "")
        val theirs = if (target == "/") emptyList() else target.substring(1).split("/")
        var common = 0
        while (common < segments.size && common < theirs.size && segments[common] == theirs[common]) {
            common++
        }
        val up = segments.drop(common).map { ".." }
        val down = theirs.drop(common)
        return AndroidPath.relative(fileSystem, (up + down).joinToString("/"))
    }

    /**
     * Joins a path onto this one, without touching the filesystem.
     *
     * An absolute argument replaces the result, as [Path.resolve] requires; a
     * relative one is appended and normalised. A `..` that would climb above the
     * root is refused by [AndroidPath.of], which is the confinement rule rather
     * than a silent clamp.
     */
    internal fun resolveString(relative: String): AndroidPath {
        if (relative.startsWith("/")) return AndroidPath.of(fileSystem, relative)
        val base = pathString.trimEnd('/')
        return AndroidPath.of(fileSystem, if (base.isEmpty()) relative else "$base/$relative")
    }

    /** Already normal; returns this. */
    override fun normalize(): Path = this

    override fun toAbsolutePath(): Path = absolute

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

    override fun equals(other: Any?): Boolean = other is AndroidPath &&
        other.pathString == pathString &&
        other.displayName == displayName

    override fun hashCode(): Int = 31 * pathString.hashCode() + (displayName?.hashCode() ?: 0)

    override fun toString(): String = displayName ?: pathString

    companion object {
        const val SCHEME = "android"

        /**
         * Builds an absolute path from raw client input.
         *
         * Normalisation here is textual only (`.` dropped, `..` popped, slashes
         * collapsed) and refuses to climb above the root, exactly as the backend
         * does. Two independent checks on the same rule are deliberate: this one
         * keeps the `Path` objects well-formed, and the backend's catches what
         * reaches it through a route that skipped this.
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
            val normalised = if (out.isEmpty()) "/" else "/" + out.joinToString("/")
            return AndroidPath(fileSystem, normalised)
        }

        /**
         * Builds a relative view that renders as [rendered].
         *
         * Used by [relativize], whose result may legitimately be `..`-prefixed
         * and so cannot be produced by [of] — normalising `..` against nothing
         * is exactly the escape [of] refuses. The absolute location is derived
         * separately and carried alongside the text, so the view still points
         * somewhere the provider can act on.
         *
         * @param rendered the text a listing shows, which for `relativize` is
         *   the relative form and for an empty result is the empty string.
         */
        fun relative(fileSystem: AndroidFileSystemNio, rendered: String): AndroidPath {
            val out = mutableListOf<String>()
            for (segment in rendered.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    // Cannot pop past the root here: a relative result that
                    // walked above both paths is not expressible, and silently
                    // clamping it would make the caller believe it had.
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(segment)
                }
            }
            val location = if (out.isEmpty()) "/" else "/" + out.joinToString("/")
            return AndroidPath(fileSystem, location, rendered)
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