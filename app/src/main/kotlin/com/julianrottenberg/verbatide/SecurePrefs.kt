package com.julianrottenberg.verbatide

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys (and only API keys) live in EncryptedSharedPreferences backed by
 * AndroidKeyStore. All other prefs stay in plain SharedPreferences so a
 * keystore failure doesn't brick the app — we fall back gracefully.
 *
 * Two separate STT / chat keys so users can mix providers with different
 * credentials (e.g. fal.ai for transcription + Together AI for cleanup).
 * Legacy `api_key` is used as a fallback/migration source for both.
 *
 * Migration: on first use we copy any existing api_key from the plain file.
 */
object SecurePrefs {

    private const val PLAIN_NAME = "phonewhisper"
    const val KEY_API_KEY = "api_key"
    const val KEY_STT_API_KEY = "stt_api_key"
    const val KEY_CHAT_API_KEY = "chat_api_key"

    // Encrypted file name — keep it distinct from the plain one.
    private const val ENCRYPTED_NAME = "phonewhisper_secure"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var encryptedUnavailable = false

    fun plainPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)

    /**
     * Returns EncryptedSharedPreferences, or null if the device can't provide
     * it (no StrongBox, keystore init failure, etc.). Caller should fall back
     * to plain prefs in that case so dictation still works.
     */
    fun encryptedPrefsOrNull(ctx: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        if (encryptedUnavailable) return null
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                ctx,
                ENCRYPTED_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            // One-time migration: move api_key out of the plain file if present.
            migrateIfNeeded(ctx, created)
            encryptedPrefs = created
            created
        } catch (_: Exception) {
            // Keystore unavailable (emulator without secure storage, etc.)
            encryptedUnavailable = true
            null
        }
    }

    private fun migrateIfNeeded(ctx: Context, encrypted: SharedPreferences) {
        val plain = plainPrefs(ctx)
        val existing = plain.getString(KEY_API_KEY, null) ?: return
        if (existing.isBlank()) return
        if (encrypted.contains(KEY_API_KEY)) {
            // Already migrated; just clean up the plain copy.
            plain.edit().remove(KEY_API_KEY).apply()
            return
        }
        encrypted.edit().putString(KEY_API_KEY, existing).apply()
        plain.edit().remove(KEY_API_KEY).apply()
    }

    // Legacy single-key API — kept for tests and any external callers that
    // assumed one key. Reads/writes the shared default key.
    fun getApiKey(ctx: Context): String = getKey(ctx, KEY_API_KEY)

    fun putApiKey(ctx: Context, value: String) = putKey(ctx, KEY_API_KEY, value)

    fun hasApiKey(ctx: Context): Boolean = getApiKey(ctx).isNotBlank()

    fun getKey(ctx: Context, key: String): String {
        val enc = encryptedPrefsOrNull(ctx)
        if (enc != null) {
            // Per-purpose key present?
            if (enc.contains(key)) return enc.getString(key, "") ?: ""
            // Fall back to the legacy single key if the caller asked for a
            // per-purpose key but only the legacy one exists (migration).
            if ((key == KEY_STT_API_KEY || key == KEY_CHAT_API_KEY) && enc.contains(KEY_API_KEY)) {
                return enc.getString(KEY_API_KEY, "") ?: ""
            }
            return enc.getString(key, "") ?: ""
        }
        // No encrypted store — fall back to plain prefs (same logic).
        val plain = plainPrefs(ctx)
        if (plain.contains(key)) return plain.getString(key, "") ?: ""
        if ((key == KEY_STT_API_KEY || key == KEY_CHAT_API_KEY) && plain.contains(KEY_API_KEY)) {
            return plain.getString(KEY_API_KEY, "") ?: ""
        }
        return plain.getString(key, "") ?: ""
    }

    fun putKey(ctx: Context, key: String, value: String) {
        val trimmed = value.trim()
        val enc = encryptedPrefsOrNull(ctx)
        if (enc != null) {
            if (trimmed.isBlank()) enc.edit().remove(key).apply()
            else enc.edit().putString(key, trimmed).apply()
            plainPrefs(ctx).edit().remove(key).apply()
        } else {
            val plain = plainPrefs(ctx)
            if (trimmed.isBlank()) plain.edit().remove(key).apply()
            else plain.edit().putString(key, trimmed).apply()
        }
    }

    fun getSttApiKey(ctx: Context): String = getKey(ctx, KEY_STT_API_KEY)
    fun putSttApiKey(ctx: Context, value: String) = putKey(ctx, KEY_STT_API_KEY, value)
    fun getChatApiKey(ctx: Context): String = getKey(ctx, KEY_CHAT_API_KEY)
    fun putChatApiKey(ctx: Context, value: String) = putKey(ctx, KEY_CHAT_API_KEY, value)
    fun hasSttApiKey(ctx: Context): Boolean = getSttApiKey(ctx).isNotBlank()
    fun hasChatApiKey(ctx: Context): Boolean = getChatApiKey(ctx).isNotBlank()
}
