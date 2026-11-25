package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ProviderRequestAuth
import com.example.audio.AudioInputHandler
import com.example.data.SecureKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityAuditTest {

    @Test
    fun `sanitizeGeminiModelName accepts valid model identifiers and strips models prefix`() {
        assertEquals("gemini-3.5-flash-lite", ProviderRequestAuth.sanitizeGeminiModelName("gemini-3.5-flash-lite"))
        assertEquals("gemini-4-hydrogen", ProviderRequestAuth.sanitizeGeminiModelName("models/gemini-4-hydrogen"))
        assertTrue(ProviderRequestAuth.isValidGeminiModelName("models/gemini-2.5_flash.preview-01"))
    }

    @Test
    fun `sanitizeGeminiModelName rejects path traversal and query injection`() {
        val maliciousInputs = listOf(
            "../../v1beta/files?alt=json",
            "gemini-flash/../../secret",
            "gemini-flash?key=injected",
            "models/gemini flash",
            ""
        )
        maliciousInputs.forEach { bad ->
            assertFalse("Expected invalid model for: $bad", ProviderRequestAuth.isValidGeminiModelName(bad))
            var threw = false
            try {
                ProviderRequestAuth.sanitizeGeminiModelName(bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected IllegalArgumentException for: $bad", threw)
        }
    }

    @Test
    fun `redactSecrets masks api keys and bearer tokens`() {
        val sample = "GET wss://host/ws?key=AIzaSyDummySecretValue1234567890 failed; Authorization: Bearer sk-proj-abc1234567890xyz"
        val redacted = ProviderRequestAuth.redactSecrets(sample)
        assertFalse(redacted.contains("AIzaSyDummySecretValue1234567890"))
        assertFalse(redacted.contains("sk-proj-abc1234567890xyz"))
        assertTrue(redacted.contains("?key=[REDACTED]"))
        assertTrue(redacted.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `audio uri scheme allowlist permits content and blocks file or web uris`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handler = AudioInputHandler(context)

        assertTrue(handler.isSupportedAudioUriScheme(Uri.parse("content://media/external/audio/media/42")))
        assertTrue(handler.isSupportedAudioUriScheme(Uri.parse("android.resource://com.example/raw/sample")))

        assertFalse(handler.isSupportedAudioUriScheme(Uri.parse("file:///data/data/com.example/files/secret")))
        assertFalse(handler.isSupportedAudioUriScheme(Uri.parse("https://example.com/audio.ogg")))
        assertFalse(handler.isSupportedAudioUriScheme(Uri.parse("http://127.0.0.1:8080/a.wav")))
    }

    @Test
    fun `SecureKeyStore validates custom Gemini model names before persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecureKeyStore(context)
        store.saveCustomGeminiModel("")

        var threw = false
        try {
            store.saveCustomGeminiModel("../bad/model?x=1")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertNull(store.resolveCustomGeminiModel())

        store.saveCustomGeminiModel("models/gemini-3.8-flash")
        assertEquals("gemini-3.8-flash", store.resolveCustomGeminiModel())
        store.saveCustomGeminiModel("")
    }
}
