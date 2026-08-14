package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys (and only API keys) live in EncryptedSharedPreferences backed by
 * AndroidKeyStore. All other prefs stay in plain SharedPreferences so a
 * keystore failure doesn't brick the app — we fall back gracefully.
 *
 * Migration: on first use we copy any existing api_key from the plain file.
 */
object SecurePrefs {

    private const val PLAIN_NAME = "phonewhisper"
    const val KEY_API_KEY = "api_key"

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

    fun getApiKey(ctx: Context): String {
        val enc = encryptedPrefsOrNull(ctx)
        if (enc != null && enc.contains(KEY_API_KEY)) {
            return enc.getString(KEY_API_KEY, "") ?: ""
        }
        return plainPrefs(ctx).getString(KEY_API_KEY, "") ?: ""
    }

    fun putApiKey(ctx: Context, value: String) {
        val trimmed = value.trim()
        val enc = encryptedPrefsOrNull(ctx)
        if (enc != null) {
            if (trimmed.isBlank()) enc.edit().remove(KEY_API_KEY).apply()
            else enc.edit().putString(KEY_API_KEY, trimmed).apply()
            // Ensure no stale plain copy lingers.
            plainPrefs(ctx).edit().remove(KEY_API_KEY).apply()
        } else {
            // Fallback: store in plain prefs if encrypted store is unavailable.
            val plain = plainPrefs(ctx)
            if (trimmed.isBlank()) plain.edit().remove(KEY_API_KEY).apply()
            else plain.edit().putString(KEY_API_KEY, trimmed).apply()
        }
    }

    fun hasApiKey(ctx: Context): Boolean = getApiKey(ctx).isNotBlank()
}
