package com.julianrottenberg.verbatide

import android.content.SharedPreferences

/**
 * Provider configuration with split STT / chat selection so users can mix,
 * e.g. fal.ai for transcription and Together AI for cleanup.
 *
 * fal.ai (Wizper) is STT-only and uses a queue-based API rather than
 * OpenAI-compatible multipart — it gets its own client (FalTranscriber).
 * All other providers speak the same HTTP contract (multipart
 * /v1/audio/transcriptions + JSON /v1/chat/completions with Bearer auth).
 */
enum class Provider(
    val displayName: String,
    val supportsStt: Boolean = true,
    val supportsChat: Boolean = true,
) {
    OPENAI("OpenAI"),
    GROQ("Groq"),
    OPENROUTER("OpenRouter"),
    TOGETHER("Together AI"),
    VENICE("Venice AI"),
    MISTRAL("Mistral"),
    NANOGPT("NanoGPT"),
    MINIMAX("MiniMax"),
    FAL("fal.ai", supportsStt = true, supportsChat = false),
    CUSTOM("Custom"),
}

object ProviderConfig {

    // -----------------------------------------------------------------------
    // Stored keys (split per purpose + legacy fallback)
    // -----------------------------------------------------------------------
    private const val KEY_PROVIDER = "provider" // legacy single key
    private const val KEY_STT_PROVIDER = "stt_provider"
    private const val KEY_CHAT_PROVIDER = "chat_provider"
    private const val KEY_CUSTOM_STT_URL = "custom_stt_base_url"
    private const val KEY_CUSTOM_STT_MODEL = "custom_stt_model"
    private const val KEY_STT_MODEL_OVERRIDE = "stt_model_override"
    private const val KEY_CHAT_MODEL_OVERRIDE = "chat_model_override"
    private const val KEY_CUSTOM_CHAT_URL = "custom_chat_base_url"
    private const val KEY_CUSTOM_CHAT_MODEL = "custom_chat_model"
    private const val KEY_STT_CHAIN = "stt_chain"
    private const val KEY_CHAT_CHAIN = "chat_chain"
    val sttProviders: List<Provider> get() = Provider.entries.filter { it.supportsStt }
    val chatProviders: List<Provider> get() = Provider.entries.filter { it.supportsChat }

    // -----------------------------------------------------------------------
    // Defaults per provider
    // -----------------------------------------------------------------------
    data class Defaults(
        val sttUrl: String,
        val sttModel: String,
        val chatUrl: String,
        val chatModel: String,
    )

    private val OPENAI_DEFAULTS = Defaults(
        sttUrl = "https://api.openai.com/v1/audio/transcriptions",
        sttModel = "whisper-1",
        chatUrl = "https://api.openai.com/v1/chat/completions",
        chatModel = "gpt-4o-mini",
    )

    private val GROQ_DEFAULTS = Defaults(
        sttUrl = "https://api.groq.com/openai/v1/audio/transcriptions",
        sttModel = "whisper-large-v3-turbo",
        chatUrl = "https://api.groq.com/openai/v1/chat/completions",
        chatModel = "llama-3.3-70b-versatile",
    )

    /**
     * OpenRouter proxies OpenAI-compatible requests to many upstream models.
     * STT is forwarded to the underlying provider, so we default to the
     * OpenAI model name which OpenRouter accepts as `openai/whisper-large-v3`.
     * Chat defaults to OpenAI's gpt-4o-mini via OpenRouter so cost/behaviour
     * stays close to the original app; users can switch to any OpenRouter
     * slug (e.g. `meta-llama/llama-3.3-70b-instruct:free`).
     */
    private val OPENROUTER_DEFAULTS = Defaults(
        sttUrl = "https://openrouter.ai/api/v1/audio/transcriptions",
        sttModel = "openai/whisper-large-v3",
        chatUrl = "https://openrouter.ai/api/v1/chat/completions",
        chatModel = "openai/gpt-4o-mini",
    )

    /**
     * Together AI hosts OpenAI-compatible Whisper + Llama endpoints.
     * STT uses whisper-large-v3; chat defaults to Llama 3.3 70B Turbo.
     */
    private val TOGETHER_DEFAULTS = Defaults(
        // Together supports the same Whisper transcription contract on
        // /v1/audio/transcriptions (translate lives on /v1/audio/translations).
        sttUrl = "https://api.together.ai/v1/audio/transcriptions",
        sttModel = "openai/whisper-large-v3",
        chatUrl = "https://api.together.ai/v1/chat/completions",
        chatModel = "Qwen/Qwen3.5-9B",
    )

