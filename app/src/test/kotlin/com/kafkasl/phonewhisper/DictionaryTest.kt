package com.julianrottenberg.verbatide

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryTest {

    private fun e(pattern: String, replacement: String, enabled: Boolean = true) =
        DictEntry(id = pattern.hashCode().toLong(), pattern = pattern, replacement = replacement, enabled = enabled)

    // --- replacement pass -------------------------------------------------

    @Test fun replacesCaseInsensitivelyAsWholeWord() {
        val out = DictionaryManager.rewriteWith(listOf(e("verbatide", "Verbatide")), "ich nutze VERBATIDE heute")
        assertEquals("ich nutze Verbatide heute", out)
    }

    @Test fun doesNotMatchInsideAnotherWord() {
        // "Bär" must not match the start of "Bären" (ä/e are letters, so no boundary).
        val out = DictionaryManager.rewriteWith(listOf(e("Bär", "Bear")), "Bären Bär")
        assertEquals("Bären Bear", out)
    }

    @Test fun umlautIsTreatedAsLetterAtBoundary() {
        val out = DictionaryManager.rewriteWith(listOf(e("Wort", "X")), "Wörtern Wort")
        assertEquals("Wörtern X", out)
    }

    @Test fun longestPatternWinsAtSamePosition() {
        val out = DictionaryManager.rewriteWith(
            listOf(e("york", "York"), e("new york", "New York")),
            "new york city",
        )
        assertEquals("New York city", out)
    }

    @Test fun phraseMatchesAnyWhitespaceRun() {
        val out = DictionaryManager.rewriteWith(listOf(e("claude code", "Claude Code")), "I use claude   code daily")
        assertEquals("I use Claude Code daily", out)
    }

    @Test fun regexMetacharactersAreLiteral() {
        val out = DictionaryManager.rewriteWith(listOf(e("c++", "C++")), "use c++ now")
        assertEquals("use C++ now", out)
    }

    @Test fun replacementIsNotRewrittenAgain() {
        // a->b must not then chain into b->c.
        val out = DictionaryManager.rewriteWith(listOf(e("a", "b"), e("b", "c")), "a b")
        assertEquals("b c", out)
    }

    @Test fun dollarInReplacementIsLiteral() {
        val out = DictionaryManager.rewriteWith(listOf(e("dollar", "\$5")), "one dollar")
        assertEquals("one \$5", out)
    }

    @Test fun disabledAndBlankReplacementEntriesAreIgnored() {
        val out = DictionaryManager.rewriteWith(
            listOf(e("alpha", "ALPHA", enabled = false), e("beta", "")),
            "alpha beta",
        )
        assertEquals("alpha beta", out)
    }

    // --- Mistral context_bias ---------------------------------------------

    @Test fun contextBiasJoinsWordsAndDropsOverLongTerms() {
        val out = DictionaryManager.contextBiasTerms(
            listOf("Claude Code", "a,b", "x".repeat(81), "Kubernetes"),
        )
        assertEquals(listOf("Claude_Code", "a_b", "Kubernetes"), out)
    }

    @Test fun contextBiasCapsAtOneHundredTerms() {
        val terms = (1..150).map { "term$it" }
        assertEquals(100, DictionaryManager.contextBiasTerms(terms).size)
    }

    // --- Whisper prompt ---------------------------------------------------

    @Test fun vocabularyPromptCutsAtTermBoundary() {
        assertEquals("aaaa", DictionaryManager.vocabularyPrompt(listOf("aaaa", "bbbb", "cccc"), limitChars = 10))
    }

    @Test fun vocabularyPromptIsPlainTermListWithoutEnglishLabel() {
        assertEquals("Kubernetes, Verbatide", DictionaryManager.vocabularyPrompt(listOf("Kubernetes", "Verbatide")))
    }

    @Test fun promptStyleGatesToDocumentedEndpoints() {
        assertEquals(DictionaryManager.PromptStyle.PROMPT_FIELD,
            DictionaryManager.promptStyleFor("https://api.openai.com/v1/audio/transcriptions"))
        assertEquals(DictionaryManager.PromptStyle.PROMPT_FIELD,
            DictionaryManager.promptStyleFor("https://api.groq.com/openai/v1/audio/transcriptions"))
        assertEquals(DictionaryManager.PromptStyle.CONTEXT_BIAS,
            DictionaryManager.promptStyleFor("https://api.mistral.ai/v1/audio/transcriptions"))
        assertEquals(DictionaryManager.PromptStyle.NONE,
            DictionaryManager.promptStyleFor("https://api.together.xyz/v1/audio/transcriptions"))
    }

    @Test fun stripPromptEchoRemovesExactEchoOnly() {
        assertEquals("", DictionaryManager.stripPromptEcho("Kubernetes, Verbatide", "Kubernetes, Verbatide"))
        assertEquals("hello Kubernetes", DictionaryManager.stripPromptEcho("hello Kubernetes", "Kubernetes, Verbatide"))
    }
}
