package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    const val MAX_WAV_BYTES = 25 * 1024 * 1024 // OpenAI's typical 25 MB audio cap
    const val MAX_RESPONSE_CHARS = 20_000

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        sttUrl: String = "https://api.openai.com/v1/audio/transcriptions",
        sttModel: String = "whisper-1",
        callback: (Result) -> Unit,
    ) {
        if (wavData.size > MAX_WAV_BYTES) {
            callback(Result(null, "Audio too large (${wavData.size / (1024*1024)} MB > 25 MB)"))
            return
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", sttModel)
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .build()

        val request = Request.Builder()
            .url(sttUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        // OkHttp validates the URL scheme; caller should pre-validate custom URLs.
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful && body.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                if (body.length > MAX_RESPONSE_CHARS + 2048) {
                    callback(Result(null, "Response too large (${body.length} chars)"))
                    return
                }
                val parsed = parseResponse(body)
                val capped = parsed.text?.take(MAX_RESPONSE_CHARS)
                callback(if (capped != null) Result(capped, null) else parsed)
            }
        })
    }
}
