package com.example.clipboardsync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ClipboardApi"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Lightweight HTTP client for the clipboard server.
 *
 * Two OkHttpClient instances are used:
 *  - [httpClient]  — standard timeouts for GET /clipboard and POST /clipboard
 *  - [sseClient]   — zero read timeout for the persistent SSE stream
 *
 * [baseUrl] is resolved from ServerConfig at construction time so the
 * Tailscale IP is user-configurable without recompiling.
 */
class ClipboardApi(private val baseUrl: String) {

    /** Backwards-compat constructor — uses hardcoded fallback URL. */
    constructor() : this(SERVER_BASE_URL)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(HTTP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** SSE client: read timeout MUST be 0 (infinite). */
    val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SSE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)  // 0 = infinite
        .writeTimeout(HTTP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * POST /clipboard with the given text (already AES-wrapped if enabled by caller).
     * Returns the server-assigned version on success, or -1 on failure.
     *
     * This is a blocking call — run it on a coroutine dispatcher (IO).
     */
    fun postClipboard(text: String): Int {
        val body = JSONObject().apply {
            put("clipboard", text)
            put("origin", ORIGIN_PHONE)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/clipboard")
            // .header("Authorization", "Bearer $token")   // <-- auth hook
            .post(body)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST /clipboard failed: ${response.code}")
                    return -1
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                json.optInt("version", -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST /clipboard exception: ${e.message}")
            -1
        }
    }

    /**
     * Build the request for GET /events (SSE).
     * The caller is responsible for executing this with [sseClient] and reading
     * the response body line-by-line.
     */
    fun buildSseRequest(): Request = Request.Builder()
        .url("$baseUrl/events")
        .header("Accept", "text/event-stream")
        .header("Cache-Control", "no-cache")
        // .header("Authorization", "Bearer $token")   // <-- auth hook
        .get()
        .build()

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        sseClient.dispatcher.executorService.shutdown()
    }
}
