package com.julianrottenberg.verbatide

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostics: a bounded ring buffer of timestamped lines that the
 * accessibility service writes to and the Settings screen can show and copy.
 *
 * Why not just logcat: dictation happens while the phone is in another app, so
 * by the time something fails the user has no adb attached. A 400-line buffer
 * plus a 256 kB on-disk mirror is enough to answer "why did that fail", and it
 * costs a few hundred kB at most.
 *
 * Everything is synchronized on the object: the accessibility service appends
 * from its worker threads while an activity reads.
 */
object LogStore {
    private const val TAG = "LogStore"
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 256L * 1024L
    private const val FILE_NAME = "verbatide.log"

    private val buffer = ArrayList<String>(MAX_LINES + 1)
    private var sinceTrim = 0
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /** Appends one line to the in-memory buffer, logcat and the on-disk mirror. */
    fun add(
        tag: String,
        msg: String,
    ) {
        val line = "${stamp.format(Date())} $tag: $msg"
        Log.i(tag, msg)
        synchronized(this) {
            buffer.add(line)
            while (buffer.size > MAX_LINES) buffer.removeAt(0)
            val file = pendingFile
            if (file == null) {
                // No Context yet: keep the line so attach() can flush it.
                pendingLines.add(line)
            } else if (++sinceTrim >= 25) {
                // Batched on purpose (one append per 25 lines to spare flash), but
                // the flush must carry the whole batch — writing only the current
                // line left the file with 4% of the trace and grew pendingLines
                // forever in a service that never restarts.
                sinceTrim = 0
                val batch = pendingLines.toList() + line
                pendingLines.clear()
                appendToFile(file, batch.joinToString("\n"))
            } else {
                pendingLines.add(line)
            }
        }
    }

    // The on-disk mirror needs a path, which only a Context can give us. The
    // service registers it once on start; before that, lines stay in memory.
    @Volatile
    private var pendingFile: File? = null
    private val pendingLines = ArrayList<String>()

    fun attach(ctx: Context) {
        val f = file(ctx)
        synchronized(this) {
            pendingFile = f
            if (pendingLines.isNotEmpty()) {
                val queued = pendingLines.toList()
                pendingLines.clear()
                queued.forEach { appendToFile(f, it) }
            }
        }
    }

    private fun appendToFile(
        f: File,
        line: String,
    ) {
        try {
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                // Keep the newest half rather than rotating numbered files: the
                // user only ever wants the tail when something just failed.
                val keep = runCatching { f.readLines().takeLast(MAX_LINES / 2) }.getOrDefault(emptyList())
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            f.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not write log file: ${e.message}")
        }
    }

    /** Newest last. Falls back to the on-disk mirror when the process restarted. */
    fun lines(ctx: Context): List<String> =
        synchronized(this) {
            if (buffer.isNotEmpty()) return@synchronized buffer.toList()
            val f = file(ctx)
            if (!f.exists()) return@synchronized emptyList()
            runCatching { f.readLines().takeLast(MAX_LINES) }.getOrDefault(emptyList())
        }

    fun text(ctx: Context): String = lines(ctx).joinToString("\n")

    fun clear(ctx: Context) {
        synchronized(this) {
            buffer.clear()
            pendingLines.clear()
        }
        runCatching { file(ctx).delete() }
    }
}

/**
 * Turns a provider/transport error string into something a human can act on.
 *
 * The pipeline stores the raw message too, but "HTTP 402: insufficient balance"
 * in a history row is noise compared to "Out of credit" plus a pointer at the
 * backup provider.
 */
object Failures {
    enum class Kind(
        val label: String,
    ) {
        TIMEOUT("Timed out"),
        QUOTA("Out of credit"),
        AUTH("Key rejected"),
        RATE_LIMIT("Rate limited"),
        SERVER("Provider error"),
        NETWORK("Network error"),
        AUDIO("Audio problem"),
        CONFIG("Not configured"),
        CANCELLED("Cancelled"),
        UNKNOWN("Failed"),
        ;

        val isFailover: Boolean get() = this == QUOTA || this == AUTH
    }

    fun classify(error: String?): Kind {
        val e = error?.lowercase()?.trim().orEmpty()
        if (e.isEmpty()) return Kind.UNKNOWN
        return when {
            e.contains("timeout") || e.contains("timed out") || e.contains("deadline") -> Kind.TIMEOUT

            e.contains("402") || e.contains("insufficient") || e.contains("quota") ||
                e.contains("balance") || e.contains("credit") || e.contains("billing") ||
                e.contains("payment required") || e.contains("budget") -> Kind.QUOTA

            e.contains("401") || e.contains("403") || e.contains("unauthor") ||
                e.contains("forbidden") || e.contains("invalid api key") ||
                e.contains("api key") || e.contains("authentication") ||
                e.contains("login fail") -> Kind.AUTH

            e.contains("429") || e.contains("rate limit") || e.contains("too many request") -> Kind.RATE_LIMIT

            e.contains("cancel") -> Kind.CANCELLED

            e.contains("too large") || e.contains("no audio") || e.contains("record") -> Kind.AUDIO

            e.contains("not configured") || e.contains("no provider") || e.contains("no api key") ||
                e.contains("set an") -> Kind.CONFIG

            e.contains("500") || e.contains("502") || e.contains("503") || e.contains("504") ||
                e.contains("server") || e.contains("overloaded") -> Kind.SERVER

            e.contains("network") || e.contains("unable to resolve") || e.contains("connect") ||
                e.contains("socket") || e.contains("failed to connect") || e.contains("offline") -> Kind.NETWORK

            else -> Kind.UNKNOWN
        }
    }

    /** "Out of credit (HTTP 402: insufficient balance)" — short, cause first. */
    fun describe(error: String?): String {
        val raw = error?.trim().orEmpty()
        if (raw.isEmpty()) return Kind.UNKNOWN.label
        val kind = classify(raw)
        val detail = if (raw.length > 120) raw.take(120) + "…" else raw
        return if (kind == Kind.UNKNOWN) detail else "${kind.label} — $detail"
    }
}
