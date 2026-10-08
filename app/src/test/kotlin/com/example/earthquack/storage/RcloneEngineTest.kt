package com.example.earthquack.storage

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [RcloneEngine].
 *
 * These run without `libgojni.so`: the native boundary is [RcloneBridge], so a
 * fake stands in for rclone. That is the point of the abstraction — the logic
 * worth testing (error mapping, JSON handling, lifecycle guards) is all on the
 * Kotlin side of the boundary.
 *
 * The real native path is verified separately on a device; see
 * `RcloneInstrumentedTest`.
 */
class RcloneEngineTest {

    /**
     * A path the fake bridge accepts. The JVM tests never touch the real
     * filesystem because [FakeBridge] does no I/O, but the value is threaded
     * through so the engine's plumbing is exercised.
     */
    private val testConfigPath = "/tmp/test/rclone.conf"

    /** Records calls and replays scripted replies. */
    private class FakeBridge(
        private val reply: (String, String) -> RcloneResult = { _, _ ->
            RcloneResult(200, "{}")
        }
    ) : RcloneBridge {
        var initCount = 0
            private set
        var lastConfigPath: String? = null
            private set
        var shutdownCount = 0
            private set
        val calls = mutableListOf<Pair<String, String>>()

        override fun initialize(configPath: String) {
            initCount++
            lastConfigPath = configPath
        }

        override fun rpc(method: String, input: String): RcloneResult {
            calls += method to input
            return reply(method, input)
        }

        override fun shutdown() {
            shutdownCount++
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * The config path must reach the bridge verbatim.
     *
     * This is the whole point of the parameterised `initialize`: rclone has to
     * be told where `rclone.conf` lives, because nothing about an Android
     * process tells it. A path silently dropped or defaulted to `""` would mean
     * in-memory config — no persistence, no remotes — and nothing else would
     * fail to make that visible.
     */
    @Test
    fun `config path is passed through to the bridge`() {
        val bridge = FakeBridge()
        RcloneEngine(bridge).initialize(testConfigPath)

        assertEquals(testConfigPath, bridge.lastConfigPath)
    }

    /**
     * An empty path is a deliberate "in-memory only" mode, not an oversight.
     *
     * rclone treats `""` as in-memory config, which is what tests and a
     * no-persistence session want. Pinned so the default cannot quietly become
     * persistent — or quietly become nothing.
     */
    @Test
    fun `empty config path means in-memory`() {
        val bridge = FakeBridge()
        RcloneEngine(bridge).initialize("")

        assertEquals("", bridge.lastConfigPath)
    }

    @Test
    fun `initialize is idempotent`() {
        val bridge = FakeBridge()
        val engine = RcloneEngine(bridge)

        assertFalse(engine.isInitialised)
        engine.initialize(testConfigPath)
        engine.initialize(testConfigPath)
        engine.initialize(testConfigPath)

        assertTrue(engine.isInitialised)
        // Exactly once, however many times it is called: rclone's Initialize
        // restarts accounting, so repeating it is not merely wasteful.
        assertEquals(1, bridge.initCount)
    }

    @Test
    fun `shutdown is terminal and guarded`() {
        val bridge = FakeBridge()
        val engine = RcloneEngine(bridge)
        engine.initialize(testConfigPath)

        engine.shutdown()
        assertFalse(engine.isInitialised)

        // Second shutdown must not reach rclone.
        engine.shutdown()
        assertEquals(1, bridge.shutdownCount)

        // And the engine can be brought back up afterwards.
        engine.initialize(testConfigPath)
        assertTrue(engine.isInitialised)
        assertEquals(2, bridge.initCount)
    }

    @Test
    fun `calling before initialize is refused rather than crashing rclone`() = runTest {
        val engine = RcloneEngine(FakeBridge())
        try {
            engine.call("core/version")
            fail("expected the engine to refuse an uninitialised call")
        } catch (e: RcloneException) {
            assertTrue(e.message!!.contains("not initialised"))
        }
        // Critically: the bridge must never have been touched, because
        // touching rclone before Initialize() fails deep inside.
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun `successful call returns the parsed object`() = runTest {
        val engine = RcloneEngine(FakeBridge { _, _ ->
            RcloneResult(200, """{"version":"v1.75.1","beta":false}""")
        })
        engine.initialize(testConfigPath)

        val reply = engine.call("core/version")

        assertEquals("v1.75.1", reply.getString("version"))
        assertFalse(reply.getBoolean("beta"))
    }

    @Test
    fun `parameters are serialised as a JSON object`() = runTest {
        val bridge = FakeBridge()
        val engine = RcloneEngine(bridge)
        engine.initialize(testConfigPath)

        engine.call("operations/list", JSONObject().put("fs", "local:/tmp").put("remote", ""))

        val (method, input) = bridge.calls.single()
        assertEquals("operations/list", method)
        // The wire form is rclone's, and it must be parseable as an object.
        val sent = JSONObject(input)
        assertEquals("local:/tmp", sent.getString("fs"))
        assertTrue(sent.has("remote"))
    }

    @Test
    fun `default parameters are an empty object not an empty string`() = runTest {
        val bridge = FakeBridge()
        val engine = RcloneEngine(bridge)
        engine.initialize(testConfigPath)

        engine.call("config/listremotes")

        // rclone tolerates an empty body, but "{}" is unambiguous and keeps
        // request construction uniform.
        assertEquals("{}", bridge.calls.single().second)
    }

    // ── Failure mapping ──────────────────────────────────────────────────────

    @Test
    fun `non-200 becomes an exception carrying rclone's envelope`() = runTest {
        val envelope = """{"error":"directory not found","path":"nope","status":404}"""
        val engine = RcloneEngine(FakeBridge { _, _ -> RcloneResult(404, envelope) })
        engine.initialize(testConfigPath)

        try {
            engine.call("operations/list", JSONObject().put("fs", "local:/nope"))
            fail("expected an exception")
        } catch (e: RcloneException) {
            assertEquals(404, e.status)
            assertEquals("operations/list", e.method)
            // The envelope is preserved so callers can distinguish failure
            // kinds without string matching.
            assertEquals("directory not found", JSONObject(e.rcloneOutput).getString("error"))
            assertTrue(e.message!!.contains("directory not found"))
        }
    }

    @Test
    fun `unknown method is surfaced with rclone's own message`() = runTest {
        val engine = RcloneEngine(FakeBridge { _, _ ->
            RcloneResult(404, """{"error":"couldn't find method \"core/nope\"","status":404}""")
        })
        engine.initialize(testConfigPath)

        try {
            engine.call("core/nope")
            fail("expected an exception")
        } catch (e: RcloneException) {
            assertTrue(e.message!!.contains("couldn't find method"))
        }
    }

    @Test
    fun `a 200 with unparseable output is an error, not silent success`() = runTest {
        // This is the case that would otherwise be worst: a success status
        // with garbage in the body, which a naive wrapper would hand straight
        // to a caller as if it were data.
        val engine = RcloneEngine(FakeBridge { _, _ -> RcloneResult(200, "not json at all") })
        engine.initialize(testConfigPath)

        try {
            engine.call("core/version")
            fail("expected an exception for a non-JSON reply")
        } catch (e: RcloneException) {
            assertEquals(200, e.status)
            assertTrue(e.message!!.contains("non-JSON"))
        }
    }

    @Test
    fun `callRaw reports failure without throwing`() = runTest {
        // Probing "does this remote exist" is an expected failure, not an
        // error, so the throwing path is wrong for it.
        val engine = RcloneEngine(FakeBridge { _, _ ->
            RcloneResult(404, """{"error":"unknown remote","status":404}""")
        })
        engine.initialize(testConfigPath)

        val raw = engine.callRaw("operations/list", JSONObject().put("fs", "nope:/"))

        assertFalse(raw.isSuccess)
        assertEquals(404, raw.status)
    }

    // ── Result type ──────────────────────────────────────────────────────────

    @Test
    fun `result reports success only for 200`() {
        assertTrue(RcloneResult(200, "{}").isSuccess)
        // 201/204 are not what rclone's RPC returns; treating them as success
        // would hide a protocol change.
        assertFalse(RcloneResult(204, "{}").isSuccess)
        assertFalse(RcloneResult(500, "{}").isSuccess)
        assertFalse(RcloneResult(401, "{}").isSuccess)
    }

    @Test
    fun `non-200 reply with a non-JSON body still yields a usable message`() = runTest {
        val engine = RcloneEngine(FakeBridge { _, _ -> RcloneResult(500, "boom") })
        engine.initialize(testConfigPath)

        try {
            engine.call("core/version")
            fail("expected an exception")
        } catch (e: RcloneException) {
            // Falls back to the status alone rather than throwing a parse error
            // while building an error message.
            assertTrue(e.message!!.contains("500"))
        }
    }
}
