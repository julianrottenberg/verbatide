package com.julianrottenberg.verbatide

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
    /** STT model that produced [text]. */
    val model: String = "",
    /** 0 = the primary provider answered; N = the (N+1)th entry of the chain. */
    val fallbackDepth: Int = 0,
    /** True when a cleanup pass ran and [text] is its output. */
    val cleaned: Boolean = false,
    val cleanupProvider: String = "",
    val cleanupModel: String = "",
    val reasoning: String = "",
    /** Wall-clock milliseconds per stage; 0 when the stage did not run. */
    val sttMs: Long = 0,
    val cleanupMs: Long = 0,
    /** Raw STT output, kept only when cleanup replaced it, so a better cleanup can be re-run. */
    val rawText: String = "",
    /** Why the pipeline degraded (timeout, quota, cleanup failed). Blank on success. */
    val failure: String = "",
    /** Identifier of the saved WAV under filesDir/recordings, blank when audio was not kept. */
    val recording: String = "",
    /** Audio length in milliseconds, so re-transcription does not re-parse the WAV. */
    val durMs: Int = 0,
) {
    /** One-line summary used under the timestamp in the history list. */
    fun summarize(): String {
        val stt =
            buildString {
                append(provider.ifBlank { "unknown" })
                if (model.isNotBlank()) append(" · ").append(model)
                if (fallbackDepth > 0) append(" (fallback #").append(fallbackDepth).append(")")
            }
        val parts = mutableListOf(stt)
        if (cleaned) {
            parts += "cleaned by " + cleanupProvider.ifBlank { "?" } +
                (if (cleanupModel.isNotBlank()) " · $cleanupModel" else "") +
                (if (reasoning.isNotBlank()) " · $reasoning" else "")
        }
        if (failure.isNotBlank()) parts += "⚠︎ $failure"
        if (recording.isNotBlank()) parts += "audio kept"
        if (sttMs > 0) parts += "${sttMs / 1000.0}s stt"
        if (cleanupMs > 0) parts += "${cleanupMs / 1000.0}s cleanup"
        lang?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    companion object {
        /** Compact age for list rows and the activity log ("3m ago"). */
        fun age(
            ts: Long,
            now: Long = System.currentTimeMillis(),
        ): String {
            val s = ((now - ts) / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> "${s}s ago"
                s < 3600 -> "${s / 60}m ago"
                s < 86_400 -> "${s / 3600}h ago"
                else -> "${s / 86_400}d ago"
            }
        }
    }
}

object HistoryManager {
    const val KEY_HISTORY_ENABLED = "history_enabled"
    const val KEY_HISTORY_MAX_MB = "history_max_mb"
    const val KEY_HISTORY_MAX_DAYS = "history_max_days"
    const val DEF_HISTORY_ENABLED = true
    const val DEF_MAX_MB = 50
    const val DEF_MAX_DAYS = 90

    private const val FILENAME = "transcription_history.json"

    fun historyFile(ctx: Context): File = File(ctx.filesDir, FILENAME)

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_HISTORY_ENABLED, DEF_HISTORY_ENABLED)

    fun append(
        ctx: Context,
        entry: HistoryEntry,
    ) {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        if (!enabled(prefs)) return
        synchronized(this) {
            val arr = readArray(ctx)
            arr.put(toJson(entry))
            val maxDays = prefs.getInt(KEY_HISTORY_MAX_DAYS, DEF_MAX_DAYS)
            val maxBytes = prefs.getInt(KEY_HISTORY_MAX_MB, DEF_MAX_MB) * 1024L * 1024L
            val cutoff = if (maxDays > 0) System.currentTimeMillis() - maxDays * 24L * 3600_000L else Long.MIN_VALUE
            val filtered =
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
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
            writeArray(ctx, out)
            syncAudio(ctx, prefs, out)
        }
    }

    fun load(ctx: Context): List<HistoryEntry> =
        synchronized(this) {
            try {
                val arr = readArray(ctx)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.ts }
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun clear(ctx: Context) {
        // Same monitor as append/replace/remove: a clear that races an append used
        // to leave the file deleted but the audio referenced (or vice versa).
        synchronized(this) {
            try {
                historyFile(ctx).delete()
                RecordingStore.clear(ctx)
            } catch (_: Exception) {
            }
        }
    }

    fun count(ctx: Context): Int = load(ctx).size

    fun bytesUsed(ctx: Context): Long =
        try {
            historyFile(ctx).length()
        } catch (_: Exception) {
            0L
        }

    fun removeByTs(
        ctx: Context,
        ts: Long,
    ) {
        synchronized(this) {
            val all = load(ctx)
            val entries = all.filter { it.ts != ts }
            // JSON first: if the write fails the entry stays and so must its audio.
            writeArray(ctx, JSONArray(entries.map { toJson(it) }))
            all.filter { it.ts == ts }.forEach { RecordingStore.delete(ctx, it.recording) }
        }
    }

    /** Replaces the entry whose timestamp matches [ts] — used after a re-run cleanup. */
    fun replace(
        ctx: Context,
        ts: Long,
        entry: HistoryEntry,
    ) {
        synchronized(this) {
            val entries = load(ctx).filter { it.ts != ts }
            writeArray(ctx, JSONArray((entries + entry).sortedBy { it.ts }.map { toJson(it) }))
        }
    }

    /**
     * Whole-file rewrite via a temp file: a process kill mid-write leaves the
     * previous history intact instead of a truncated JSON blob.
     */
    private fun writeArray(
        ctx: Context,
        arr: JSONArray,
    ) {
        val file = historyFile(ctx)
        val tmp = File(file.parentFile, "$FILENAME.tmp")
        try {
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Keeps the audio directory in sync with the entries that survived retention.
     * Runs on every append: retention may have dropped entries whose WAVs are now
     * orphans, and a user who turned audio off expects the files gone.
     */
    private fun syncAudio(
        ctx: Context,
        prefs: SharedPreferences,
        arr: JSONArray,
    ) {
        if (!RecordingStore.enabled(prefs)) {
            if (RecordingStore.count(ctx) > 0) RecordingStore.clear(ctx)
            return
        }
        val ids =
            (0 until arr.length())
                .mapNotNull { arr.getJSONObject(it).optString("recording", "").takeIf { id -> id.isNotBlank() } }
        RecordingStore.prune(ctx, ids, RecordingStore.maxFiles(prefs))
    }

    private fun readArray(ctx: Context): JSONArray {
        val file = historyFile(ctx)
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (_: Exception) {
            // Unparseable (truncated write, hand-restored backup). Move it aside
            // first: returning an empty array means the next append rewrites the
            // file, which would silently destroy every dictation in it.
            runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
            JSONArray()
        }
    }

    private fun toJson(e: HistoryEntry): JSONObject =
        JSONObject().apply {
            put("ts", e.ts)
            put("text", e.text)
            put("provider", e.provider)
            if (e.lang?.isNotBlank() == true) put("lang", e.lang)
            if (e.model.isNotBlank()) put("model", e.model)
            if (e.fallbackDepth > 0) put("backup", e.fallbackDepth)
            if (e.cleaned) put("cleaned", true)
            if (e.cleanupProvider.isNotBlank()) put("cleanup_provider", e.cleanupProvider)
            if (e.cleanupModel.isNotBlank()) put("cleanup_model", e.cleanupModel)
            if (e.reasoning.isNotBlank()) put("reasoning", e.reasoning)
            if (e.sttMs > 0) put("stt_ms", e.sttMs)
            if (e.cleanupMs > 0) put("cleanup_ms", e.cleanupMs)
            if (e.rawText.isNotBlank()) put("raw_text", e.rawText)
            if (e.failure.isNotBlank()) put("failure", e.failure)
            if (e.recording.isNotBlank()) put("recording", e.recording)
            if (e.durMs > 0) put("dur_ms", e.durMs)
        }

    private fun fromJson(o: JSONObject): HistoryEntry =
        HistoryEntry(
            ts = o.optLong("ts", 0L),
            text = o.optString("text", ""),
            provider = o.optString("provider", ""),
            lang = o.optString("lang", "").takeIf { it.isNotBlank() },
            model = o.optString("model", ""),
            fallbackDepth = o.optInt("backup", 0),
            cleaned = o.optBoolean("cleaned", false),
            cleanupProvider = o.optString("cleanup_provider", ""),
            cleanupModel = o.optString("cleanup_model", ""),
            reasoning = o.optString("reasoning", ""),
            sttMs = o.optLong("stt_ms", 0L),
            cleanupMs = o.optLong("cleanup_ms", 0L),
            rawText = o.optString("raw_text", ""),
            failure = o.optString("failure", ""),
            recording = o.optString("recording", ""),
            durMs = o.optInt("dur_ms", 0),
        )
}
