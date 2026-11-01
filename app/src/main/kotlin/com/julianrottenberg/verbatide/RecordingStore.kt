package com.julianrottenberg.verbatide

import android.content.Context
import java.io.File

/**
 * Optional per-dictation audio, kept so a bad transcript can be re-run without
 * recording again.
 *
     * One WAV per entry rather than an append-only blob: retention deletes arbitrary
     * entries, and rewriting a growing file on every prune would be worse than a
     * directory of small files. 16 kHz mono 16-bit is 32 kB/s, so a 10 s dictation is
     * ~320 kB and the default cap of 50 files is ~16 MB at typical lengths.
     *
     * Files live in filesDir (app-private, no permission). They are not encrypted —
     * the audio is speech, i.e. the same content as the transcript stored next to it
     * in plain JSON. Backups are off app-wide (`android:allowBackup="false"`), so the
     * files never leave the device.
     */
object RecordingStore {
    const val KEY_KEEP_AUDIO = "keep_audio"
    const val KEY_KEEP_MAX = "keep_audio_max"
    const val DEF_KEEP_AUDIO = false
    const val DEF_KEEP_MAX = 50
    const val MAX_KEEP = 500

    private const val DIR = "recordings"

    /**
     * WAVs written for dictations still in flight. A prune can run between the
     * file write and the history append (an earlier entry's syncAudio, retention
     * in MainActivity) — without this it deletes the audio of the live dictation.
     */
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    fun registerInFlight(id: String) {
        if (id.isNotBlank()) inFlight.add(id)
    }

    fun releaseInFlight(id: String) {
        if (id.isNotBlank()) inFlight.remove(id)
    }

    /** A copy, so prune never iterates while a dictation mutates the live set. */
    private fun inFlightSnapshot(): Set<String> =
        synchronized(inFlight) {
            HashSet(inFlight)
        }

    fun enabled(prefs: android.content.SharedPreferences): Boolean = prefs.getBoolean(KEY_KEEP_AUDIO, DEF_KEEP_AUDIO)

    fun maxFiles(prefs: android.content.SharedPreferences): Int = prefs.getInt(KEY_KEEP_MAX, DEF_KEEP_MAX).coerceIn(1, MAX_KEEP)

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun fileFor(
        ctx: Context,
        id: String,
    ): File = File(dir(ctx), "$id.wav")

    /** Returns the new recording id, or null when writing failed. */
    fun save(
        ctx: Context,
        id: String,
        wav: ByteArray,
    ): String? =
        try {
            val tmp = File(dir(ctx), "$id.wav.tmp")
            tmp.writeBytes(wav)
            val out = fileFor(ctx, id)
            if (!tmp.renameTo(out)) {
                out.writeBytes(wav)
                tmp.delete()
            }
            id
        } catch (_: Exception) {
            null
        }

    fun delete(
        ctx: Context,
        id: String,
    ) {
        if (id.isBlank()) return
        try {
            fileFor(ctx, id).delete()
        } catch (_: Exception) {
        }
    }

    fun read(
        ctx: Context,
        id: String,
    ): ByteArray? =
        try {
            fileFor(ctx, id).takeIf { it.isFile }?.readBytes()
        } catch (_: Exception) {
            null
        }

    fun bytesUsed(ctx: Context): Long =
        try {
            dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }

    fun count(ctx: Context): Int =
        try {
            dir(ctx).listFiles()?.count { it.isFile && it.name.endsWith(".wav") } ?: 0
        } catch (_: Exception) {
            0
        }

    /**
     * Deletes files that no history entry references, then the oldest ones beyond
     * [maxFiles]. Called after a history append/delete so the two never drift.
     */
    fun prune(
        ctx: Context,
        keepIds: Collection<String>,
        maxFiles: Int,
    ) = pruneDir(dir(ctx), keepIds, maxFiles)

    /** Directory-level core, so retention is testable without a Context. */
    fun pruneDir(
        dir: File,
        keepIds: Collection<String>,
        maxFiles: Int,
    ) {
        val keep = (keepIds.filter { it.isNotBlank() } + inFlightSnapshot()).toSet()
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        // Half-written files from an interrupted save belong to no entry; sweep them.
        files.filter { it.name.endsWith(".tmp") }.forEach { runCatching { it.delete() } }
        val wavs = files.filter { it.name.endsWith(".wav") }
        val referenced = wavs.filter { it.name.removeSuffix(".wav") in keep }
        (wavs - referenced.toSet()).forEach { runCatching { it.delete() } }
        referenced.sortedByDescending { it.lastModified() }.drop(maxFiles.coerceAtLeast(0)).forEach { runCatching { it.delete() } }
    }

    fun clear(ctx: Context) {
        try {
            dir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
        }
    }
}
