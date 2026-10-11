package com.example.earthquack.transfer

import org.json.JSONObject

/**
 * Which way the bytes move.
 *
 * Deliberately only two values. A "move" is a download plus an upload to the
 * opposite account, and the two halves have different failure modes, different
 * cleanup and different resume rules. Composing them here would hide all
 * three behind one row that fails in the middle.
 */
enum class TransferType {
    /** Remote → this device. */
    DOWNLOAD,

    /** This device → remote. */
    UPLOAD
}

/**
 * Where a transfer is in its life.
 *
 * QUEUED and RUNNING are in-memory only by nature — a transfer that was
 * RUNNING when the process died is loaded back as [INTERRUPTED], never as
 * RUNNING, because nothing is running until something starts it. See
 * [TransferTask.loadRecovered].
 */
enum class TransferStatus {
    QUEUED,
    RUNNING,

    /** Finished, and verified where the backend allowed verification. */
    COMPLETED,

    /** Finished badly, with a message in [TransferTask.error]. */
    FAILED,

    /** Stopped by the user, or abandoned when the process died. */
    CANCELLED,

    /**
     * Recovered from storage after the process died mid-transfer.
     *
     * Honest by construction: no executor has claimed it, no bytes are
     * moving. It offers "retry" if and only if [TransferTask.canResume]
     * says the backend supports continuing; otherwise retry restarts from
     * the beginning, which is what the UI text says it will do.
     */
    INTERRUPTED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
}

/**
 * Which backend performs the bytes, and how.
 *
 * A sealed payload rather than free-form strings so a transfer loaded from
 * storage can only describe something an executor knows how to run, and so a
 * typo in a stored task is impossible to express.
 */
sealed class TransferBackend {

    /**
     * rclone `operations/copyfile`, remote → local cache.
     *
     * Carries the exact strings [com.example.earthquack.storage.RcloneDownloadRequest]
     * needs, so the existing planner and transfer class stay untouched.
     */
    data class RcloneDownload(
        val srcFs: String,
        val srcRemote: String,
        val dstFs: String,
        val dstRemote: String,
        val localFile: String
    ) : TransferBackend()

    /**
     * SFTP over an existing connection profile.
     *
     * [localFile] is the local end (source for an upload, destination for a
     * download) and [remotePath] the remote end. [profileId] selects the
     * row the saved profile screen keeps.
     */
    data class Sftp(
        val profileId: String,
        val localFile: String,
        val remotePath: String
    ) : TransferBackend()

    /** A tag for logs and the UI: "rclone" or "sftp". */
    val tag: String
        get() = when (this) {
            is RcloneDownload -> "rclone"
            is Sftp -> "sftp"
        }
}

/**
 * One file transfer, from submission to a terminal state.
 *
 * ## What is stored and what is not
 *
 * Everything needed to show history, restart an unfinished transfer, or
 * explain a failure is a field here; everything needed to *perform* the
 * transfer is in [backend], serialised alongside. There is deliberately no
 * credential field and no progress percentage: progress is bytes (see
 * [bytesTransferred]), the fraction is derived, and a percent would be a
 * rounded lie when the total is unknown.
 *
 * ## Identity
 *
 * [id] is generated at submission and never changes. [dedupeKey] is the
 * anti-double-tap key: two submissions with the same key while one is
 * already queued or running are the same transfer, however the user
 * produced them.
 */
