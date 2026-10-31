package com.julianrottenberg.verbatide

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys (and only API keys) live in EncryptedSharedPreferences backed by
 * AndroidKeyStore. All other prefs stay in plain SharedPreferences so a
 * keystore failure doesn't brick the app — we fall back gracefully, but the
 * Settings screen says so, because "silently unencrypted" is a bad surprise.
 *
 * Two separate STT / chat keys so users can mix providers with different
 * credentials (e.g. fal.ai for transcription + Together AI for cleanup).
 *
 * Key resolution order for a provider:
 *   1. its own slot (`stt_api_key_mistral`)
 *   2. the shared purpose slot (`stt_api_key`) — only when stamped with that
 *      provider's name. The shared slot used to belong to whichever provider
 *      was selected; without the owner stamp, reordering the chain would send
 *      the old primary's credential to the new one (a guaranteed 401 and a
 *      24 h health block).
 *   3. nothing. Callers skip providers without a key.
 *
 * Migration: legacy `api_key`, purpose keys and per-provider keys are moved out
 * of the plain file on first use, and the legacy global key is folded into the
 * selected providers' slots and then deleted, so a later "clear key" cannot be
 * undone by the legacy fallback.
 */
object SecurePrefs {
    private const val PLAIN_NAME = "phonewhisper"
    const val KEY_API_KEY = "api_key"
    const val KEY_STT_API_KEY = "stt_api_key"
    const val KEY_CHAT_API_KEY = "chat_api_key"

    private const val OWNER_SUFFIX = "_owner"

    // Encrypted file name — keep it distinct from the plain one.
    private const val ENCRYPTED_NAME = "phonewhisper_secure"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var encryptedUnavailable = false

