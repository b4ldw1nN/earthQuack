package com.example.earthquack.tailscale

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Taildrop send/receive for the embedded Tailscale node.
 *
 * Send stages a local file (or content [Uri]) and pushes it to a peer via
 * the node's Taildrop API. Receive lists pending incoming offers and
 * accepts/declines them into a destination directory.
 *
 * The gomobile calls sit behind [TaildropControl] so the logic is
 * JVM-testable with a fake.
 */
class TaildropManager(
    private val control: TaildropControl,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val context: Context? = null,
) {

    /** Push a local file path to [peer]. */
    suspend fun send(peer: TailnetPeer, path: String) {
        sendPath(peer, path)
    }

    suspend fun sendFile(peer: TailnetPeer, path: String) = send(peer, path)
    suspend fun sendFile(peer: TailnetPeer, file: File) = send(peer, file)
    suspend fun sendFile(peer: TailnetPeer, uri: Uri) = send(peer, uri)
    suspend fun push(peer: TailnetPeer, path: String) = send(peer, path)
    suspend fun push(peer: TailnetPeer, file: File) = send(peer, file)
    suspend fun push(peer: TailnetPeer, uri: Uri) = send(peer, uri)

    /** Push a [File] to [peer]. */
    suspend fun send(peer: TailnetPeer, file: File) {
        sendPath(peer, file.absolutePath)
    }

    /** Push the content identified by [uri] to [peer]. Stages it first. */
    suspend fun send(peer: TailnetPeer, uri: Uri) {
        if (context == null) {
            sendPath(peer, uri.toString())
            return
        }
        val staged = stageUri(uri)
        sendPath(peer, staged.absolutePath)
    }

    private suspend fun sendPath(peer: TailnetPeer, path: String) {
        withContext(io) {
            try {
                control.taildropSend(peer.id, path)
            } catch (e: Exception) {
                Log.e(TAG, "taildrop send failed for ${peer.id}", e)
                throw IllegalStateException("Failed to send file to '${peer.name}': ${e.message}", e)
            }
        }
    }

    private suspend fun stageUri(uri: Uri): File = withContext(io) {
        val ctx = context ?: throw IllegalStateException("no context available to read $uri")
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "taildrop-${System.currentTimeMillis()}"
        val dir = File(ctx.cacheDir, "taildrop-outgoing")
        dir.mkdirs()
        val dest = File(dir, name)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("cannot open $uri")
        dest
    }

    /** Incoming offers waiting to be accepted. */
    fun pendingOffers(): List<TaildropOffer> =
        TaildropOffer.parseList(control.taildropPending())

    fun pending(): List<TaildropOffer> = pendingOffers()

    fun incomingOffers(): List<TaildropOffer> = pendingOffers()

    /** Accept [offer] into [destDir]. Returns the saved file. */
    suspend fun accept(offer: TaildropOffer, destDir: File): File =
        accept(offer.id, destDir, offer.name)

    suspend fun accept(offerId: String, destDir: File): File =
        accept(offerId, destDir, offerId)

    suspend fun accept(offerId: String, destDir: String): File =
        accept(offerId, File(destDir), offerId)

    private suspend fun accept(offerId: String, destDir: File, name: String): File = withContext(io) {
        try {
            destDir.mkdirs()
            control.taildropAccept(offerId, destDir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "taildrop accept failed for $offerId", e)
            throw IllegalStateException("Failed to accept '$offerId': ${e.message}", e)
        }
        File(destDir, name)
    }

    /** Decline [offer]. */
    suspend fun decline(offer: TaildropOffer) {
        decline(offer.id)
    }

    suspend fun decline(offerId: String) {
        withContext(io) {
            try {
                control.taildropDecline(offerId)
            } catch (e: Exception) {
                Log.e(TAG, "taildrop decline failed for $offerId", e)
                throw IllegalStateException("Failed to decline '$offerId': ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "TaildropManager"
    }
}

interface TaildropControl {
    /** Push a file to a peer. Throws on backend error. */
    fun taildropSend(peerId: String, filePath: String)

    /** JSON array of pending incoming offers. */
    fun taildropPending(): String

    /** Accept a pending offer into destDir. Throws on backend error. */
    fun taildropAccept(offerId: String, destDir: String)

    /** Decline a pending offer. Throws on backend error. */
    fun taildropDecline(offerId: String)
}

/** Production [TaildropControl]: the gomobile binding from the merged AAR. */
object NativeTaildropControl : TaildropControl {
    override fun taildropSend(peerId: String, filePath: String) {
        try {
            com.example.earthquack.tailscale.Tailscale.taildropSend(peerId, filePath)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "taildropSend failed", e)
        }
    }

    override fun taildropPending(): String =
        com.example.earthquack.tailscale.Tailscale.taildropPending()

    override fun taildropAccept(offerId: String, destDir: String) {
        try {
            com.example.earthquack.tailscale.Tailscale.taildropAccept(offerId, destDir)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "taildropAccept failed", e)
        }
    }

    override fun taildropDecline(offerId: String) {
        try {
            com.example.earthquack.tailscale.Tailscale.taildropDecline(offerId)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "taildropDecline failed", e)
        }
    }
}