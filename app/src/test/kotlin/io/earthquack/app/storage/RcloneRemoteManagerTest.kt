package io.earthquack.app.storage

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the JSON translation in [RcloneRemoteManager].
 *
 * Everything here is pure request-building and reply-parsing, so it belongs on
 * the JVM: no device, no `libgojni.so`, and it runs in CI.
 *
 * These tests exist because both bugs they guard against were real, and both
 * were originally found by an on-device test — i.e. late. A wrong request shape
 * fails against a live rclone with a message that points at the wrong thing:
 *
 * ```
 * 400 Didn't find key "parameters" in input
 * ```
 *
 * which reads like a rclone configuration problem rather than "you nested your
 * settings one level too shallow". Pinning the exact request body turns a
 * confusing device failure into a fast, local, legible one.
 */
class RcloneRemoteManagerTest {

    /**
     * A bridge that records every request and replies from a script keyed by
     * RPC method.
     *
     * Unlike the engine's fake it hands back the *raw* reply, so this test can
     * assert on exactly what crossed the boundary in both directions.
     */
    private class RecordingBridge(
        private val replies: Map<String, String> = emptyMap()
    ) : RcloneBridge {
        val requests = mutableListOf<Pair<String, JSONObject>>()

        /** The body sent for [method], or null if it was never called. */
        fun body(method: String): JSONObject? =
            requests.firstOrNull { it.first == method }?.second

        /** How many times [method] was called. */
        fun count(method: String): Int = requests.count { it.first == method }

        override fun initialize(configPath: String) = Unit

        override fun rpc(method: String, input: String): RcloneResult {
            requests += method to JSONObject(input)
            return RcloneResult(200, replies[method] ?: "{}")
        }

        override fun shutdown() = Unit
    }

    /**
     * An initialised [RcloneRemoteManager] over [bridge].
     *
     * The engine refuses to serve an RPC before initialisation, so this cannot
     * be skipped — it is part of the contract, not ceremony.
     */
    private fun manager(bridge: RcloneBridge): RcloneRemoteManager {
        val engine = RcloneEngine(bridge)
        engine.initialize("")
        return RcloneRemoteManager(engine)
    }

    // ── Request shape ───────────────────────────────────────────────────────

    /**
     * Backend settings belong inside a nested `parameters` object.
     *
     * Flattening them to the top level fails with
     * `400 Didn't find key "parameters" in input` — rclone's required-parameter
     * check, which names the *container* and says nothing about the values
     * being fine.
     */
    @Test
    fun `createRemote nests settings under parameters`() {
        val bridge = RecordingBridge(replies = mapOf("config/create" to """{"name":"box"}"""))

        runBlocking {
            manager(bridge).createRemote(
                name = "box",
                type = "sftp",
                params = mapOf("host" to "example.com", "user" to "alice")
            )
        }

        val body = bridge.body("config/create")!!
        val parameters = body.getJSONObject("parameters")
        assertEquals("example.com", parameters.getString("host"))
        assertEquals("alice", parameters.getString("user"))

        // opt carries obscure and nonInteractive
        val opt = body.getJSONObject("opt")
        assertTrue(opt.getBoolean("obscure"))
        assertTrue(opt.getBoolean("nonInteractive"))

        // ...and specifically not at the top level.
        assertFalse(
            "settings must not be flattened into the top level",
            body.has("host")
        )
    }

    /**
     * `nonInteractive` must default to true for an embedded app.
     *
     * Left false, rclone will try to start a browser-based OAuth flow for
     * backends that want one. In an Android service there is no terminal to
     * prompt at, so the call would hang rather than fail informatively.
     */
    @Test
    fun `createRemote is nonInteractive by default`() {
        val bridge = RecordingBridge()
        runBlocking { manager(bridge).createRemote("box", "sftp") }

        assertTrue(
            "embedded rclone must not attempt an interactive flow",
            bridge.body("config/create")!!.getBoolean("nonInteractive")
        )
    }

    /**
     * `config/create` needs a type; `config/update` must not send one.
     *
     * Sending `type` on update would silently re-declare the backend, which is
     * a different operation from changing its settings.
     */
    @Test
    fun `create sends type but update does not`() {
        val bridge = RecordingBridge()
        val api = manager(bridge)

        runBlocking {
            api.createRemote("box", "sftp", mapOf("host" to "a"))
            api.updateRemote("box", mapOf("host" to "b"))
        }

        assertEquals("sftp", bridge.body("config/create")!!.getString("type"))
        assertFalse(
            "update must not restate the backend type",
            bridge.body("config/update")!!.has("type")
        )
    }