    fun plainPrefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)

    /**
     * Returns EncryptedSharedPreferences, or null if the device can't provide
     * it (no StrongBox, keystore init failure, etc.). Caller should fall back
     * to plain prefs in that case so dictation still works.
     */
    fun encryptedPrefsOrNull(ctx: Context): SharedPreferences? {
        encryptedPrefs?.let { return it }
        if (encryptedUnavailable) return null
        return try {
            val masterKey =
                MasterKey
                    .Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val created =
                EncryptedSharedPreferences.create(
                    ctx,
                    ENCRYPTED_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            migrateIfNeeded(ctx, created)
            encryptedPrefs = created
            created
        } catch (_: Exception) {
            // Keystore unavailable (emulator without secure storage, etc.)
            encryptedUnavailable = true
            null
        }
    }

    /** False when keys are being stored in the plain prefs file. Surfaced in Settings. */
    fun isEncrypted(ctx: Context): Boolean = encryptedPrefsOrNull(ctx) != null

    private fun migrateIfNeeded(
        ctx: Context,
        encrypted: SharedPreferences,
    ) {
        val plain = plainPrefs(ctx)
        val plainValues = runCatching { plain.all }.getOrDefault(emptyMap())

        // 1. Every *_api_key* value still sitting in the plain file gets moved.
        //    Not just the global one: keys typed while the keystore was broken
        //    would otherwise be stranded there and ignored once it recovers.
        val stolen = plainValues.filterKeys { it.contains("api_key") }
        if (stolen.isNotEmpty()) {
            val edit = encrypted.edit()
            stolen.forEach { (k, v) -> if (v is String && v.isNotBlank() && !encrypted.contains(k)) edit.putString(k, v) }
            edit.apply()
            val cleanup = plain.edit()
            stolen.keys.forEach { cleanup.remove(it) }
            cleanup.apply()
        }

        // 2. Fold the legacy single key into the selected providers' slots, then
        //    delete it. Keeping it around meant clearing one provider's key
        //    silently resurrected the old one through the legacy fallback.
        val legacy = encrypted.getString(KEY_API_KEY, "")?.takeIf { it.isNotBlank() } ?: return
        val edit = encrypted.edit()
        ProviderConfig.selectedStt(plain).let { p ->
            val slot = sttKeyFor(p)
            if (!encrypted.contains(slot)) {
                edit.putString(slot, legacy)
                if (encrypted.getString(KEY_STT_API_KEY, "").isNullOrBlank()) {
                    edit.putString(KEY_STT_API_KEY, legacy)
                    edit.putString(KEY_STT_API_KEY + OWNER_SUFFIX, p.name)
                }
            }
        }
        ProviderConfig.selectedChat(plain).let { p ->
            val slot = chatKeyFor(p)
            if (!encrypted.contains(slot)) {
                edit.putString(slot, legacy)
                if (encrypted.getString(KEY_CHAT_API_KEY, "").isNullOrBlank()) {
                    edit.putString(KEY_CHAT_API_KEY, legacy)
                    edit.putString(KEY_CHAT_API_KEY + OWNER_SUFFIX, p.name)
                }
            }
        }
        edit.remove(KEY_API_KEY).apply()
    }

    // Legacy single-key API — kept for tests and any external callers that
    // assumed one key. Reads/writes the shared default slot.
    fun getApiKey(ctx: Context): String = getKey(ctx, KEY_API_KEY)

    fun putApiKey(
        ctx: Context,
        value: String,
    ) = putKey(ctx, KEY_API_KEY, value)

    fun hasApiKey(ctx: Context): Boolean = getApiKey(ctx).isNotBlank()

    /** Reads [key] with the legacy `api_key` fallback for the two purpose slots. */
    fun getKey(
        ctx: Context,
        key: String,
    ): String {
        val enc = encryptedPrefsOrNull(ctx)
        if (enc != null) {
            try {
                if (enc.contains(key)) return enc.getString(key, "") ?: ""
                if ((key == KEY_STT_API_KEY || key == KEY_CHAT_API_KEY) && enc.contains(KEY_API_KEY)) {
                    return enc.getString(KEY_API_KEY, "") ?: ""
                }
                return ""
            } catch (_: Exception) {
                // Unreadable encrypted value: retry the store next time, and read
                // the plain file now so a restore does not crash the service.
                encryptedPrefs = null
            }
        }
        val plain = plainPrefs(ctx)
        return try {
            plain.getString(key, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** No legacy fallback: "the slot as stored", used internally. */
    private fun getRaw(
        ctx: Context,
        key: String,
    ): String {
        val enc = encryptedPrefsOrNull(ctx)
        if (enc != null) {
            try {
                return enc.getString(key, "") ?: ""
            } catch (_: Exception) {
                // Keystore value unreadable under us (restored app data, reset
                // key). Drop the handle so the next call retries creation
                // instead of downgrading every later read to plaintext.
                encryptedPrefs = null
            }
        }
        return try {
            plainPrefs(ctx).getString(key, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun putKey(
        ctx: Context,
        key: String,
        value: String,
    ) {
        val trimmed = value.trim()
        val enc = encryptedPrefsOrNull(ctx)
        if (enc != null) {
            try {
                if (trimmed.isBlank()) {
                    enc.edit().remove(key).apply()
                } else {
                    enc.edit().putString(key, trimmed).apply()
                }
                plainPrefs(ctx).edit().remove(key).apply()
                return
            } catch (_: Exception) {
                // Write failed: keep the value in plain prefs rather than losing
                // it, and drop the handle so the next call retries the keystore.
                encryptedPrefs = null
            }
        }
        val plain = plainPrefs(ctx)
        if (trimmed.isBlank()) {
            plain.edit().remove(key).apply()
        } else {
            plain.edit().putString(key, trimmed).apply()
        }
    }

    /**
     * Per-provider key slots, so a fallback provider gets its own credential
     * instead of sharing (and overwriting) the primary's.
     */
    fun sttKeyFor(provider: Provider): String = "stt_api_key_" + provider.name.lowercase()

    fun chatKeyFor(provider: Provider): String = "chat_api_key_" + provider.name.lowercase()

    fun getSttApiKey(
        ctx: Context,
        provider: Provider? = null,
    ): String = getScopedKey(ctx, KEY_STT_API_KEY, provider?.let(::sttKeyFor), provider)

    fun putSttApiKey(
        ctx: Context,
        value: String,
        provider: Provider? = null,
    ) = putScopedKey(ctx, KEY_STT_API_KEY, provider?.let(::sttKeyFor), provider, value)

    fun getChatApiKey(
        ctx: Context,
        provider: Provider? = null,
    ): String = getScopedKey(ctx, KEY_CHAT_API_KEY, provider?.let(::chatKeyFor), provider)

    fun putChatApiKey(
        ctx: Context,
        value: String,
        provider: Provider? = null,
    ) = putScopedKey(ctx, KEY_CHAT_API_KEY, provider?.let(::chatKeyFor), provider, value)

    fun hasSttApiKey(
        ctx: Context,
        provider: Provider? = null,
    ): Boolean = getSttApiKey(ctx, provider).isNotBlank()

    fun hasChatApiKey(
        ctx: Context,
        provider: Provider? = null,
    ): Boolean = getChatApiKey(ctx, provider).isNotBlank()

    /** True when this provider can work at all — either stage having a key is enough. */
    fun hasApiKey(
        ctx: Context,
        provider: Provider,
    ): Boolean = hasSttApiKey(ctx, provider) || hasChatApiKey(ctx, provider)

    /** Removes both credentials for a provider, e.g. when the user strips it from a chain. */
    fun forgetProvider(
        ctx: Context,
        provider: Provider,
    ) {
        putSttApiKey(ctx, "", provider)
        putChatApiKey(ctx, "", provider)
    }

    private fun getScopedKey(
        ctx: Context,
        purposeKey: String,
        providerKey: String?,
        provider: Provider?,
    ): String {
        if (provider == null || providerKey == null) return getKey(ctx, purposeKey)
        val scoped = getKey(ctx, providerKey)
        if (scoped.isNotBlank()) return scoped
        // Shared slot is only trusted when stamped with this provider's name.
        val owner = getRaw(ctx, purposeKey + OWNER_SUFFIX)
        if (owner.isNotBlank() && owner.equals(provider.name, ignoreCase = true)) {
            return getRaw(ctx, purposeKey)
        }
        return ""
    }

    private fun putScopedKey(
        ctx: Context,
        purposeKey: String,
        providerKey: String?,
        provider: Provider?,
        value: String,
    ) {
        if (providerKey == null || provider == null) {
            putKey(ctx, purposeKey, value)
            return
        }
        putKey(ctx, providerKey, value)
        val owner = getRaw(ctx, purposeKey + OWNER_SUFFIX)
        val ownsSharedSlot = owner.isNotBlank() && owner.equals(provider.name, ignoreCase = true)
        if (value.isBlank()) {
            // A cleared field must actually clear. Leaving the shared slot (or
            // the legacy key) behind meant reads resurrected the credential.
            if (ownsSharedSlot) {
                putKey(ctx, purposeKey, "")
                putKey(ctx, purposeKey + OWNER_SUFFIX, "")
            }
            putKey(ctx, KEY_API_KEY, "")
            return
        }
        // Mirror into the shared slot only while it is empty, and stamp it so
        // another provider can never inherit this credential.
        if (getRaw(ctx, purposeKey).isBlank() || ownsSharedSlot) {
            putKey(ctx, purposeKey, value)
            putKey(ctx, purposeKey + OWNER_SUFFIX, provider.name)
        }
    }

    /**
     * Provider errors get persisted (health) and copied into the diagnostic
     * log. Some providers echo request credentials back in the message, so
     * strip anything key-shaped before it lands on disk.
     */
    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace(Regex("""\b(sk|gsk|xai|pplx|hf)[-_][A-Za-z0-9_\-]{8,}"""), "[redacted]")
            .replace(Regex("""eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""), "[redacted]")
    }
}
