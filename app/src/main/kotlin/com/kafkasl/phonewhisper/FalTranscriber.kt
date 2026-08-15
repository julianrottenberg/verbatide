package com.kafkasl.phonewhisper

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * fal.ai Queue-based STT via Wizper (whisper v3 large).
 *
 * Wizper rejects data: URIs (see: https://fal.ai/models/fal-ai/wizper?share=... ) —
 * we must use fal Storage: initiate upload -> PUT bytes -> use file_url as audio_url.
 *
 * Queue: POST https://queue.fal.run/fal-ai/wizper     -> {request_id, status_url, response_url}
 *        poll GET status_url -> {status: ...}
 *        GET response_url   -> {text, chunks, languages}
 *
 * Model card: https://fal.ai/models/fal-ai/wizper/api — defaults to
 * language="en" and task="transcribe"; to support auto-detect we must
 * send language: null explicitly so wizper does not force English output.
 * Auth is `Authorization: Key <key>` (not Bearer).
 */
object FalTranscriber {

    private const val TAG = "FalTranscriber"
    private const val SUBMIT_URL = "https://queue.fal.run/fal-ai/wizper"
    // fal's JS SDK posts to https://rest.fal.ai/storage/upload/initiate
    // with {content_type, file_name}. This is the documented storage API.
    private const val STORAGE_INIT_URL = "https://rest.fal.ai/storage/upload/initiate?storage_type=fal-cdn-v3"
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
        cancelled = false
        thread {
            try {
                if (wavData.size > TranscriberClient.MAX_WAV_BYTES) {
                    callback(Result(null, "Audio too large (${wavData.size / (1024 * 1024)} MB > 25 MB)"))
                    return@thread
                }
                // Upload to fal Storage first — wizper rejects data: URIs
                // (https://fal.ai/models/fal-ai/wizper reports "Unsupported data URL").
                val fileUrl = uploadToFalStorage(apiKey, wavData)
                    ?: return@thread // error already reported via callback

                val lang = language?.trim()?.takeIf { it.isNotBlank() && it.lowercase() != "auto" }?.lowercase()
                // language = null means auto-detect for wizper; "en" default
                // would force English output. Send null explicitly for auto.
                val languageJsonValue: Any = if (lang != null) lang else JSONObject.NULL

                val payload = JSONObject().apply {
                    put("audio_url", fileUrl)
                    put("task", "transcribe")
                    put("language", languageJsonValue)
                    put("version", "3")
                    put("chunk_level", "segment")
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

                Log.d(TAG, "Wizper submit: ${respBody.take(500)} — payload language=${payload.opt("language")}")
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

    /**
     * fal Storage: POST /storage/upload/initiate -> {upload_url, file_url}, then PUT wav bytes.
     * Returns the file_url to use as audio_url, or null after reporting an error via callback.
     */
    @Volatile
    var cancelled = false
    fun cancel() { cancelled = true }

    private fun uploadToFalStorage(apiKey: String, wavData: ByteArray): String? {
        val initBody = JSONObject().apply {
            put("content_type", "audio/wav")
            put("file_name", "phonewhisper-${System.currentTimeMillis()}.wav")
        }.toString().toRequestBody("application/json".toMediaType())

        val initReq = Request.Builder()
            .url(STORAGE_INIT_URL)
            .header("Authorization", "Key $apiKey")
            .post(initBody)
            .build()

        val (uploadUrl, fileUrl) = try {
            client.newCall(initReq).execute().use { r ->
                val body = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    Log.e(TAG, "storage initiate ${r.code}: $body")
                    return null
                }
                val obj = JSONObject(body)
                Pair(obj.getString("upload_url"), obj.getString("file_url"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "storage initiate failed", e)
            return null
        }

        // PUT the bytes to the signed URL
        val putReq = Request.Builder()
            .url(uploadUrl)
            .put(wavData.toRequestBody("audio/wav".toMediaType()))
            .build()
        // Don't add Authorization to the signed URL.
        try {
            client.newCall(putReq).execute().use { r ->
                if (!r.isSuccessful) {
                    val body = r.body?.string()?.take(500) ?: ""
                    Log.e(TAG, "storage PUT ${r.code}: $body")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "storage PUT failed", e)
            return null
        }
        return fileUrl
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
                val obj: JSONObject? = try { JSONObject(body) } catch (e: Exception) {
                    Log.w(TAG, "fetch invalid JSON: ${e.message} — ${body.take(500)}")
                    return@use null
                }
                // wizper returns {text, chunks, languages} top-level, sometimes
                // wrapped as {output: {...}} or queue logs. Handle both.
                val text: String? = when {
                    obj == null -> null
                    obj.has("text") -> obj.getString("text").trim()
                    obj.has("output") && obj.get("output") is JSONObject ->
                        (obj.get("output") as JSONObject).optString("text", "").trim()
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
