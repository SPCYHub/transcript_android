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
    fun `gemini generateContent url routes through api-router gemini prefix`() {
        val url = ProviderRequestAuth.geminiGenerateContentUrl("gemini-3.8-flash")
        assertEquals(
            "https://api-router.alchemys.ai/gemini/v1beta/models/gemini-3.8-flash:generateContent",
            url
        )
        assertFalse(url.contains("firebase", ignoreCase = true))
        assertFalse(url.contains("appcheck", ignoreCase = true))
    }

    @Test
    fun `gemini stream url uses router prefix and SSE query`() {
        val url = ProviderRequestAuth.geminiStreamGenerateContentUrl("gemini-3.5-flash")
        assertEquals(
            "https://api-router.alchemys.ai/gemini/v1beta/models/gemini-3.5-flash:streamGenerateContent?alt=sse",
            url
        )
    }

    @Test
    fun `gemini live websocket routes through api-router and puts key only in query param`() {
        val fixtureKey = "test-gemini-fixture-key-not-real"
        val url = ProviderRequestAuth.geminiLiveWebSocketUrl(fixtureKey)
        assertEquals(
            "wss://api-router.alchemys.ai/gemini/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$fixtureKey",
            url
        )
        assertFalse(url.contains("firebase", ignoreCase = true))
        assertFalse(url.contains("AIza"))
    }

    @Test
    fun `openai endpoints and authorization use api-router openai prefix and bearer auth`() {
        val fixtureKey = "sk-test-fixture-not-a-real-key"
        assertEquals("Bearer $fixtureKey", ProviderRequestAuth.openAiAuthorization(fixtureKey))
        assertEquals("x-goog-api-key", ProviderRequestAuth.GEMINI_API_KEY_HEADER)
        assertEquals(
            "https://api-router.alchemys.ai/gemini/v1beta/models?pageSize=5",
            ProviderRequestAuth.GEMINI_MODELS_LIST_URL
        )
        assertEquals(
            "https://api-router.alchemys.ai/openai/",
            ProviderRequestAuth.OPENAI_REST_BASE
        )
        assertEquals(
            "https://api-router.alchemys.ai/openai/v1/chat/completions",
            ProviderRequestAuth.OPENAI_CHAT_COMPLETIONS_URL
        )
        assertEquals(
            "https://api-router.alchemys.ai/openai/v1/models",
            ProviderRequestAuth.OPENAI_MODELS_URL
        )
        assertEquals(
            "wss://api-router.alchemys.ai/openai/v1/realtime?intent=transcription",
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
