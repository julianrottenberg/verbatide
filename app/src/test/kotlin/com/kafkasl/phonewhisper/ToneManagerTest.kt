package com.julianrottenberg.verbatide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun everyNonDefaultToneHasAnInstructionAndDescription() {
        ToneManager.TONES.drop(1).forEach { tone ->
            assert(tone.instruction.isNotBlank()) { "tone ${tone.key} needs an instruction" }
            assert(tone.description.isNotBlank()) { "tone ${tone.key} needs a description" }
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

    @Test
    fun toneForResolvesCustomToneKeys() {
        val custom = Tone("custom_sarcasm_ab12", "Sarcasm", "Custom tone.", "Be sarcastic.", isCustom = true)
        val mappings = listOf(AppToneMapping("com.slack", "Slack", custom.key))
        assertEquals(custom, ToneManager.toneFor(mappings, "com.slack", ToneManager.TONES + custom))
        // Without the custom tones list the same key falls back to default.
        assertEquals(ToneManager.TONES[0], ToneManager.toneFor(mappings, "com.slack"))
    }

    @Test
    fun makeCustomKeySlugsLabel() {
        val key = ToneManager.makeCustomKey("My Fancy Tone!", "Do things.")
        assertTrue(key.startsWith("custom_my-fancy-tone_"))
        assertTrue(key.removePrefix("custom_my-fancy-tone_").isNotBlank())
    }

    @Test
    fun makeCustomKeyHandlesBlankLabel() {
        assertTrue(ToneManager.makeCustomKey("!!!", "x").startsWith("custom_tone_"))
    }

    @Test
    fun resolveTonesAppliesOverrideToBuiltIn() {
        val overrides = """{"formal":"Be extra fancy."}"""
        val tones = ToneManager.resolveTones(null, overrides)
        assertEquals("Be extra fancy.", tones.first { it.key == "formal" }.instruction)
        // Untouched tones keep their default instruction.
        assertEquals(ToneManager.tone("casual").instruction, tones.first { it.key == "casual" }.instruction)
    }

    @Test
    fun resolveTonesIgnoresUnknownOverrideKeys() {
        val tones = ToneManager.resolveTones(null, """{"nope":"x"}""")
        assertEquals(ToneManager.TONES, tones)
    }

    @Test
    fun resolveTonesAppendsCustomsAfterBuiltIns() {
        val customs = """[{"key":"custom_a_1","label":"A","instruction":"Do A."}]"""
        val tones = ToneManager.resolveTones(customs, null)
        assertEquals(ToneManager.TONES.size + 1, tones.size)
        assertTrue(tones.last().isCustom)
        assertEquals("Do A.", tones.last().instruction)
    }

    @Test
    fun resolveTonesSurvivesCorruptJson() {
        assertEquals(ToneManager.TONES, ToneManager.resolveTones("{oops", "[not json"))
    }

    @Test
    fun pruneToneFromMappingsDropsOnlyThatTone() {
        val mappings =
            listOf(
                AppToneMapping("com.slack", "Slack", "casual"),
                AppToneMapping("com.gmail", "Gmail", "formal"),
            )
        val pruned = ToneManager.pruneToneFromMappings(mappings, "casual")
        assertEquals(listOf("com.gmail"), pruned.map { it.packageName })
    }

    @Test
    fun parseCustomTonesReadsBackWhatWasWritten() {
        val json = """[{"key":"custom_a_1","label":"A","instruction":"Do A."}]"""
        val tones = ToneManager.parseCustomTones(json)
        assertEquals(1, tones.size)
        assertTrue(tones[0].isCustom)
        assertEquals("A", tones[0].label)
        assertFalse(tones[0].instruction.isBlank())
    }
}
