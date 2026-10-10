package com.julianrottenberg.verbatide

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AppToneMapping(
    val packageName: String,
    val appLabel: String,
    val toneKey: String,
)

data class Tone(
    val key: String,
    val label: String,
    val description: String,
    val instruction: String,
    val isCustom: Boolean = false,
)

object ToneManager {
    private const val PREFS = "app_tones"
    private const val KEY_MAPPINGS = "mappings"
    private const val KEY_CUSTOM_TONES = "custom_tones"
    private const val KEY_OVERRIDES = "instruction_overrides"

    val TONES =
        listOf(
            Tone(
                "default",
                "Default",
                "Fallback for every app without an assigned tone. No style instruction is added.",
                "",
            ),
            Tone(
                "formal",
                "Formal",
                "Polished, complete sentences. For official letters or your boss.",
                "Rewrite the text in a formal, polite register. Complete sentences, no slang, no abbreviations.",
            ),
            Tone(
                "casual",
                "Casual",
                "Relaxed and friendly, contractions welcome. For chats with friends.",
                "Rewrite the text in a casual, conversational register. Contractions and informal phrasing are welcome.",
            ),
            Tone(
                "concise",
                "Concise",
                "Strips filler and gets to the point. For quick notes and busy people.",
                "Make the text as brief as possible while keeping all facts. Remove filler words and redundancies.",
            ),
            Tone(
                "email",
                "Professional email",
                "Greeting, structured paragraphs, sign-off. Ready to send.",
                "Format the text as a professional email: short greeting, well-structured paragraphs, appropriate sign-off.",
            ),
            Tone(
                "technical",
                "Technical",
                "Precise register. Identifiers, jargon and code references stay untouched.",
                "Use a precise technical register. Keep technical terms, identifiers and code references unchanged.",
            ),
        )

    fun tone(key: String): Tone = TONES.firstOrNull { it.key == key } ?: TONES[0]

    // ---------- pure helpers (unit-tested) ----------

