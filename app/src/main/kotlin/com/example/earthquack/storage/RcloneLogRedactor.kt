package com.example.earthquack.storage

/**
 * Redaction for anything derived from rclone configuration or errors.
 *
 * rclone.conf holds OAuth refresh tokens and passwords. The gomobile
 * integration requires the file on disk as plaintext (`config.SetConfigPath`
 * points at it and rclone reads/writes it directly), so at-rest encryption
 * by the app is not compatible: rclone itself must be able to read and
 * rewrite the file on every `config/create` / `config/update` RPC. The
 * protection this app provides is therefore:
 *
 * - app-private location with owner-only (600) permissions,
 * - excluded from backup (`allowBackup="false"`),
 * - never served to viewers (FileProvider root excludes it),
 * - and never written to logcat — this object is that last rule as code.
 *
 * `config/dump` already redacts secrets server-side; this redactor is for
 * our own error paths and any string that might embed a value (exception
 * messages, RPC envelopes, file snippets).
 */
object RcloneLogRedactor {

    private val secretKeyPattern = Regex(
        "(?i)(password|passwd|secret|token|refresh[_-]?token|access[_-]?token|" +
            "client_secret|private_key|passphrase|bearer)[\"'\\s:=]+([^\\s\",}]+)"
    )

    /**
     * Replaces probable secret values with `[redacted]`. Idempotent and
     * safe on already-redacted or plain text. Never throws.
     */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return runCatching {
            secretKeyPattern.replace(text) { m -> m.value.replace(m.groupValues[2], "[redacted]") }
        }.getOrDefault("[redacted]")
    }

    /**
     * A log-safe one-line summary of an rclone failure: method + status +
     * redacted detail. The raw envelope (which may carry values on some
     * backends) is never returned.
     */
    fun describeFailure(method: String, status: Int, rawOutput: String?): String {
        val detail = runCatching {
            org.json.JSONObject(rawOutput ?: "{}").optString("error").ifBlank { null }
        }.getOrNull()
        val safe = detail?.let { redact(it).take(200) }
        return buildString {
            append("rclone RPC ")
            append(method)
            append(" failed with status ")
            append(status)
            if (safe != null) {
                append(": ")
                append(safe)
            }
        }
    }

    /**
     * True when [path] looks like it could be the rclone config: callers use
     * this to refuse logging file contents. The check is deliberately narrow
     * (exact filename) so it cannot suppress unrelated diagnostics.
     */
    fun isSensitivePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return name.equals("rclone.conf", ignoreCase = true)
    }
}
