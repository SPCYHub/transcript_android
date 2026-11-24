package com.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-secret fixture tests for provider request URL/header construction.
 * Does not contact live Gemini or OpenAI APIs and does not use real keys.
 */
class ProviderRequestAuthTest {

    @Test
    fun `gemini generateContent url is the official REST path for the model`() {
        val url = ProviderRequestAuth.geminiGenerateContentUrl("gemini-3.8-flash")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            url
        )
        assertFalse(url.contains("firebase", ignoreCase = true))
        assertFalse(url.contains("appcheck", ignoreCase = true))
    }

    @Test
    fun `gemini stream url uses official SSE query`() {
        val url = ProviderRequestAuth.geminiStreamGenerateContentUrl("gemini-3.5-flash")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:streamGenerateContent?alt=sse",
            url
        )
    }

    @Test
    fun `gemini live websocket puts the caller-supplied key only in the key query param`() {
        val fixtureKey = "test-gemini-fixture-key-not-real"
        val url = ProviderRequestAuth.geminiLiveWebSocketUrl(fixtureKey)
        assertTrue(url.startsWith("wss://generativelanguage.googleapis.com/ws/"))
        assertTrue(url.endsWith("?key=$fixtureKey"))
        assertFalse(url.contains("firebase", ignoreCase = true))
        assertFalse(url.contains("AIza"))
    }

    @Test
    fun `openai authorization is bearer with the caller-supplied key`() {
        val fixtureKey = "sk-test-fixture-not-a-real-key"
        assertEquals("Bearer $fixtureKey", ProviderRequestAuth.openAiAuthorization(fixtureKey))
        assertEquals("x-goog-api-key", ProviderRequestAuth.GEMINI_API_KEY_HEADER)
        assertEquals(
            "wss://api.openai.com/v1/realtime?intent=transcription",
            ProviderRequestAuth.OPENAI_REALTIME_TRANSCRIPTION_URL
        )
    }

    @Test
    fun `helpers do not inject a default or embedded token`() {
        val emptyAuth = ProviderRequestAuth.openAiAuthorization("")
        assertEquals("Bearer ", emptyAuth)
        val emptyWs = ProviderRequestAuth.geminiLiveWebSocketUrl("")
        assertTrue(emptyWs.endsWith("?key="))
    }
}
