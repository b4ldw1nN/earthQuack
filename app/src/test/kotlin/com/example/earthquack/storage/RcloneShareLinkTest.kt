package com.example.earthquack.storage

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RcloneShareLink].
 *
 * These run without `libgojni.so`: the native boundary is [RcloneBridge], so a
 * fake stands in for rclone and the whole workflow — collision avoidance,
 * remote→remote copy, permission/link creation, and the three outcome kinds — is
 * exercised on the Kotlin side. The real native path is verified separately on a
 * device (see `RcloneInstrumentedTest`).
 *
 * The bundled rclone is v1.75.1. Its `operations/publiclink` creates a real
 * Google Drive permission (`type=anyone, role=reader`) and returns a
 * `https://drive.google.com/open?id=…` URL, so a success here models exactly
 * what the device will do.
 */
class RcloneShareLinkTest {

    private fun engine(
        reply: (String, JSONObject) -> JSONObject
    ): RcloneEngine = RcloneEngine(FakeBridge(reply)).apply { initialize("") }

    private fun request(
        srcFs: String = "sftp:laptop:",
        srcRemote: String = "docs/report.pdf",
        dstFs: String = "gdrive:",
        dstFolder: String = "",
        fileName: String = "report.pdf"
    ) = ShareLinkRequest(srcFs, srcRemote, dstFs, dstFolder, fileName)

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun `successful share returns the url and copies remote to remote`() = runTest {
        val seen = mutableListOf<Pair<String, JSONObject>>()
        val engine = engine { method, params ->
            seen += method to params
            when (method) {
                "operations/list" -> JSONObject().put("list", JSONArray())
                "operations/copyfile" -> JSONObject()
                "operations/publiclink" ->
                    JSONObject().put("url", "https://drive.google.com/open?id=ABC")
                else -> JSONObject()
            }
        }

        val result = RcloneShareLink(engine).share(request())

        assertTrue(result is ShareLinkResult.Success)
        result as ShareLinkResult.Success
        assertEquals("https://drive.google.com/open?id=ABC", result.url)
        assertEquals("report.pdf", result.dstRemote)

        // The copy must be remote→remote, never via a local Android path: a
        // download-then-reupload would set dstFs to a :local: root and this
        // workflow must not.
        val copy = seen.first { it.first == "operations/copyfile" }.second
        assertEquals("sftp:laptop:", copy.getString("srcFs"))
        assertEquals("docs/report.pdf", copy.getString("srcRemote"))
        assertEquals("gdrive:", copy.getString("dstFs"))
        assertEquals("report.pdf", copy.getString("dstRemote"))
    }

    @Test
    fun `the destination folder is joined into the copy and link paths`() = runTest {
        val params = mutableListOf<Pair<String, JSONObject>>()
        val engine = engine { method, p ->
            params += method to p
            when (method) {
                "operations/list" -> JSONObject().put("list", JSONArray())
                "operations/copyfile" -> JSONObject()
                else -> JSONObject().put("url", "https://drive.google.com/open?id=X")
            }
        }

        RcloneShareLink(engine).share(request(dstFolder = "Shared/EarthQuack"))

        val copy = params.first { it.first == "operations/copyfile" }.second
        assertEquals("Shared/EarthQuack/report.pdf", copy.getString("dstRemote"))
        val link = params.first { it.first == "operations/publiclink" }.second
        assertEquals("gdrive:", link.getString("fs"))
        assertEquals("Shared/EarthQuack/report.pdf", link.getString("remote"))
    }

    // ── Collision handling ─────────────────────────────────────────────────────

    @Test
    fun `a name already present gets a numbered suffix`() = runTest {
        val params = mutableListOf<Pair<String, JSONObject>>()
        val engine = engine { method, p ->
            params += method to p
            when (method) {
                "operations/list" -> listingWith("report.pdf")
                "operations/copyfile" -> JSONObject()
                else -> JSONObject().put("url", "https://drive.google.com/open?id=X")
            }
        }

        val result = RcloneShareLink(engine).share(request())

        assertTrue(result is ShareLinkResult.Success)
        result as ShareLinkResult.Success
        assertEquals("report (1).pdf", result.dstRemote)
        val copy = params.first { it.first == "operations/copyfile" }.second
        assertEquals("report (1).pdf", copy.getString("dstRemote"))
    }

