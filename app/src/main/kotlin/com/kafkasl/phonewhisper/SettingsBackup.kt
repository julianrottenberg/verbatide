package com.kafkasl.phonewhisper

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Export/import of user settings as JSON.
 *
 * The API key is deliberately excluded: it lives in EncryptedSharedPreferences
 * and should never land in a plaintext file the user might share or sync.
 *
 * Key names must stay in sync with ProviderConfig/MainActivity pref keys.
 */
object SettingsBackup {

    const val BACKUP_VERSION = 1

    private val STRING_KEYS = listOf(
        "provider",
        "custom_stt_base_url",
        "custom_stt_model",
        "custom_chat_base_url",
        "custom_chat_model",
        "post_processing_prompt",
        "custom_post_processing_prompt",
        "reasoning_effort",
        "stt_language",
    )

    private val BOOL_KEYS = listOf(
        "use_local",
        "use_post_processing",
    )

    fun export(prefs: SharedPreferences): String {
        val values = JSONObject()
        for (k in STRING_KEYS) prefs.getString(k, null)?.let { values.put(k, it) }
        for (k in BOOL_KEYS) if (prefs.contains(k)) values.put(k, prefs.getBoolean(k, false))
        return JSONObject()
            .put("app", "phone-whisper")
            .put("backup_version", BACKUP_VERSION)
            .put("values", values)
            .toString(2)
    }

    /** Returns the number of settings applied, or a failure with the parse error. */
    fun import(prefs: SharedPreferences, json: String): Result<Int> {
        return try {
            val root = JSONObject(json)
            // Tolerate a bare {key: value} object too, not just the wrapped format.
            val values = root.optJSONObject("values") ?: root
            val editor = prefs.edit()
            var count = 0
            for (k in STRING_KEYS) if (values.has(k)) { editor.putString(k, values.getString(k)); count++ }
            for (k in BOOL_KEYS) if (values.has(k)) { editor.putBoolean(k, values.getBoolean(k)); count++ }
            editor.apply()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
