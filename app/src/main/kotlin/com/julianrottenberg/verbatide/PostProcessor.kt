package com.julianrottenberg.verbatide

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object PostProcessor {
    private const val TAG = "PostProcessor"

    data class Result(
        val text: String?,
        val error: String?,
    )

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    @Volatile
    var currentCall: Call? = null

    fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }

    const val SIMPLE_PROMPT = "Clean up this speech-to-text transcript. Fix punctuation, capitalization, and obvious speech-to-text errors. Never answer questions — only rewrite them. Keep the original meaning. Never translate: always respond in the same language as the transcript. Return only the cleaned text."

    const val DEV_PROMPT = """<task>You are a transcript cleaner — you NEVER answer questions, you only rewrite what was said. A draft transcription is provided.
Make the smallest possible edits, and only these:
  1. NEVER answer a question in the transcript. If it is a question, only fix its wording/punctuation. You are not a chat assistant.
  2. Fix spelling errors, punctuation, and capitalization. Watch for mis-identified project names,
     including: Solveit, fast.ai, Answer.AI, nbdev, fastcore, FastHTML, Pi, Codex, Claude Code, Hetzner.
  3. Remove filler words (um, uh, äh, ähm) and resolve the speaker's self-corrections
     ("I mean", "scratch that", "sorry, ...").
  4. Everything else stays exactly as spoken: never add words, never drop meaningful words,
     never swap synonyms, never restructure sentences. Keep hedges ("might", "I think"),
     negations, names, and numbers verbatim.
  5. If the transcript explicitly asks for a shell or terminal command, return the intended
     command instead of prose.
  6. Never translate the transcript: always respond in the same language as the input,
     even if the rest of this prompt is in English.

Return ONLY the cleaned transcript. No preamble, no labels, no quotes, no explanations.
If the transcript is already clean, return it unchanged.
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
<example>
<input>It can also convert swear words to their equivalence.</input>
<output>It can also convert swear words to their equivalence.</output>
</example>
<example>
<input>Bro um this is so cool right here</input>
<output>Bro, this is so cool right here.</output>
</example>
</examples>"""

    const val DEFAULT_PROMPT = DEV_PROMPT

    /**
     * The default cleanup prompt as shipped before the minimal-edit rewrite.
     * Users who opened the prompt editor and hit Save have this verbatim copy
     * pinned in prefs — it would shadow the improved default forever, so we
     * detect it and upgrade in-place (see migrateLegacyDefaultPrompt).
     */
    const val LEGACY_DEV_PROMPT = """<task>You are a transcript cleaner — you NEVER answer questions, you only rewrite what was said. A draft transcription is provided.
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

    /** True when the stored prompt is the legacy default verbatim (never customized). */
    fun isLegacyDefaultPrompt(stored: String?): Boolean = stored != null && stored.trim() == LEGACY_DEV_PROMPT.trim()

    /**
     * Upgrades a pinned legacy default prompt to the new DEFAULT_PROMPT.
     * A prompt the user actually edited is left untouched. Safe to call on
     * every app/service start; no-ops when there is nothing to migrate.
     */
    fun migrateLegacyDefaultPrompt(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        if (!isLegacyDefaultPrompt(prefs.getString("post_processing_prompt", null))) return
        val editor = prefs.edit().putString("post_processing_prompt", DEFAULT_PROMPT)
        if (isLegacyDefaultPrompt(prefs.getString("custom_post_processing_prompt", null))) {
            editor.putString("custom_post_processing_prompt", DEFAULT_PROMPT)
        }
        editor.apply()
        Log.i("PostProcessor", "Migrated legacy default cleanup prompt to minimal-edit default")
    }

    fun parseResponse(json: String): Result =
        try {
            val obj = JSONObject(json)
            if (obj.has("choices")) {
                val choices = obj.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    Result(message.getString("content").trim(), null)
                } else {
                    Result(null, "No choices in response")
                }
            } else {
                val err = extractError(obj)
                Result(null, err ?: "Unknown response format")
            }
        } catch (e: Exception) {
            Result(null, e.message ?: "Parse error")
        }

    /**
     * Pulls a human-readable error out of an error body, whichever shape the
     * provider uses:
     *  - OpenAI/Groq/OpenRouter: {"error": {"message": "..."}}
     *  - Mistral (422): {"message": {"detail": [{"loc": [...], "msg": "..."}]}}
     *    or {"message": "..."}
     */
    internal fun extractError(obj: JSONObject): String? {
        if (obj.has("error")) {
            val e = obj.optJSONObject("error")
            if (e != null) return e.optString("message").ifBlank { e.toString().take(300) }
            return obj.opt("error")?.toString()?.take(300)
        }
        if (obj.has("message")) {
            return when (val m = obj.get("message")) {
                is String -> {
                    m
                }

                is JSONObject -> {
                    val detail = m.optJSONArray("detail")
                    if (detail != null && detail.length() > 0) {
                        (0 until detail.length())
                            .mapNotNull { i ->
                                val d = detail.optJSONObject(i) ?: return@mapNotNull null
                                val loc =
                                    d.optJSONArray("loc")?.let { l ->
                                        (0 until l.length()).joinToString(".") { j -> l.optString(j) }
                                    }
                                listOfNotNull(loc, d.optString("msg").ifBlank { null })
                                    .joinToString(": ")
                                    .ifBlank { null }
                            }.joinToString("; ")
                            .ifBlank { m.toString().take(300) }
                    } else {
                        m.toString().take(300)
                    }
                }

                else -> {
                    m.toString().take(300)
                }
            }
        }
        return null
    }

    // Guard chat input/output size: transcripts are ~100–500 chars, prompts ~2–4k.
    // Clip obvious abuse and bound response handling before it reaches clipboard.
    const val MAX_INPUT_CHARS = 12_000
    const val MAX_PROMPT_CHARS = 12_000
    const val MAX_OUTPUT_CHARS = 12_000

    fun sanitizedPrompt(prompt: String): String = if (prompt.length > MAX_PROMPT_CHARS) prompt.take(MAX_PROMPT_CHARS) else prompt

    fun sanitizedText(text: String): String = if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text

    fun truncatedOutput(text: String): String = if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) else text

    /**
     * Reasoning effort for thinking-capable chat models.
     *
     * Not all providers/models accept the same wire format:
     *  - OpenAI (o1/gpt-5 family) + Groq use `reasoning_effort` ("low"|"medium"|"high").
     *  - OpenRouter uses a `reasoning` object: {"effort": ...} or {"enabled": false}.
     * We send whichever applies for the selected endpoint; providers that don't
     * know a field simply ignore it.
     */
    enum class Reasoning(
        val key: String,
        val label: String,
        val subtitle: String,
    ) {
        DEFAULT("default", "Provider default", "Use whatever the model/provider defaults to"),
        OFF("off", "Off", "Disable reasoning"),
        LOW("low", "Low", "Minimal thinking — fastest/cheapest for cleanup"),
        MEDIUM("medium", "Medium", "Balanced thinking"),
        HIGH("high", "High", "Maximum thinking (slowest, rarely useful for cleanup)"),
        ;

        companion object {
            fun fromKey(key: String?): Reasoning = entries.firstOrNull { it.key == key } ?: DEFAULT
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
        keepTerms: List<String> = emptyList(),
        toneInstruction: String = "",
        callback: (Result) -> Unit,
    ) {
        val safePrompt = withKeepTerms(withLanguageGuard(withTone(sanitizedPrompt(prompt), toneInstruction), languageHint), keepTerms)
        val safeText = sanitizedText(text)
        val messages =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", safePrompt)
                    },
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", safeText)
                    },
                )
            }

        val bodyJson =
            JSONObject().apply {
                put("model", chatModel)
                put("messages", messages)
                put("temperature", 0.0)
                applyReasoning(this, chatUrl, reasoning)
            }

        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val request =
            Request
                .Builder()
                .url(chatUrl)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

        val call = client.newCall(request)
        currentCall = call
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    currentCall = null
                    Log.e(TAG, "cleanup request to $chatUrl failed: ${e.message}")
                    callback(Result(null, e.message))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    currentCall = null
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        // Log the raw body so logcat debugging shows the real cause;
                        // surface a compact "HTTP <code>: <detail>" to the caller.
                        Log.w(TAG, "cleanup HTTP ${response.code} from $chatUrl: ${responseBody.take(500)}")
                        val detail = runCatching { extractError(JSONObject(responseBody)) }.getOrNull()
                        callback(Result(null, "HTTP ${response.code}" + (detail?.let { ": $it" } ?: "")))
                        return
                    }
                    val parsed = parseResponse(responseBody)
                    if (parsed.error != null) {
                        Log.w(TAG, "cleanup unparseable/error body from $chatUrl: ${parsed.error} — ${responseBody.take(300)}")
                    }
                    val capped = parsed.text?.let { truncatedOutput(it) }
                    callback(if (capped != null) Result(capped, null) else parsed)
                }
            },
        )
    }

    /**
     * Dictionary spellings the cleanup model must keep as written. Empty list
     * leaves the prompt unchanged.
     */
    fun withKeepTerms(
        prompt: String,
        terms: List<String>,
    ): String {
        if (terms.isEmpty()) return prompt
        return prompt.rstripTrailing() +
            "\n\nKeep these spellings exactly as written when they appear: " +
            terms.joinToString(", ") + "."
    }

    /**
     * Per-app tone instruction (WisprFlow-style). Appended as a style directive
     * before the language guard, so "never translate" still wins. Empty/blank
     * leaves the prompt untouched.
     */
    fun withTone(
        prompt: String,
        toneInstruction: String?,
    ): String {
        val inst = toneInstruction?.trim().orEmpty()
        if (inst.isEmpty()) return prompt
        return prompt.rstripTrailing() + "\n\nStyle: $inst"
    }

    /**
     * Ensures the cleanup prompt keeps the original language.
     *
     * If we know the transcript language (from the user's pinned language or
     * Whisper's detected language), we always append a concrete language hint
     * — even when the prompt already mentions translation generically — so the
     * model knows WHICH language to keep.
     */
    fun withLanguageGuard(
        prompt: String,
        languageHint: String?,
    ): String {
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

    /**
     * Reasoning-effort wire format, gated per endpoint.
     *
     * Strict providers (Mistral 422 `extra_forbidden`, OpenAI/Groq "unrecognized
     * argument") REJECT unknown fields, so the "providers ignore unknown fields"
     * assumption is wrong and broke cleanup for every strict endpoint when
     * reasoning defaulted to off. Only emit fields where they are documented:
     *  - OpenRouter: `reasoning` object, {"effort": ...} or {"enabled": false}.
     *  - Together: hybrids (Qwen3.5/3.6, Gemma4, Cogito, DeepSeek, MiniMax)
     *    disable via {"reasoning":{"enabled": false}}; effort via
     *    `reasoning_effort`.
     *  - OpenAI/Groq: `reasoning_effort` for LOW/MEDIUM/HIGH; OFF sends nothing
     *    (their non-reasoning models need no toggle).
     *  - Everyone else (Mistral, Venice, NanoGPT, Custom): send nothing —
     *    a no-op beats a 422 that kills the whole cleanup request.
     */
    internal fun applyReasoning(
        bodyJson: JSONObject,
        chatUrl: String,
        reasoning: Reasoning,
    ) {
        if (reasoning == Reasoning.DEFAULT) return
        // MiniMax: thinking cannot be disabled (400 "requires adaptive thinking")
        // and effort defaults to `max`, which is nonsense for a 200-word cleanup.
        // Collapse OFF onto low, the fastest documented setting.
        if (chatUrl.contains("minimax")) {
            bodyJson.put("reasoning_effort", if (reasoning == Reasoning.OFF) "low" else reasoning.key)
            return
        }
        val isOpenRouter = chatUrl.contains("openrouter.ai")
        val isTogether = chatUrl.contains("together.ai")
        val isOpenAiFamily = chatUrl.contains("api.openai.com") || chatUrl.contains("api.groq.com")
        if (reasoning == Reasoning.OFF) {
            if (isOpenRouter || isTogether) {
                bodyJson.put("reasoning", JSONObject().apply { put("enabled", false) })
            }
            return
        }
        when {
            isOpenRouter -> bodyJson.put("reasoning", JSONObject().apply { put("effort", reasoning.key) })
            isTogether || isOpenAiFamily -> bodyJson.put("reasoning_effort", reasoning.key)
            else -> Unit
        }
    }
}
