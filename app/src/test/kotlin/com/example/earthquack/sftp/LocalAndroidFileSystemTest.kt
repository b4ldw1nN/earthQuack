package com.example.earthquack.sftp

import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.HostKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Confinement rules for [LocalAndroidFileSystem].
 *
 * These are the tests that matter for the server's security: they are the
 * difference between "the root is configured" and "a client cannot reach
 * anything outside it". They run on the JVM because the class under test is
 * plain `java.io` — no Android, no device, no network.
 */
class LocalAndroidFileSystemTest {

    private lateinit var root: File
    private lateinit var fs: LocalAndroidFileSystem

    private fun setUp(): LocalAndroidFileSystem {
        root = createTempDir()
        fs = LocalAndroidFileSystem(root)
        return fs
    }

    @Test
    fun `root is exposed as slash`() {
        setUp()
        assertEquals("/", fs.realPath("/"))
        assertEquals("/", fs.stat("/")?.path)
        assertTrue(fs.isDirectory("/"))
    }

    @Test
    fun `nested paths resolve inside the root`() {
        setUp()
        File(root, "a/b").mkdirs()
        assertEquals("/a/b", fs.realPath("/a/b"))
        assertTrue(fs.isDirectory("/a/b"))
    }

    @Test
    fun `dot segments are ignored`() {
        setUp()
        File(root, "dir").mkdirs()
        assertEquals("/dir", fs.realPath("/dir/./"))
        assertEquals("/dir", fs.realPath("/./dir"))
    }

    @Test
    fun `dot-dot inside the root is allowed`() {
        setUp()
        File(root, "a/b").mkdirs()
        assertEquals("/a", fs.realPath("/a/b/.."))
    }

    @Test
    fun `dot-dot above the root is refused`() {
        setUp()
        assertRefused("/..")
        assertRefused("/../")
        assertRefused("/a/../../")
        // "..%2F" is NOT traversal: SFTP paths are raw UTF-8 and are never
        // URL-decoded (OpenSSH's resolveFile does no decoding either). This is
        // a legal single segment — a file whose name literally contains a
        // percent sign — and it must resolve inside the root as such.
        val resolved = fs.resolve("/..%2F")
        assertTrue(
            "expected /..%2F to stay inside the root, got ${resolved.canonicalPath}",
            resolved.canonicalPath.startsWith(root.canonicalPath)
        )
    }

    @Test
    fun `absolute path escapes are confined to the served root`() {
        setUp()
        // The SFTP root IS "/" for the client: an absolute device-looking path
        // is served relative to the root, so "/data/data/..." means
        // "<root>/data/data/...". What matters is that the real device path is
        // never reachable — the resolved file must stay under the root. This
        // matches OpenSSH's ChrootDirectory, which rewrites rather than refuses.
        val one = fs.resolve("/data/data/com.example/files/secret")
        assertTrue(
            "expected confinement, got ${one.canonicalPath}",
            one.canonicalPath.startsWith(root.canonicalPath)
        )
        assertFalse(
            "the device path itself must not be resolved",
            one.canonicalPath == "/data/data/com.example/files/secret"
        )
        val two = fs.resolve("/data/local/tmp/x")
        assertTrue(
            "expected confinement, got ${two.canonicalPath}",
            two.canonicalPath.startsWith(root.canonicalPath)
        )
    }

    @Test
    fun `traversal through a symlink pointing outside is refused`() {
        setUp()
        val outside = createTempDir()
        File(outside, "secret.txt").writeText("secret")
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), File(outside, "secret.txt").toPath())

        // Listing the root is fine; the link itself is reported as a link.
        val entries = fs.list("/")
        assertEquals(1, entries.size)
        assertEquals("link", entries[0].name)
        assertTrue(entries[0].isSymbolicLink)

        // But the link's target is outside the root, so it cannot be opened.
        assertRefused("/link")
        assertRefused("/link/")
    }

    @Test
    fun `symlink to a directory outside the root is refused`() {
        setUp()
        val outside = createTempDir()
        File(outside, "sub").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(root, "dlink").toPath(), File(outside, "sub").toPath())
        assertRefused("/dlink")
        assertRefused("/dlink/")
    }

    @Test
    fun `symlink inside the root is allowed`() {
        setUp()
        File(root, "real").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "real").toPath())
        // The link resolves to a real directory inside the root...
        assertTrue(fs.isDirectory("/alias"))
        // ...and SFTP REALPATH returns the canonical target, exactly as
        // OpenSSH's realpath(1) does. What matters for confinement is that the
        // result stays inside the root — following an *internal* link is safe.
        assertEquals("/real", fs.realPath("/alias"))
    }

    @Test
    fun `writing outside the root through a traversal fails`() {
        setUp()
        val outside = File(createTempDir(), "escaped.txt")
        // The write path goes through the same resolve() as every other call.
        val caught = runCatching { fs.openWrite("/../escaped.txt", append = false) }
        assertTrue("expected a PathEscapeException", caught.isFailure)
        assertFalse("nothing should have been written", outside.exists())
    }

    @Test
    fun `rename cannot move a file outside the root`() {
        setUp()
        File(root, "file.txt").writeText("x")
        val outside = createTempDir()
        val caught = runCatching { fs.rename("/file.txt", "/../outside.txt") }
        assertTrue(caught.isFailure)
        assertTrue(File(root, "file.txt").exists())
        assertEquals(0, outside.listFiles()?.size ?: 0)
    }

    @Test
    fun `rename inside the root works`() {
        setUp()
        File(root, "a.txt").writeText("x")
        assertTrue(fs.rename("/a.txt", "/b.txt"))
        assertEquals("x", File(root, "b.txt").readText())
    }

    @Test
    fun `delete of a non-empty directory requires recursive`() {
        setUp()
        File(root, "d/inner").mkdirs()
        val caught = runCatching { fs.delete("/d", recursive = false) }
        assertTrue(caught.isFailure)
        assertTrue(File(root, "d").exists())

        assertTrue(fs.delete("/d", recursive = true))
        assertFalse(File(root, "d").exists())
    }

    @Test
    fun `stat of a missing path returns null`() {
        setUp()
        assertNull(fs.stat("/nope"))
    }

    @Test
    fun `permissions are reported from what the app can actually do`() {
        setUp()
        val file = File(root, "f.txt")
        file.writeText("x")
        val entry = fs.stat("/f.txt")
        assertNotNull(entry)
        assertTrue(entry!!.permissions and 0b100_000_000 != 0) // owner read
    }

    @Test
    fun `realPath of a normalised traversal stays inside`() {
        setUp()
        File(root, "a/b").mkdirs()
        assertEquals("/a", fs.realPath("/a/b/.."))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun assertNull(value: Any?) = assertTrue("expected null", value == null)

    private fun assertNotNull(value: Any?) = assertTrue("expected non-null", value != null)

    private fun assertRefused(path: String) {
        val caught = runCatching { fs.resolve(path) }
        assertTrue("expected $path to be refused", caught.isFailure)
        val exception = caught.exceptionOrNull()
        assertTrue(
            "expected PathEscapeException for $path, got $exception",
            exception is PathEscapeException
        )
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("sftp-test").toFile()
}
