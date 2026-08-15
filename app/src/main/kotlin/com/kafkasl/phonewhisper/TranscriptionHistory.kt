package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only JSON transcript history.
 *
 * Defaults: enabled, 50 MB cap OR 90 days, whichever fires first; oldest
 * entries are dropped. User can set max MB (0=unlimited) and max days
 * (0=keep forever) independently, plus clear-all.
 *
 * Stored at filesDir/transcription_history.json — shared prefs stay light.
 * TODO: migrate to Room if you need query/indexing.
 */
data class HistoryEntry(
    val ts: Long,
    val text: String,
    val provider: String,
    val lang: String? = null,
)

object HistoryManager {
    const val KEY_HISTORY_ENABLED = "history_enabled"
    const val KEY_HISTORY_MAX_MB = "history_max_mb"
    const val KEY_HISTORY_MAX_DAYS = "history_max_days"
    const val DEF_HISTORY_ENABLED = true
    const val DEF_MAX_MB = 50
    const val DEF_MAX_DAYS = 90

    private const val FILENAME = "transcription_history.json"

    fun historyFile(ctx: Context): File = File(ctx.filesDir, FILENAME)

    fun enabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_HISTORY_ENABLED, DEF_HISTORY_ENABLED)

    fun append(ctx: Context, entry: HistoryEntry) {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        if (!enabled(prefs)) return
        synchronized(this) {
            val file = historyFile(ctx)
            val arr = try {
                if (file.exists()) JSONArray(file.readText()) else JSONArray()
            } catch (_: Exception) {
                JSONArray()
            }
            arr.put(JSONObject().apply {
                put("ts", entry.ts)
                put("text", entry.text)
                put("provider", entry.provider)
                if (entry.lang?.isNotBlank() == true) put("lang", entry.lang)
            })
            val maxDays = prefs.getInt(KEY_HISTORY_MAX_DAYS, DEF_MAX_DAYS)
            val maxBytes = prefs.getInt(KEY_HISTORY_MAX_MB, DEF_MAX_MB) * 1024L * 1024L
            val cutoff = if (maxDays > 0) System.currentTimeMillis() - maxDays * 24L * 3600_000L else Long.MIN_VALUE
            val filtered = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optLong("ts", 0) >= cutoff }
            val totalBytes = filtered.sumOf { it.toString().length + 2 }.toLong()
            var dropUntil = 0
            var running = totalBytes
            while (maxBytes > 0 && running > maxBytes && dropUntil < filtered.size) {
                running -= (filtered[dropUntil].toString().length + 2)
                dropUntil++
            }
            val out = JSONArray()
            for (i in dropUntil until filtered.size) out.put(filtered[i])
            try {
                file.writeText(out.toString())
            } catch (_: Exception) {
            }
        }
    }

    fun load(ctx: Context): List<HistoryEntry> = synchronized(this) {
        val file = historyFile(ctx)
        if (!file.exists()) return emptyList()
        try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryEntry(
                    ts = o.optLong("ts", 0L),
                    text = o.optString("text", ""),
                    provider = o.optString("provider", ""),
                    lang = o.optString("lang", null as String?).takeIf { v -> v?.isNotBlank() == true },
                )
            }.sortedByDescending { it.ts }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        try {
            historyFile(ctx).delete()
        } catch (_: Exception) {
        }
    }

    fun count(ctx: Context): Int = load(ctx).size

    fun bytesUsed(ctx: Context): Long = try {
        historyFile(ctx).length()
    } catch (_: Exception) {
        0L
    }
}
