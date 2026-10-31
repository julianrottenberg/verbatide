package com.julianrottenberg.verbatide

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Backup-provider usage check.
 *
 * The user's rule: never pay the latency of a usage probe on the dictation
 * path, and still recover automatically once the primary is topped up again.
 * So this runs on its own schedule — at most once every 24 h, kicked off from
 * the settings screen — and the result is cached in prefs for the UI.
 *
 * There is no quota endpoint to call: none of the supported providers exposes a
 * usable one to a phone client. The cheapest honest signal is the thing we
 * actually care about — can this provider serve one tiny chat request? A 402
 * or 401 means no, anything else means don't block it.
 */
object QuotaChecker {
    private const val TAG = "QuotaChecker"

    private const val KEY_LAST_CHECK = "usage_last_check_at"
    private const val KEY_LAST_SUMMARY = "usage_last_check_summary"

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Short timeouts: this runs in the background and must never linger. */
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

    @Volatile
    private var running = false

    enum class Verdict { OK, BLOCKED, UNKNOWN }

    data class Probe(val verdict: Verdict, val label: String)

    fun lastCheckAt(prefs: SharedPreferences): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun lastSummary(prefs: SharedPreferences): String = prefs.getString(KEY_LAST_SUMMARY, "") ?: ""

    fun needsDailyCheck(
        prefs: SharedPreferences,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val last = lastCheckAt(prefs)
        return last <= 0L || now - last >= DAY_MS
    }

    /** Settings-row subtitle. */
    fun describe(
        prefs: SharedPreferences,
        now: Long = System.currentTimeMillis(),
    ): String {
        val last = lastCheckAt(prefs)
        if (last <= 0L) return "Never checked — runs in the background once a day"
        val summary = lastSummary(prefs).ifBlank { "no result" }
        return "Checked ${timeAgo(now - last)} — $summary"
    }

    /**
     * Called from the always-on accessibility service (service start, dictation
     * start) and from the settings screen. No-ops when checked recently or when
     * another run is already in flight — the guard is a prefs read, so calling it
     * on the dictation path costs nothing; the probe always runs on its own
     * thread and never blocks recording or transcription.
     */
    fun maybeCheckDaily(ctx: Context) {
        val app = ctx.applicationContext
        if (!needsDailyCheck(SecurePrefs.plainPrefs(app))) return
        runInBackground(app, null)
    }

    /** The "check now" button: same probe, ignoring the daily guard. */
    fun checkNow(
        ctx: Context,
        onDone: (String) -> Unit,
    ) = runInBackground(ctx.applicationContext, onDone)

    private fun runInBackground(
        ctx: Context,
        onDone: ((String) -> Unit)?,
    ) {
        if (running) return
        running = true
        Thread {
            val summary =
                try {
                    runCheck(ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "usage check failed: ${e.message}")
                    "check failed"
                } finally {
                    running = false
                }
            Handler(Looper.getMainLooper()).post { onDone?.invoke(summary) }
        }.start()
    }

    /**
     * Probes every provider the user actually wired up (primary STT, primary
     * cleanup, backup — deduplicated) and records the outcome. Returns the
     * summary kept in prefs.
     */
    internal fun runCheck(ctx: Context): String {
        val prefs = SecurePrefs.plainPrefs(ctx)
        val providers = providersToCheck(prefs)
        val parts = mutableListOf<String>()
        for (provider in providers) {
            val apiKey = SecurePrefs.getChatApiKey(ctx, provider)
            if (apiKey.isBlank()) {
                parts += "${provider.displayName}: no key"
                continue
            }
            val (url, model) = endpointFor(prefs, provider) ?: continue
            val probe = probe(url, model, apiKey)
            when (probe.verdict) {
                Verdict.OK -> ProviderHealth.clear(prefs, provider)
                Verdict.BLOCKED -> ProviderHealth.block(prefs, provider, probe.label)
                // Inconclusive — leave whatever we already knew in place.
                Verdict.UNKNOWN -> Unit
            }
            parts += "${provider.displayName}: ${probe.label}"
        }
        val summary = if (parts.isEmpty()) "nothing configured to check" else parts.joinToString(" · ")
        prefs
            .edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_LAST_SUMMARY, summary)
            .apply()
        Log.i(TAG, "usage check: $summary")
        return summary
    }

    private fun providersToCheck(prefs: SharedPreferences): List<Provider> =
        listOfNotNull(
            ProviderConfig.selectedStt(prefs),
            ProviderConfig.selectedChat(prefs),
            ProviderConfig.backupProvider(prefs),
        ).distinct().filter { it.supportsChat }

    /** Chat endpoint + model for a provider, honouring the active overrides. */
    private fun endpointFor(
        prefs: SharedPreferences,
        provider: Provider,
    ): Pair<String, String>? {
        val url = ProviderConfig.chatUrlFor(prefs, provider)
        if (url.isBlank()) return null
        val model = ProviderConfig.chatModelFor(prefs, provider)
        if (model.isBlank()) return null
        return url to model
    }

    /**
     * One tiny chat completion. Synchronous on purpose — callers run this off
     * the main thread.
     */
    internal fun probe(
        url: String,
        model: String,
        apiKey: String,
    ): Probe {
        val body =
            JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ok")),
                ).put("max_tokens", 8)
                .put("temperature", 0.0)
                .apply {
                    // MiniMax defaults to effort=max; a probe should not think.
                    if (url.contains("minimax")) put("reasoning_effort", "low")
                }.toString()
                .toRequestBody("application/json".toMediaType())

        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val failure = ProviderHealth.classify(text.ifBlank { "HTTP ${response.code}" })
                when {
                    response.isSuccessful -> {
                        Probe(Verdict.OK, "OK")
                    }

                    response.code == 402 || failure == ProviderHealth.Failure.QUOTA -> {
                        Probe(Verdict.BLOCKED, "out of quota")
                    }

                    response.code == 401 || response.code == 403 || failure == ProviderHealth.Failure.AUTH -> {
                        Probe(Verdict.BLOCKED, "key rejected")
                    }

                    response.code == 429 -> {
                        Probe(Verdict.OK, "rate limited")
                    }

                    else -> {
                        Probe(Verdict.UNKNOWN, "HTTP ${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Probe(Verdict.UNKNOWN, e.message?.take(60) ?: "unreachable")
        }
    }

    /** Compact age label: "just now", "12 min ago", "3 h ago", "2 d ago". */
    internal fun timeAgo(ageMs: Long): String =
        when {
            ageMs < 2 * 60 * 1000 -> "just now"
            ageMs < 60 * 60 * 1000 -> "${ageMs / (60 * 1000)} min ago"
            ageMs < DAY_MS -> "${ageMs / (60 * 60 * 1000)} h ago"
            else -> "${ageMs / DAY_MS} d ago"
        }
}