    private val VENICE_DEFAULTS = Defaults(
        sttUrl = "https://api.venice.ai/api/v1/audio/transcriptions",
        sttModel = "whisper-large-v3",
        chatUrl = "https://api.venice.ai/api/v1/chat/completions",
        chatModel = "venice-uncensored",
    )

    private val MISTRAL_DEFAULTS = Defaults(
        sttUrl = "https://api.mistral.ai/v1/audio/transcriptions",
        sttModel = "voxtral-mini-latest",
        chatUrl = "https://api.mistral.ai/v1/chat/completions",
        chatModel = "mistral-small-latest",
    )

    private val NANOGPT_DEFAULTS = Defaults(
        sttUrl = "https://api.nano-gpt.com/api/v1/audio/transcriptions",
        sttModel = "Whisper-Large-V3",
        chatUrl = "https://api.nano-gpt.com/api/v1/chat/completions",
        chatModel = "openai/gpt-4o-mini",
    )

    /**
     * MiniMax: ASR (`asr-1.0`) on /v1/speech_to_text plus an OpenAI-compatible
     * chat endpoint. Three quirks the transport layer has to know about:
     *  - ASR takes `language` as a *header*, and has no prompt/context hint.
     *  - M3.1 thinking cannot be turned off; effort defaults to `max`, so
     *    cleanup has to ask for `reasoning_effort=low` explicitly.
     *  - Errors are OpenAI-shaped with the HTTP status carrying the meaning
     *    (402 = out of quota), which is exactly what the fallback logic needs.
     */
    private val MINIMAX_DEFAULTS = Defaults(
        sttUrl = "https://api.minimax.io/v1/speech_to_text",
        sttModel = "asr-1.0",
        chatUrl = "https://api.minimax.io/v1/chat/completions",
        chatModel = "MiniMax-M3.1-Flash",
    )

    /**
     * fal.ai Wizper (whisper v3 large) — STT only, queue-based API at
     * https://queue.fal.run/fal-ai/wizper (audio_url + polling). Handled by
     * FalTranscriber rather than the generic multipart path.
     */
    private val FAL_DEFAULTS = Defaults(
        sttUrl = "https://queue.fal.run/fal-ai/wizper",
        sttModel = "wizper",
        chatUrl = "",
        chatModel = "",
    )

    fun fromString(raw: String?): Provider = when (raw?.lowercase()?.trim()) {
        "groq" -> Provider.GROQ
        "openrouter", "open_router" -> Provider.OPENROUTER
        "together", "together_ai", "togetherai" -> Provider.TOGETHER
        "venice", "venice_ai", "veniceai" -> Provider.VENICE
        "mistral", "voxtral" -> Provider.MISTRAL
        "nanogpt", "nano_gpt", "nano-gpt" -> Provider.NANOGPT
        "minimax", "mini_max", "minimaxi", "minimax.io" -> Provider.MINIMAX
        "fal", "fal.ai", "fal_ai", "falai", "wizper" -> Provider.FAL
        "custom" -> Provider.CUSTOM
        else -> Provider.OPENAI
    }

    // Legacy single-provider shim.
    fun selected(prefs: SharedPreferences): Provider =
        fromString(prefs.getString(KEY_PROVIDER, null))

    /**
     * Which half of the pipeline a provider list belongs to. Each half has its
     * own ordered chain, so transcription and cleanup can use different
     * providers and fail over independently.
     */
    enum class Purpose(
        val label: String,
    ) {
        STT("Transcription"),
        CHAT("Cleanup"),
        ;

        /** Providers that can serve this half of the pipeline. */
        /**
         * Providers that can serve this half of the pipeline. Custom is included
         * so the picker can offer it, but it is only usable once a URL is set —
         * callers must also check [ProviderConfig.isReady].
         */
        fun eligible(): List<Provider> = Provider.entries.filter { if (this == STT) it.supportsStt else it.supportsChat }
    }

    private fun chainKey(purpose: Purpose) = if (purpose == Purpose.STT) KEY_STT_CHAIN else KEY_CHAT_CHAIN

