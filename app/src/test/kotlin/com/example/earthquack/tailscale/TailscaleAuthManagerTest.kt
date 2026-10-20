package com.example.earthquack.tailscale

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Phase 1 browser-login flow.
 *
 * Everything here runs on the JVM against a fake [TailscaleNode]: no device,
 * no `libgojni.so`, and it runs in CI. The state machine is the part of the
 * embedded-Tailscale foundation most likely to regress silently — the failure
 * mode is "the app sits on a spinner while the user is already logged in" —
 * so the transitions are pinned here rather than discovered on a phone.
 */
class TailscaleAuthManagerTest {

    /** Fake node: replays a scripted sequence of Start/Status replies. */
    private class FakeNode(
        startReply: String,
        private val statusReplies: List<String> = emptyList(),
        /** Invoked at the top of every status() call, before the reply is served. */
        private val onStatus: () -> Unit = {},
    ) : TailscaleNode {
        var startArgs: Pair<String, String>? = null
        var statusCalls = 0
        private var start = startReply
        private val statuses = statusReplies.toMutableList()

        override fun start(stateDir: String, hostname: String): String {
            startArgs = stateDir to hostname
            return start
        }

        override fun status(): String {
            statusCalls++
            onStatus()
            return statuses.removeFirstOrNull() ?: start
        }

        override fun stop() = Unit
    }

    private fun manager(node: TailscaleNode, timeoutMs: Long = 30_000) = TailscaleAuthManager(
        store = object : TailscaleNodeConfig {
            override fun stateDirPath() = "/data/files/tailscale"
            override fun advertisedHostname() = "pixel-8"
        },
        node = node,
        io = Dispatchers.Unconfined,
        pollIntervalMs = 1, // effectively "as fast as the loop can go"
        loginTimeoutMs = timeoutMs,
    )

    // ── JSON parsing ───────────────────────────────────────────────────────

    @Test
    fun `status Running parses to Running with ip and socks port`() {
        val state = TailscaleAuthManager.parseStatusResult(
            """{"state":"Running","node_ip":"100.92.160.31","login_url":"","socks_port":43123}"""
        )
        assertEquals(TailscaleLoginState.Running("100.92.160.31", 43123), state)
    }

    @Test
    fun `status NeedsLogin keeps the login url`() {
        val state = TailscaleAuthManager.parseStatusResult(
            """{"state":"NeedsLogin","node_ip":"","login_url":"https://login.tailscale.com/a/abc","socks_port":0}"""
        )
        assertEquals(
            TailscaleLoginState.NeedsLogin("https://login.tailscale.com/a/abc"),
            state,
        )
    }

    @Test
    fun `shim error carries its message`() {
        val state = TailscaleAuthManager.parseStatusResult(
            """{"state":"Error","error":"mkdir /x: permission denied","socks_port":0}"""
        )
        assertEquals(
            TailscaleLoginState.Error("mkdir /x: permission denied"),
            state,
        )
    }

    /**
     * An error with no message must not surface as a blank Error — the UI
     * would show an empty snackbar and the logs would say nothing.
     */
    @Test
    fun `shim error without message gets a placeholder`() {
        val state = TailscaleAuthManager.parseStatusResult("""{"state":"Error","socks_port":0}""")
        assertEquals(TailscaleLoginState.Error("unknown tailscale error"), state)
    }

    /**
     * The node reports its BackendState verbatim, so states we do not
     * special-case ("Starting", "NoState", "Stopped") arrive mid-flight.
     * They must not be misread as failures — the poll loop keeps waiting.
     */
    @Test
    fun `transient backend states parse to Starting, not Error`() {
        for (s in listOf("Starting", "NoState", "Stopped", "")) {
            val state = TailscaleAuthManager.parseStatusResult("""{"state":"$s"}""")
            assertTrue("state=$s should be Starting, was $state", state is TailscaleLoginState.Starting)
        }
    }

    @Test
    fun `unknown backend state is an error, not a silent hang`() {
        val state = TailscaleAuthManager.parseStatusResult("""{"state":"Weird"}""")
        assertTrue(state is TailscaleLoginState.Error)
    }

    @Test
    fun `malformed JSON is an error rather than a crash`() {
        val state = TailscaleAuthManager.parseStatusResult("not json at all")
        assertTrue(state is TailscaleLoginState.Error)
    }

    // ── State machine ──────────────────────────────────────────────────────

    /** An already-enrolled node goes straight to Running: no login UI. */
    @Test
    fun `enrolled node reaches Running without login`() = runBlocking {
        val node = FakeNode(
            startReply = """{"state":"Running","node_ip":"100.1.2.3","socks_port":1234}"""
        )
        val mgr = manager(node)

        mgr.startLogin()

        assertEquals(TailscaleLoginState.Running("100.1.2.3", 1234), mgr.state.value)
        assertEquals("/data/files/tailscale" to "pixel-8", node.startArgs)
    }

