package com.kafkasl.phonewhisper

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * fal.ai Queue-based STT via Wizper (whisper v3 large).
 *
 * Flow is intentionally different from OpenAI-compatible multipart:
 *   POST https://queue.fal.run/fal-ai/wizper  {audio_url, task, language?}
 *   -> {request_id, status_url, response_url}
 *   poll  GET status_url  -> {status: IN_QUEUE|IN_PROGRESS|COMPLETED|FAILED}
 *   GET response_url      -> {text, chunks, ...}
 *
 * The local audio WAV is sent inline as a data URI (fal supports data: URIs
 * for file inputs; uploading separately isn't needed for dictation-sized clips).
 * Auth is `Authorization: Key <key>` (not Bearer).
 */
object FalTranscriber {

    private const val TAG = "FalTranscriber"
    private const val SUBMIT_URL = "https://queue.fal.run/fal-ai/wizper"
    private const val MAX_POLL_MS = 90_000L
    private const val POLL_INTERVAL_MS = 1_000L

    data class Result(val text: String?, val error: String?, val language: String? = null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        language: String? = null,
        callback: (Result) -> Unit,
    ) {
        thread {
            try {
                if (wavData.size > TranscriberClient.MAX_WAV_BYTES) {
                    callback(Result(null, "Audio too large (${wavData.size / (1024 * 1024)} MB > 25 MB)"))
                    return@thread
                }
                val dataUri = "data:audio/wav;base64,${Base64.encodeToString(wavData, Base64.NO_WRAP)}"
                val lang = language?.takeIf { it.isNotBlank() && it != "auto" }

                val payload = JSONObject().apply {
                    put("audio_url", dataUri)
                    put("task", "transcribe")
                    if (lang != null) put("language", lang)
                    // Optional: chunk-level timestamps are useful for debugging
                    // but not needed for dictation, so we skip them.
                }

                val req = Request.Builder()
                    .url(SUBMIT_URL)
                    .header("Authorization", "Key $apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val respBody = try {
                    client.newCall(req).execute().use { r ->
                        val body = r.body?.string() ?: ""
                        if (!r.isSuccessful) {
                            callback(Result(null, "fal.ai ${r.code}: ${body.take(500)}"))
                            return@thread
                        }
                        body
                    }
                } catch (e: Exception) {
                    callback(Result(null, "fal.ai submit failed: ${e.message}"))
                    return@thread
                }

                Log.d(TAG, "Wizper submit: ${respBody.take(300)}")
                val obj = try { JSONObject(respBody) } catch (e: Exception) {
                    callback(Result(null, "fal.ai: invalid JSON (${e.message})"))
                    return@thread
                }

                val requestId = obj.optString("request_id", "").ifBlank { null }
                val statusUrl = obj.optString("status_url", "").ifBlank { null }
                val responseUrl = obj.optString("response_url", "").ifBlank { null }

                // Some fal SDK variants return request_id without the URL helpers — build them.
                val resolvedStatusUrl = statusUrl ?: requestId?.let { "https://queue.fal.run/fal-ai/wizper/requests/$it/status" }
                val resolvedResponseUrl = responseUrl ?: requestId?.let { "https://queue.fal.run/fal-ai/wizper/requests/$it" }

                if (resolvedStatusUrl == null || resolvedResponseUrl == null) {
                    callback(Result(null, "fal.ai: missing request_id/status_url in response"))
                    return@thread
                }

                val text = pollAndFetch(apiKey, resolvedStatusUrl, resolvedResponseUrl, callback)
                text?.let { callback(Result(text, null, lang)) }
            } catch (e: Exception) {
                Log.e(TAG, "transcribe error", e)
                callback(Result(null, e.message ?: "Unknown fal.ai error"))
            }
        }
    }

    private fun pollAndFetch(
        apiKey: String,
        statusUrl: String,
        responseUrl: String,
        callback: (Result) -> Unit,
    ): String? {
        val deadline = System.currentTimeMillis() + MAX_POLL_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)

            val statusResp = try {
                client.newCall(
                    Request.Builder().url(statusUrl).header("Authorization", "Key $apiKey").get().build(),
                ).execute().use { r -> r.body?.string() ?: "" }
            } catch (e: Exception) {
                Log.w(TAG, "status poll failed: ${e.message}")
                continue
            }

            val statusObj = try { JSONObject(statusResp) } catch (_: Exception) { continue }
            val status = statusObj.optString("status", "").lowercase()

            when (status) {
                "completed" -> {
                    return fetchResult(apiKey, responseUrl, callback)
                }
                "failed", "error" -> {
                    val err = statusObj.optString("error", statusResp).take(500)
                    callback(Result(null, "fal.ai processing failed: $err"))
                    return null
                }
                else -> Log.d(TAG, "wizper status: $status")
            }
        }

        // Poll timeout — try one last fetch; some queues complete without the
        // status object updating cleanly.
        val lastTry = fetchResult(apiKey, responseUrl) { null }
        if (lastTry != null) return lastTry

        callback(Result(null, "fal.ai request timed out"))
        return null
    }

    private fun fetchResult(
        apiKey: String,
        responseUrl: String,
        callback: (Result) -> Unit = { },
    ): String? {
        return try {
            client.newCall(
                Request.Builder().url(responseUrl).header("Authorization", "Key $apiKey").get().build(),
            ).execute().use { r ->
                val body = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    // Let the caller handle errors; for the final-timeout probe,
                    // silently return null so we can report the timeout instead.
                    val alreadyCalled = body.contains("\"error\"")
                    if (alreadyCalled) Log.w(TAG, "fetch ${r.code}: $body")
                    return@use null
                }
                val obj = JSONObject(body)
                // wizper returns {text: "...", chunks: [...]} at top level, or
                // {output: {text: ...}} — handle both.
                val text = when {
                    obj.has("text") -> obj.getString("text").trim()
                    obj.has("output") -> obj.getJSONObject("output").optString("text", "").trim()
                    obj.has("logs") && obj.has("status") -> {
                        // Queue / response wrapper: status COMPLETED logs — try nested
                        null
                    }
                    else -> null
                }
                text?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchResult failed: ${e.message}")
            null
        }
    }
}
