package com.example.earthquack.storage

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that Kotlin → gomobile → rclone actually works.
 *
 * This is the only test that loads `libgojni.so`; everything in
 * `RcloneEngineTest` runs on the JVM against a fake. If this file fails, the
 * problem is packaging, ABI or native linkage — not our logic.
 *
 * These use the in-memory configuration (no rclone.conf). Persistence is
 * covered separately by `RcloneConfigInstrumentedTest`, which needs a real
 * file to observe anything.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * `runBlocking<Unit>` is explicit because JUnit4 requires test methods to
 * return void; relying on the last expression's type is how that silently
 * breaks.
 */
@RunWith(AndroidJUnit4::class)
class RcloneInstrumentedTest {

    private companion object {
        const val TAG = "RcloneInstrumented"
    }

    /** An engine with no config file: enough to exercise the RPC surface. */
    private fun engine(): RcloneEngine = RcloneEngine().also { it.initialize() }

    /**
     * The whole point of the exercise: a real in-process call that returns a
     * real rclone answer.
     */
    @Test
    fun rcloneCoreVersion() = runBlocking<Unit> {
        val reply = engine().call("core/version")

        val version = reply.optString("version")
        Log.i(TAG, "core/version -> version=$version isGit=${reply.optBoolean("isGit")}")

        assertTrue("expected a rclone version string, got '$version'", version.isNotEmpty())
        assertTrue(
            "expected a semver-ish version, got '$version'",
            Regex("""^v?\d+\.\d+""").containsMatchIn(version)
        )
        // A build from a git checkout reports isGit; a release build does not.
        // Assert only that the key exists, since it legitimately differs.
        assertTrue("isGit key missing from core/version", reply.has("isGit"))
    }

    /**
     * `config/listremotes` must work with no rclone.conf, returning an empty
     * list rather than an error. That is the contract the remote-management
     * UI depends on.
     */
    @Test
    fun configListRemotesOnFreshInstall() = runBlocking<Unit> {
        val remotes = RcloneRemoteManager(engine()).listRemotes()
        Log.i(TAG, "config/listremotes -> count=${remotes.size}")
        // A fresh app legitimately has no remotes configured yet.
        assertEquals(0, remotes.size)
    }

    /**
     * `operations/list` against the local backend, which the shim registers.
     *
     * Both failure modes found during bring-up are covered by the comments:
     *   - without the `fs/operations` blank import the method does not exist
     *     at all: 404 "couldn't find method"
     *   - without a config section, `local:` is not resolvable: 500
     *     "didn't find section in config file"
     *
     * The leading colon in `:local:/` is rclone's *on-the-fly remote* syntax.
     * A bare `local:/` is looked up as a named section in rclone.conf, which
     * does not exist here; on-the-fly remotes skip the config lookup.
     */
    @Test
    fun operationsListOnLocalBackend() = runBlocking<Unit> {
        val reply = engine().call(
            "operations/list",
            JSONObject().put("fs", ":local:/").put("remote", "")
        )

        val list = reply.getJSONArray("list")
        Log.i(TAG, "operations/list :local:/ -> ${list.length()} entries")

        if (list.length() > 0) {
            // Fields the storage UI will bind to.
            val first = list.getJSONObject(0)
            for (field in listOf("Path", "Name", "Size", "MimeType", "IsDir", "ModTime")) {
                assertTrue("list entry missing $field: $first", first.has(field))
            }
        }
    }

    /**
     * An unknown remote must fail cleanly with rclone's own envelope, not
     * crash and not hang.
     */
    @Test
    fun unknownRemoteFailsCleanly() = runBlocking<Unit> {
        val raw = engine().callRaw(
            "operations/list",
            JSONObject().put("fs", "definitelynotarealremote:/").put("remote", "")
        )
        Log.i(TAG, "unknown remote -> status=${raw.status} output=${raw.output.take(160)}")
        assertFalse("expected a non-200 status", raw.isSuccess)
        assertNotNull(raw.output)
    }

    /**
     * A named remote with no config section must fail *and say why*.
     *
     * This is the error the storage layer will translate into "please
     * configure this remote", so rclone's own explanation must survive to the
     * caller rather than being flattened away.
     */
    @Test
    fun unconfiguredRemoteFailsWithConfigError() = runBlocking<Unit> {
        val raw = engine().callRaw(
            "operations/list",
            JSONObject().put("fs", "notconfigured:/").put("remote", "")
        )
        Log.i(TAG, "unconfigured remote -> status=${raw.status} output=${raw.output.take(160)}")
        assertFalse("expected a non-200 status", raw.isSuccess)

        val error = JSONObject(raw.output).optString("error")
        assertTrue(
            "expected rclone's config error to survive, got: ${raw.output}",
            error.contains("config", ignoreCase = true)
        )
    }

    /**
     * Repeated calls must be stable, and shutdown must not poison a
     * re-initialised engine. Also proves rclone survives many RPCs in one
     * process rather than leaking or wedging after the first.
     */
    @Test
    fun repeatedCallsAndReinitialise() = runBlocking<Unit> {
        val e = engine()

        repeat(20) { i ->
            val v = e.call("core/version").optString("version")
            assertTrue("version missing on iteration $i", v.isNotEmpty())
        }

        e.shutdown()
        assertFalse(e.isInitialised)

        // Upstream Finalize is only a GC, so a re-initialise must still work.
        e.initialize()
        assertTrue(e.isInitialised)
        assertTrue(e.call("core/version").optString("version").isNotEmpty())
    }

    /**
     * Concurrent RPCs from several coroutines.
     *
     * rclone's RPC layer is built to serve many simultaneous callers — its own
     * rcserver does exactly this — so this should simply pass. It is here to
     * prove the gomobile boundary tolerates it rather than to assert a
     * limitation: if it ever fails, the fix is on our side, not in rclone.
     */
    @Test
    fun concurrentCallsSucceed() = runBlocking<Unit> {
        val e = engine()

        val results = (1..16)
            .map { async(Dispatchers.IO) { e.call("core/version").optString("version") } }
            .map { it.await() }

        Log.i(TAG, "concurrent calls -> ${results.distinct()}")
        assertEquals("all concurrent calls should agree", 1, results.distinct().size)
        assertTrue(results.first().isNotEmpty())
    }
}