data class TransferTask(
    val id: String,
    val type: TransferType,
    val backend: TransferBackend,
    /** What the user tapped: the file's display name. */
    val displayName: String,
    /** Human-readable origin, e.g. "Drive:" or "archii:/home/zoro". Never a credential. */
    val sourceLabel: String,
    val destination: String,
    val totalBytes: Long = -1L,
    val bytesTransferred: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val createdAt: Long = 0L,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val error: String? = null,
    /** Attempts made so far; a retry increments it. */
    val attempts: Int = 0
) {

    /** 0..1, or null when the total size is unknown. Never invented. */
    val fraction: Float?
        get() = if (totalBytes > 0) {
            (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }

    /**
     * Two submissions are the same transfer when they target the same bytes
     * to the same place with the same backend. A completed or failed task
     * does not block re-submission; only live ones do.
     */
    val dedupeKey: String
        get() = buildString {
            append(type.name); append('|'); append(backend.tag); append('|')
            append(backend::class.simpleName); append('|'); append(backendKey()); append('|')
            append(destination)
        }

    private fun backendKey(): String = when (backend) {
        is TransferBackend.RcloneDownload ->
            "${backend.srcFs}|${backend.srcRemote}|${backend.dstRemote}"
        is TransferBackend.Sftp ->
            "${backend.profileId}|${backend.remotePath}"
    }

    /**
     * Marks a task that was RUNNING when the process died.
     *
     * Kept as [TransferStatus.INTERRUPTED] with its byte progress: the byte
     * count is real work that partially happened, and discarding it would
     * either lose a resumable position or claim more progress than exists.
     */
    fun loadRecovered(): TransferTask = copy(status = TransferStatus.INTERRUPTED)

    // ── Serialisation ────────────────────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("name", displayName)
        put("srcLabel", sourceLabel)
        put("dst", destination)
        put("total", totalBytes)
        put("done", bytesTransferred)
        put("status", status.name)
        put("createdAt", createdAt)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("attempts", attempts)
        if (error != null) put("error", error) else put("error", JSONObject.NULL)
        put("backend", backendJson(backend))
    }

    companion object {

        /**
         * Rebuilds a task from storage. Returns null for an entry that cannot
         * be understood — a task whose backend tag is unknown is a task no
         * executor can run, and surfacing it as a permanently broken row
         * would be worse than dropping it.
         */
        fun fromJson(obj: JSONObject): TransferTask? {
            val backend = backendFromJson(obj.optJSONObject("backend")) ?: return null
            val status = runCatching {
                TransferStatus.valueOf(obj.optString("status", TransferStatus.QUEUED.name))
            }.getOrDefault(TransferStatus.QUEUED)
            val type = runCatching {
                TransferType.valueOf(obj.optString("type", TransferType.DOWNLOAD.name))
            }.getOrDefault(TransferType.DOWNLOAD)
            return TransferTask(
                id = obj.optString("id"),
                type = type,
                backend = backend,
                displayName = obj.optString("name"),
                sourceLabel = obj.optString("srcLabel"),
                destination = obj.optString("dst"),
                totalBytes = obj.optLong("total", -1L),
                bytesTransferred = obj.optLong("done", 0L),
                // A RUNNING task in storage is not running: nothing is
                // executing it until the manager says so.
                status = if (status == TransferStatus.RUNNING) TransferStatus.INTERRUPTED else status,
                createdAt = obj.optLong("createdAt", 0L),
                startedAt = obj.optLong("startedAt", 0L),
                finishedAt = obj.optLong("finishedAt", 0L),
                error = if (obj.isNull("error")) null else obj.optString("error").takeIf { it.isNotBlank() },
                attempts = obj.optInt("attempts", 0)
            ).takeIf { it.id.isNotBlank() }
        }

        private fun backendJson(backend: TransferBackend): JSONObject = when (backend) {
            is TransferBackend.RcloneDownload -> JSONObject().apply {
                put("kind", "rclone")
                put("srcFs", backend.srcFs)
                put("srcRemote", backend.srcRemote)
                put("dstFs", backend.dstFs)
                put("dstRemote", backend.dstRemote)
                put("localFile", backend.localFile)
            }
            is TransferBackend.Sftp -> JSONObject().apply {
                put("kind", "sftp")
                put("profileId", backend.profileId)
                put("localFile", backend.localFile)
                put("remotePath", backend.remotePath)
            }
        }

        private fun backendFromJson(obj: JSONObject?): TransferBackend? {
            if (obj == null) return null
            return when (obj.optString("kind")) {
                "rclone" -> TransferBackend.RcloneDownload(
                    srcFs = obj.optString("srcFs"),
                    srcRemote = obj.optString("srcRemote"),
                    dstFs = obj.optString("dstFs"),
                    dstRemote = obj.optString("dstRemote"),
                    localFile = obj.optString("localFile")
                ).takeIf { it.srcFs.isNotBlank() && it.localFile.isNotBlank() }
                "sftp" -> TransferBackend.Sftp(
                    profileId = obj.optString("profileId"),
                    localFile = obj.optString("localFile"),
                    remotePath = obj.optString("remotePath")
                ).takeIf { it.profileId.isNotBlank() && it.localFile.isNotBlank() && it.remotePath.isNotBlank() }
                else -> null
            }
        }
    }
}
