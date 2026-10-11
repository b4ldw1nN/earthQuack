package com.example.earthquack.storage

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A request to publish one remote file as a public link on a Google Drive remote.
 *
 * Constructed before any I/O, and every field is a plain string, so the mapping
 * from "the row the user tapped" to the two `(fs, remote)` pairs rclone needs is
 * a pure value that can be built and asserted in a JVM test without a remote.
 *
 * @param srcFs the source filesystem root the entry was listed from, i.e. the
 *   browser screen's current `fs` (`name:`, `:local:/abs/path`, …).
 * @param srcRemote the source path **relative to [srcFs]**, exactly as rclone's
 *   `operations/list` returned it. Passed through unmodified — rclone already
 *   escaped it, so re-escaping would corrupt a name with spaces or Unicode.
 * @param dstFs the destination Drive remote root, e.g. `gdrive:`. Must name a
 *   configured remote whose backend supports public links (Google Drive does).
 * @param dstFolder a folder **within [dstFs]** to place the copy in; empty for
 *   the remote's root. Never a leading or trailing slash — rclone wants a path
 *   relative to the filesystem root, and `/x` would be read as absolute.
 * @param fileName the name to give the copy on Drive. Kept verbatim so a name
 *   with spaces, Unicode or punctuation survives; [RcloneShareLink.resolveUniqueName]
 *   only appends a counter when the name is already taken, it never rewrites it.
 */
data class ShareLinkRequest(
    val srcFs: String,
    val srcRemote: String,
    val dstFs: String,
    val dstFolder: String,
    val fileName: String
)

/**
 * The outcome of a [RcloneShareLink.share] call.
 *
 * Deliberately three-way rather than success/failure, because the operation is
 * genuinely two steps and they fail independently. A file that uploaded to Drive
 * but whose permission could not be set (a Workspace admin who blocks public
 * sharing, most often) is **not** a success — no link exists — but it is also not
 * a clean failure: the copy is on Drive and re-running only the link step would
 * work. Collapsing that into "failed" throws away a file the user can still
 * reach; collapsing it into "success" hands back a link nobody can open. So the
 * partial state is its own case, carrying the destination so the caller can offer
 * a recovery path.
 */
sealed class ShareLinkResult {

    /**
     * The file was copied to Drive **and** a working public link was returned.
     * [url] is the address rclone produced (a `https://drive.google.com/...`
     * link for Drive); it is only populated here, never on a partial result.
     */
    data class Success(val url: String, val dstRemote: String) : ShareLinkResult()

    /**
     * The copy is on Drive at [dstRemote], but creating the public link failed.
     * [detail] is rclone's own error text where it gave one. The file is intact
     * and reachable by its owner; only the public permission is missing.
     */
    data class UploadedNoLink(
        val dstRemote: String,
        val detail: String
    ) : ShareLinkResult()

    /** The upload/copy itself failed. Nothing was published. [detail] is safe
     *  to show: it is rclone's error text or a fixed sentence, never a path or
     *  a secret. */
    data class Failure(val detail: String) : ShareLinkResult()
}

/**
 * Publishes a remote file as a public "anyone with the link" share on a Google
 * Drive remote, using the app's existing embedded rclone and its JSON RPC.
 *
 * The whole flow is expressed through rclone's documented RC interface
 * (https://rclone.org/rc/) — there is no separate Drive SDK, no second rclone
 * binary, and no bespoke OAuth flow. Authentication is whatever the configured
 * Drive remote already carries in `rclone.conf`; this class never sees a token.
 *
 * Three RPCs, in order:
 *  1. `operations/list` on the destination folder, once, to learn which names
 *     already exist so a collision can be avoided. rclone has no atomic
 *     "create if absent" for an object, and silently overwriting a same-named
 *     file is the wrong default for a share action.
 *  2. `operations/copyfile` with `srcFs/srcRemote/dstFs/dstRemote`. This is
 *     remote → remote *inside rclone*: the bytes go from the source backend to
 *     Drive without ever being written to Android storage. When the source is
 *     already the same Drive remote rclone performs a server-side copy; when the
 *     source is another remote it streams through rclone's own process. Neither
 *     path downloads to the phone and re-uploads.
 *  3. `operations/publiclink` on the destination. For the Google Drive backend
 *     this is not merely a lookup: it creates a real permission
 *     (`type=anyone, role=reader, allowFileDiscovery=false`) and returns the
 *     file's share URL — precisely "Anyone with the link — Viewer", which is why
 *     no separate permission call is needed for Drive.
 *
 * Every call goes through [RcloneEngine], whose `call`/`callRaw` already switch
 * to [Dispatchers.IO]; the native rclone RPC is a blocking cgo call, so none of
 * this runs on the main thread. `librclone.RPC` cannot be interrupted once it has
 * entered native code, so cancelling the coroutine discards the result but does
 * not halt bytes already in flight (the same limitation [RcloneFileTransfer]
 * documents); for a remote→remote copy there is no local partial file to clean
 * up. There is no per-byte progress because a determinate figure needs rclone's
 * async job API (`_async` + `job/status`), which requires the `fs/rc/jobs`
 * package linked into the shim — a Go rebuild plus an NDK this project lacks.
 */
class RcloneShareLink(private val engine: RcloneEngine) {

