package com.julianrottenberg.verbatide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Retention and the opt-in key export are the two places where this feature can
 * quietly do the wrong thing — deleting audio that is still referenced, or
 * writing keys into a file the user meant to share. Both are pinned here.
 */
class RetentionAndBackupTest {
    // --- audio retention ---------------------------------------------------

    private fun tempDir(): File =
        File.createTempFile("rec", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    private fun wav(
        dir: File,
        id: String,
        ageMs: Long = 0,
    ) = File(dir, "$id.wav").apply {
        writeBytes(ByteArray(16))
        setLastModified(System.currentTimeMillis() - ageMs)
    }

    @Test fun pruneKeepsReferencedFiles() {
        val dir = tempDir()
        val keep = wav(dir, "a")
        val orphan = wav(dir, "b")
        RecordingStore.pruneDir(dir, listOf("a"), 10)
        assertTrue(keep.isFile)
        assertFalse(orphan.isFile)
        dir.deleteRecursively()
    }

    @Test fun pruneDropsOldestBeyondTheCap() {
        val dir = tempDir()
        val old = wav(dir, "old", ageMs = 60_000)
        val mid = wav(dir, "mid", ageMs = 30_000)
        val new = wav(dir, "new", ageMs = 0)
        RecordingStore.pruneDir(dir, listOf("old", "mid", "new"), 2)
        assertFalse(old.isFile)
        assertTrue(mid.isFile)
        assertTrue(new.isFile)
        dir.deleteRecursively()
    }

    @Test fun pruneIgnoresNonWavFiles() {
        val dir = tempDir()
        val stray = File(dir, "notes.txt").apply { writeText("x") }
        val kept = wav(dir, "a")
        RecordingStore.pruneDir(dir, listOf("a"), 10)
        assertTrue(stray.isFile)
        assertTrue(kept.isFile)
        dir.deleteRecursively()
    }

    @Test fun pruneWithEmptyHistoryRemovesEverything() {
        val dir = tempDir()
        val a = wav(dir, "a")
        RecordingStore.pruneDir(dir, emptyList(), 10)
        assertFalse(a.isFile)
        dir.deleteRecursively()
    }

    // --- settings export ---------------------------------------------------

    private fun prefs() = FakePrefs(mapOf("stt_provider" to "mistral", "post_processing_prompt" to "clean up"))

    @Test fun exportOmitsKeysByDefault() {
        val json = SettingsBackup.export(prefs())
        assertFalse(json.contains("secrets"))
        assertFalse(json.contains("api_key"))
        assertTrue(json.contains("post_processing_prompt"))
    }

    @Test fun exportIncludesKeysWhenAsked() {
        val json = SettingsBackup.export(prefs(), mapOf("stt_api_key_mistral" to "sk-123", "chat_api_key_mistral" to " "))
        assertTrue(json.contains("stt_api_key_mistral"))
        assertTrue(json.contains("sk-123"))
        // Blank keys are dropped rather than written as empty strings.
        assertFalse(json.contains("chat_api_key_mistral"))
    }

    @Test fun importRoutesSecretsThroughTheCallback() {
        val json =
            """
            {"app":"phone-whisper","backup_version":3,
             "values":{"stt_provider":"groq"},
             "secrets":{"stt_api_key_groq":"sk-groq","evil_pref":"boom"}}
            """.trimIndent()
        val target = FakePrefs()
        val seen = LinkedHashMap<String, String>()
        val result = SettingsBackup.import(target, json) { k, v -> seen[k] = v }
        assertTrue(result.isSuccess)
        assertEquals("groq", target.getString("stt_provider", null))
        assertEquals(mapOf("stt_api_key_groq" to "sk-groq"), seen)
    }

    @Test fun importWithoutCallbackIgnoresSecrets() {
        val json = """{"values":{"stt_provider":"groq"},"secrets":{"api_key":"sk"}}"""
        val target = FakePrefs()
        assertTrue(SettingsBackup.import(target, json).isSuccess)
        assertNull(target.getString("api_key", null))
        assertEquals("groq", target.getString("stt_provider", null))
    }

    @Test fun secretKeyNamesAreValidated() {
        assertTrue(SettingsBackup.isSecretKey("api_key"))
        assertTrue(SettingsBackup.isSecretKey("stt_api_key_mistral"))
        assertTrue(SettingsBackup.isSecretKey("chat_api_key_custom"))
        assertFalse(SettingsBackup.isSecretKey("stt_provider"))
        assertFalse(SettingsBackup.isSecretKey("api_key_"))
        assertFalse(SettingsBackup.isSecretKey("api_key_X;B"))
    }

    @Test fun garbageJsonFailsWithoutThrowing() {
        assertTrue(SettingsBackup.import(FakePrefs(), "not json").isFailure)
    }
}
