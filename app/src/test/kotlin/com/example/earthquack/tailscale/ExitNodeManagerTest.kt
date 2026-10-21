package com.example.earthquack.tailscale

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for exit-node selection.
 *
 * JVM-only, against a fake [ExitNodeControl]: the logic under test is peer
 * filtering, ordering, and the StableNodeID contract between the picker and
 * `SetExitNode` — all of which is pure. The bug that motivated the ID
 * contract (returning the transient NodeID where EditPrefs expects a
 * StableNodeID) is invisible on device until an exit node mysteriously
 * refuses to apply, which is exactly the class of failure these tests pin.
 */
class ExitNodeManagerTest {

    private class FakeControl(
        peersJson: String,
        private val exitNodeId: String = "",
        private val failOnSet: Boolean = false,
    ) : ExitNodeControl {
        var peers: String = peersJson
        val setCalls = mutableListOf<String>()

        override fun peers(): String = peers

        override fun exitNode(): String = exitNodeId

        override fun setExitNode(peerId: String) {
            if (failOnSet) throw RuntimeException("backend rejected")
            setCalls += peerId
        }
    }

    private fun manager(control: ExitNodeControl) =
        ExitNodeManager(control, io = Dispatchers.Unconfined)

    private fun peerJson(
        id: String,
        name: String = id,
        online: Boolean = true,
        exitOption: Boolean = false,
        isExit: Boolean = false,
    ) = """{"id":"$id","name":"$name","ip":"100.1.0.1","os":"linux","online":$online,"exit_node_option":$exitOption,"exit_node":$isExit}"""

    private fun peersJson(vararg peers: String) = peers.joinToString(",", "[", "]")

    // ── TailnetPeer parsing ────────────────────────────────────────────────

    @Test
    fun `parseList reads exit-node flags`() {
        val list = TailnetPeer.parseList(
            peersJson(peerJson("n1", exitOption = true), peerJson("n2"))
        )
        assertEquals(2, list.size)
        assertTrue(list[0].exitNodeOption)
        assertTrue(!list[1].exitNodeOption)
    }

    /** An entry without an id cannot be acted on; it is skipped, not shown. */
    @Test
    fun `parseList skips entries without id`() {
        val list = TailnetPeer.parseList(
            """[{"name":"ghost","online":true},{"id":"real","name":"real"}]"""
        )
        assertEquals(listOf("real"), list.map { it.id })
    }

    @Test
    fun `parseList survives malformed input`() {
        assertEquals(emptyList<TailnetPeer>(), TailnetPeer.parseList("not json"))
        assertEquals(emptyList<TailnetPeer>(), TailnetPeer.parseList("{}"))
        assertEquals(emptyList<TailnetPeer>(), TailnetPeer.parseList(""))
    }

    // ── ExitNodeManager ────────────────────────────────────────────────────

    /** Only exit-node-capable peers are offered; others never reach the picker. */
    @Test
    fun `availableExitNodes filters to exit-capable peers`() {
        val control = FakeControl(
            peersJson(
                peerJson("n1", name = "archii", exitOption = true),
                peerJson("n2", name = "pixel"),
                peerJson("n3", name = "desktop", exitOption = true),
            )
        )
        val names = manager(control).availableExitNodes().map { it.name }
        assertEquals(listOf("archii", "desktop"), names)
    }

    /** The current exit node sorts first — it is the state to show, not a peer to scan for. */
    @Test
    fun `current exit node sorts first, rest alphabetical`() {
        val control = FakeControl(
            peersJson(
                peerJson("n1", name = "archii", exitOption = true),
                peerJson("n2", name = "desktop", exitOption = true, isExit = true),
                peerJson("n3", name = "beta", exitOption = true),
            )
        )
        assertEquals(
            listOf("desktop", "archii", "beta"),
            manager(control).availableExitNodes().map { it.name },
        )
    }

    /** Offline exit nodes stay listed — the only approved one may be down right now. */
    @Test
    fun `offline exit nodes remain available`() {
        val control = FakeControl(
            peersJson(peerJson("n1", name = "archii", online = false, exitOption = true))
        )
        val list = manager(control).availableExitNodes()
        assertEquals(1, list.size)
        assertTrue(!list[0].online)
    }

    @Test
    fun `currentExitNode reflects the isExitNode flag`() {
        val control = FakeControl(
            peersJson(
                peerJson("n1"),
                peerJson("n2", name = "desktop", isExit = true),
            )
        )
        assertEquals("n2", manager(control).currentExitNode()?.id)
    }

    @Test
    fun `currentExitNode is null when nothing is selected`() {
        val control = FakeControl(peersJson(peerJson("n1")))
        assertNull(manager(control).currentExitNode())
    }

    /** The picker sends the peer's StableNodeID verbatim — the ID space EditPrefs stores. */
    @Test
    fun `setExitNode forwards the stable peer id`() = runBlocking {
        val control = FakeControl(peersJson(peerJson("n42", exitOption = true)))
        val peer = manager(control).availableExitNodes().single()

        manager(control).setExitNode(peer)

        assertEquals(listOf("n42"), control.setCalls)
    }

    /** Clearing sends the empty string, which is what the shim maps to ExitNodeID="". */
    @Test
    fun `setExitNode null clears with empty string`() = runBlocking {
        val control = FakeControl(peersJson(peerJson("n1")))
        manager(control).setExitNode(null)
        assertEquals(listOf(""), control.setCalls)
    }

    /** A backend rejection must not be swallowed — the UI needs to show it. */
    @Test(expected = IllegalStateException::class)
    fun `backend failure surfaces as IllegalStateException`() = runBlocking {
        val control = FakeControl(peersJson(), failOnSet = true)
        manager(control).setExitNode(null)
    }
}