    @Test
    fun `a suffix skips over every taken number`() = runTest {
        val engine = engine { method, _ ->
            when (method) {
                "operations/list" ->
                    listingWith("report.pdf", "report (1).pdf", "report (2).pdf")
                "operations/copyfile" -> JSONObject()
                else -> JSONObject().put("url", "https://drive.google.com/open?id=X")
            }
        }

        val result = RcloneShareLink(engine).share(request())

        result as ShareLinkResult.Success
        assertEquals("report (3).pdf", result.dstRemote)
    }

    @Test
    fun `an unlistable destination still attempts the share`() = runTest {
        // A list failure must not abort the flow; the copy is still attempted
        // with the plain name (documented trade-off, and the cheap common case
        // lists fine).
        val params = mutableListOf<Pair<String, JSONObject>>()
        val engine = engine { method, p ->
            params += method to p
            when (method) {
                "operations/list" -> throw RcloneException(
                    "operations/list", 500, """{"error":"boom"}""", "boom"
                )
                "operations/copyfile" -> JSONObject()
                else -> JSONObject().put("url", "https://drive.google.com/open?id=X")
            }
        }

        val result = RcloneShareLink(engine).share(request())

        assertTrue(result is ShareLinkResult.Success)
        val copy = params.first { it.first == "operations/copyfile" }.second
        assertEquals("report.pdf", copy.getString("dstRemote"))
    }


    // ── Failure and partial outcomes ───────────────────────────────────────────

    @Test
    fun `a copy failure is reported as a failure and no link is requested`() = runTest {
        val methods = mutableListOf<String>()
        val engine = engine { method, _ ->
            methods += method
            when (method) {
                "operations/list" -> JSONObject().put("list", JSONArray())
                "operations/copyfile" -> throw RcloneException(
                    "operations/copyfile", 500,
                    """{"error":"directory not found"}""", "copy failed"
                )
                else -> JSONObject().put("url", "should-not-happen")
            }
        }

        val result = RcloneShareLink(engine).share(request())

        assertTrue(result is ShareLinkResult.Failure)
        result as ShareLinkResult.Failure
        assertEquals("directory not found", result.detail)
        // The link step must not run after a failed copy: it would create a
        // permission on a file that is not there.
        assertFalse(methods.contains("operations/publiclink"))
    }

    @Test
    fun `a permission failure is a partial upload, not a success`() = runTest {
        // This is the Workspace-admin-blocks-public-sharing case: the copy lands,
        // the permission call 403s. It must be UploadedNoLink, never Success.
        val engine = engine { method, _ ->
            when (method) {
                "operations/list" -> JSONObject().put("list", JSONArray())
                "operations/copyfile" -> JSONObject()
                "operations/publiclink" -> throw RcloneException(
                    "operations/publiclink", 403,
                    """{"error":"The user does not have sufficient permissions"}""",
                    "forbidden"
                )
                else -> JSONObject()
            }
        }

        val result = RcloneShareLink(engine).share(request())

        assertTrue(result is ShareLinkResult.UploadedNoLink)
        result as ShareLinkResult.UploadedNoLink
        assertEquals("report.pdf", result.dstRemote)
        assertTrue(result.detail.contains("sufficient permissions"))
    }

    @Test
    fun `a 200 publiclink with no url is treated as a partial upload`() = runTest {
        // rclone's contract is {"url": …}; an empty/missing url means we cannot
        // honestly hand back a link even though the copy is on Drive.
        val engine = engine { method, _ ->
            when (method) {
                "operations/list" -> JSONObject().put("list", JSONArray())
                "operations/copyfile" -> JSONObject()
                "operations/publiclink" -> JSONObject() // no url key
                else -> JSONObject()
            }
        }

        val result = RcloneShareLink(engine).share(request())

        assertTrue(result is ShareLinkResult.UploadedNoLink)
        result as ShareLinkResult.UploadedNoLink
        assertEquals("report.pdf", result.dstRemote)
    }

