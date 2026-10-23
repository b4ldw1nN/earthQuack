package io.earthquack.app.ssh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the connection-profile store.
 *
 * The store is SharedPreferences-backed, which is exactly the part a JVM test
 * cannot reach — and the part the app depends on: a saved profile must
 * survive the process being recreated, and a stored profile must never carry
 * the credential itself. The read/write shape, the isolation between
 * profiles, and the version tag are what pin the migration story down.
 *
 * Nothing here talks to a network; the transport is tested in
 * `SshProfileProbeTest` and `SshTerminalEndToEndTest`.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionProfileStoreInstrumentedTest {

    private companion object {
        const val PREFS = "earthquack_connections"
        const val KEY_PROFILES = "profiles"
        const val KEY_VERSION = "version"
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = ConnectionProfileStore(context)

    private fun profile(
        id: String = "p1",
        name: String = "archii",
        host: String = "192.168.1.10",
        port: Int = 22,
        username: String = "zoro",
        authMethod: AuthMethod = AuthMethod.PUBLIC_KEY,
        keyAlias: String? = "key-1",
        passwordAlias: String? = null
    ) = ConnectionProfile(
        id = id, name = name, host = host, port = port, username = username,
        authMethod = authMethod, identityKeyAlias = keyAlias, passwordAlias = passwordAlias
    )

    @Test
    fun createReadUpdateDelete_roundTrip() {
        val store = store()
        val created = profile()

        assertTrue("a valid profile must save", store.upsert(created))
        assertNotNull(store.get("p1"))
        assertEquals(created, store.get("p1"))

        val updated = created.copy(username = "aria", port = 2222)
        assertTrue(store.upsert(updated))
        assertEquals("aria", store.get("p1")!!.username)
        assertEquals(2222, store.get("p1")!!.port)
        // An update replaces; it does not duplicate.
        assertEquals(1, store.list().size)

        store.delete("p1")
        assertNull(store.get("p1"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun theStoreSurvivesRecreation() {
        store().upsert(profile())
        // A new store instance, as a new process would build.
        val reread = store().list()
        assertEquals(1, reread.size)
        assertEquals("archii", reread[0].name)
    }

    @Test
    fun multipleProfilesAreIsolated() {
        val store = store()
        store.upsert(profile(id = "a", name = "first", username = "one"))
        store.upsert(profile(id = "b", name = "second", username = "two", authMethod = AuthMethod.PASSWORD, keyAlias = null, passwordAlias = "pw-b"))

        val list = store.list().sortedBy { it.id }
        assertEquals(2, list.size)
        assertEquals("one", list[0].username)
        assertEquals("two", list[1].username)
        assertEquals(AuthMethod.PASSWORD, list[1].authMethod)

        store.delete("a")
        assertNull(store.get("a"))
        // Deleting one must not touch the other.
        assertNotNull(store.get("b"))
        assertEquals("pw-b", store.get("b")!!.passwordAlias)
    }

    @Test
    fun anInvalidProfileIsRefused() {
        val store = store()
        assertFalse("a blank host must not be saved", store.upsert(profile(host = "")))
        assertFalse("a zero port must not be saved", store.upsert(profile(id = "p2", port = 0)))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun theStoredBlobCarriesAliasesAndNoSecrets() {
        // Even though the app never puts credentials here, assert the
        // invariant: the persisted blob must reference secrets, never hold
        // them, so a read of the file in a backup or bug report is harmless.
        val store = store()
        store.upsert(profile(authMethod = AuthMethod.PASSWORD, keyAlias = null, passwordAlias = "alias-123"))

        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
        assertNotNull(raw)
        assertTrue("the alias must be stored", raw!!.contains("alias-123"))
        assertFalse("no password value may appear", raw.contains("hunter2"))
        assertFalse("no key material may appear", raw.contains("PRIVATE KEY"))
        // The version tag is written with the blob.
        assertEquals(
            1,
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, -1)
        )
    }

    @Test
    fun aPreExistingRecordFromAnOlderInstallLoadsWithoutLosingSettings() {
        // The migration story: an install written before the current shape
        // must still load. The v1 writer is reproduced here byte-for-byte in
        // the fields an old build would have written; an absent optional
        // field must fall back to its default, not crash the parser.
        val legacy = """[
            {"id":"legacy-1","name":"old-box","host":"10.0.0.5","port":22,
             "username":"kevin","auth":"PUBLIC_KEY","key":"key-legacy",
             "pw":null,"policy":"TOUF"}
        ]"""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, legacy).putInt(KEY_VERSION, 1).commit()

        val store = store()
        val restored = store.get("legacy-1")
        assertNotNull("a legacy record must load", restored)
        assertEquals("old-box", restored!!.name)
        assertEquals("10.0.0.5", restored.host)
        assertEquals("kevin", restored.username)
        assertEquals("key-legacy", restored.identityKeyAlias)
        assertEquals(AuthMethod.PUBLIC_KEY, restored.authMethod)
    }

    @Test
    fun aRecordMissingAnOptionalFieldFallsBackRatherThanDropping() {
        val partial = """[
            {"id":"legacy-2","name":"box","host":"10.0.0.6","port":22,
             "username":"sam","auth":"PUBLIC_KEY","key":"key-2"}
        ]"""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, partial).commit()

        val restored = store().get("legacy-2")
        assertNotNull("a record missing 'policy' must still load", restored)
        assertEquals(HostKeyPolicy.TOUF, restored!!.hostKeyPolicy)
    }

    @Test
    fun aCorruptBlobDegradesToEmptyRatherThanCrashing() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, "}}not json{{").commit()
        assertTrue(store().list().isEmpty())
        // And a fresh write repairs it.
        assertTrue(store().upsert(profile(id = "fresh")))
        assertEquals(1, store().list().size)
    }
}