    /**
     * Blank names are rejected before crossing the native boundary.
     *
     * rclone would reject them too, but as an opaque 400. Failing in Kotlin
     * names the mistake and keeps a needless JNI call off the path.
     */
    @Test
    fun `blank remote names are rejected locally`() = runBlocking {
        val bridge = RecordingBridge()
        val api = manager(bridge)

        // Suspend lambdas, so each can be invoked inside this coroutine.
        val attempts: List<suspend () -> Unit> = listOf(
            { api.createRemote("", "sftp") },
            { api.createRemote("box", "") },
            { api.updateRemote("  ", emptyMap()) }
        )

        for (attempt in attempts) {
            try {
                attempt()
                throw AssertionError("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // Fall through: the rejection is the assertion.
            }
        }

        assertEquals(
            "no blank-argument call may reach the native boundary",
            0,
            bridge.requests.size
        )
    }

    // ── Reply parsing ───────────────────────────────────────────────────────

    /**
     * A fresh install reports an empty remote list, and that is success.
     *
     * Not an error case: `{"remotes":[]}` must yield an empty list, never a
     * throw. This is the state every user's first launch is in.
     */
    @Test
    fun `empty remote list parses to an empty list`() = runBlocking {
        val bridge = RecordingBridge(replies = mapOf("config/listremotes" to """{"remotes":[]}"""))

        assertEquals(emptyList<String>(), manager(bridge).listRemotes())
    }

    /**
     * rclone returns plain strings here, but accepting an object with a `name`
     * field costs nothing and means a future format change degrades to "still
     * works" instead of "silently no remotes".
     */
    @Test
    fun `remote list accepts both string and object entries`() = runBlocking {
        val bridge = RecordingBridge(
            replies = mapOf(
                "config/listremotes" to
                    """{"remotes":["one",{"name":"two"},{"nope":3},null]}"""
            )
        )

        assertEquals(listOf("one", "two"), manager(bridge).listRemotes())
    }

    /**
     * `config/providers` marshals its Go struct field names verbatim.
     *
     * So the JSON keys are capitalised — `Name`, `Description`, `Settings`,
     * `Help`, `Type` — and the secret marker is `Sensitive`, not `Obscure`.
     * Reading the lower-case names produced a list of providers with blank
     * names and no settings, which would have driven an empty UI form with no
     * error anywhere.
     */
    @Test
    fun `providers parse capitalised keys and Sensitive flag`() = runBlocking {
        val bridge = RecordingBridge(
            replies = mapOf(
                "config/providers" to """
                {"providers":[{
                  "Name":"sftp",
                  "Description":"SSH File Transfer",
                  "Settings":[
                    {"Name":"host","Help":"Hostname","Type":"String","Required":true,"Sensitive":false},
                    {"Name":"pass","Help":"Password","Type":"Password","Required":false,"Sensitive":true},
                    {"Name":"port","Help":"SSH port","Type":"Int","Required":false,"Sensitive":false}
                  ]
                }]}
                """.trimIndent()
            )
        )

        val providers = manager(bridge).providers()
        assertEquals(1, providers.size)

        val sftp = providers.single()
        assertEquals("sftp", sftp.name)
        assertEquals("SSH File Transfer", sftp.description)
        assertEquals(listOf("host", "pass", "port"), sftp.settingNames)
        assertEquals(listOf("host"), sftp.requiredSettings)

        // `Sensitive` is what rclone masks in `config/dump`, so it is what must
        // drive redaction on our side.
        assertEquals(listOf("pass"), sftp.secretSettings)
        assertTrue(sftp.settings.first { it.name == "pass" }.isSecret)
        assertFalse(sftp.settings.first { it.name == "host" }.isSecret)
    }

    /**
     * Upstream rclone (v1.75+) returns backend configuration under the key "Options"
     * rather than "Settings", with "IsPassword" for credentials and "Sensitive" for
     * PII (host/user).
     */
    @Test
    fun `providers parse Options key and IsPassword flag with examples`() = runBlocking {
        val bridge = RecordingBridge(
            replies = mapOf(
                "config/providers" to """
                {"providers":[{
                  "Name":"sftp",
                  "Description":"SSH/SFTP",
                  "Options":[
                    {"Name":"host","Help":"SSH host","Type":"string","Required":true,"Sensitive":true,"IsPassword":false},
                    {"Name":"user","Help":"SSH username","Type":"string","Required":false,"Sensitive":true,"IsPassword":false,"DefaultStr":"root"},
                    {"Name":"pass","Help":"SSH password","Type":"string","Required":false,"Sensitive":false,"IsPassword":true},
                    {"Name":"port","Help":"SSH port","Type":"int","Required":false,"Sensitive":false,"IsPassword":false,"DefaultStr":"22"},
                    {"Name":"vendor","Help":"SFTP vendor","Type":"string","Required":false,"Sensitive":false,"IsPassword":false,"Advanced":true,"Examples":[{"Value":"openssh","Help":"OpenSSH"},{"Value":"other","Help":"Other"}]},
                    {"Name":"internal_key","Help":"Internal hidden","Type":"string","Hide":2}
                  ]
                }]}
                """.trimIndent()
            )
        )

        val providers = manager(bridge).providers()
        assertEquals(1, providers.size)

        val sftp = providers.single()
        assertEquals("sftp", sftp.name)
        // Hidden flag 2 must be excluded
        assertEquals(listOf("host", "user", "pass", "port", "vendor"), sftp.settingNames)
        assertEquals(listOf("host"), sftp.requiredSettings)
        assertEquals(listOf("pass"), sftp.secretSettings)

        val host = sftp.settings.first { it.name == "host" }
        assertFalse(host.isSecret)
        assertTrue(host.isSensitive)

        val pass = sftp.settings.first { it.name == "pass" }
        assertTrue(pass.isSecret)
        assertTrue(pass.isPassword)

        val port = sftp.settings.first { it.name == "port" }
        assertEquals("22", port.defaultStr)

        val vendor = sftp.settings.first { it.name == "vendor" }
        assertTrue(vendor.isAdvanced)
        assertEquals(2, vendor.examples.size)
        assertEquals("openssh", vendor.examples[0].value)
        assertEquals("OpenSSH", vendor.examples[0].help)
    }

    /**
     * A backend whose secret is marked `Sensitive` but typed `String` is still
     * a secret.
     *
     * The flag and the type are independent signals and either can be absent,
     * so treating only one as authoritative risks echoing a credential.
     */
    @Test
    fun `secret detection accepts either the flag or the type`() {
        val byFlag = ProviderSetting("k", "String", required = false, obscure = true, help = "")
        val byType = ProviderSetting("k", "Password", required = false, obscure = false, help = "")
        val byToken = ProviderSetting("k", "Token", required = false, obscure = false, help = "")
        val neither = ProviderSetting("k", "String", required = false, obscure = false, help = "")

        assertTrue(byFlag.isSecret)
        assertTrue(byType.isSecret)
        assertTrue(byToken.isSecret)
        assertFalse(neither.isSecret)
    }

    /**
     * Provider metadata must survive being absent rather than crashing.
     *
     * `config/providers` is not guaranteed to exist in every build, and a
     * storage UI should render an empty picker, not an exception.
     */
    @Test
    fun `missing providers payload yields an empty list`() = runBlocking {
        val bridge = RecordingBridge(replies = mapOf("config/providers" to "{}"))

        assertEquals(emptyList<ProviderInfo>(), manager(bridge).providers())
    }

    /**
     * `config/delete` succeeds for a remote that is not there.
     *
     * Verified against the real binary, not assumed: rclone treats the call as
     * idempotent. The API reports that faithfully, so `deleteRemote` returning
     * true is correct and callers must not read it as "something was removed".
     */
    @Test
    fun `deleteRemote reports rclone's idempotent success`() = runBlocking {
        val bridge = RecordingBridge(replies = mapOf("config/delete" to "{}"))

        assertTrue(manager(bridge).deleteRemote("neverexisted"))
        assertEquals("neverexisted", bridge.body("config/delete")!!.getString("name"))
    }

    // ── Drive-remote filter (Share as link) ───────────────────────────────────

    /**
     * `config/dump` is a map keyed by remote name, each value carrying a `type`.
     * `driveRemotes` must pick out only the Drive ones and ignore the rest.
     */
    @Test
    fun `driveRemotes keeps only remotes whose type is drive`() = runBlocking {
        val dump = """
            {
              "gdrive": { "type": "drive", "token": "***" },
              "mybox":  { "type": "box" },
              "backup": { "type": "sftp", "host": "example.com" },
              "photos": { "type": "drive" }
            }
        """.trimIndent()
        val bridge = RecordingBridge(replies = mapOf("config/dump" to dump))

        val result = manager(bridge).driveRemotes()

        assertEquals(listOf("gdrive", "photos"), result)
        // Only config/dump is read; nothing about the config is written.
        assertEquals(1, bridge.count("config/dump"))
    }

    @Test
    fun `driveRemotes is empty when no remote is drive`() = runBlocking {
        val dump = """{ "a": { "type": "sftp" }, "b": { "type": "box" } }"""
        val bridge = RecordingBridge(replies = mapOf("config/dump" to dump))

        assertEquals(emptyList<String>(), manager(bridge).driveRemotes())
    }

    @Test
    fun `driveRemotes is empty for an empty config`() = runBlocking {
        val bridge = RecordingBridge(replies = mapOf("config/dump" to "{}"))

        assertEquals(emptyList<String>(), manager(bridge).driveRemotes())
    }
}
