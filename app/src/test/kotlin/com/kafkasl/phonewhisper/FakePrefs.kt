package com.julianrottenberg.verbatide

import android.content.SharedPreferences

/**
 * In-memory SharedPreferences.
 *
 * The provider/fallback logic is pure preference bookkeeping, but it is the
 * part where a wrong chain silently sends dictation to the wrong account — so
 * it gets tested for real instead of being eyeballed. Android's own interface
 * is enough: unit tests run against the stub android.jar, and implementing the
 * interface keeps the values live instead of throwing "not mocked".
 */
class FakePrefs(
    initial: Map<String, Any?> = emptyMap(),
) : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>(initial)

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        val stored = values[key] as? Set<String> ?: return defValues
        return LinkedHashSet(stored)
    }

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = LinkedHashSet<String>()
        private var wipe = false

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor = stash(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor =
            stash(
                key,
                values?.let {
                    LinkedHashSet(it)
                },
            )

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = stash(key, value)

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = stash(key, value)

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = stash(key, value)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = stash(key, value)

        override fun remove(key: String): SharedPreferences.Editor =
            apply {
                pending.remove(key)
                removed += key
            }

        override fun clear(): SharedPreferences.Editor = apply { wipe = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (wipe) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
        }

        private fun stash(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor =
            apply {
                pending[key] = value
                removed -= key
            }
    }
}
