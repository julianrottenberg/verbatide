package com.julianrottenberg.verbatide

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User dictionary. Each entry is a word/phrase the user dictates (`pattern`)
 * and optionally the spelling it should come out as (`replacement`).
 *
 * Applied in two places:
 *  - [outputTerms] / [vocabularyPrompt]: the spellings go to STT as a vocabulary
 *    hint, for endpoints that document one (see [promptStyleFor]).
 *  - [applyReplacements]: every transcript (local or cloud) is rewritten, so
 *    the dictionary holds even when the provider ignores the hint.
 */
data class DictEntry(
    val id: Long,
    val pattern: String,    // raw phrase / word to listen for
    val replacement: String, // text to substitute (blank = keep as spoken)
    val enabled: Boolean = true,
)

object DictionaryManager {
    private const val FILENAME = "dictionary.json"
    private const val KEY_DICT_ENABLED = "dictionary_enabled"
    private const val PREFS = "phonewhisper"
    private val WHITESPACE = Regex("\\s+")

    fun file(ctx: Context) = File(ctx.filesDir, FILENAME)

    fun enabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DICT_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DICT_ENABLED, on).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<DictEntry> = synchronized(this) {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                DictEntry(
                    id = o.optLong("id", System.currentTimeMillis()),
                    pattern = o.optString("pattern", ""),
                    replacement = o.optString("replacement", ""),
                    enabled = o.optBoolean("enabled", true),
                )
            }.filter { it.pattern.isNotBlank() }.sortedBy { it.pattern.lowercase() }
        } catch (_: Exception) { emptyList() }
    }

    fun save(ctx: Context, list: List<DictEntry>) {
        val f = file(ctx)
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("pattern", e.pattern)
                put("replacement", e.replacement)
                put("enabled", e.enabled)
            })
        }
        try { f.writeText(arr.toString()) } catch (_: Exception) {}
    }

    fun upsert(ctx: Context, entry: DictEntry) {
        val cur = load(ctx).toMutableList()
        val ix = cur.indexOfFirst { it.id == entry.id }
        if (ix >= 0) cur[ix] = entry else cur.add(entry)
        save(ctx, cur)
    }

    fun remove(ctx: Context, id: Long) {
        val cur = load(ctx).filter { it.id != id }
        save(ctx, cur)
    }

    /**
     * The spellings the user wants to see in output: the replacement when set,
     * otherwise the pattern as spoken. Empty when the dictionary is switched off.
     */
    fun outputTerms(ctx: Context): List<String> {
        if (!enabled(prefs(ctx))) return emptyList()
        return load(ctx)
            .filter { it.enabled }
            .map { (if (it.replacement.isNotBlank()) it.replacement else it.pattern).trim() }
            .distinctBy { it.lowercase() }
    }

    /**
     * Vocabulary hint for STT: the spellings the user wants, language-neutral.
     * An English label ("Names/terms:") in the prompt biases Whisper toward
     * English on German audio, the drift this app already fixed once.
     * Cut at a term boundary, not mid-word.
     */
    fun vocabularyPrompt(terms: List<String>, limitChars: Int = 500): String {
        if (terms.isEmpty()) return ""
        val hint = terms.joinToString(", ")
        return if (hint.length > limitChars) hint.take(limitChars).substringBeforeLast(", ") else hint
    }

    /** How an STT endpoint accepts the vocabulary. Only providers whose docs confirm it. */
    enum class PromptStyle { NONE, PROMPT_FIELD, CONTEXT_BIAS }

    fun promptStyleFor(sttUrl: String): PromptStyle = when {
        sttUrl.contains("api.openai.com") || sttUrl.contains("api.groq.com") -> PromptStyle.PROMPT_FIELD
        sttUrl.contains("api.mistral.ai") -> PromptStyle.CONTEXT_BIAS
        else -> PromptStyle.NONE
    }

    /**
     * Mistral `context_bias`: at most 100 phrases of up to 80 chars. Voxtral rejects
     * the WHOLE request when one item holds a comma or whitespace, so inner
     * whitespace becomes "_" (the documented way to bias a phrase). Over-long
     * items are dropped rather than cut mid-word.
     */
    fun contextBiasTerms(terms: List<String>): List<String> = terms
        .map { it.replace(",", " ").trim().split(WHITESPACE).filter { w -> w.isNotEmpty() }.joinToString("_") }
        .filter { it.isNotBlank() && it.length <= 80 }
        .distinctBy { it.lowercase() }
        .take(100)

    /** Rewrites a transcript with the enabled dictionary rules. */
    fun applyReplacements(ctx: Context, text: String): String {
        if (!enabled(prefs(ctx))) return text
        return rewriteWith(load(ctx), text)
    }

    /**
     * Whisper can echo its prompt back when the audio is silence or noise.
     * Drop an exact echo so the prompt never ends up in the user's text.
     */
    fun stripPromptEcho(transcript: String, prompt: String?): String {
        if (prompt.isNullOrBlank()) return transcript
        return transcript.replace(prompt, "").trim()
    }

    /**
     * Pure rewrite, separated from storage so it can be unit-tested.
     *
     * Whole-word/phrase, case-insensitive, Unicode-aware (letters and digits
     * include umlauts). Longest pattern wins at each position. The text is
     * scanned once, so a replacement is never rewritten again. Whitespace inside
     * a pattern matches any run of whitespace in the transcript.
     */
    internal fun rewriteWith(entries: List<DictEntry>, text: String): String {
        val rules = LinkedHashMap<String, String>()
        for (e in entries) {
            if (!e.enabled || e.pattern.isBlank() || e.replacement.isBlank()) continue
            rules.putIfAbsent(lookupKey(e.pattern), e.replacement.trim())
        }
        if (rules.isEmpty() || text.isEmpty()) return text

        val alternation = rules.keys
            .sortedByDescending { it.length }
            .joinToString("|") { phrase ->
                phrase.split(' ').joinToString("\\s+") { Regex.escape(it) }
            }
        val regex = Regex(
            "(?<![\\p{L}\\p{N}])(?:$alternation)(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { m -> rules[lookupKey(m.value)] ?: m.value }
    }

    private fun lookupKey(s: String): String =
        s.trim().split(WHITESPACE).joinToString(" ").lowercase()
}
