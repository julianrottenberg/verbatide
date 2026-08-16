package com.julianrottenberg.verbatide

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `parses verbose_json language field`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hallo Welt", "language": "german"}""")
        assertEquals("Hallo Welt", r.text)
        assertEquals("german", r.language)
    }

    @Test fun `language is null when absent`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello"}""")
        assertNull(r.language)
    }
}
