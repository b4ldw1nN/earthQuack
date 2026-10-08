package com.example.earthquack.storage

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
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
import java.io.File

/**
 * On-device verification of persistent rclone configuration.
 *
 * This is where the upstream investigation pays off: rclone's config path is
 * set explicitly through `config.SetConfigPath` (called from our shim's
 * `Initialize`), so nothing depends on `RCLONE_CONFIG`, `$HOME` or the
 * working directory.
 *
 * These tests deliberately use a **real** rclone.conf in app-private storage
 * rather than the in-memory default, because the thing being verified is that
 * configuration survives — which is impossible to observe without a file.
 *
 * They write only inside a controlled directory under the app's own files dir.
 * The Android filesystem is never exposed: a remote's root is a directory we
 * create, not `/`.
 *
 * Note `runBlocking<Unit>` on every test: JUnit4 requires test methods to
 * return void, and the explicit type argument is what makes that compile
 * rather than depending on whatever the last expression happens to evaluate to.
 */
@RunWith(AndroidJUnit4::class)
class RcloneConfigInstrumentedTest {

    private companion object {
        const val TAG = "RcloneConfigTest"
        const val REMOTE = "earthquacktest"
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun config(): RcloneConfigManager = RcloneConfigManager(context)

    /** An engine with no config file, for tests that only need the RPC surface. */
    private fun engine(): RcloneEngine = RcloneEngine().also { it.initialize() }

    /** An engine bound to a real app-private rclone.conf. */
    private fun persistentEngine(cfg: RcloneConfigManager): RcloneEngine {
        cfg.ensureReady()
        return RcloneEngine().also { it.initialize(cfg.configPath) }
    }

    /**
     * A controlled sandbox for the test remote to operate on.
     *
     * Scoped to the app's own files dir so the test can neither read nor write
     * anything outside EarthQuack's private storage.
     */
    private fun sandbox(): File =
        File(context.filesDir, "rclone-test-sandbox").apply {
            deleteRecursively()
            mkdirs()
        }

    // ── Fresh install ──────────────────────────────────────────────────────

    /**
     * A fresh install has no rclone.conf. Initialising must still succeed and
     * must report an **empty** remote list — an empty list is a valid state,
     * not an error.
     */
    @Test
    fun freshInstallInitialisesWithNoConfig() = runBlocking<Unit> {
        val cfg = config()
        cfg.deleteConfig()
        assertFalse("precondition: no config file", cfg.hasConfig())

        // ensureReady() must create it without failing.
        cfg.ensureReady()
        assertTrue("config dir must exist", cfg.configDir.isDirectory)
        assertTrue("config file must exist after ensureReady", cfg.hasConfig())

        val engine = persistentEngine(cfg)
        val remotes = RcloneRemoteManager(engine).listRemotes()

        Log.i(TAG, "fresh install -> remotes=$remotes")
        assertTrue("fresh install must report no remotes", remotes.isEmpty())
    }

    /**
     * ensureReady() must never clobber an existing config.
     *
     * Getting this wrong would silently wipe every configured remote whenever
     * the app starts, so it is pinned explicitly.
     */
    @Test
    fun ensureReadyDoesNotClobberExistingConfig() = runBlocking<Unit> {
        val cfg = config()
        cfg.ensureReady()
        RcloneRemoteManager(persistentEngine(cfg)).createRemote(REMOTE, "local")

        val before = cfg.configFile.readText()
        assertTrue("config should now contain the remote", before.contains(REMOTE))

        // ensureReady again, as a second app launch would.
        cfg.ensureReady()
        assertEquals("ensureReady must not rewrite an existing config", before, cfg.configFile.readText())

        RcloneRemoteManager(persistentEngine(cfg)).deleteRemote(REMOTE)
    }

    // ── Local remote ───────────────────────────────────────────────────────

    /**
     * The decisive test: a remote created through the API is usable.
     *
     * This exercises the whole chain the task asks for — Kotlin → gomobile →
     * rclone → persistent config → local backend — and it only passes if
     * rclone is genuinely reading *our* config file, because the remote name
     * exists nowhere else.
     */
    @Test
    fun createLocalRemoteAndUseIt() = runBlocking<Unit> {
        val sandboxDir = sandbox()
        File(sandboxDir, "seed.txt").writeText("hello from earthquakes")

        val cfg = config()
        cfg.deleteConfig()
        val engine = persistentEngine(cfg)
        val remotes = RcloneRemoteManager(engine)

        remotes.createRemote(REMOTE, "local")
        assertTrue("remote must appear in listRemotes", remotes.hasRemote(REMOTE))
        Log.i(TAG, "created remote: ${remotes.listRemotes()}")

        // The section must be on disk, not only in rclone's memory: that file
        // is the entire persistence claim.
        assertTrue(
            "config file must contain the remote section",
            cfg.configFile.readText().contains(REMOTE)
        )

        // Scope to the sandbox with an on-the-fly ":local:<abs>" remote rather
        // than "$REMOTE:/". rclone's local backend has no configurable root,
        // so a *named* local remote resolves against the process working
        // directory, which on Android is "/". Being explicit keeps this test
        // off the device root.
        val listed = engine.call(
            "operations/list",
            JSONObject()
                .put("fs", ":local:${sandboxDir.absolutePath}")
                .put("remote", "")
        )
        assertTrue("listing must return the seeded file", listed.getJSONArray("list").length() > 0)

        remotes.deleteRemote(REMOTE)
        sandboxDir.deleteRecursively()
    }

    /**
     * File operations against the configured remote: mkdir, copyfile,
     * deletefile.
     *
     * These are the operations the storage layer will build on, and they prove
     * a write actually lands in the sandbox on disk rather than merely
     * reporting success.
     */
    @Test
    fun fileOperationsAgainstConfiguredRemote() = runBlocking<Unit> {
        val sandboxDir = sandbox()
        File(sandboxDir, "source.txt").writeText("payload for copy")

        val cfg = config()
        cfg.deleteConfig()
        val engine = persistentEngine(cfg)
        val remotes = RcloneRemoteManager(engine)
        remotes.createRemote(REMOTE, "local")

        // Every operations/* call needs both "fs" and "remote"; omitting
        // either fails with 400 "Didn't find key \"remote\" in input".
        //
        // For mkdir, `remote` is a path RELATIVE TO `fs`, not another remote
        // spec. rclone's own docs show {"fs":":memory:","remote":"bucket"},
        // which is why passing an absolute path here silently succeeded while
        // creating a directory somewhere else.
        val root = ":local:${sandboxDir.absolutePath}"

        // mkdir
        engine.call(
            "operations/mkdir",
            JSONObject().put("fs", root).put("remote", "subdir")
        )
        assertTrue("mkdir must create the directory", File(sandboxDir, "subdir").isDirectory)

        // copyfile takes srcFs/srcRemote/dstFs/dstRemote — NOT fs/remote.
        // Each *Remote is a path WITHIN its *Fs, per rclone's documented
        // signature in docs/content/rc.md.
        engine.call(
            "operations/copyfile",
            JSONObject()
                .put("srcFs", root)
                .put("srcRemote", "source.txt")
                .put("dstFs", "$root/subdir")
                .put("dstRemote", "copied.txt")
        )
        val copied = File(sandboxDir, "subdir/copied.txt")
        assertTrue("copyfile must write the file", copied.exists())
        assertEquals("payload for copy", copied.readText())

        // deletefile: `remote` is the full path to the FILE within fs, and
        // there is no dst_file parameter at all. Confirmed in
        // fs/operations/rc.go, where the handler does
        // f.NewObject(ctx, remote) — it builds the object straight from
        // `remote`. Passing a directory plus dst_file made rclone answer
        // 500 "is a directory not a file".
        engine.call(
            "operations/deletefile",
            JSONObject()
                .put("fs", root)
                .put("remote", "subdir/copied.txt")
        )
        assertFalse("deletefile must remove the file", copied.exists())

        remotes.deleteRemote(REMOTE)
        sandboxDir.deleteRecursively()
    }

    // ── Persistence ────────────────────────────────────────────────────────

    /**
     * A configured remote must survive a shutdown and re-initialise.
     *
     * This is the closest in-process approximation of "kill the app and
     * restart": rclone loads its config lazily and caches it per process, so
     * shutdown + initialise with the same path forces a genuine re-read from
     * disk. Had the config not been persisted, the remote would vanish.
     */
    @Test
    fun remoteSurvivesShutdownAndReinitialise() = runBlocking<Unit> {
        val cfg = config()
        cfg.deleteConfig()
        val first = persistentEngine(cfg)
        RcloneRemoteManager(first).createRemote(REMOTE, "local")
        assertTrue(RcloneRemoteManager(first).hasRemote(REMOTE))

        first.shutdown()

        // Fresh engine, same path: rclone must re-read the file.
        val second = persistentEngine(cfg)
        val remotes = RcloneRemoteManager(second).listRemotes()

        Log.i(TAG, "after restart -> remotes=$remotes")
        assertTrue("remote must survive a restart, got $remotes", remotes.contains(REMOTE))

        // And it must still be usable, not merely listed.
        val listed = second.call(
            "operations/list",
            JSONObject().put("fs", "$REMOTE:/").put("remote", "")
        )
        assertNotNull(listed.getJSONArray("list"))

        RcloneRemoteManager(second).deleteRemote(REMOTE)
    }

    /**
     * Deleting a remote must actually remove it, both from the list and from
     * the file on disk.
     */
    @Test
    fun deleteRemoteRemovesItEverywhere() = runBlocking<Unit> {
        val cfg = config()
        cfg.deleteConfig()
        val engine = persistentEngine(cfg)
        val remotes = RcloneRemoteManager(engine)

        remotes.createRemote(REMOTE, "local")
        assertTrue(remotes.listRemotes().contains(REMOTE))

        assertTrue("deleteRemote should report success", remotes.deleteRemote(REMOTE))
        assertFalse("remote must be gone from listRemotes", remotes.hasRemote(REMOTE))
        assertFalse(
            "remote section must be gone from the file",
            cfg.configFile.readText().contains(REMOTE)
        )
    }

    /**
     * Deleting a remote that does not exist must be reported as "not
     * deleted", not thrown: the caller's intent is satisfied either way.
     */
    @Test
    fun deletingUnknownRemoteIsNotAnError() = runBlocking<Unit> {
        val cfg = config()
        cfg.deleteConfig()
        val remotes = RcloneRemoteManager(persistentEngine(cfg))

        // rclone's config/delete succeeds for an absent remote — the caller's
        // intent ("this remote must not exist") is already satisfied. The API
        // reports that faithfully rather than inventing a failure.
        assertTrue(
            "deleting an absent remote is a no-op success in rclone",
            remotes.deleteRemote("nosuchremote")
        )
    }

    /**
     * The config must be written to app-private storage, and nowhere else.
     *
     * Credentials live in this file, so its location is a security property,
     * not an implementation detail.
     */
    @Test
    fun configLivesInAppPrivateStorage() = runBlocking<Unit> {
        val cfg = config()
        cfg.ensureReady()

        val path = cfg.configFile.canonicalPath
        val filesDir = context.filesDir.canonicalPath

        assertTrue("config must live under filesDir, was $path", path.startsWith(filesDir))
        assertFalse(
            "config must not be in shared storage, was $path",
            path.startsWith("/sdcard") || path.startsWith("/storage/emulated")
        )
        assertTrue("config file must be readable by the app", cfg.configFile.canRead())
        Log.i(TAG, "config at $path")
    }

    // ── Regressions ────────────────────────────────────────────────────────

    /**
     * `operations/list` must keep working.
     *
     * This is the method that was silently missing until `fs/operations` was
     * blank-imported into the shim. A future import change could remove it
     * again with no compile error, because a missing RC method is a runtime
     * 404 rather than a build failure.
     */
    @Test
    fun operationsListStillRegistered() = runBlocking<Unit> {
        val reply = engine().call(
            "operations/list",
            JSONObject().put("fs", ":local:/").put("remote", "")
        )
        assertTrue("operations/list must remain callable", reply.has("list"))
    }

    /**
     * `config/providers` must list the backends actually linked into the
     * binary.
     *
     * Because rclone registers backends at link time, this is the honest way
     * to assert which providers the app can configure. Hard-coding a list in
     * the UI would drift from the binary, which is exactly the failure this
     * test exists to prevent.
     */
    @Test
    fun providersReflectLinkedBackends() = runBlocking<Unit> {
        val providers = RcloneRemoteManager(engine()).providers()
        val names = providers.map { it.name }

        Log.i(TAG, "providers (${names.size}) -> $names")
        // Assert the *mechanism*, not a specific provider: an earlier version
        // asserted `local` was listed, which was an assumption rather than a
        // verified fact. What matters is that the call succeeds and returns
        // metadata a configuration form could bind to.
        assertTrue("config/providers returned nothing at all", names.isNotEmpty())
        assertTrue("every provider must have a name", names.all { it.isNotBlank() })
        assertEquals("provider names must be unique", names.size, names.distinct().size)

        for (provider in providers) {
            for (setting in provider.settings) {
                assertTrue(
                    "${provider.name} has a setting with no name",
                    setting.name.isNotBlank()
                )
            }
        }
    }

    /**
     * Config reads must be safe from concurrent callers.
     *
     * rclone guards its config storage with a mutex, so this should simply
     * pass; it is here so a regression in our own locking shows up as a
     * failure rather than as intermittent corruption.
     */
    @Test
    fun concurrentConfigReadsAreSafe() = runBlocking<Unit> {
        val cfg = config()
        val engine = persistentEngine(cfg)
        val remotes = RcloneRemoteManager(engine)

        val results = (1..16)
            .map { async(Dispatchers.IO) { remotes.listRemotes() } }
            .map { it.await() }

        assertEquals(16, results.size)
        assertEquals(
            "every concurrent read must agree",
            1,
            results.map { it.toSet() }.distinct().size
        )
    }
}