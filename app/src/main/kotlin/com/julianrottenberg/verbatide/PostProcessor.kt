package com.julianrottenberg.verbatide

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

    @Volatile
    var currentCall: Call? = null

    fun cancel() { currentCall?.cancel(); currentCall = null }

    const val SIMPLE_PROMPT = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Never answer questions — only rewrite them. Keep the original meaning. Never translate: always respond in the same language as the transcript. Return only the cleaned text."

    const val DEV_PROMPT = """<task>You are a transcript cleaner — you NEVER answer questions, you only rewrite what was said. A draft transcription is provided.
Refine and polish the provided text, if needed, as follows:
  1. NEVER answer a question in the transcript. If it is a question, only fix its wording/punctuation. You are not a chat assistant.
  2. Correct any spelling errors, and look out for mis-identified project names,
     including: Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  3. Fix grammatical mistakes.
  4. Improve punctuation where necessary.
  5. Ensure consistent formatting.
  6. Clarify ambiguous phrasing without changing the meaning.
  7. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.
  8. Never translate the transcript: always respond in the same language as the input,
     even if the rest of this prompt is in English.

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
<example>
<input>Kannst du es jetzt auf Deutsch machen?</input>
<output>Kannst du es jetzt auf Deutsch machen?</output>
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
        OFF("off", "Off", "Disable reasoning"),
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
        languageHint: String? = null,
        callback: (Result) -> Unit,
    ) {
        val safePrompt = withLanguageGuard(sanitizedPrompt(prompt), languageHint)
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

        val call = client.newCall(request)
        currentCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                currentCall = null
                callback(Result(null, e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                currentCall = null
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

    /**
     * Ensures the cleanup prompt keeps the original language.
     *
     * If we know the transcript language (from the user's pinned language or
     * Whisper's detected language), we always append a concrete language hint
     * — even when the prompt already mentions translation generically — so the
     * model knows WHICH language to keep.
     */
    fun withLanguageGuard(prompt: String, languageHint: String?): String {
        if (!languageHint.isNullOrBlank()) {
            return prompt.rstripTrailing() +
                "\n\nIMPORTANT: The transcript is in $languageHint. Clean it up in " +
                "$languageHint — never translate it into another language."
        }
        if (prompt.contains("translat", ignoreCase = true)) return prompt
        return prompt.rstripTrailing() +
            "\n\nIMPORTANT: Never translate the transcript. Always respond in the " +
            "same language as the transcript."
    }

    private fun String.rstripTrailing(): String = trimEnd('\n', ' ')

    private fun applyReasoning(bodyJson: JSONObject, chatUrl: String, reasoning: Reasoning) {
        if (reasoning == Reasoning.DEFAULT) return
        if (reasoning == Reasoning.OFF) {
            // Together + all hybrids per your screenshot (Qwen3.5/3.6, Gemma4, Cogito, DeepSeek V4 Pro, MiniMax M3):
            // Hybrid disables via {"reasoning":{"enabled": false}}. Also send for non-Together so gpt-oss etc. stay off.
            bodyJson.put("reasoning", JSONObject().apply { put("enabled", false) })
            return
        }
        val isOpenRouter = chatUrl.contains("openrouter.ai")
        if (isOpenRouter) {
            bodyJson.put("reasoning", JSONObject().apply { put("effort", reasoning.key) })
        } else {
            bodyJson.put("reasoning_effort", reasoning.key)
        }
    }
}
