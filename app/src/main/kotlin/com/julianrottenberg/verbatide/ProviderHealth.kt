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
 * Blocked state is per provider, not per purpose: one account balance funds
 * both the STT and the chat endpoint, which is exactly why a single switch to
 * the backup covers both halves.
 */
object ProviderHealth {
    const val BLOCK_TTL_MS = 24L * 60 * 60 * 1000

    private const val PREFIX = "provider_block_"

    enum class Failure { QUOTA, AUTH, OTHER }

    /** The failure that caused the current block. */
    data class Block(
        val reason: String,
        val at: Long,
    )

    /**
     * Whether a provider is worth trying. Expired entries are dropped lazily so
     * a stale block can never outlive its TTL because nothing wrote to prefs.
     */
    fun blocked(
        prefs: SharedPreferences,
        provider: Provider,
        now: Long = System.currentTimeMillis(),
    ): Block? {
        val raw = prefs.getString(key(provider), null) ?: return null
        val at = raw.substringBefore('|').toLongOrNull()
        if (at == null || now - at > BLOCK_TTL_MS) {
            prefs.edit().remove(key(provider)).apply()
            return null
        }
        return Block(raw.substringAfter('|', ""), at)
    }

    fun isBlocked(
        prefs: SharedPreferences,
        provider: Provider,
        now: Long = System.currentTimeMillis(),
    ): Boolean = blocked(prefs, provider, now) != null

    fun block(
        prefs: SharedPreferences,
        provider: Provider,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ) {
        prefs
            .edit()
            .putString(key(provider), "$now|$reason")
            .apply()
    }

    /** No-op when the provider was healthy, so the common path writes nothing. */
    fun clear(
        prefs: SharedPreferences,
        provider: Provider,
    ) {
        if (prefs.contains(key(provider))) prefs.edit().remove(key(provider)).apply()
    }

    /**
     * Which failures justify switching providers mid-dictation.
     *
     * Deliberately conservative: 429 (rate limit) and 5xx are transient and a
     * 24 h block would be wrong, so they stay [Failure.OTHER] and the caller
     * just surfaces the error.
     */
    fun classify(error: String?): Failure {
        val e = error?.lowercase().orEmpty().trim()
        if (e.isEmpty()) return Failure.OTHER
        return when {
            e.contains("402") ||
                e.contains("insufficient") ||
                e.contains("quota") ||
                e.contains("balance") ||
                e.contains("credit") ||
                e.contains("billing") ||
                e.contains("payment required") -> Failure.QUOTA

            e.contains("401") ||
                e.contains("403") ||
                e.contains("unauthor") ||
                e.contains("forbidden") ||
                e.contains("invalid api key") ||
                e.contains("api key") ||
                e.contains("secret key") ||
                e.contains("login fail") ||
                e.contains("authentication") -> Failure.AUTH

            else -> Failure.OTHER
        }
    }

    /**
     * Providers to try, in order: the primary, then the configured backup,
     * minus anything currently blocked. If *everything* is blocked the full
     * list is returned anyway — better one doomed request than a silent
     * failure on a guess that predates a top-up.
     */
    fun chain(
        prefs: SharedPreferences,
        primary: Provider,
        now: Long = System.currentTimeMillis(),
    ): List<Provider> {
        val backup = ProviderConfig.backupProvider(prefs)?.takeIf { it != primary }
        val all = if (backup == null) listOf(primary) else listOf(primary, backup)
        val healthy = all.filter { blocked(prefs, it, now) == null }
        return healthy.ifEmpty { all }
    }

    fun sttChain(prefs: SharedPreferences): List<Provider> = chain(prefs, ProviderConfig.selectedStt(prefs))

    fun chatChain(prefs: SharedPreferences): List<Provider> = chain(prefs, ProviderConfig.selectedChat(prefs))

    /** Short "· MiniMax (no quota)" suffix for the settings row, or "" when healthy. */
    fun blockedSummary(
        prefs: SharedPreferences,
        providers: List<Provider>,
    ): String {
        val blockedOnes = providers.mapNotNull { p -> blocked(prefs, p)?.let { p to it } }
        if (blockedOnes.isEmpty()) return ""
        return blockedOnes.joinToString(" · ", prefix = " · ") { (p, _) -> "${p.displayName} paused" }
    }

    private fun key(provider: Provider) = PREFIX + provider.name.lowercase()
}