    /** The stored primary without consulting the chain — breaks the recursion in [chain]. */
    private fun legacyPrimary(
        prefs: SharedPreferences,
        purpose: Purpose,
    ): Provider {
        val key = if (purpose == Purpose.STT) KEY_STT_PROVIDER else KEY_CHAT_PROVIDER
        val raw = prefs.getString(key, null) ?: prefs.getString(KEY_PROVIDER, null)
        val p = fromString(raw)
        return when {
            // A legacy primary that cannot serve the purpose at all falls back to
            // the one provider guaranteed to be eligible for both halves.
            purpose == Purpose.CHAT && !p.supportsChat -> Provider.OPENAI
            purpose == Purpose.STT && !p.supportsStt -> Provider.OPENAI
            else -> p
        }
    }

    /**
     * Ordered providers for [purpose], priority first. Never empty.
     *
     * The chain is the single source of truth for "who is primary" — the old
     * `stt_provider` / `chat_provider` keys survive only as a one-time source
     * for installs that predate it (and so a downgrade still boots).
     */
    fun chain(
        prefs: SharedPreferences,
        purpose: Purpose,
    ): List<Provider> {
        val raw = prefs.getString(chainKey(purpose), null)?.trim().orEmpty()
        if (raw.isNotBlank()) {
            val parsed =
                raw
                    .split(',')
                    .mapNotNull { name -> Provider.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
                    .filter { it in purpose.eligible() }
                    .distinct()
            // A chain that exists but parses to nothing (older build, renamed or
            // removed provider) still carries a user decision — fall back to the
            // legacy primary mirror (saveChain keeps it in sync with the chain
            // head), never to the first enum entry, which would silently route
            // audio and cleanup to a provider the user never picked.
            return parsed.ifEmpty { listOf(legacyPrimary(prefs, purpose)) }
        }
        val primary = legacyPrimary(prefs, purpose)
        val backup = backupProvider(prefs)?.takeIf { it in purpose.eligible() && it != primary }
        return listOfNotNull(primary, backup)
    }

    fun primary(
        prefs: SharedPreferences,
        purpose: Purpose,
    ): Provider = chain(prefs, purpose).first()

    /**
     * Writes the chain and mirrors its head into the legacy single-provider
     * key. Also drops the pre-chain `backup_provider` value so a downgrade
     * cannot resurrect a stale second entry.
     */
    fun saveChain(
        prefs: SharedPreferences,
        purpose: Purpose,
        chain: List<Provider>,
    ) {
        val clean = chain.filter { it in purpose.eligible() }.distinct()
        if (clean.isEmpty()) return
        val editor =
            prefs
                .edit()
                .putString(chainKey(purpose), clean.joinToString(",") { it.name.lowercase() })
        // `backup_provider` is the pre-chain fallback for *both* halves. Drop it
        // only once the other half also has a chain, or saving one chain would
        // silently discard the other half's fallback.
        val otherKey = chainKey(if (purpose == Purpose.STT) Purpose.CHAT else Purpose.STT)
        if (!prefs.getString(otherKey, null).isNullOrBlank()) editor.remove(KEY_BACKUP_PROVIDER)
        editor.apply()
        if (purpose == Purpose.STT) saveSttProvider(prefs, clean.first()) else saveChatProvider(prefs, clean.first())
    }
    /**
     * Makes [provider] the primary for [purpose], keeping the rest of the chain
     * behind it. The radio lists in settings call this; without rewriting the
     * chain, picking a different primary would be ignored on reload.
     */
    fun select(
        prefs: SharedPreferences,
        purpose: Purpose,
        provider: Provider,
    ) {
        if (provider !in purpose.eligible()) return
        val current = chain(prefs, purpose)
        saveChain(prefs, purpose, listOf(provider) + current.filter { it != provider })
    }
    fun appendToChain(
        prefs: SharedPreferences,
        purpose: Purpose,
        provider: Provider,
    ) {
        val current = chain(prefs, purpose)
        if (provider in current || provider !in purpose.eligible()) return
        saveChain(prefs, purpose, current + provider)
    }

    fun removeFromChain(
        prefs: SharedPreferences,
        purpose: Purpose,
        provider: Provider,
    ) {
        val current = chain(prefs, purpose)
        // Removing the last entry would leave the pipeline with nothing to try.
        if (current.size <= 1 || provider !in current) return
        saveChain(prefs, purpose, current - provider)
    }

    /** Moves an entry one slot up (-1) or down (+1); no-ops at the edges. */
    fun moveInChain(
        prefs: SharedPreferences,
        purpose: Purpose,
        provider: Provider,
        delta: Int,
    ) {
        val current = chain(prefs, purpose).toMutableList()
        val from = current.indexOf(provider)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.size - 1)
        if (to == from) return
        current.removeAt(from)
        current.add(to, provider)
        saveChain(prefs, purpose, current)
    }

