package com.julianrottenberg.verbatide

import android.content.Context

/**
 * Re-runs only the cleanup stage on text that is already transcribed.
 *
 * This is what makes "cleanup failed / cleanup was mediocre" recoverable from the
 * history screen: the raw STT text is stored with every cleaned entry, so a better
 * provider, model or prompt can be tried without recording the dictation again.
 *
 * Mirrors the service's chain rule (primary first, then the configured backups,
 * health-paused providers last) but stays independent of the accessibility
 * service: no UI, no injection, one callback.
 */
object RedoCleanup {
    data class Outcome(
        val text: String?,
        val error: String?,
        val provider: String = "",
        val model: String = "",
        val reasoning: String = "",
        val elapsedMs: Long = 0,
    )

    fun run(
        ctx: Context,
        text: String,
        onDone: (Outcome) -> Unit,
    ) {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        val chain =
            ProviderHealth
                .chatChain(prefs)
                .filter { SecurePrefs.getChatApiKey(ctx, it).isNotBlank() }
                .filter { ProviderConfig.isReady(prefs, ProviderConfig.Purpose.CHAT, it) }
        if (chain.isEmpty()) {
            onDone(Outcome(null, "No cleanup provider with a key"))
            return
        }
        val prompt = prefs.getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        val reasoning = PostProcessor.Reasoning.fromKey(prefs.getString("reasoning_effort", "off"))
        val language = prefs.getString("stt_language", "auto")?.takeIf { it != "auto" }
        attempt(ctx, chain, 0, text, prompt, reasoning, language, onDone)
    }

    private fun attempt(
        ctx: Context,
        chain: List<Provider>,
        index: Int,
        text: String,
        prompt: String,
        reasoning: PostProcessor.Reasoning,
        language: String?,
        onDone: (Outcome) -> Unit,
    ) {
        val provider =
            chain.getOrNull(index) ?: run {
                onDone(Outcome(null, "Every cleanup provider failed"))
                return
            }
        val model =
            ProviderConfig.chatModelFor(
                ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE),
                provider,
            )
        val startedAt = System.currentTimeMillis()
        PostProcessor.process(
            text = text,
            prompt = prompt,
            apiKey = SecurePrefs.getChatApiKey(ctx, provider),
            chatUrl =
                ProviderConfig.chatUrlFor(
                    ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE),
                    provider,
                ),
            chatModel = model,
            reasoning = reasoning,
            languageHint = language,
            keepTerms = DictionaryManager.outputTerms(ctx),
            toneInstruction = "",
        ) { result ->
            val cleaned = result.text
            if (!cleaned.isNullOrBlank()) {
                onDone(
                    Outcome(
                        text = cleaned,
                        error = null,
                        provider = provider.name.lowercase(),
                        model = model,
                        reasoning =
                            if (reasoning == PostProcessor.Reasoning.DEFAULT ||
                                reasoning == PostProcessor.Reasoning.AUTO
                            ) {
                                ""
                            } else {
                                reasoning.key
                            },
                        elapsedMs = System.currentTimeMillis() - startedAt,
                    ),
                )
            } else {
                val error = result.error ?: "empty response"
                val failure = ProviderHealth.classify(error)
                if (failure != ProviderHealth.Failure.OTHER) {
                    ProviderHealth.block(
                        ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE),
                        provider,
                        error,
                    )
                }
                // A rejected key or a dry account is worth trying elsewhere; a
                // one-off network hiccup is not, because the next provider would
                // just as likely fail and the user is waiting on a button press.
                if (failure != ProviderHealth.Failure.OTHER && index + 1 < chain.size) {
                    attempt(ctx, chain, index + 1, text, prompt, reasoning, language, onDone)
                } else {
                    onDone(Outcome(null, error, provider.name.lowercase(), model))
                }
            }
        }
    }
}
