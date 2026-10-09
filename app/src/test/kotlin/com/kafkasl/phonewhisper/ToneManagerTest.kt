package com.julianrottenberg.verbatide

import org.junit.Assert.assertEquals
import org.junit.Test

class ToneManagerTest {
    @Test
    fun unknownKeyFallsBackToDefault() {
        assertEquals(ToneManager.TONES[0], ToneManager.tone("does-not-exist"))
    }

    @Test
    fun defaultToneHasNoInstruction() {
        assertEquals("", ToneManager.tone("default").instruction)
        assertEquals("", ToneManager.TONES[0].instruction)
    }

    @Test
    fun everyNonDefaultToneHasAnInstruction() {
        ToneManager.TONES.drop(1).forEach { tone ->
            assert(tone.instruction.isNotBlank()) { "tone ${tone.key} needs an instruction" }
        }
    }

    @Test
    fun toneKeysAreUnique() {
        assertEquals(
            ToneManager.TONES.size,
            ToneManager.TONES
                .map { it.key }
                .distinct()
                .size,
        )
    }

    @Test
    fun toneForFindsMapping() {
        val mappings = listOf(AppToneMapping("com.slack", "Slack", "casual"))
        assertEquals("casual", ToneManager.toneFor(mappings, "com.slack").key)
    }

    @Test
    fun toneForFallsBackForUnknownOrNullPackage() {
        val mappings = listOf(AppToneMapping("com.slack", "Slack", "casual"))
        assertEquals(ToneManager.TONES[0], ToneManager.toneFor(mappings, "com.gmail"))
        assertEquals(ToneManager.TONES[0], ToneManager.toneFor(mappings, null))
        assertEquals(ToneManager.TONES[0], ToneManager.toneFor(emptyList(), "com.slack"))
    }

    @Test
    fun toneForWithUnknownStoredKeyFallsBackToDefault() {
        // Hand-edited or stale JSON could reference a tone that no longer exists.
        val mappings = listOf(AppToneMapping("com.slack", "Slack", "removed-tone"))
        assertEquals(ToneManager.TONES[0], ToneManager.toneFor(mappings, "com.slack"))
    }
}