    fun makeCustomKey(
        label: String,
        instruction: String,
    ): String {
        val slug =
            label
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "tone" }
        return "custom_" + slug + "_" + Integer.toHexString(instruction.hashCode())
    }

    fun parseMappings(json: String?): List<AppToneMapping> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AppToneMapping(o.getString("pkg"), o.optString("label", o.getString("pkg")), o.getString("tone"))
            }
        }.getOrElse { emptyList() }
    }

    fun parseCustomTones(json: String?): List<Tone> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Tone(
                    o.getString("key"),
                    o.getString("label"),
                    o.optString("description", "Custom tone."),
                    o.getString("instruction"),
                    isCustom = true,
                )
            }
        }.getOrElse { emptyList() }
    }

    fun parseOverrides(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrElse { emptyMap() }
    }

    /** Built-in tones with instruction overrides applied, followed by custom tones. */
    fun resolveTones(
        customsJson: String?,
        overridesJson: String?,
    ): List<Tone> {
        val overrides = parseOverrides(overridesJson)
        val builtIns =
            TONES.map { tone ->
                val override = overrides[tone.key]
                if (override != null) tone.copy(instruction = override) else tone
            }
        return builtIns + parseCustomTones(customsJson)
    }

    fun pruneToneFromMappings(
        mappings: List<AppToneMapping>,
        toneKey: String,
    ): List<AppToneMapping> = mappings.filterNot { it.toneKey == toneKey }

    fun toneFor(
        mappings: List<AppToneMapping>,
        packageName: String?,
        tones: List<Tone> = TONES,
    ): Tone {
        if (packageName == null) return TONES[0]
        val key = mappings.firstOrNull { it.packageName == packageName }?.toneKey ?: return TONES[0]
        return tones.firstOrNull { it.key == key } ?: TONES[0]
    }

    // ---------- context-backed storage ----------

    private var mappingsCache: Pair<String?, List<AppToneMapping>>? = null

    @Synchronized
    fun mappings(context: Context): List<AppToneMapping> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MAPPINGS, null)
        mappingsCache?.let { if (it.first == raw) return it.second }
        val parsed = parseMappings(raw)
        mappingsCache = raw to parsed
        return parsed
    }

    fun upsert(
        context: Context,
        mapping: AppToneMapping,
    ) {
        val list = mappings(context).filterNot { it.packageName == mapping.packageName } + mapping
        writeMappings(context, list)
    }

    fun remove(
        context: Context,
        packageName: String,
    ) {
        writeMappings(context, mappings(context).filterNot { it.packageName == packageName })
    }

    fun effectiveTones(context: Context): List<Tone> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return resolveTones(prefs.getString(KEY_CUSTOM_TONES, null), prefs.getString(KEY_OVERRIDES, null))
    }

    fun instructionFor(
        context: Context,
        packageName: String?,
    ): String = toneFor(mappings(context), packageName, effectiveTones(context)).instruction

    fun addCustomTone(
        context: Context,
        label: String,
        instruction: String,
    ): Tone {
        val tone = Tone(makeCustomKey(label, instruction), label.trim(), "Custom tone.", instruction.trim(), isCustom = true)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customs = parseCustomTones(prefs.getString(KEY_CUSTOM_TONES, null)) + tone
        val arr = JSONArray()
        customs.forEach {
            arr.put(
                JSONObject()
                    .put("key", it.key)
                    .put("label", it.label)
                    .put("description", it.description)
                    .put("instruction", it.instruction),
            )
        }
        prefs.edit().putString(KEY_CUSTOM_TONES, arr.toString()).apply()
        return tone
    }

    /** Edits a custom tone's instruction, or stores an override for a built-in tone. */
    fun setInstructionOverride(
        context: Context,
        key: String,
        instruction: String,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customs = parseCustomTones(prefs.getString(KEY_CUSTOM_TONES, null))
        if (customs.any { it.key == key }) {
            val arr = JSONArray()
            customs.forEach {
                arr.put(
                    JSONObject()
                        .put("key", it.key)
                        .put("label", it.label)
                        .put("description", it.description)
                        .put("instruction", if (it.key == key) instruction.trim() else it.instruction),
                )
            }
            prefs.edit().putString(KEY_CUSTOM_TONES, arr.toString()).apply()
            return
        }
        if (TONES.none { it.key == key }) return
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null)) + (key to instruction.trim())
        prefs.edit().putString(KEY_OVERRIDES, JSONObject(overrides).toString()).apply()
    }

    /** Removes a built-in tone's override, restoring its default instruction. */
    fun resetInstruction(
        context: Context,
        key: String,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null)) - key
        prefs.edit().putString(KEY_OVERRIDES, JSONObject(overrides).toString()).apply()
    }

    /** Deletes a custom tone, its override (if any), and every app mapping pointing at it. */
    fun deleteCustomTone(
        context: Context,
        key: String,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customs = parseCustomTones(prefs.getString(KEY_CUSTOM_TONES, null)).filterNot { it.key == key }
        val arr = JSONArray()
        customs.forEach {
            arr.put(
                JSONObject()
                    .put("key", it.key)
                    .put("label", it.label)
                    .put("description", it.description)
                    .put("instruction", it.instruction),
            )
        }
        val overrides = parseOverrides(prefs.getString(KEY_OVERRIDES, null)) - key
        prefs
            .edit()
            .putString(KEY_CUSTOM_TONES, arr.toString())
            .putString(KEY_OVERRIDES, JSONObject(overrides).toString())
            .apply()
        writeMappings(context, pruneToneFromMappings(mappings(context), key))
    }

    private fun writeMappings(
        context: Context,
        list: List<AppToneMapping>,
    ) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("pkg", it.packageName).put("label", it.appLabel).put("tone", it.toneKey))
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MAPPINGS, arr.toString()).apply()
        mappingsCache = arr.toString() to list
    }
}
