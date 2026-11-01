package com.julianrottenberg.verbatide

import android.content.Context

/**
 * Re-runs transcription on the audio kept by [RecordingStore].
 *
 * The point is the dictation that came back as mush: the model misheard, or the
 * wrong provider was used because the primary was out of quota. Without the
 * audio the only fix was to say it again; with it, the same bytes go through the
 * whole provider chain again and the entry's text is replaced in place.
 *
 * Mirrors [RedoCleanup]: same chain rule (primary, backups, health-paused last),
 * same quota-vs-hiccup distinction, no UI, one callback.
 */
object RedoTranscribe {
    data class Outcome(
        val text: String?,
        val error: String?,
        val language: String? = null,
        val provider: String = "",
        val model: String = "",
        val elapsedMs: Long = 0,
    )

    fun run(
        ctx: Context,
        wav: ByteArray,
        onDone: (Outcome) -> Unit,
    ) {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        val chain =
            ProviderHealth
                .sttChain(prefs)
                .filter { SecurePrefs.getSttApiKey(ctx, it).isNotBlank() }
                .filter { ProviderConfig.isReady(prefs, ProviderConfig.Purpose.STT, it) }
        if (chain.isEmpty()) {
            onDone(Outcome(null, "No transcription provider with a key"))
            return
        }
        val pinned = prefs.getString("stt_language", "auto")?.takeIf { it != "auto" }
        val vocabulary = DictionaryManager.outputTerms(ctx)
        attempt(ctx, chain, 0, wav, pinned, vocabulary, onDone)
    }

    private fun attempt(
        ctx: Context,
        chain: List<Provider>,
        index: Int,
        wav: ByteArray,
        language: String?,
        vocabulary: List<String>,
        onDone: (Outcome) -> Unit,
    ) {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        val provider =
            chain.getOrNull(index) ?: run {
                onDone(Outcome(null, "Every transcription provider failed"))
                return
            }
        val model = ProviderConfig.sttModelFor(prefs, provider)
        val startedAt = System.currentTimeMillis()
        val apiKey = SecurePrefs.getSttApiKey(ctx, provider)
        val deliver: (String?, String?, String?) -> Unit = cb@{ text, detected, error ->
            val ms = System.currentTimeMillis() - startedAt
            if (!text.isNullOrBlank() && error == null) {
                ProviderHealth.clear(prefs, provider, ProviderConfig.Purpose.STT)
                ProviderHealth.markOk(prefs, provider, ProviderConfig.Purpose.STT)
                LogStore.add(
                    "redo",
                    "Re-transcribed with ${provider.displayName} in ${ms}ms, ${text.length} chars",
                )
                onDone(Outcome(text, null, detected ?: language, provider.name.lowercase(), model, ms))
            } else {
                val reason = error ?: "empty transcript"
                val failure = ProviderHealth.classify(reason)
                LogStore.add("redo", "Re-transcribe ${provider.displayName} failed: ${SecurePrefs.redact(reason)}")
                if (failure != ProviderHealth.Failure.OTHER) {
                    ProviderHealth.block(prefs, provider, ProviderConfig.Purpose.STT, reason)
                    if (index + 1 < chain.size) {
                        attempt(ctx, chain, index + 1, wav, language, vocabulary, onDone)
                        return@cb
                    }
                }
                onDone(Outcome(null, reason, null, provider.name.lowercase(), model, ms))
            }
        }
        when (provider) {
            Provider.FAL -> {
                FalTranscriber.transcribe(wav, apiKey, language) { r ->
                    deliver(r.text, r.language, r.error)
                }
            }

            else -> {
                TranscriberClient.transcribe(
                    wavData = wav,
                    apiKey = apiKey,
                    sttUrl = ProviderConfig.sttUrlFor(prefs, provider),
                    sttModel = model,
                    language = language,
                    vocabulary = vocabulary,
                ) { r ->
                    deliver(r.text, r.language, r.error)
                }
            }
        }
    }
}
