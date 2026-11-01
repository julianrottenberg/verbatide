package com.julianrottenberg.verbatide

import android.content.SharedPreferences

/**
 * Remembers which providers recently hit a hard wall — out of quota/credit or a
 * rejected key — so the next dictation can go straight to the backup provider
 * instead of paying the latency of a request that is certain to fail.
 *
 * Why local state instead of asking the provider: none of the supported
 * providers exposes a quota endpoint that a phone client could query cheaply
 * (Mistral's billing API needs an admin key). The only reliable signal is a
 * request coming back 402/401, so we learn from those and forget again after
 * [BLOCK_TTL_MS] — the daily usage check clears a block earlier as soon as the
 * primary works again.
 *
 * Blocks are per purpose as well as per provider: API keys are scoped per
 * purpose now, so a 401 on a broken STT key says nothing about the chat key of
 * the same provider (and the daily check only probes the chat endpoint — it
 * must not be able to pull a working provider out of the transcription chain).
 */
object ProviderHealth {
    const val BLOCK_TTL_MS = 24L * 60 * 60 * 1000

    private const val PREFIX = "provider_block_"
    private const val OK_PREFIX = "provider_ok_"

    enum class Failure { QUOTA, AUTH, OTHER }

    /** The failure that caused the current block. */
    data class Block(
        val reason: String,
        val at: Long,
    )

    /**
     * Whether a provider is worth trying for [purpose]. Expired entries are
     * dropped lazily so a stale block can never outlive its TTL because nothing
     * wrote to prefs.
     */
    fun blocked(
        prefs: SharedPreferences,
        provider: Provider,
        purpose: ProviderConfig.Purpose,
        now: Long = System.currentTimeMillis(),
    ): Block? {
        val raw = prefs.getString(key(provider, purpose), null) ?: return null
        val at = raw.substringBefore('|').toLongOrNull()
        if (at == null || now - at > BLOCK_TTL_MS) {
            prefs.edit().remove(key(provider, purpose)).apply()
            return null
        }
        return Block(raw.substringAfter('|', ""), at)
    }

    fun isBlocked(
        prefs: SharedPreferences,
        provider: Provider,
        purpose: ProviderConfig.Purpose,
        now: Long = System.currentTimeMillis(),
    ): Boolean = blocked(prefs, provider, purpose, now) != null

    fun block(
        prefs: SharedPreferences,
        provider: Provider,
        purpose: ProviderConfig.Purpose,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ) {
        // Persisted for 24 h and shown in Settings — never store a raw body that
        // might echo a credential.
        val safe = SecurePrefs.redact(reason).take(200)
        prefs
            .edit()
            .putString(key(provider, purpose), "$now|$safe")
            .apply()
    }

    /** No-op when the provider was healthy, so the common path writes nothing. */
    fun clear(
        prefs: SharedPreferences,
        provider: Provider,
        purpose: ProviderConfig.Purpose,
    ) {
        if (prefs.contains(key(provider, purpose))) prefs.edit().remove(key(provider, purpose)).apply()
    }

    /**
     * Which failures justify switching providers mid-dictation.
     *
     * Deliberately conservative: 429 (rate limit) and 5xx are transient and a
     * 24 h block would be wrong, so they stay [Failure.OTHER] and the caller
     * just surfaces the error. An HTML body is a CDN/WAF interstitial, not a
     * provider verdict — a Cloudflare 403 page must not park the provider.
     */
    fun classify(error: String?): Failure {
        val e = error?.lowercase().orEmpty().trim()
        if (e.isEmpty()) return Failure.OTHER
        if (e.contains("<html") || e.contains("<!doctype")) return Failure.OTHER
        // Match real status codes, not digits inside ids ("14032", "4031 ms").
        val http402 = Regex("""\b402\b""").containsMatchIn(e)
        val http401or403 = Regex("""\b40[13]\b""").containsMatchIn(e)
        return when {
            http402 ||
                e.contains("insufficient") ||
                e.contains("quota") ||
                e.contains("balance") ||
                e.contains("credit") ||
                e.contains("billing") ||
                e.contains("payment required") ||
                e.contains("1008") -> Failure.QUOTA

            http401or403 ||
                e.contains("unauthor") ||
                e.contains("forbidden") ||
                e.contains("invalid api key") ||
                e.contains("secret key") ||
                e.contains("login fail") ||
                e.contains("authentication") ||
                e.contains("1004") -> Failure.AUTH

            else -> Failure.OTHER
        }
    }

    /** Records that [provider] served a real [purpose] request at [now] — drives the status row. */
    fun markOk(
        prefs: SharedPreferences,
        provider: Provider,
        purpose: ProviderConfig.Purpose,
        now: Long = System.currentTimeMillis(),
    ) {
        prefs.edit().putLong(okKey(provider, purpose), now).apply()
    }

    fun lastOk(
        prefs: SharedPreferences,
        provider: Provider,
        purpose: ProviderConfig.Purpose,
    ): Long = prefs.getLong(okKey(provider, purpose), 0L)

    /**
     * Providers to try for [purpose], in chain order, minus anything currently
     * paused. Paused entries move to the back rather than disappearing: if
     * *everything* is paused the full chain comes back, because one doomed
     * request beats a silent failure on a guess that predates a top-up.
     */
    fun order(
        prefs: SharedPreferences,
        purpose: ProviderConfig.Purpose,
        now: Long = System.currentTimeMillis(),
    ): List<Provider> {
        val full = ProviderConfig.chain(prefs, purpose)
        val healthy = full.filter { blocked(prefs, it, purpose, now) == null }
        return healthy.ifEmpty { full }
    }

    fun sttChain(prefs: SharedPreferences): List<Provider> = order(prefs, ProviderConfig.Purpose.STT)

    fun chatChain(prefs: SharedPreferences): List<Provider> = order(prefs, ProviderConfig.Purpose.CHAT)

    /** Panel copy for a provider row: "OK · checked 2 h ago" / "Out of credit · 3 h ago". */
    fun statusLine(
        prefs: SharedPreferences,
        provider: Provider,
        purpose: ProviderConfig.Purpose,
        now: Long = System.currentTimeMillis(),
    ): String {
        val paused = blocked(prefs, provider, purpose, now)
        if (paused != null) {
            val why =
                if (paused.reason.length <= 40) {
                    paused.reason.replaceFirstChar { c -> c.uppercase() }.ifBlank { "Paused" }
                } else {
                    Failures.describe(paused.reason)
                }
            return "$why · ${QuotaChecker.timeAgo(now - paused.at)}"
        }
        val ok = lastOk(prefs, provider, purpose)
        if (ok > 0L) return "OK · checked ${QuotaChecker.timeAgo(now - ok)}"
        return if (prefs.contains(key(provider, purpose))) "OK" else "not checked yet"
    }

    private fun okKey(
        provider: Provider,
        purpose: ProviderConfig.Purpose,
    ) = OK_PREFIX + purpose.name.lowercase() + "_" + provider.name.lowercase()

    private fun key(
        provider: Provider,
        purpose: ProviderConfig.Purpose,
    ) = PREFIX + purpose.name.lowercase() + "_" + provider.name.lowercase()
}
