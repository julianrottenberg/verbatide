package com.julianrottenberg.verbatide

import android.content.Context
import java.util.Locale

/**
 * Runs every configured provider against the same input, so "which one is
 * actually fastest for me" stops being guesswork.
 *
 * Input is the user's own audio, not a bundled clip: latency depends on their
 * voice, language, mic and length, and the cleanup stage depends on their prompt
 * and dictionary. A canned sample would measure the network and nothing else.
 *
 * Results are reported but deliberately NOT fed into [ProviderHealth]: a
 * benchmark never marks a provider dry or healthy, because it runs outside the
 * dictation path and a single failure there would otherwise pause a provider the
 * user needs.
 */
object BenchmarkRunner {
    data class Sample(
        val stage: String,
        val provider: String,
        val model: String,
        val ms: Long,
        val ok: Boolean,
        val chars: Int = 0,
        val error: String = "",
    )

    data class Report(
        val audioMs: Int,
        val textChars: Int,
        val samples: List<Sample>,
    ) {
        fun asText(): String {
            val b = StringBuilder()
            b.append("Verbatide benchmark — ${audioMs / 1000.0}s audio, $textChars chars input\n")
            for (stage in listOf("Transcription", "Cleanup")) {
                val rows = samples.filter { it.stage == stage }
                if (rows.isEmpty()) continue
                b.append('\n').append(stage).append('\n')
                rows.sortedBy { if (it.ok) it.ms else Long.MAX_VALUE }.forEach { s ->
                    val status =
                        if (s.ok) {
                            String.format(Locale.US, "%.1fs", s.ms / 1000.0)
                        } else {
                            "-"
                        }
                    b
                        .append("  ")
                        .append(s.provider)
                        .append(" · ")
                        .append(s.model)
                        .append(" · ")
                        .append(status)
                    if (s.ok && s.chars > 0) b.append(" · ${s.chars} chars")
                    if (!s.ok) b.append(" · ${SecurePrefs.redact(s.error).take(90)}")
                    b.append('\n')
                }
            }
            return b.toString().trimEnd()
        }

        /** Fastest successful provider on a stage, or null when every one failed. */
        fun fastest(stage: String): Sample? = samples.filter { it.stage == stage && it.ok }.minByOrNull { it.ms }
    }

    /**
     * Blocking on purpose: the caller owns the background thread and the progress
     * callbacks, so this object stays testable and holds no handler.
     */
    fun run(
        ctx: Context,
        wav: ByteArray,
        rawText: String,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Report {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        val language = prefs.getString("stt_language", "auto")?.takeIf { it != "auto" }
        val vocabulary = DictionaryManager.outputTerms(ctx)
        val audioMs = ((wav.size - 44).coerceAtLeast(0) / 32)
        val out = ArrayList<Sample>()
        val sttChain = ProviderHealth.sttChain(prefs).filter { SecurePrefs.getSttApiKey(ctx, it).isNotBlank() }
        for (p in sttChain) {
            if (isCancelled()) break
            val model = ProviderConfig.sttModelFor(prefs, p)
            onProgress("Transcribing with ${p.displayName}…")
            out += transcribeOne(ctx, wav, language, vocabulary, p, model)
        }
        if (rawText.isNotBlank()) {
            val prompt = prefs.getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            val reasoning = PostProcessor.Reasoning.fromKey(prefs.getString("reasoning_effort", "off"))
            val chatChain = ProviderHealth.chatChain(prefs).filter { SecurePrefs.getChatApiKey(ctx, it).isNotBlank() }
            for (p in chatChain) {
                if (isCancelled()) break
                val model = ProviderConfig.chatModelFor(prefs, p)
                onProgress("Cleaning up with ${p.displayName}…")
                out += cleanupOne(ctx, rawText, prompt, reasoning, language, p, model)
            }
        }
        return Report(audioMs, rawText.length, out)
    }

    private fun transcribeOne(
        ctx: Context,
        wav: ByteArray,
        language: String?,
        vocabulary: List<String>,
        provider: Provider,
        model: String,
    ): Sample {
        var sample: Sample? = null
        val lock = Object()
        val started = System.currentTimeMillis()
        val settle = { ok: Boolean, text: String?, error: String? ->
            synchronized(lock) {
                if (sample == null) {
                    sample =
                        Sample(
                            "Transcription",
                            provider.displayName,
                            model,
                            System.currentTimeMillis() - started,
                            ok,
                            text?.length ?: 0,
                            error ?: "",
                        )
                    lock.notifyAll()
                }
            }
        }
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        when (provider) {
            Provider.FAL -> {
                FalTranscriber.transcribe(wav, SecurePrefs.getSttApiKey(ctx, provider), language) { r ->
                    val ok = !r.text.isNullOrBlank() && r.error == null
                    settle(ok, r.text, r.error ?: if (ok) null else "empty transcript")
                }
            }

            else -> {
                TranscriberClient.transcribe(
                    wavData = wav,
                    apiKey = SecurePrefs.getSttApiKey(ctx, provider),
                    sttUrl = ProviderConfig.sttUrlFor(prefs, provider),
                    sttModel = model,
                    language = language,
                    vocabulary = vocabulary,
                ) { r ->
                    val ok = !r.text.isNullOrBlank() && r.error == null
                    settle(ok, r.text, r.error ?: if (ok) null else "empty transcript")
                }
            }
        }
        // The clients have internal timeouts, but a wedged socket should not hang
        // the whole benchmark: budget + slack, then report a timeout and move on.
        val deadline = System.currentTimeMillis() + TranscriberClient.budgetMs(wav) + 20_000L
        synchronized(lock) {
            while (sample == null && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return sample ?: Sample("Transcription", provider.displayName, model, deadline - started, false, 0, "timeout")
        }
    }

    private fun cleanupOne(
        ctx: Context,
        text: String,
        prompt: String,
        reasoning: PostProcessor.Reasoning,
        language: String?,
        provider: Provider,
        model: String,
    ): Sample {
        val prefs = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
        var sample: Sample? = null
        val lock = Object()
        val started = System.currentTimeMillis()
        PostProcessor.process(
            text = text,
            prompt = prompt,
            apiKey = SecurePrefs.getChatApiKey(ctx, provider),
            chatUrl = ProviderConfig.chatUrlFor(prefs, provider),
            chatModel = model,
            reasoning = reasoning,
            languageHint = language,
            keepTerms = DictionaryManager.outputTerms(ctx),
            toneInstruction = "",
        ) { r ->
            synchronized(lock) {
                if (sample == null) {
                    val ok = !r.text.isNullOrBlank()
                    sample =
                        Sample(
                            "Cleanup",
                            provider.displayName,
                            model,
                            System.currentTimeMillis() - started,
                            ok,
                            r.text?.length ?: 0,
                            r.error ?: if (ok) "" else "empty response",
                        )
                    lock.notifyAll()
                }
            }
        }
        val deadline = System.currentTimeMillis() + 120_000L
        synchronized(lock) {
            while (sample == null && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return sample ?: Sample("Cleanup", provider.displayName, model, deadline - started, false, 0, "timeout")
        }
    }
}