    // ── Special filenames ──────────────────────────────────────────────────────

    @Test
    fun `a filename with spaces and unicode survives verbatim when free`() = runTest {
        val params = mutableListOf<Pair<String, JSONObject>>()
        val engine = engine { method, p ->
            params += method to p
            when (method) {
                "operations/list" -> JSONObject().put("list", JSONArray())
                "operations/copyfile" -> JSONObject()
                else -> JSONObject().put("url", "https://drive.google.com/open?id=X")
            }
        }

        val name = "Résumé — café ☕.pdf"
        RcloneShareLink(engine).share(request(fileName = name))

        val copy = params.first { it.first == "operations/copyfile" }.second
        assertEquals(name, copy.getString("dstRemote"))
    }

    @Test
    fun `a colliding unicode name gets a suffixed variant, name intact`() = runTest {
        val name = "Résumé — café ☕.pdf"
        val engine = engine { method, _ ->
            when (method) {
                "operations/list" -> listingWith(name)
                "operations/copyfile" -> JSONObject()
                else -> JSONObject().put("url", "https://drive.google.com/open?id=X")
            }
        }

        val result = RcloneShareLink(engine).share(request(fileName = name))

        result as ShareLinkResult.Success
        assertEquals("Résumé — café ☕ (1).pdf", result.dstRemote)
    }


    // ── Pure helpers ───────────────────────────────────────────────────────────

    @Test
    fun `joinDestination places the file under a trimmed folder`() {
        assertEquals("a/b/c.pdf", RcloneShareLink.joinDestination("a/b", "c.pdf"))
        assertEquals("a/b/c.pdf", RcloneShareLink.joinDestination("a/b/", "c.pdf"))
        assertEquals("a/b/c.pdf", RcloneShareLink.joinDestination("/a/b", "c.pdf"))
        assertEquals("c.pdf", RcloneShareLink.joinDestination("", "c.pdf"))
        assertEquals("c.pdf", RcloneShareLink.joinDestination("/", "c.pdf"))
    }

    @Test
    fun `resolveUniqueName leaves a free name untouched`() {
        assertEquals("a.pdf", RcloneShareLink.resolveUniqueName("a.pdf", setOf("b.pdf")))
    }

    @Test
    fun `resolveUniqueName appends before the extension`() {
        assertEquals("a (1).pdf", RcloneShareLink.resolveUniqueName("a.pdf", setOf("a.pdf")))
    }

    @Test
    fun `resolveUniqueName keeps a leading-dot name whole`() {
        // ".gitignore" has no extension to split at; the suffix goes on the end.
        assertEquals(
            ".gitignore (1)",
            RcloneShareLink.resolveUniqueName(".gitignore", setOf(".gitignore"))
        )
    }

    @Test
    fun `resolveUniqueName handles a name with no extension`() {
        assertEquals("README (1)", RcloneShareLink.resolveUniqueName("README", setOf("README")))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun listingWith(vararg names: String): JSONObject {
        val arr = JSONArray()
        names.forEach { n -> arr.put(JSONObject().put("Name", n).put("IsDir", false)) }
        return JSONObject().put("list", arr)
    }

    /**
     * Records calls and replays scripted replies, mirroring the engine test.
     *
     * The body returns a parsed [JSONObject] (the shape tests naturally build)
     * and this bridge wraps it into rclone's `(status, output)` envelope, so a
     * test reads like the reply it expects rather than hand-serialising JSON.
     * Throwing from the body stands in for rclone reporting an error, exactly as
     * `RcloneFileTransferTest`'s fake does.
     */
    private class FakeBridge(
        private val reply: (String, JSONObject) -> JSONObject
    ) : RcloneBridge {
        override fun initialize(configPath: String) = Unit
        override fun rpc(method: String, input: String): RcloneResult =
            RcloneResult(200, reply(method, JSONObject(input)).toString())
        override fun shutdown() = Unit
    }
}

