package com.julianrottenberg.verbatide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessorTest {

    @Test
    fun parseSuccess() {
        val json = """
        {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1677652288,
            "model": "gpt-4o-mini",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "Hello there, how are you?"
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 9,
                "completion_tokens": 12,
                "total_tokens": 21
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals("Hello there, how are you?", result.text)
        assertEquals(null, result.error)
    }

    @Test
    fun parseError() {
        val json = """
        {
            "error": {
                "message": "Incorrect API key provided.",
                "type": "invalid_request_error",
                "param": null,
                "code": "invalid_api_key"
            }
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("Incorrect API key provided.", result.error)
    }

    @Test
    fun parseEmptyChoices() {
        val json = """
        {
            "choices": []
        }
        """.trimIndent()

        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertEquals("No choices in response", result.error)
    }

    @Test
    fun parseInvalidJson() {
        val result = PostProcessor.parseResponse("invalid json")
        assertEquals(null, result.text)
        assertTrue(
            result.error?.contains("JSONObject") == true ||
                result.error?.contains("must begin with '{'") == true
        )
    }

    @Test fun `language guard appended when prompt lacks translation intent`() {
        val out = PostProcessor.withLanguageGuard("Fix punctuation.", "German")
        assertTrue(out.contains("German"))
        assertTrue(out.contains("never translate", ignoreCase = true))
    }

    @Test fun `language guard with concrete hint overrides generic no-translate line`() {
        val p = "Never translate: always respond in the same language as the input."
        val out = PostProcessor.withLanguageGuard(p, "German")
        assertTrue(out.contains("German"))
    }

    @Test fun `language guard still respects user-requested translate without a language hint`() {
        val p = "Translate this to English."
        assertEquals(p, PostProcessor.withLanguageGuard(p, null))
    }

    @Test fun `language guard generic when no hint`() {
        val out = PostProcessor.withLanguageGuard("Fix punctuation.", null)
        assertTrue(out.contains("same language", ignoreCase = true))
    }

    @Test fun `keep terms appended to prompt`() {
        val out = PostProcessor.withKeepTerms("Fix punctuation.", listOf("Kubernetes", "Schrödinger"))
        assertTrue(out.startsWith("Fix punctuation."))
        assertTrue(out.contains("Keep these spellings exactly as written", ignoreCase = true))
        assertTrue(out.contains("Kubernetes, Schrödinger"))
    }

    @Test fun `keep terms empty list leaves prompt unchanged`() {
        assertEquals("Fix punctuation.", PostProcessor.withKeepTerms("Fix punctuation.", emptyList()))
    }

    @Test fun `tone instruction appended as style directive`() {
        val out = PostProcessor.withTone("Fix punctuation.", "Rewrite in a formal, polite register.")
        assertTrue(out.startsWith("Fix punctuation."))
        assertTrue(out.contains("\n\nStyle: Rewrite in a formal, polite register."))
    }

    @Test fun `tone blank or null leaves prompt unchanged`() {
        assertEquals("Fix punctuation.", PostProcessor.withTone("Fix punctuation.", null))
        assertEquals("Fix punctuation.", PostProcessor.withTone("Fix punctuation.", ""))
        assertEquals("Fix punctuation.", PostProcessor.withTone("Fix punctuation.", "   "))
    }

    @Test fun `tone composes with language guard and keep terms in process order`() {
        // Same composition as PostProcessor.process: tone -> language guard -> keep terms.
        var p = PostProcessor.withTone("Fix punctuation.", "Be casual.")
        p = PostProcessor.withLanguageGuard(p, "German")
        p = PostProcessor.withKeepTerms(p, listOf("Verbatide"))
        assertTrue(p.contains("Style: Be casual."))
        assertTrue(p.contains("never translate", ignoreCase = true))
        assertTrue(p.contains("Verbatide"))
    }
}
