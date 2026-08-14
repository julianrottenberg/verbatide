package com.kafkasl.phonewhisper

import android.content.SharedPreferences

/**
 * OpenAI-compatible provider configuration.
 *
 * All providers speak the same HTTP contract (multipart /v1/audio/transcriptions
 * + JSON /v1/chat/completions with Bearer auth), so switching is just a
 * base-URL + model-name swap. "Custom" lets power users point at any
 * OpenAI-compatible endpoint (self-hosted, proxy, OpenRouter raw, etc.).
 */
enum class Provider(val displayName: String) {
    OPENAI("OpenAI"),
    GROQ("Groq"),
    OPENROUTER("OpenRouter"),
    CUSTOM("Custom"),
}

object ProviderConfig {

    // -----------------------------------------------------------------------
    // Stored keys
    // -----------------------------------------------------------------------
    private const val KEY_PROVIDER = "provider"
    private const val KEY_CUSTOM_STT_URL = "custom_stt_base_url"
    private const val KEY_CUSTOM_STT_MODEL = "custom_stt_model"
    private const val KEY_CUSTOM_CHAT_URL = "custom_chat_base_url"
    private const val KEY_CUSTOM_CHAT_MODEL = "custom_chat_model"

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

    fun fromString(raw: String?): Provider = when (raw?.lowercase()?.trim()) {
        "groq" -> Provider.GROQ
        "openrouter", "open_router" -> Provider.OPENROUTER
        "custom" -> Provider.CUSTOM
        else -> Provider.OPENAI
    }

    fun selected(prefs: SharedPreferences): Provider =
        fromString(prefs.getString(KEY_PROVIDER, null))

    fun defaultsFor(provider: Provider): Defaults = when (provider) {
        Provider.OPENAI -> OPENAI_DEFAULTS
        Provider.GROQ -> GROQ_DEFAULTS
        Provider.OPENROUTER -> OPENROUTER_DEFAULTS
        Provider.CUSTOM -> OPENAI_DEFAULTS // fallback shape; custom values override
    }

    // -----------------------------------------------------------------------
    // Resolved values (defaults + custom overrides)
    // -----------------------------------------------------------------------
    fun sttUrl(prefs: SharedPreferences): String {
        val p = selected(prefs)
        if (p == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_STT_URL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return normalizeUrl(custom)
        }
        return defaultsFor(p).sttUrl
    }

    fun sttModel(prefs: SharedPreferences): String {
        val p = selected(prefs)
        if (p == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_STT_MODEL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return custom
        }
        return defaultsFor(p).sttModel
    }

    fun chatUrl(prefs: SharedPreferences): String {
        val p = selected(prefs)
        if (p == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_CHAT_URL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return normalizeUrl(custom)
        }
        return defaultsFor(p).chatUrl
    }

    fun chatModel(prefs: SharedPreferences): String {
        val p = selected(prefs)
        if (p == Provider.CUSTOM) {
            val custom = prefs.getString(KEY_CUSTOM_CHAT_MODEL, null)?.trim().orEmpty()
            if (custom.isNotBlank()) return custom
        }
        return defaultsFor(p).chatModel
    }

    fun saveProvider(prefs: SharedPreferences, provider: Provider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name.lowercase()).apply()
    }

    fun customSttUrl(prefs: SharedPreferences): String =
        prefs.getString(KEY_CUSTOM_STT_URL, "") ?: ""

    fun customSttModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_CUSTOM_STT_MODEL, "") ?: ""

    fun customChatUrl(prefs: SharedPreferences): String =
        prefs.getString(KEY_CUSTOM_CHAT_URL, "") ?: ""

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
        // Disallow bare http except for loopback (self-hosted dev). Warn upstream
        // by normalizing only; the caller decides whether to show a warning.
        return normalizeUrl(t)
    }

    fun isLoopbackHttp(url: String): Boolean = try {
        val u = java.net.URL(url)
        u.protocol == "http" && (u.host == "localhost" || u.host == "127.0.0.1" || u.host == "::1")
    } catch (_: Exception) { false }

    private fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/')

    /** Human-readable summary for the Settings row subtitle. */
    fun summary(prefs: SharedPreferences): String {
        val p = selected(prefs)
        val d = defaultsFor(p)
        return when (p) {
            Provider.CUSTOM -> {
                val stt = sttUrl(prefs)
                val chat = chatUrl(prefs)
                if (stt == chat) "Custom · $stt"
                else "Custom · STT: ${shortHost(stt)} · Chat: ${shortHost(chat)}"
            }
            else -> "${p.displayName} · STT: ${d.sttModel} · Chat: ${d.chatModel}"
        }
    }

    private fun shortHost(url: String): String =
        try { java.net.URL(url).host } catch (_: Exception) { url }
}
