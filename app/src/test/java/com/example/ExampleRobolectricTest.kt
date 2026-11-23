package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.LiveStreamState
import com.example.core.AppContainer
import com.example.model.AudioSourceType
import com.example.model.RoutingMode
import com.example.model.TranscriptionEngineId
import org.junit.Assert.assertEquals
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
    fun `app container initializes and resolves routing hints`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppContainer.ensureInitialized(context)

        val hintGemini = AppContainer.transcriptionRouter.resolvePlannedEngineHint(
            sourceType = AudioSourceType.STUDIO_RECORD,
            routingMode = RoutingMode.GEMINI_ONLY
        )
        assertTrue(hintGemini.contains("Gemini"))
        assertEquals("gemini-3.5-transcribe-live", TranscriptionEngineId.GEMINI_LIVE.modelTag)
        assertEquals("gemini-3.5-transcribe-live", TranscriptionEngineId.GEMINI_DIRECT_LIVE.modelTag)
        assertNotNull(AppContainer.geminiLiveEngine)
    }
}
