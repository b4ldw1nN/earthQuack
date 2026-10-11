package com.example.earthquack.storage

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RcloneShareLink.directLink] — the in-place "Share as
 * link" flow (`rclone link remote:path/to/file` via `operations/publiclink`).
 *
 * These run without `libgojni.so`: a fake [RcloneBridge] stands in for rclone
 * and records every RPC, so each test can assert not just the result but
 * **exactly which calls were made** — the load-bearing guarantee of this
 * feature is that the link is requested for the file's own `(fs, remote)` pair
 * and that no copy, upload or listing ever happens.
 *
 * The bundled rclone is v1.75.1. `operations/publiclink` takes `fs` + `remote`
 * and returns `{"url": …}` (upstream `fs/operations/rc.go`); a backend without
 * the PublicLink feature answers `"<fs> doesn't support public links"`
 * (`fs/operations/operations.go`). Error classification pins to those strings.
 */
class RcloneDirectLinkTest {

    private fun engine(
        reply: (String, JSONObject) -> JSONObject
    ): RcloneEngine = RcloneEngine(FakeBridge(reply)).apply { initialize("") }

    // ── The two guarantees: exact addressing, and nothing else happens ────────

    @Test
    fun `the link is requested for the exact fs and remote of the entry`() = runTest {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val engine = engine { method, params ->
            calls += method to params
            JSONObject().put("url", "https://example.invalid/x")
        }

        RcloneShareLink(engine).directLink(
            DirectLinkRequest(fs = "sftp:laptop:", remote = "docs/quarterly report.pdf")
        )

        val publiclink = calls.single()
        assertEquals("operations/publiclink", publiclink.first)
        assertEquals("sftp:laptop:", publiclink.second.getString("fs"))
        assertEquals("docs/quarterly report.pdf", publiclink.second.getString("remote"))
    }

    @Test
    fun `no upload copy or listing call is ever made`() = runTest {
        val methods = mutableListOf<String>()
        val engine = engine { method, _ ->
            methods += method
            JSONObject().put("url", "https://example.invalid/x")
        }

        RcloneShareLink(engine).directLink(
            DirectLinkRequest(fs = "gdrive:", remote = "photos/img.png")
        )

        // The whole point of the fix: exactly one publiclink, and specifically
        // no copyfile/list — the old flow's upload pipeline.
        assertTrue(methods == listOf("operations/publiclink"))
    }

    @Test
    fun `a unicode and space path reaches rclone verbatim`() = runTest {
        var seenRemote = ""
        val engine = engine { _, params ->
            seenRemote = params.getString("remote")
            JSONObject().put("url", "https://example.invalid/u")
        }

        RcloneShareLink(engine).directLink(
            DirectLinkRequest(fs = ":local:/sdcard/Документы", remote = "проект/финал v2.pdf")
        )

        assertEquals("проект/финал v2.pdf", seenRemote)
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    @Test
    fun `a successful call returns the generated url`() = runTest {
        val engine = engine { _, _ ->
            JSONObject().put("url", "https://drive.google.com/open?id=ABC")
        }

        val result = RcloneShareLink(engine).directLink(
            DirectLinkRequest(fs = "gdrive:", remote = "a.txt")
        )

        assertTrue(result is DirectLinkResult.Success)
        assertEquals(
            "https://drive.google.com/open?id=ABC",
            (result as DirectLinkResult.Success).url
        )
    }

    @Test
    fun `a 200 reply with no url is a failure not a fabricated link`() = runTest {
        val engine = engine { _, _ -> JSONObject() } // no "url" key

        val result = RcloneShareLink(engine).directLink(
            DirectLinkRequest(fs = "gdrive:", remote = "a.txt")
        )

        assertTrue(result is DirectLinkResult.Failure)
    }

    // ── Error classification (rclone v1.75.1 message shapes) ──────────────────

    @Test
    fun `an unsupported backend is reported as unsupported`() {
        val result = RcloneShareLink.classifyLinkError(
            "sftp:laptop: doesn't support public links"
        )
        assertTrue(result is DirectLinkResult.Unsupported)
    }

    @Test
    fun `a missing file is reported as not found`() {
        assertTrue(
            RcloneShareLink.classifyLinkError("directory not found")
                    is DirectLinkResult.NotFound
        )
        assertTrue(
            RcloneShareLink.classifyLinkError("object not found in directory")
                    is DirectLinkResult.NotFound
        )
    }

    @Test
    fun `a permission refusal is reported as an auth failure`() {
        assertTrue(
            RcloneShareLink.classifyLinkError("permission denied")
                    is DirectLinkResult.AuthFailed
        )
        assertTrue(
            RcloneShareLink.classifyLinkError(
                "The user does not have sufficient permissions"
            ) is DirectLinkResult.AuthFailed
        )
        assertTrue(
            RcloneShareLink.classifyLinkError("oauth token expired")
                    is DirectLinkResult.AuthFailed
        )
    }

    @Test
    fun `a connectivity problem is reported as a network failure`() {
        assertTrue(
            RcloneShareLink.classifyLinkError(
                "failed to create link: connection reset by peer"
            ) is DirectLinkResult.Network
        )
        assertTrue(
            RcloneShareLink.classifyLinkError("dial tcp: lookup drive.google.com: no such host")
                    is DirectLinkResult.Network
        )
    }

    @Test
    fun `an unknown error keeps rclone's text as a plain failure`() {
        val result = RcloneShareLink.classifyLinkError("something inexplicable happened")
        assertTrue(result is DirectLinkResult.Failure)
        assertEquals(
            "something inexplicable happened",
            (result as DirectLinkResult.Failure).detail
        )
    }

    @Test
    fun `classification is case-insensitive but preserves the original text`() {
        val result = RcloneShareLink.classifyLinkError("Directory Not Found")
        assertTrue(result is DirectLinkResult.NotFound)
        assertEquals("Directory Not Found", (result as DirectLinkResult.NotFound).detail)
    }

    @Test
    fun `an rclone exception from the engine is classified, not rethrown`() = runTest {
        val engine = engine { _, _ ->
            throw RcloneException(
                "operations/publiclink", 500,
                """{"error":"gdrive: doesn't support public links"}""",
                "gdrive: doesn't support public links"
            )
        }

        val result = RcloneShareLink(engine).directLink(
            DirectLinkRequest(fs = "gdrive:", remote = "a.txt")
        )

        assertTrue(result is DirectLinkResult.Unsupported)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Replays scripted replies, mirroring [RcloneShareLinkTest]'s fake.
     * Throwing from the body stands in for rclone reporting an error, exactly
     * as the engine's real bridge does with a non-200 status.
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