    /**
     * Copies [request]'s source file to its Drive destination and returns a
     * public link.
     *
     * Runs entirely on [Dispatchers.IO] (via [RcloneEngine]). Throws only
     * `CancellationException` when the coroutine is cancelled; every other
     * outcome is a [ShareLinkResult].
     */
    suspend fun share(request: ShareLinkRequest): ShareLinkResult =
        withContext(Dispatchers.IO) {
            require(request.srcFs.isNotBlank()) { "srcFs must not be blank" }
            require(request.srcRemote.isNotBlank()) { "srcRemote must not be blank" }
            require(request.dstFs.isNotBlank()) { "dstFs must not be blank" }
            require(request.fileName.isNotBlank()) { "fileName must not be blank" }

            // 1. Pick a destination name that does not clobber an existing file.
            val existing = existingNames(request.dstFs, request.dstFolder)
            val uniqueName = resolveUniqueName(request.fileName, existing)
            val dstRemote = joinDestination(request.dstFolder, uniqueName)

            // 2. Copy remote → remote. Never downloads to Android first.
            try {
                engine.call(
                    METHOD_COPYFILE,
                    JSONObject()
                        .put("srcFs", request.srcFs)
                        .put("srcRemote", request.srcRemote)
                        .put("dstFs", request.dstFs)
                        .put("dstRemote", dstRemote)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "share copyfile failed: ${e.javaClass.simpleName}")
                return@withContext ShareLinkResult.Failure(describe(e))
            }

            // 3. Create the public permission and fetch the link.
            try {
                val reply = engine.call(
                    METHOD_PUBLICLINK,
                    JSONObject()
                        .put("fs", request.dstFs)
                        .put("remote", dstRemote)
                )
                val url = reply.optString("url").trim()
                if (url.isEmpty()) {
                    return@withContext ShareLinkResult.UploadedNoLink(
                        dstRemote,
                        "The link could not be created"
                    )
                }
                ShareLinkResult.Success(url, dstRemote)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "share publiclink failed: ${e.javaClass.simpleName}")
                ShareLinkResult.UploadedNoLink(dstRemote, describe(e))
            }
        }

    /**
     * The names already present in `dstFs/dstFolder`, or an empty set if the
     * folder cannot be listed.
     *
     * A listing failure is not fatal here: the worst case is that a rare
     * collision is not pre-empted and `operations/copyfile` overwrites — the
     * same risk a browser without this check would have. The common, cheap case
     * (root of Drive) lists fine. A single `operations/list` rather than an
     * `operations/stat` per candidate keeps this to one round trip.
     */
    private suspend fun existingNames(dstFs: String, dstFolder: String): Set<String> {
        val reply = try {
            engine.call(
                METHOD_LIST,
                JSONObject().put("fs", dstFs).put("remote", dstFolder)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptySet()
        }
        val list = reply.optJSONArray("list") ?: return emptySet()
        val names = LinkedHashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            item.optString("Name").takeIf { it.isNotEmpty() }?.let { names.add(it) }
        }
        return names
    }

    /** rclone's human-readable error text, or a fixed fallback. Mirrors
     *  [RcloneFileTransfer]'s mapping so the two screens read the same. */
    private fun describe(e: Exception): String {
        if (e is RcloneException) {
            val detail = try {
                JSONObject(e.rcloneOutput).optString("error").trim()
            } catch (_: Exception) {
                ""
            }
            if (detail.isNotEmpty()) {
                return detail.lineSequence().firstOrNull()?.trim().orEmpty()
                    .ifEmpty { FALLBACK_MESSAGE }
                    .take(MAX_DETAIL)
            }
        }
        return e.message?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL) ?: FALLBACK_MESSAGE
    }

    companion object {
        private const val TAG = "RcloneShareLink"

        private const val METHOD_LIST = "operations/list"
        private const val METHOD_COPYFILE = "operations/copyfile"
        private const val METHOD_PUBLICLINK = "operations/publiclink"

        private const val FALLBACK_MESSAGE = "The file could not be shared"
        private const val MAX_DETAIL = 200
        private const val FALLBACK_NAME = "shared file"

        /** Upper bound on the collision counter before we stop trying. */
        private const val MAX_COLLISION_ATTEMPTS = 999

        /**
         * Joins a destination folder and a file name into a path relative to the
         * filesystem root, the shape `operations/copyfile`'s `dstRemote` wants.
         *
         * Pure and public so the exact path rclone is asked to write can be
         * asserted in a test: a trailing slash on the folder, or a leading slash
         * on the result, would both change what rclone interprets.
         */
        fun joinDestination(folder: String, fileName: String): String {
            val trimmedFolder = folder.trim().trim('/')
            return if (trimmedFolder.isEmpty()) fileName
            else "$trimmedFolder/$fileName"
        }

        /**
         * Returns [desired] if it is free, otherwise the first
         * `base (n).ext` that is not in [existing] — Google Drive's own
         * disambiguation, so the behaviour matches what a user expects from
         * uploading the same name twice.
         *
         * Pure and public: this is the rule that guarantees a share never
         * silently overwrites an existing file. A name is only ever appended to,
         * never rewritten, so Unicode, spaces and punctuation in [desired] are
         * preserved.
         */
        fun resolveUniqueName(desired: String, existing: Set<String>): String {
            val clean = desired.trim().ifEmpty { return FALLBACK_NAME }
            if (clean !in existing) return clean

            // Split at the last dot, but only when it is not the first character
            // and not a trailing dot: ".gitignore" and "archive." keep their dots.
            val dot = clean.lastIndexOf('.')
            val hasExt = dot > 0 && dot < clean.length - 1
            val base = if (hasExt) clean.substring(0, dot) else clean
            val ext = if (hasExt) clean.substring(dot) else ""

            for (n in 1..MAX_COLLISION_ATTEMPTS) {
                val candidate = "$base ($n)$ext"
                if (candidate !in existing) return candidate
            }
            // Astronomically unlikely; return a name that will collide and let
            // copyfile surface the real error rather than loop forever.
            return "$base (${MAX_COLLISION_ATTEMPTS + 1})$ext"
        }
    }
}