    /** "1. Mistral → 2. MiniMax" for the settings row. */
    fun chainSummary(
        prefs: SharedPreferences,
        purpose: Purpose,
    ): String =
        chain(prefs, purpose)
            .mapIndexed { i, p -> "${i + 1}. ${p.displayName}" }
            .joinToString("  →  ")

    private fun legacySelected(
        prefs: SharedPreferences,
        purpose: Purpose,
    ): Provider = chain(prefs, purpose).first()

    fun selectedStt(prefs: SharedPreferences): Provider = legacySelected(prefs, Purpose.STT)

    fun selectedChat(prefs: SharedPreferences): Provider = legacySelected(prefs, Purpose.CHAT)

    fun defaultsFor(provider: Provider): Defaults = when (provider) {
        Provider.OPENAI -> OPENAI_DEFAULTS
        Provider.GROQ -> GROQ_DEFAULTS
        Provider.OPENROUTER -> OPENROUTER_DEFAULTS
        Provider.TOGETHER -> TOGETHER_DEFAULTS
        Provider.VENICE -> VENICE_DEFAULTS
        Provider.MISTRAL -> MISTRAL_DEFAULTS
        Provider.NANOGPT -> NANOGPT_DEFAULTS
        Provider.MINIMAX -> MINIMAX_DEFAULTS
        Provider.FAL -> FAL_DEFAULTS
        Provider.CUSTOM -> OPENAI_DEFAULTS // fallback shape; custom values override
    }

    // -----------------------------------------------------------------------
    // Resolved values (defaults + custom overrides)
    // -----------------------------------------------------------------------
    /**
     * STT endpoint for an explicit provider. The no-argument variants below are
     * the primary-provider shortcuts; the backup chain uses these directly.
     */
    fun sttUrlFor(prefs: SharedPreferences, provider: Provider): String {
        if (provider == Provider.FAL) return FAL_DEFAULTS.sttUrl
        if (provider == Provider.CUSTOM) {
            // Never fall back to OpenAI here: a Custom entry with no URL must be
            // skipped, not quietly ship audio to api.openai.com with someone
            // else's key.
            return validateCustomUrlOrNull(prefs.getString(KEY_CUSTOM_STT_URL, "") ?: "").orEmpty()
        }
        return defaultsFor(provider).sttUrl
    }

    fun sttUrl(prefs: SharedPreferences): String = sttUrlFor(prefs, selectedStt(prefs))

    fun sttModelFor(prefs: SharedPreferences, provider: Provider): String {
        if (provider == Provider.FAL) return FAL_DEFAULTS.sttModel
        // Per-provider override set via the "STT model" row. Keyed by provider
        // so switching the primary can never hand Mistral's model name to Groq.
        modelOverride(prefs, Purpose.STT, provider).let { if (it.isNotBlank()) return it }
        if (provider == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_STT_MODEL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return custom
        }
        return defaultsFor(provider).sttModel
    }

    fun sttModel(prefs: SharedPreferences): String = sttModelFor(prefs, selectedStt(prefs))

    fun chatUrlFor(prefs: SharedPreferences, provider: Provider): String {
        if (provider == Provider.CUSTOM) {
            return validateCustomUrlOrNull(prefs.getString(KEY_CUSTOM_CHAT_URL, "") ?: "").orEmpty()
        }
        return defaultsFor(provider).chatUrl
    }

    fun chatUrl(prefs: SharedPreferences): String = chatUrlFor(prefs, selectedChat(prefs))

    fun chatModelFor(prefs: SharedPreferences, provider: Provider): String {
        // Per-provider override, same rule as STT: bound to the provider it was
        // typed for, never inherited by a fallback.
        modelOverride(prefs, Purpose.CHAT, provider).let { if (it.isNotBlank()) return it }
        if (provider == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_CHAT_MODEL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return custom
        }
        return defaultsFor(provider).chatModel
    }

