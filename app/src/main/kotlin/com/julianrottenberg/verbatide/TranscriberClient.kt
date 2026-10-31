package com.julianrottenberg.verbatide

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object TranscriberClient {
    data class Result(val text: String?, val error: String?, val language: String? = null)

    private val client = OkHttpClient.Builder()
        // Inactivity timeouts. The total budget is per call (see [budgetMs]) so a
        // 30 s dictation keeps a short leash while a 5 min one is not cut off.
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Volatile
    var currentCall: Call? = null

    fun cancel() { currentCall?.cancel(); currentCall = null }

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(
                obj.getString("text"),
                null,
                language = obj.optString("language", "").ifBlank { null },
            )
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    const val MAX_WAV_BYTES = 25 * 1024 * 1024 // OpenAI's typical 25 MB audio cap
    const val MAX_RESPONSE_CHARS = 20_000

    /**
     * Whole-call budget scaled to the audio: 16 kHz mono 16-bit WAV is 32 kB/s,
     * so a 10 s clip gets ~40 s and a 5 min one ~180 s. The floor keeps a short
     * clip from dying while a slow provider warms up; the ceiling stays under the
     * documented per-request ASR limits.
     */
    fun budgetMs(wavData: ByteArray): Long {
        val seconds = ((wavData.size - 44).coerceAtLeast(0) / 32_000).toLong()
        return (30_000L + seconds * 1_000L).coerceIn(45_000L, 180_000L)
    }

    /**
     * [vocabulary] is the dictionary's output spellings. It is sent only where the
     * endpoint documents a hint (see [DictionaryManager.promptStyleFor]); elsewhere
     * the post-transcription replacement pass is the only dictionary effect.
     */
    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        sttUrl: String = "https://api.openai.com/v1/audio/transcriptions",
        sttModel: String = "whisper-1",
        language: String? = null,
        vocabulary: List<String> = emptyList(),
        callback: (Result) -> Unit,
    ) {
        if (wavData.size > MAX_WAV_BYTES) {
            callback(Result(null, "Audio too large (${wavData.size / (1024*1024)} MB > 25 MB)"))
            return
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", sttModel)
            // Together's /audio/transcriptions still drifts to English when
            // `language` is not pinned (even when cleanup is off) — always
            // pass the user's Transcription language setting when set.
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            // verbose_json adds a `language` field so the cleanup step can be
            // told which language to keep. Not all providers support it —
            // Together rejects it, so omit for Together.
            .let { b ->
                if (!sttUrl.contains("together.ai") && !isMiniMaxUrl(sttUrl)) b.addFormDataPart("response_format", "verbose_json") else b
            }

        // Pinning the language prevents drift-to-English for non-English speech.
        // Provider behavior for 'auto':
        //   OpenAI/Groq/OpenRouter: omitted == auto (Groq turbo does short-clip VAD).
        //   Together:      docs list 'auto' as explicit enum (default) and omitted -> en,
        //                  so we must send 'auto' explicitly or it translates (even long clips).
        //   Fal wizper:    send language=null JSON for auto (handled in FalTranscriber).
        if (!language.isNullOrBlank()) {
            if (language == "auto") {
                if (sttUrl.contains("together.ai")) bodyBuilder.addFormDataPart("language", "auto")
                // else: omit -> true auto for OpenAI/Groq/OpenRouter
            } else {
                bodyBuilder.addFormDataPart("language", language)
            }
        }

        // Dictionary vocabulary, in the encoding each endpoint documents.
        // Whisper-family: one `prompt` string. Mistral: repeated `context_bias`
        // fields, one per phrase (a single comma-joined value biases nothing).
        val style = DictionaryManager.promptStyleFor(sttUrl)
        val promptText = if (style == DictionaryManager.PromptStyle.PROMPT_FIELD) {
            DictionaryManager.vocabularyPrompt(vocabulary).ifBlank { null }
        } else null
        if (promptText != null) bodyBuilder.addFormDataPart("prompt", promptText)
        if (style == DictionaryManager.PromptStyle.CONTEXT_BIAS) {
            DictionaryManager.contextBiasTerms(vocabulary).forEach { bodyBuilder.addFormDataPart("context_bias", it) }
        }

        val body = bodyBuilder.build()

        val request =
            Request.Builder()
                .url(sttUrl)
                .header("Authorization", "Bearer $apiKey")
                // MiniMax ASR is the odd one out: it wants `language` as a BCP-47
                // *header*, not a form field, and documents no prompt/citation
                // hint at all (DictionaryManager.promptStyleFor returns NONE).
                .apply {
                    if (isMiniMaxUrl(sttUrl) && !language.isNullOrBlank() && language != "auto") {
                        header("language", language)
                    }
                }.post(body)
                .build()

        // OkHttp validates the URL scheme; caller should pre-validate custom URLs.
        val budget = budgetMs(wavData)
        val call = client.newCall(request)
        // Whole-call ceiling: a long dictation gets more room, a short one does
        // not sit for two minutes when the provider is simply stuck.
        call.timeout().timeout(budget, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Published for cancel(): the stop button and a new dictation must be able
        // to abort the request that is actually in flight.
        currentCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                currentCall = null
                // A per-call timeout surfaces as InterruptedIOException, not
                // SocketTimeoutException — both have to read as "timeout" in the
                // history, otherwise the cause looks like a network failure.
                val timedOut = e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException
                callback(Result(null, if (timedOut) "timeout after ${budget / 1000}s" else e.message))
            }
            override fun onResponse(call: Call, response: Response) {
                currentCall = null
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    // Keep the status code: ProviderHealth.classify() drives the
                    // backup switch from it (402 = out of quota, 401 = bad key),
                    // and providers disagree wildly about error body shapes.
                    val detail = if (body.isBlank()) null else runCatching { parseResponse(body).error }.getOrNull()
                    callback(Result(null, "HTTP ${response.code}" + (detail?.let { ": $it" } ?: "")))
                    return
                }
                if (body.length > MAX_RESPONSE_CHARS + 2048) {
                    callback(Result(null, "Response too large (${body.length} chars)"))
                    return
                }
                val parsed = parseResponse(body)
                // Whisper can echo the prompt back on silence; never let it reach the user.
                val capped = parsed.text
                    ?.take(MAX_RESPONSE_CHARS)
                    ?.let { DictionaryManager.stripPromptEcho(it, promptText) }
                callback(if (capped != null) Result(capped, null, parsed.language) else parsed)
            }
        })
    }

    /** MiniMax's ASR endpoint is OpenAI-shaped but takes `language` as a header. */
    internal fun isMiniMaxUrl(url: String): Boolean = url.contains("minimax")
}
