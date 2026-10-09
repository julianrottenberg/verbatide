package com.julianrottenberg.verbatide

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One per-app tone assignment: transcriptions targeted at this package get this tone. */
data class AppToneMapping(
    val packageName: String,
    val appLabel: String,
    val toneKey: String,
)

object ToneManager {
    data class Tone(
        val key: String,
        val label: String,
        val instruction: String,
    )

    // "default" must stay first and keep key "default". A mapping pointing at
    // "default" is meaningless — upsert() deletes those instead of storing them.
    val TONES =
        listOf(
            Tone("default", "Default", ""),
            Tone("formal", "Formal & polite", "Rewrite in a formal, polite register."),
            Tone(
                "casual",
                "Casual",
                "Rewrite in a relaxed, conversational tone, as if texting a friend. Contractions and informal phrasing are fine.",
            ),
            Tone("concise", "Concise", "Make it as brief as possible while keeping all key information."),
            Tone(
                "email",
                "Professional email",
                "Format as a professional email body: short greeting line, body paragraphs, brief sign-off. No markdown, no subject line unless one was dictated.",
            ),
            Tone(
                "technical",
                "Technical",
                "Preserve technical terms, code identifiers, file names and commands exactly as spoken; keep the style precise and neutral.",
            ),
        )

    private const val FILE_NAME = "app_tones.json"

    fun tone(key: String): Tone = TONES.firstOrNull { it.key == key } ?: TONES[0]

    fun toneFor(
        mappings: List<AppToneMapping>,
        packageName: String?,
    ): Tone =
        packageName
            ?.let { pkg -> mappings.firstOrNull { it.packageName == pkg } }
            ?.let { tone(it.toneKey) }
            ?: TONES[0]

    fun instructionFor(
        ctx: Context,
        packageName: String?,
    ): String = toneFor(load(ctx), packageName).instruction

    fun load(ctx: Context): List<AppToneMapping> {
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pkg = o.optString("package").trim()
                val toneKey = o.optString("tone").trim()
                if (pkg.isEmpty() || toneKey.isEmpty() || toneKey == "default") {
                    null
                } else {
                    AppToneMapping(pkg, o.optString("label", pkg), toneKey)
                }
            }
        } catch (_: Exception) {
            // Corrupted file: start from empty rather than crash the UI.
            emptyList()
        }
    }

    fun save(
        ctx: Context,
        mappings: List<AppToneMapping>,
    ) {
        val arr = JSONArray()
        mappings.forEach { m ->
            arr.put(
                JSONObject()
                    .put("package", m.packageName)
                    .put("label", m.appLabel)
                    .put("tone", m.toneKey),
            )
        }
        File(ctx.filesDir, FILE_NAME).writeText(arr.toString())
    }

    /** Adds or replaces the mapping for [m].packageName; "default" tone removes it. */
    fun upsert(
        ctx: Context,
        m: AppToneMapping,
    ) {
        val list = load(ctx).filterNot { it.packageName == m.packageName }.toMutableList()
        if (m.toneKey != "default") list.add(m)
        save(ctx, list)
    }

    fun remove(
        ctx: Context,
        packageName: String,
    ) {
        save(ctx, load(ctx).filterNot { it.packageName == packageName })
    }
}
