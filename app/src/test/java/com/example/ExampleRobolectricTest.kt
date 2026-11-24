package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CantonesePrompts
import com.example.ai.LiveStreamState
import com.example.ai.TranscriptSanitizer
import com.example.core.AppContainer
import com.example.model.AudioSourceType
import com.example.model.RoutingMode
import com.example.model.TranscriptionEngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("CantoScribe", appName)
    }

    @Test
    fun `live stream state combines confirmed and partial text cleanly`() {
        val state = LiveStreamState(
            isActive = true,
            confirmedText = "聽日開 meeting",
            partialText = "confirm 個 deadline",
            engineId = TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE
        )
        assertEquals("聽日開 meeting confirm 個 deadline", state.combinedText)

        val cantoState = LiveStreamState(
            isActive = true,
            confirmedText = "今日去將軍澳",
            partialText = "食下午茶"
        )
        assertEquals("今日去將軍澳食下午茶", cantoState.combinedText)
    }

    @Test
    fun `openai prompt never injects imperative instructions`() {
        assertEquals("", CantonesePrompts.buildOpenAiPrompt(customGlossary = ""))
        val glossaryOnly = CantonesePrompts.buildOpenAiPrompt(customGlossary = "將軍澳, K11, Jira")
        assertEquals("將軍澳、K11、Jira", glossaryOnly)
        assertFalse(glossaryOnly.contains("請用"))
        assertFalse(glossaryOnly.contains("書面語"))
    }

    @Test
    fun `transcript sanitizer strips leaked instructions silence hallucinations and duplicates`() {
        val leaked = "你覺得你可以做啲乜嘢?請用香港繁體中文清晰標點記錄，整理成自然流暢嘅書面語。不如我哋一齊試吓。不如我哋一齊試吓。"
        val cleaned = TranscriptSanitizer.sanitizeSegment(leaked)
        assertEquals("你覺得你可以做啲乜嘢?不如我哋一齊試吓。", cleaned)

        assertEquals("", TranscriptSanitizer.sanitizeSegment("請不吝點讚訂閱轉發打賞支持明鏡與點點欄目"))
        assertEquals("", TranscriptSanitizer.sanitizeSegment("謝謝觀看。"))

        assertEquals(
            "",
            TranscriptSanitizer.deduplicateAgainstExisting(
                existing = "你覺得你可以做啲乜嘢?不如我哋一齊試吓。",
                incoming = "不如我哋一齊試吓。"
            )
        )
        assertEquals(
            "啦",
            TranscriptSanitizer.deduplicateAgainstExisting(
                existing = "不如我哋一齊試吓",
                incoming = "一齊試吓啦"
            )
        )
    }

    @Test
    fun `app container initializes and resolves routing hints`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppContainer.ensureInitialized(context)

        val hintGemini = AppContainer.transcriptionRouter.resolvePlannedEngineHint(
            sourceType = AudioSourceType.STUDIO_RECORD,
            routingMode = RoutingMode.GEMINI_ONLY
        )
        assertTrue(hintGemini.contains("Gemini"))
        assertEquals("gemini-3.5-transcribe-live", TranscriptionEngineId.GEMINI_DIRECT_LIVE.modelTag)
        assertNotNull(AppContainer.geminiLiveEngine)
    }
}
