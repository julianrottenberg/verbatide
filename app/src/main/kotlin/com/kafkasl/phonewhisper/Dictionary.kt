package com.kafkasl.phonewhisper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User dictionary for STT hints. Word/phrase replacement rules are sent to
 * STT providers as `prompt` (Whisper-family) when supported. Case-insensitive
 * match, longest wins.
 */
data class DictEntry(
    val id: Long,
    val pattern: String,    // raw phrase / word to listen for
    val replacement: String, // text to substitute
    val enabled: Boolean = true,
)

object DictionaryManager {
    private const val FILENAME = "dictionary.json"
    private const val KEY_DICT_ENABLED = "dictionary_enabled"

    fun file(ctx: Context) = File(ctx.filesDir, FILENAME)

    fun enabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DICT_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DICT_ENABLED, on).apply()
    }

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

    /** Build a Whisper-style prompt hint from enabled entries. */
    fun buildPrompt(ctx: Context, limitChars: Int = 500): String {
        if (!enabled(ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE))) return ""
        val entries = load(ctx).filter { it.enabled }
        if (entries.isEmpty()) return ""
        // "Names/terms: foo bar baz, lorem ipsum" — Whisper uses this to bias vocab.
        val hint = entries.joinToString(", ") { e ->
            if (e.replacement.isBlank() || e.replacement.equals(e.pattern, true)) e.pattern
            else "${e.pattern} → ${e.replacement}"
        }
        return if (hint.length > limitChars) "Names/terms: ${hint.take(limitChars - 14)}…" else "Names/terms: $hint"
    }
}