    fun chatModel(prefs: SharedPreferences): String = chatModelFor(prefs, selectedChat(prefs))

    fun saveProvider(prefs: SharedPreferences, provider: Provider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name.lowercase()).apply()
    }

    fun saveSttProvider(prefs: SharedPreferences, provider: Provider) {
        prefs.edit().putString(KEY_STT_PROVIDER, provider.name.lowercase()).apply()
    }

    fun saveChatProvider(prefs: SharedPreferences, provider: Provider) {
        prefs.edit().putString(KEY_CHAT_PROVIDER, provider.name.lowercase()).apply()
    }

    // -----------------------------------------------------------------------
    // Backup (fallback) provider
    // -----------------------------------------------------------------------
    private const val KEY_BACKUP_PROVIDER = "backup_provider"

    /**
     * Providers eligible as a fallback. One backup covers both halves — STT
     * *and* cleanup — so it has to support both. fal.ai is STT-only and Custom
     * points at a single self-hosted endpoint, so neither qualifies.
     */
    val backupProviders: List<Provider>
        get() = Provider.entries.filter { it.supportsStt && it.supportsChat && it != Provider.CUSTOM }

    /** Null means "no fallback configured" — unlike [fromString], which defaults to OpenAI. */
    fun backupProvider(prefs: SharedPreferences): Provider? {
        val raw = prefs.getString(KEY_BACKUP_PROVIDER, null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return Provider.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

    /** Passing null clears the fallback. */
    fun saveBackupProvider(
        prefs: SharedPreferences,
        provider: Provider?,
    ) {
        val editor = prefs.edit()
        if (provider == null) {
            editor.remove(KEY_BACKUP_PROVIDER)
        } else {
            editor.putString(KEY_BACKUP_PROVIDER, provider.name.lowercase())
        }
        editor.apply()
    }

    fun backupSummary(prefs: SharedPreferences): String {
        val p = backupProvider(prefs) ?: return "None — a failed request just shows an error"
        if (p == selectedStt(prefs) || p == selectedChat(prefs)) {
            return "${p.displayName} is already in use — pick a different provider"
        }
        val d = defaultsFor(p)
        return "${p.displayName} · ${d.sttModel} · ${d.chatModel}"
    }

    fun customSttUrl(prefs: SharedPreferences): String =
        prefs.getString(KEY_CUSTOM_STT_URL, "") ?: ""

    fun customSttModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_CUSTOM_STT_MODEL, "") ?: ""

    fun customChatUrl(prefs: SharedPreferences): String =
        prefs.getString(KEY_CUSTOM_CHAT_URL, "") ?: ""

    fun saveSttModelOverride(
        prefs: SharedPreferences,
        value: String,
    ) = saveModelOverride(prefs, Purpose.STT, value)

    fun saveChatModelOverride(
        prefs: SharedPreferences,
        value: String,
    ) = saveModelOverride(prefs, Purpose.CHAT, value)

    private fun overrideKey(
        purpose: Purpose,
        provider: Provider,
    ) = (if (purpose == Purpose.STT) "stt_model_override_" else "chat_model_override_") + provider.name.lowercase()

    /**
     * Read-through for the pre-0.9.23 global override (single `*_model_override`
     * key). It belonged to whichever provider was primary at the time, so it is
     * adopted by the current head only, once, then retired.
     */
    private fun modelOverride(
        prefs: SharedPreferences,
        purpose: Purpose,
        provider: Provider,
    ): String {
        val perProvider = prefs.getString(overrideKey(purpose, provider), null)?.trim().orEmpty()
        if (perProvider.isNotBlank()) return perProvider
        val legacyKey = if (purpose == Purpose.STT) KEY_STT_MODEL_OVERRIDE else KEY_CHAT_MODEL_OVERRIDE
        val legacy = prefs.getString(legacyKey, null)?.trim().orEmpty()
        if (legacy.isNotBlank() && provider == primary(prefs, purpose)) {
            prefs.edit().putString(overrideKey(purpose, provider), legacy).remove(legacyKey).apply()
            return legacy
        }
        return ""
    }

    private fun saveModelOverride(
        prefs: SharedPreferences,
        purpose: Purpose,
        value: String,
    ) {
        val provider = primary(prefs, purpose)
        val key = overrideKey(purpose, provider)
        if (value.isBlank()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, value.trim()).apply()
    }

    /** True when the provider has everything it needs to be called. */
    fun isReady(
        prefs: SharedPreferences,
        purpose: Purpose,
        provider: Provider,
    ): Boolean = provider in purpose.eligible() && (provider != Provider.CUSTOM || urlFor(prefs, purpose, provider).isNotBlank())

    private fun urlFor(
        prefs: SharedPreferences,
        purpose: Purpose,
        provider: Provider,
    ) = if (purpose == Purpose.STT) sttUrlFor(prefs, provider) else chatUrlFor(prefs, provider)

    fun customChatModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_CUSTOM_CHAT_MODEL, "") ?: ""

    fun saveCustom(
        prefs: SharedPreferences,
        sttUrl: String? = null,
        sttModel: String? = null,
        chatUrl: String? = null,
        chatModel: String? = null,
    ) {
        prefs.edit().apply {
            if (sttUrl != null) putString(KEY_CUSTOM_STT_URL, sttUrl.trim())
            if (sttModel != null) putString(KEY_CUSTOM_STT_MODEL, sttModel.trim())
            if (chatUrl != null) putString(KEY_CUSTOM_CHAT_URL, chatUrl.trim())
            if (chatModel != null) putString(KEY_CUSTOM_CHAT_MODEL, chatModel.trim())
        }.apply()
    }

    fun isValidCustomUrl(raw: String): Boolean {
        val t = raw.trim()
        if (t.isBlank()) return false
        return try {
            val u = java.net.URL(t)
            (u.protocol == "https" || u.protocol == "http") && u.host.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun validateCustomUrlOrNull(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        if (!isValidCustomUrl(t)) return null
        return normalizeUrl(t)
    }

    fun isLoopbackHttp(url: String): Boolean = try {
        val u = java.net.URL(url)
        u.protocol == "http" && (u.host == "localhost" || u.host == "127.0.0.1" || u.host == "::1")
    } catch (_: Exception) { false }

    private fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/')

    /** Human-readable summary. Shows split selection when STT != chat. */
    fun summary(prefs: SharedPreferences): String {
        val stt = selectedStt(prefs)
        val chat = selectedChat(prefs)
        if (stt == chat) {
            val p = stt
            val d = defaultsFor(p)
            return when (p) {
                Provider.CUSTOM -> {
                    val su = sttUrl(prefs)
                    val cu = chatUrl(prefs)
                    if (su == cu) "Custom · $su" else "Custom · STT: ${shortHost(su)} · Chat: ${shortHost(cu)}"
                }
                Provider.FAL -> "fal.ai · wizper"
                else -> "${p.displayName} · STT: ${d.sttModel} · Chat: ${d.chatModel}"
            }
        }
        fun label(p: Provider, d: Defaults): String = when (p) {
            Provider.CUSTOM -> shortHost(if (p == selectedStt(prefs)) sttUrl(prefs) else chatUrl(prefs))
            Provider.FAL -> "wizper"
            else -> d.sttModel.takeIf { p == stt } ?: d.chatModel
        }
        val sttD = defaultsFor(stt)
        val chatD = defaultsFor(chat)
        return "STT: ${stt.displayName} (${label(stt, sttD)}) · Cleanup: ${chat.displayName} (${label(chat, chatD)})"
    }

    fun sttSummary(prefs: SharedPreferences): String {
        val p = selectedStt(prefs)
        val d = defaultsFor(p)
        return when (p) {
            Provider.CUSTOM -> customSttUrl(prefs).ifBlank { d.sttUrl }.let { shortHost(it) + " · " + customSttModel(prefs).ifBlank { d.sttModel } }
            Provider.FAL -> "fal.ai · wizper"
            else -> "${p.displayName} · ${d.sttModel}"
        }
    }

    fun chatSummary(prefs: SharedPreferences): String {
        val p = selectedChat(prefs)
        val d = defaultsFor(p)
        return when (p) {
            Provider.CUSTOM -> customChatUrl(prefs).ifBlank { d.chatUrl }.let { shortHost(it) + " · " + customChatModel(prefs).ifBlank { d.chatModel } }
            else -> "${p.displayName} · ${d.chatModel}"
        }
    }

    private fun shortHost(url: String): String =
        try { java.net.URL(url).host } catch (_: Exception) { url }
}
