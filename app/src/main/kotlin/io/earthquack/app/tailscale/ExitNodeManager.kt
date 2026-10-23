package io.earthquack.app.tailscale

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exit-node selection for the embedded Tailscale node.
 *
 * All tsnet outbound traffic — including everything EarthQuack sends through
 * the SOCKS5 proxy — follows the chosen exit node. The
 * picker lists only peers that *offer* exit-node service (`exit_node_option`:
 * advertised **and** approved by the tailnet policy); picking one calls
 * `SetExitNode(id)`, picking "None" clears it.
 *
 * ## Preconditions
 *
 * These calls need a **Running** node; the shim returns a "not running"
 * error otherwise. This manager does not start the node — lifecycle belongs
 * to [TailscaleAuthManager] (TailscaleEngine will supersede it).
 * Callers should gate the picker UI on `state is Running`.
 *
 * ## Testability
 *
 * The gomobile calls sit behind [ExitNodeControl]; the JVM tests drive the
 * real logic with a fake.
 */
class ExitNodeManager(
    private val control: ExitNodeControl,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Peers offering exit-node service, current one first.
     *
     * Offline exit nodes are still listed — an exit node that is down now may
     * be the only approved one, and hiding it would leave the user with an
     * empty picker and no explanation. The UI marks them by `online`.
     */
    fun availableExitNodes(): List<TailnetPeer> =
        control.peers().let { TailnetPeer.parseList(it) }
            .filter { it.exitNodeOption }
            .sortedWith(compareByDescending<TailnetPeer> { it.isExitNode }.thenBy { it.name.lowercase() })

    /**
     * The peer currently used as exit node, or null when none/detached.
     *
     * Identified by [TailnetPeer.isExitNode] on the peer list rather than by
     * matching `ExitNode()`'s raw ID: the prefs ID and the list ID share the
     * StableNodeID space, so either works, but the flag is what
     * `tailscale status` itself reports and avoids a second source of truth.
     */
    fun currentExitNode(): TailnetPeer? =
        control.peers().let { TailnetPeer.parseList(it) }.firstOrNull { it.isExitNode }

    /**
     * Route all tailnet traffic through [peer], or clear the exit node when
     * null. Suspends on IO — EditPrefs is a JNI round-trip into the Go
     * backend that talks to the control plane.
     *
     * @throws IllegalStateException when the node is not running or the
     *   backend rejects the change (e.g. the peer revoked exit-node
     *   service between listing and selecting).
     */
    suspend fun setExitNode(peer: TailnetPeer?) {
        withContext(io) {
            try {
                control.setExitNode(peer?.id ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "setExitNode failed for ${peer?.id ?: "<clear>"}", e)
                throw IllegalStateException(
                    if (peer == null) "Failed to clear exit node: ${e.message}"
                    else "Failed to use '${peer.name}' as exit node: ${e.message}",
                    e,
                )
            }
        }
    }

    companion object {
        private const val TAG = "ExitNodeManager"
    }
}

/**
 * The tailnet-control surface [ExitNodeManager] needs. Implemented by the
 * gomobile shim in production, by a fake in JVM tests.
 */
interface ExitNodeControl {
    /** `Peers()` JSON array. */
    fun peers(): String

    /** Current exit-node StableID from prefs, or "" when detached. */
    fun exitNode(): String

    /** Set by StableID; "" clears. Throws on backend error. */
    fun setExitNode(peerId: String)
}

/** Production [ExitNodeControl]: the gomobile binding from the merged AAR. */
object NativeExitNodeControl : ExitNodeControl {
    override fun peers(): String = io.earthquack.app.tailscale.Tailscale.peers()

    override fun exitNode(): String = io.earthquack.app.tailscale.Tailscale.exitNode()

    override fun setExitNode(peerId: String) {
        // gomobile declares the Go error as a checked Exception; surface it
        // to callers instead of letting it escape as an undeclared throw.
        try {
            io.earthquack.app.tailscale.Tailscale.setExitNode(peerId)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "setExitNode failed", e)
        }
    }
}
