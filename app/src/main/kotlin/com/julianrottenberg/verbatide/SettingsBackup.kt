package com.julianrottenberg.verbatide

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Export/import of user settings as JSON.
 *
 * The API key is deliberately excluded: it lives in EncryptedSharedPreferences
 * and should never land in a plaintext file the user might share or sync.
 *
 * History entries themselves are not exported either, only the retention limits.
 *
 * Key names must stay in sync with ProviderConfig/MainActivity pref keys.
 */
object SettingsBackup {
    const val BACKUP_VERSION = 2

    private val STRING_KEYS =
        listOf(
            "provider",
            "stt_provider",
            "chat_provider",
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

    fun export(prefs: SharedPreferences): String {
        val values = JSONObject()
        for (k in STRING_KEYS) prefs.getString(k, null)?.let { values.put(k, it) }
        for (k in BOOL_KEYS) if (prefs.contains(k)) values.put(k, prefs.getBoolean(k, false))
        for (k in INT_KEYS) if (prefs.contains(k)) values.put(k, prefs.getInt(k, 0))
        return JSONObject()
            .put("app", "phone-whisper")
            .put("backup_version", BACKUP_VERSION)
            .put("values", values)
            .toString(2)
    }

    /** Returns the number of settings applied, or a failure with the parse error. */
    fun import(
        prefs: SharedPreferences,
        json: String,
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
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
}