    /**
     * The core Phase 1 flow: Start reports NeedsLogin with a URL, the user
     * logs in, the next Status poll says Running.
     */
    @Test
    fun `first run surfaces login url then reaches Running`() = runBlocking {
        val node = FakeNode(
            startReply = """{"state":"NeedsLogin","login_url":"https://login.tailscale.com/a/x","socks_port":0}""",
            statusReplies = listOf(
                """{"state":"NeedsLogin","login_url":"https://login.tailscale.com/a/x","socks_port":0}""",
                """{"state":"Running","node_ip":"100.9.9.9","socks_port":777}""",
            ),
        )
        val mgr = manager(node)

        mgr.startLogin()

        assertEquals(TailscaleLoginState.Running("100.9.9.9", 777), mgr.state.value)
        // Polling happened: at least one status() call after Start().
        assertTrue(node.statusCalls >= 1)
    }

    /**
     * tsnet often reports NeedsLogin *without* an auth URL first — the URL
     * is minted once control is contacted. The UI must see the URL appear
     * on a later poll, not treat the empty one as final.
     *
     * The observed state is sampled from inside the fake's status() hook, so
     * the assertion is on exactly what the manager had published by the time
     * the URL-less replies ran — no StateFlow-conflation raciness.
     */
    @Test
    fun `login url arriving late is surfaced when it appears`() = runBlocking {
        val sampled = mutableListOf<TailscaleLoginState?>()
        lateinit var mgr: TailscaleAuthManager
        val node = FakeNode(
            startReply = """{"state":"NeedsLogin","login_url":"","socks_port":0}""",
            statusReplies = listOf(
                // First poll: still no URL. Second: URL minted. Third: logged in.
                """{"state":"NeedsLogin","login_url":"","socks_port":0}""",
                """{"state":"NeedsLogin","login_url":"https://login.tailscale.com/a/late","socks_port":0}""",
                """{"state":"Running","node_ip":"100.5.5.5","socks_port":1}""",
            ),
            onStatus = { sampled += mgr.state.value },
        )
        mgr = manager(node)

        mgr.startLogin()

        assertEquals(3, node.statusCalls)
        // The state is published *after* each status() reply is processed, so
        // the sample taken at the top of call 3 reflects the outcome of calls
        // 1 and 2: poll 2's reply minted the URL, so by call 3 the manager
        // must be publishing NeedsLogin with the real URL — not stuck on the
        // empty one from Start().
        assertTrue(
            "expected NeedsLogin with the real URL by poll 3, saw ${sampled.getOrNull(2)}",
            sampled.getOrNull(2) is TailscaleLoginState.NeedsLogin &&
                (sampled[2] as TailscaleLoginState.NeedsLogin).loginUrl.endsWith("/late"),
        )
        assertEquals(TailscaleLoginState.Running("100.5.5.5", 1), mgr.state.value)
    }

    /** A node whose Start() fails must land in Error with the message. */
    @Test
    fun `start throwing lands in Error`() = runBlocking {
        val node = object : TailscaleNode {
            override fun start(stateDir: String, hostname: String): String =
                throw IllegalStateException("libgojni.so missing")
            override fun status() = "{}"
            override fun stop() = Unit
        }
        val mgr = manager(node)

        mgr.startLogin()

        val state = mgr.state.value
        assertTrue("expected Error, was $state", state is TailscaleLoginState.Error)
        assertTrue((state as TailscaleLoginState.Error).message.contains("libgojni.so"))
    }

    /** Never logging in must time out with a retryable error, not spin forever. */
    @Test
    fun `login that never completes times out`() = runBlocking {
        val node = FakeNode(
            startReply = """{"state":"NeedsLogin","login_url":"https://login.tailscale.com/a/x","socks_port":0}""",
        )
        val mgr = manager(node, timeoutMs = 50)

        mgr.startLogin()

        val state = mgr.state.value
        assertTrue("expected a timeout Error, was $state", state is TailscaleLoginState.Error)
        assertTrue((state as TailscaleLoginState.Error).message.contains("timed out"))
    }

    /** An error during polling stops the loop instead of spinning to timeout. */
    @Test
    fun `status error mid-login stops with Error`() = runBlocking {
        val node = FakeNode(
            startReply = """{"state":"NeedsLogin","login_url":"https://login.tailscale.com/a/x","socks_port":0}""",
            statusReplies = listOf("""{"state":"Error","error":"control plane unreachable"}"""),
        )
        val mgr = manager(node)

        mgr.startLogin()

        assertEquals(TailscaleLoginState.Error("control plane unreachable"), mgr.state.value)
    }

    /** reset() must return the machine to Idle so login can be retried. */
    @Test
    fun `reset returns to Idle`() = runBlocking {
        val node = FakeNode(startReply = """{"state":"Running","node_ip":"100.0.0.1","socks_port":1}""")
        val mgr = manager(node)

        mgr.startLogin()
        mgr.reset()

        assertEquals(TailscaleLoginState.Idle, mgr.state.value)
    }

    /** JSON helper: JSONObject used directly in fakes must not need optString defaults. */
    @Test
    fun `start result with extra fields still parses`() {
        val state = TailscaleAuthManager.parseStartResult(
            JSONObject()
                .put("state", "Running")
                .put("node_ip", "100.64.0.1")
                .put("socks_port", 42)
                .put("unexpected_future_field", true)
                .toString()
        )
        assertEquals(TailscaleLoginState.Running("100.64.0.1", 42), state)
    }
}
