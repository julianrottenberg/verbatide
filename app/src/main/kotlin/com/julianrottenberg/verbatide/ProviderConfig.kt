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

    fun selectedStt(prefs: SharedPreferences): Provider {
        val raw = prefs.getString(KEY_STT_PROVIDER, null)
            ?: prefs.getString(KEY_PROVIDER, null)
        return fromString(raw)
    }

    fun selectedChat(prefs: SharedPreferences): Provider {
        val raw = prefs.getString(KEY_CHAT_PROVIDER, null)
            ?: prefs.getString(KEY_PROVIDER, null)
        val p = fromString(raw)
        // fal.ai is STT-only; fall back to the legacy/openai default for chat.
        return if (!p.supportsChat) Provider.OPENAI else p
    }

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
            val custom = prefs.getString(KEY_CUSTOM_STT_URL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return normalizeUrl(custom)
        }
        return defaultsFor(provider).sttUrl
    }

    fun sttUrl(prefs: SharedPreferences): String = sttUrlFor(prefs, selectedStt(prefs))

    fun sttModelFor(prefs: SharedPreferences, provider: Provider): String {
        if (provider == Provider.FAL) return FAL_DEFAULTS.sttModel
        // Per-STT override set via the "STT model" row — it belongs to the
        // selected provider, so a backup must not inherit it.
        if (provider == selectedStt(prefs)) {
            prefs.getString(KEY_STT_MODEL_OVERRIDE, null)?.trim().orEmpty().let { if (it.isNotBlank()) return it }
        }
        if (provider == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_STT_MODEL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return custom
        }
        return defaultsFor(provider).sttModel
    }

    fun sttModel(prefs: SharedPreferences): String = sttModelFor(prefs, selectedStt(prefs))

    fun chatUrlFor(prefs: SharedPreferences, provider: Provider): String {
        if (provider == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_CHAT_URL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return normalizeUrl(custom)
        }
        return defaultsFor(provider).chatUrl
    }

    fun chatUrl(prefs: SharedPreferences): String = chatUrlFor(prefs, selectedChat(prefs))

    fun chatModelFor(prefs: SharedPreferences, provider: Provider): String {
        // Per-chat override set via the "Chat model" row — bound to the
        // selected provider, never inherited by a backup.
        if (provider == selectedChat(prefs)) {
            prefs.getString(KEY_CHAT_MODEL_OVERRIDE, null)?.trim().orEmpty().let { if (it.isNotBlank()) return it }
        }
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

    fun saveSttModelOverride(prefs: SharedPreferences, value: String) {
        if (value.isBlank()) prefs.edit().remove(KEY_STT_MODEL_OVERRIDE).apply()
        else prefs.edit().putString(KEY_STT_MODEL_OVERRIDE, value.trim()).apply()
    }

    fun saveChatModelOverride(prefs: SharedPreferences, value: String) {
        if (value.isBlank()) prefs.edit().remove(KEY_CHAT_MODEL_OVERRIDE).apply()
        else prefs.edit().putString(KEY_CHAT_MODEL_OVERRIDE, value.trim()).apply()
    }

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
