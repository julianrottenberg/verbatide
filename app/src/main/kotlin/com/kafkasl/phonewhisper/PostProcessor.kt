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

    /**
     * Reasoning effort for thinking-capable chat models.
     *
     * Not all providers/models accept the same wire format:
     *  - OpenAI (o1/gpt-5 family) + Groq use `reasoning_effort` ("low"|"medium"|"high").
     *  - OpenRouter uses a `reasoning` object: {"effort": ...} or {"enabled": false}.
     * We send whichever applies for the selected endpoint; providers that don't
     * know a field simply ignore it.
     */
    enum class Reasoning(val key: String, val label: String, val subtitle: String) {
        DEFAULT("default", "Provider default", "Use whatever the model/provider defaults to"),
        OFF("off", "Off", "Disable reasoning (OpenRouter only; others ignore)"),
        LOW("low", "Low", "Minimal thinking — fastest/cheapest for cleanup"),
        MEDIUM("medium", "Medium", "Balanced thinking"),
        HIGH("high", "High", "Maximum thinking (slowest, rarely useful for cleanup)"),
        ;

        companion object {
            fun fromKey(key: String?): Reasoning =
                entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    fun process(
        text: String,
        prompt: String,
        apiKey: String,
        chatUrl: String = "https://api.openai.com/v1/chat/completions",
        chatModel: String = "gpt-4o-mini",
        reasoning: Reasoning = Reasoning.DEFAULT,
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
            applyReasoning(this, chatUrl, reasoning)
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

    private fun applyReasoning(bodyJson: JSONObject, chatUrl: String, reasoning: Reasoning) {
        if (reasoning == Reasoning.DEFAULT) return
        val isOpenRouter = chatUrl.contains("openrouter.ai")
        if (isOpenRouter) {
            // OpenRouter: reasoning: {"effort": "low"} or {"enabled": false}
            val obj = JSONObject()
            if (reasoning == Reasoning.OFF) {
                obj.put("enabled", false)
            } else {
                obj.put("effort", reasoning.key)
            }
            bodyJson.put("reasoning", obj)
        } else {
            // OpenAI / Groq / OpenAI-compatible: reasoning_effort: "low" (no "off" —
            // unsupported fields are ignored by providers, and models that don't
            // reason at all don't care either).
            if (reasoning != Reasoning.OFF) {
                bodyJson.put("reasoning_effort", reasoning.key)
            }
        }
    }
}
