package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Non-secret fixture tests for in-app API key storage and resolution.
 * Uses obviously fake keys only. Does not call live provider endpoints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecureKeyStoreApiKeyTest {

    private fun store(): SecureKeyStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SecureKeyStore(context)
    }


    @Before
    fun clearStoredKeys() {
        val keys = store()
        keys.clearGeminiApiKey()
        keys.clearOpenAiApiKey()
    }

    @Test
    fun `build-time OpenAI placeholder is not treated as a configured key`() {
        assertEquals("MY_OPENAI_API_KEY", BuildConfig.OPENAI_API_KEY)
        val keys = store()
        // A clean test context has no user-saved OpenAI key.
        if (!keys.status.value.hasUserOpenAiKey) {
            assertFalse(keys.hasEffectiveOpenAiKey())
            assertNull(keys.resolveEffectiveOpenAiKey())
        }
    }

    @Test
    fun `user-supplied fixture Gemini key is resolved and can be cleared`() {
        val keys = store()
        val fixture = "test-gemini-fixture-key-not-real"
        keys.saveGeminiApiKey(fixture)
        assertTrue(keys.hasDirectGeminiKey())
        assertEquals(fixture, keys.resolveDirectGeminiKey())
        assertTrue(keys.status.value.hasUserGeminiKey)
        assertFalse(keys.status.value.maskedGeminiKey.isNullOrBlank())
        assertFalse(keys.status.value.maskedGeminiKey!!.contains(fixture))

        keys.clearGeminiApiKey()
        assertFalse(keys.hasDirectGeminiKey())
        assertNull(keys.resolveDirectGeminiKey())
    }

    @Test
    fun `user-supplied fixture OpenAI key is preferred over the build placeholder`() {
        val keys = store()
        val fixture = "sk-test-fixture-not-a-real-key"
        keys.saveOpenAiApiKey(fixture)
        assertTrue(keys.hasEffectiveOpenAiKey())
        assertEquals(fixture, keys.resolveEffectiveOpenAiKey())

        keys.clearOpenAiApiKey()
        assertFalse(keys.hasEffectiveOpenAiKey())
        assertNull(keys.resolveEffectiveOpenAiKey())
    }
}
