package com.julianrottenberg.verbatide

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Export/import of user settings as JSON.
 *
 * The API key is deliberately excluded: it lives in EncryptedSharedPreferences
 * API keys are excluded unless the caller passes them explicitly. They come from
 * EncryptedSharedPreferences and must not land in a plaintext file by accident,
 * so the default export stays shareable and the key path is opt-in.
 *
 *
 * Key names must stay in sync with ProviderConfig/MainActivity pref keys.
 */
object SettingsBackup {
    const val BACKUP_VERSION = 3

    private val STRING_KEYS =
        listOf(
            "provider",
            "stt_provider",
            "chat_provider",
            // Ordered failover chains — v2 exported only the primaries, so a
            // restore silently forgot every fallback provider.
            "stt_chain",
            "chat_chain",
            "backup_provider",
            "model_name",
            "custom_stt_base_url",
            "custom_stt_model",
            "custom_chat_base_url",
            "custom_chat_model",
            "post_processing_prompt",
            "custom_post_processing_prompt",
            "reasoning_effort",
            "stt_language",
            // Per-app tones. Without these an export silently drops every tone
            // assignment, custom tone and instruction override.
            ToneManager.KEY_MAPPINGS,
            ToneManager.KEY_CUSTOM_TONES,
            ToneManager.KEY_OVERRIDES,
        )

    private val BOOL_KEYS =
        listOf(
            "use_local",
            "use_post_processing",
            DictionaryManager.KEY_DICT_ENABLED,
            VoiceCommands.KEY_ENABLED,
        )

    private val INT_KEYS =
        listOf(
            HistoryManager.KEY_HISTORY_MAX_MB,
            HistoryManager.KEY_HISTORY_MAX_DAYS,
        )

    fun export(
        prefs: SharedPreferences,
        secrets: Map<String, String> = emptyMap(),
    ): String {
        val values = JSONObject()
        for (k in STRING_KEYS) prefs.getString(k, null)?.let { values.put(k, it) }
        for (k in BOOL_KEYS) if (prefs.contains(k)) values.put(k, prefs.getBoolean(k, false))
        for (k in INT_KEYS) if (prefs.contains(k)) values.put(k, prefs.getInt(k, 0))
        val root =
            JSONObject()
                .put("app", "phone-whisper")
                .put("backup_version", BACKUP_VERSION)
                .put("values", values)
        if (secrets.isNotEmpty()) {
            val keys = JSONObject()
            for ((k, v) in secrets) if (v.isNotBlank()) keys.put(k, v)
            root.put("secrets", keys)
        }
        return root.toString(2)
    }

    /** Key names an export may contain, so import can refuse anything else. */
    /**
     * Provider slots only. The `_owner` stamps are deliberately excluded: they
     * decide *which* provider may read the shared slot, so an imported
     * `stt_api_key_owner` plus `stt_chain=custom` would route an existing key to
     * an attacker-chosen endpoint. Imports must not move credentials around.
     */
    fun isSecretKey(key: String): Boolean =
        key.matches(Regex("^(api_key|stt_api_key|chat_api_key)(_[a-z0-9_]+)?$")) && !key.endsWith("_owner")

    /** Returns the number of settings applied, or a failure with the parse error. */
    fun import(
        prefs: SharedPreferences,
        json: String,
        onSecret: ((String, String) -> Unit)? = null,
    ): Result<Int> =
        try {
            val root = JSONObject(json)
            // Tolerate a bare {key: value} object too, not just the wrapped format.
            val values = root.optJSONObject("values") ?: root
            val editor = prefs.edit()
            var count = 0
            for (k in STRING_KEYS) {
                if (values.has(k)) {
                    editor.putString(k, values.getString(k))
                    count++
                }
            }
            for (k in BOOL_KEYS) {
                if (values.has(k)) {
                    editor.putBoolean(k, values.getBoolean(k))
                    count++
                }
            }
            for (k in INT_KEYS) {
                if (values.has(k)) {
                    editor.putInt(k, values.getInt(k))
                    count++
                }
            }
            editor.apply()
            // Keys go through the caller so they land in the encrypted store; the
            // name check keeps a hand-edited file from writing arbitrary prefs.
            root.optJSONObject("secrets")?.let { secrets ->
                if (onSecret == null) return@let
                for (k in secrets.keys()) {
                    if (!isSecretKey(k)) continue
                    val v = secrets.optString(k, "")
                    onSecret(k, v)
                    count++
                }
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
}
