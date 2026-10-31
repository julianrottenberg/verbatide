package com.julianrottenberg.verbatide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback chain decides which account pays for a dictation. Getting the
 * order, the TTL or the "backup is also the primary" edge case wrong is silent
 * — the text still arrives, just from the wrong place — so each rule is pinned
 * down here.
 */
class FallbackProviderTest {
    private val t0 = 1_700_000_000_000L

    private fun prefs(
        stt: Provider = Provider.MISTRAL,
        chat: Provider = Provider.MISTRAL,
        backup: Provider? = Provider.MINIMAX,
    ) = FakePrefs(
        buildMap {
            put("stt_provider", stt.name.lowercase())
            put("chat_provider", chat.name.lowercase())
            backup?.let { put("backup_provider", it.name.lowercase()) }
        },
    )

    // --- chain order -------------------------------------------------------

    @Test fun healthyPrimaryComesFirstBackupSecond() {
        assertEquals(listOf(Provider.MISTRAL, Provider.MINIMAX), ProviderHealth.sttChain(prefs()))
    }

    @Test fun blockedPrimaryLeavesOnlyTheBackup() {
        val p = prefs()
        ProviderHealth.block(p, Provider.MISTRAL, "402 out of quota")
        assertEquals(listOf(Provider.MINIMAX), ProviderHealth.sttChain(p))
    }

    @Test fun allBlockedStillTriesThePrimaryFirst() {
        val p = prefs()
        ProviderHealth.block(p, Provider.MISTRAL, "402", t0)
        ProviderHealth.block(p, Provider.MINIMAX, "401", t0)
        assertEquals(
            listOf(Provider.MISTRAL, Provider.MINIMAX),
            ProviderHealth.chain(p, Provider.MISTRAL, t0 + 1_000),
        )
    }

    @Test fun blockExpiresAfterItsTtl() {
        val p = prefs()
        ProviderHealth.block(p, Provider.MISTRAL, "402", t0)
        assertTrue(ProviderHealth.isBlocked(p, Provider.MISTRAL, t0 + 1_000))
        assertNull(ProviderHealth.blocked(p, Provider.MISTRAL, t0 + ProviderHealth.BLOCK_TTL_MS + 1))
        assertEquals(
            listOf(Provider.MISTRAL, Provider.MINIMAX),
            ProviderHealth.chain(p, Provider.MISTRAL, t0 + ProviderHealth.BLOCK_TTL_MS + 1),
        )
    }

    @Test fun clearingABlockRestoresThePrimary() {
        val p = prefs()
        ProviderHealth.block(p, Provider.MISTRAL, "402")
        ProviderHealth.clear(p, Provider.MISTRAL)
        assertEquals(listOf(Provider.MISTRAL, Provider.MINIMAX), ProviderHealth.sttChain(p))
    }

    @Test fun backupEqualToPrimaryIsIgnored() {
        val p = prefs(stt = Provider.MISTRAL, chat = Provider.MISTRAL, backup = Provider.MISTRAL)
        assertEquals(listOf(Provider.MISTRAL), ProviderHealth.sttChain(p))
    }

    @Test fun noBackupMeansPrimaryOnly() {
        val p = prefs(backup = null)
        assertNull(ProviderConfig.backupProvider(p))
        assertEquals(listOf(Provider.MISTRAL), ProviderHealth.sttChain(p))
    }

    @Test fun sttAndChatSwitchIndependently() {
        val p =
            FakePrefs(
                mapOf(
                    "stt_provider" to "mistral",
                    "chat_provider" to "openai",
                    "backup_provider" to "minimax",
                ),
            )
        assertEquals(listOf(Provider.MISTRAL, Provider.MINIMAX), ProviderHealth.sttChain(p))
        assertEquals(listOf(Provider.OPENAI, Provider.MINIMAX), ProviderHealth.chatChain(p))
    }

    // --- what may be a backup ---------------------------------------------

    @Test fun onlyDualPurposeProvidersQualifyAsBackup() {
        assertTrue(Provider.FAL !in ProviderConfig.backupProviders)
        assertTrue(Provider.CUSTOM !in ProviderConfig.backupProviders)
        assertTrue(Provider.MINIMAX in ProviderConfig.backupProviders)
    }

    // --- which failures justify switching ----------------------------------

    @Test fun quotaAndAuthErrorsAreBlockableButRateLimitsAreNot() {
        assertEquals(ProviderHealth.Failure.QUOTA, ProviderHealth.classify("HTTP 402: insufficient balance"))
        assertEquals(ProviderHealth.Failure.QUOTA, ProviderHealth.classify("{\"error\":\"insufficient credit\"}"))
        assertEquals(ProviderHealth.Failure.AUTH, ProviderHealth.classify("401 Unauthorized"))
        assertEquals(ProviderHealth.Failure.AUTH, ProviderHealth.classify("invalid api key"))
        assertEquals(ProviderHealth.Failure.OTHER, ProviderHealth.classify("429 too many requests"))
        assertEquals(ProviderHealth.Failure.OTHER, ProviderHealth.classify("503 overloaded"))
        assertEquals(ProviderHealth.Failure.OTHER, ProviderHealth.classify(null))
    }

    // --- per-provider endpoint resolution ----------------------------------

    @Test fun modelOverrideBelongsToTheSelectedProviderOnly() {
        val p =
            FakePrefs(
                mapOf(
                    "stt_provider" to "mistral",
                    "stt_model_override" to "voxtral-custom",
                ),
            )
        assertEquals("voxtral-custom", ProviderConfig.sttModelFor(p, Provider.MISTRAL))
        assertEquals("asr-1.0", ProviderConfig.sttModelFor(p, Provider.MINIMAX))
    }

    @Test fun minimaxUsesItsOwnEndpoints() {
        val p = FakePrefs(mapOf("stt_provider" to "minimax", "chat_provider" to "minimax"))
        assertEquals("https://api.minimax.io/v1/speech_to_text", ProviderConfig.sttUrlFor(p, Provider.MINIMAX))
        assertEquals("https://api.minimax.io/v1/chat/completions", ProviderConfig.chatUrlFor(p, Provider.MINIMAX))
        assertEquals("asr-1.0", ProviderConfig.sttModelFor(p, Provider.MINIMAX))
    }

    // --- usage-check wording ----------------------------------------------

    @Test fun ageLabelsStayReadable() {
        assertEquals("just now", QuotaChecker.timeAgo(30_000))
        assertEquals("12 min ago", QuotaChecker.timeAgo(12 * 60_000L))
        assertEquals("3 h ago", QuotaChecker.timeAgo(3 * 3_600_000L))
        assertEquals("2 d ago", QuotaChecker.timeAgo(2 * QuotaChecker.DAY_MS))
    }

    @Test fun dailyCheckRunsOncePerDay() {
        val p = FakePrefs()
        assertTrue(QuotaChecker.needsDailyCheck(p, t0))
        p.edit().putLong("usage_last_check_at", t0).apply()
        assertEquals(false, QuotaChecker.needsDailyCheck(p, t0 + 60_000))
        assertTrue(QuotaChecker.needsDailyCheck(p, t0 + QuotaChecker.DAY_MS))
    }
}
