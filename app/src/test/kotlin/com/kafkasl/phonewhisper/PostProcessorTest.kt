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

    // --- Reasoning wire format gating ---

    private fun bodyWithReasoning(chatUrl: String, reasoning: PostProcessor.Reasoning): org.json.JSONObject {
        val body = org.json.JSONObject()
        PostProcessor.applyReasoning(body, chatUrl, reasoning)
        return body
    }

    @Test fun `reasoning off sends nothing to strict providers`() {
        // Mistral 422s on unknown fields (extra_forbidden) — the cleanup regression.
        assertEquals(0, bodyWithReasoning("https://api.mistral.ai/v1/chat/completions", PostProcessor.Reasoning.OFF).length())
        assertEquals(0, bodyWithReasoning("https://api.openai.com/v1/chat/completions", PostProcessor.Reasoning.OFF).length())
        assertEquals(0, bodyWithReasoning("https://api.groq.com/openai/v1/chat/completions", PostProcessor.Reasoning.OFF).length())
        assertEquals(0, bodyWithReasoning("https://api.venice.ai/api/v1/chat/completions", PostProcessor.Reasoning.OFF).length())
        assertEquals(0, bodyWithReasoning("https://selfhosted.example.com/v1/chat/completions", PostProcessor.Reasoning.OFF).length())
    }

    @Test fun `reasoning off sent where supported`() {
        val or = bodyWithReasoning("https://openrouter.ai/api/v1/chat/completions", PostProcessor.Reasoning.OFF)
        assertEquals(false, or.getJSONObject("reasoning").getBoolean("enabled"))
        val tg = bodyWithReasoning("https://api.together.ai/v1/chat/completions", PostProcessor.Reasoning.OFF)
        assertEquals(false, tg.getJSONObject("reasoning").getBoolean("enabled"))
    }

    @Test fun `reasoning effort wire format per provider`() {
        val or = bodyWithReasoning("https://openrouter.ai/api/v1/chat/completions", PostProcessor.Reasoning.LOW)
        assertEquals("low", or.getJSONObject("reasoning").getString("effort"))
        val oa = bodyWithReasoning("https://api.openai.com/v1/chat/completions", PostProcessor.Reasoning.HIGH)
        assertEquals("high", oa.getString("reasoning_effort"))
        val gq = bodyWithReasoning("https://api.groq.com/openai/v1/chat/completions", PostProcessor.Reasoning.MEDIUM)
        assertEquals("medium", gq.getString("reasoning_effort"))
        val tg = bodyWithReasoning("https://api.together.ai/v1/chat/completions", PostProcessor.Reasoning.LOW)
        assertEquals("low", tg.getString("reasoning_effort"))
        // Unknown/strict endpoints get nothing rather than a request-killing 422.
        assertEquals(0, bodyWithReasoning("https://api.mistral.ai/v1/chat/completions", PostProcessor.Reasoning.LOW).length())
        assertEquals(0, bodyWithReasoning("https://api.nano-gpt.com/api/v1/chat/completions", PostProcessor.Reasoning.LOW).length())
    }

    @Test fun `reasoning default sends nothing`() {
        assertEquals(0, bodyWithReasoning("https://openrouter.ai/api/v1/chat/completions", PostProcessor.Reasoning.DEFAULT).length())
    }

    // --- Mistral error shape ---

    @Test fun `parse mistral 422 extra_forbidden detail`() {
        val json = """
        {"object":"error","message":{"detail":[{"type":"extra_forbidden","loc":["body","reasoning"],"msg":"Extra inputs are not permitted","input":{"enabled":false}}]},"type":"invalid_request_error","param":null,"code":null,"raw_status_code":422}
        """.trimIndent()
        val result = PostProcessor.parseResponse(json)
        assertEquals(null, result.text)
        assertTrue(result.error!!.contains("Extra inputs are not permitted"))
        assertTrue(result.error!!.contains("body.reasoning"))
    }

    @Test fun `parse mistral plain message error`() {
        val result = PostProcessor.parseResponse("""{"object":"error","message":"Unauthorized","type":"invalid_request_error"}""")
        assertEquals("Unauthorized", result.error)
    }
}
