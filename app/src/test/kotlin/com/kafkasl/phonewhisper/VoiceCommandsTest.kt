package com.julianrottenberg.verbatide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandsTest {
    @Test
    fun parsesDeleteCommands() {
        assertEquals(VoiceCommands.Command.DeleteLast, VoiceCommands.parse("delete that"))
        assertEquals(VoiceCommands.Command.DeleteLast, VoiceCommands.parse("Delete that."))
        assertEquals(VoiceCommands.Command.DeleteLast, VoiceCommands.parse("  SCRATCH THAT  "))
        assertEquals(VoiceCommands.Command.DeleteLast, VoiceCommands.parse("undo that!"))
        assertEquals(VoiceCommands.Command.DeleteLast, VoiceCommands.parse("remove this"))
        assertEquals(VoiceCommands.Command.DeleteLast, VoiceCommands.parse("Delete last sentence"))
    }

    @Test
    fun parsesBreakCommands() {
        assertEquals(VoiceCommands.Command.Break("\n\n"), VoiceCommands.parse("new paragraph"))
        assertEquals(VoiceCommands.Command.Break("\n\n"), VoiceCommands.parse("Paragraph break."))
        assertEquals(VoiceCommands.Command.Break("\n"), VoiceCommands.parse("new line"))
        assertEquals(VoiceCommands.Command.Break("\n"), VoiceCommands.parse("Next line."))
    }

    @Test
    fun leavesNormalSpeechAlone() {
        assertNull(VoiceCommands.parse("please delete that file tomorrow"))
        assertNull(VoiceCommands.parse("the period of the loan is five years"))
        assertNull(VoiceCommands.parse("hello world"))
        assertNull(VoiceCommands.parse(""))
        assertNull(VoiceCommands.parse("   "))
        assertNull(VoiceCommands.parse("new paragraph please"))
    }

    @Test
    fun normalizeStripsPunctuationAndCase() {
        assertEquals("delete that", VoiceCommands.normalize("Delete that!!"))
        assertEquals("new line", VoiceCommands.normalize("  New   Line…  "))
        assertEquals("", VoiceCommands.normalize("?!"))
    }

    @Test
    fun applyInlineReplacesPunctuatedBreakPhrases() {
        assertEquals("done.\n\nmoving on", VoiceCommands.applyInline("done. new paragraph. moving on"))
        assertEquals("first.\nsecond", VoiceCommands.applyInline("first. new line. second"))
        assertEquals("first.\nsecond", VoiceCommands.applyInline("first. NEW LINE. second"))
    }

    @Test
    fun applyInlineLeavesUnpunctuatedPhrasesAlone() {
        // "new line" is common in normal prose, so without punctuation it is not a command.
        assertEquals("first new line second", VoiceCommands.applyInline("first new line second"))
        assertEquals("we'll add a new line item", VoiceCommands.applyInline("we'll add a new line item"))
        assertEquals("the new lineup shipped", VoiceCommands.applyInline("the new lineup shipped"))
    }

    @Test
    fun applyInlineCollapsesRunawayBreaks() {
        assertEquals("a.\n\nb", VoiceCommands.applyInline("a.\n\n\nb"))
    }

    @Test
    fun inlineIfEnabledRespectsToggle() {
        val text = "done. new paragraph. moving on"
        assertEquals("done.\n\nmoving on", VoiceCommands.inlineIfEnabled(true, text))
        assertEquals(text, VoiceCommands.inlineIfEnabled(false, text))
    }
}
