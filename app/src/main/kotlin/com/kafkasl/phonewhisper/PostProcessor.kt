package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object PostProcessor {
    data class Result(val text: String?, val error: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    const val SIMPLE_PROMPT = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Keep the original meaning. Return only the cleaned text."

    const val DEV_PROMPT = """<task>A text is provided which is a draft transcription from a speech to text model.
Refine and polish the provided text, if needed, as follows:
  1. Correct any spelling errors, and look out for mis-identified project names,
     including: Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  2. Fix grammatical mistakes.
  3. Improve punctuation where necessary.
  4. Ensure consistent formatting.
  5. Clarify ambiguous phrasing without changing the meaning.
  6. If the transcript contains a question, edit it for clarity but do not provide an
     answer.
  7. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.

Return *only* the cleaned-up version of the transcript. Do *not* add any explanations or
comments about your edits. Do *not* answer any question in the text, *only* transcribe it.
</task>
<examples>
<example>
<input>How do eye increase the font size in fast html?</input>
<output>How do I increase the font size in FastHTML?</output>
</example>
<example>
<input>Where is Paris?</input>
<output>Where is Paris?</output>
</example>
<example>
<input>Here is the full list of options colon</input>
<output>Here is the full list of options:</output>
</example>
<example>
<input>Command mode ssh into morty user at rubicon</input>
<output>ssh morty@rubicon</output>
</example>
<example>
<input>List files in current directory</input>
<output>ls -l .</output>
</example>
</examples>"""

    const val DEFAULT_PROMPT = DEV_PROMPT

    fun parseResponse(json: String): Result {
        return try {
            val obj = JSONObject(json)
            if (obj.has("choices")) {
                val choices = obj.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    Result(message.getString("content").trim(), null)
                } else {
                    Result(null, "No choices in response")
                }
            } else if (obj.has("error")) {
                Result(null, obj.getJSONObject("error").getString("message"))
            } else {
                Result(null, "Unknown response format")
            }
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }
    }

    // Guard chat input/output size: transcripts are ~100–500 chars, prompts ~2–4k.
    // Clip obvious abuse and bound response handling before it reaches clipboard.
    const val MAX_INPUT_CHARS = 12_000
    const val MAX_PROMPT_CHARS = 12_000
    const val MAX_OUTPUT_CHARS = 12_000

    fun sanitizedPrompt(prompt: String): String =
        if (prompt.length > MAX_PROMPT_CHARS) prompt.take(MAX_PROMPT_CHARS) else prompt

    fun sanitizedText(text: String): String =
        if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text

    fun truncatedOutput(text: String): String =
        if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) else text

    fun process(
        text: String,
        prompt: String,
        apiKey: String,
        chatUrl: String = "https://api.openai.com/v1/chat/completions",
        chatModel: String = "gpt-4o-mini",
        callback: (Result) -> Unit,
    ) {
        val safePrompt = sanitizedPrompt(prompt)
        val safeText = sanitizedText(text)
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", safePrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", safeText)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", chatModel)
            put("messages", messages)
            put("temperature", 0.0)
        }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(chatUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful && responseBody.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}"))
                    return
                }
                val parsed = parseResponse(responseBody)
                val capped = parsed.text?.let { truncatedOutput(it) }
                callback(if (capped != null) Result(capped, null) else parsed)
            }
        })
    }
}
