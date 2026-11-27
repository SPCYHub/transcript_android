package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.BufferedPcm16
import com.example.ai.CantonesePrompts
import com.example.ai.TranscriptSanitizer
import com.example.audio.AudioPcmDecoder
import com.example.audio.LivePcmStreamRecorder
import com.example.core.AppContainer
import com.example.data.WhisperModelStore
import com.example.model.AudioSourceType
import com.example.model.PreparedAudio
import com.example.model.RoutingMode
import com.example.model.TranscriptRecord
import com.example.model.TranscriptionEngineId
import com.example.model.UserPreferences
import com.example.model.WhisperModelCatalog
import com.example.model.WhisperModelTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class LocalWhisperFeatureTest {

    @Test
    fun `catalog provides tiny base small and turbo tiers with base recommended`() {
        val specs = WhisperModelCatalog.builtInSpecs
        assertEquals(4, specs.size)
        assertEquals(
            listOf(
                WhisperModelTier.TINY,
                WhisperModelTier.BASE,
                WhisperModelTier.SMALL,
                WhisperModelTier.TURBO
            ),
            specs.map { it.tier }
        )
        val recommended = specs.filter { it.isRecommended }
        assertEquals(1, recommended.size)
        assertEquals(WhisperModelTier.BASE, recommended.first().tier)
        assertTrue(specs.all { it.downloadUrl.endsWith(".bin") })
    }

    @Test
    fun `ggml magic validation accepts lmgg header and rejects corrupt or short files`() {
        val validMagic = byteArrayOf(0x6c, 0x6d, 0x67, 0x67, 0x01, 0x00)
        val invalidMagic = byteArrayOf(0x3c, 0x21, 0x44, 0x4f) // "<!DO" (HTML error page)
        assertTrue(WhisperModelStore.isValidGgmlMagic(validMagic))
        assertFalse(WhisperModelStore.isValidGgmlMagic(invalidMagic))
        assertFalse(WhisperModelStore.isValidGgmlMagic(byteArrayOf(0x6c, 0x6d)))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val validTemp = File(context.cacheDir, "test_valid_ggml.bin").apply {
            writeBytes(ByteArray(2048).also { buf ->
                validMagic.copyInto(buf)
            })
        }
        val htmlTemp = File(context.cacheDir, "test_bad_ggml.bin").apply {
            writeBytes(ByteArray(2048).also { buf ->
                invalidMagic.copyInto(buf)
            })
        }
        try {
            assertTrue(WhisperModelStore.isValidGgmlFile(validTemp, minBytes = 1024L))
            assertFalse(WhisperModelStore.isValidGgmlFile(validTemp)) // fails default 1MB minimum
            assertFalse(WhisperModelStore.isValidGgmlFile(htmlTemp, minBytes = 1024L))
        } finally {
            validTemp.delete()
            htmlTemp.delete()
        }
    }

    @Test
    fun `wav decoder downmixes stereo and resamples 8kHz to 16kHz mono float`() {
        // 8000 Hz stereo PCM16 WAV with 80 frames (10 ms) -> expected 160 samples at 16000 Hz
        val srcRate = 8_000
        val channels = 2
        val frames = 80
        val dataSize = frames * channels * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(srcRate)
        buf.putInt(srcRate * channels * 2)
        buf.putShort((channels * 2).toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (i in 0 until frames) {
            val left: Short = 16384 // +0.5
            val right: Short = 0    // 0.0 -> mono avg +0.25
            buf.putShort(left)
            buf.putShort(right)
        }

        val floats = AudioPcmDecoder.decodeWavBytesTo16kMonoFloat(buf.array())
        assertNotNull(floats)
        assertEquals(160, floats!!.size)
        assertEquals(0.25f, floats[10], 0.01f)
    }

    @Test
    fun `queued 24kHz fallback keeps its duration through Whisper resampling`() {
        val rateHz = LivePcmStreamRecorder.SAMPLE_RATE_OPENAI_REALTIME_HZ
        val samples = ByteBuffer.allocate(rateHz / 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rateHz / 4) { samples.putShort(16384) }
        val buffered = BufferedPcm16(samples.array(), rateHz)

        assertTrue(buffered.hasDurationAtLeast(250))
        assertFalse(buffered.copy(bytes = buffered.bytes.copyOf(8_000)).hasDurationAtLeast(250))
        val decoded = AudioPcmDecoder.pcm16BytesTo16kMonoFloat(buffered.bytes, buffered.sampleRateHz)
        assertEquals(4_000, decoded.size)
        assertEquals(0.5f, decoded.last(), 0.001f)
    }

    @Test
    fun `local mode rejects cloud conversion and review with both keys configured`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppContainer.ensureInitialized(context)
        val keys = AppContainer.secureKeyStore
        val router = AppContainer.transcriptionRouter
        keys.saveGeminiApiKey("test-gemini-whisper-key-not-real")
        keys.saveOpenAiApiKey("sk-test-whisper-key-not-real")
        try {
            assertTrue(router.isGeminiReady)
            assertTrue(router.isOpenAiReady)
            val transcript = fixtureTranscript(TranscriptionEngineId.WHISPER_LOCAL_BASE)
            val offlineMessage = context.getString(R.string.local_only_cloud_processing_unavailable)

            val conversion = router.ensureWrittenChinese(transcript, RoutingMode.LOCAL_ONLY)
            assertEquals(offlineMessage, conversion.exceptionOrNull()?.message)
            val review = router.runDeepReview(transcript, null, "", RoutingMode.LOCAL_ONLY)
            assertEquals(offlineMessage, review.exceptionOrNull()?.message)

            val alreadyWritten = transcript.copy(writtenChinese = "我們開會。")
            assertEquals(alreadyWritten, router.ensureWrittenChinese(alreadyWritten, RoutingMode.LOCAL_ONLY).getOrThrow())
        } finally {
            keys.clearGeminiApiKey()
            keys.clearOpenAiApiKey()
        }
    }

    @Test
    fun `local mode reuses local but not cloud transcript cache entries`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppContainer.ensureInitialized(context)
        val store = AppContainer.sessionStore
        val router = AppContainer.transcriptionRouter
        val cloud = fixtureTranscript(TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE)
        val audio = PreparedAudio(
            bytes = byteArrayOf(1, 2),
            fileName = cloud.fileName,
            geminiMimeType = "audio/wav",
            openAiFileName = cloud.fileName,
            openAiMimeType = "audio/wav",
            durationMillis = cloud.durationMillis,
            sizeBytes = cloud.audioSizeBytes,
            sha256 = cloud.audioSha256,
            sourceType = cloud.sourceType,
            codecLabel = cloud.codecLabel
        )
        val preferences = UserPreferences(routingMode = RoutingMode.LOCAL_ONLY)
        try {
            store.addOrUpdate(cloud)
            assertTrue(router.transcribe(audio, preferences).isFailure)
            val local = cloud.copy(engineUsed = TranscriptionEngineId.WHISPER_LOCAL_BASE)
            store.addOrUpdate(local)
            val cached = router.transcribe(audio, preferences).getOrThrow()
            assertTrue(cached.fromCache)
            assertEquals(local.engineUsed, cached.engineUsed)
        } finally {
            store.clearAll()
        }
    }

    private fun fixtureTranscript(engine: TranscriptionEngineId) = TranscriptRecord(
        sourceType = AudioSourceType.AUDIO_IMPORT,
        fileName = "fixture.wav",
        codecLabel = "PCM WAV",
        durationMillis = 1_000L,
        audioSizeBytes = 2L,
        audioSha256 = "whisper-local-cache-fixture",
        spokenCantonese = "我哋開會。",
        writtenChinese = null,
        detectedLanguageMix = "Cantonese",
        engineUsed = engine,
        latencyMillis = 1L
    )

    @Test
    fun `local whisper prompt anchors traditional cantonese and sanitizer strips primer echo`() {
        val prompt = CantonesePrompts.buildLocalWhisperPrompt(
            customGlossary = "將軍澳, K11",
            contextTail = "聽日食晏"
        )
        assertTrue(prompt.contains("係、唔、嘅、咗、喺、啲"))
        assertTrue(prompt.contains("將軍澳、K11"))
        assertTrue(prompt.contains("聽日食晏"))

        val echoed = "香港廣東話口語，用繁體字：係、唔、嘅、咗、喺、啲。 我哋一陣開會"
        val cleaned = TranscriptSanitizer.sanitizeSegment(rawText = echoed, activePrompt = "")
        assertEquals("我哋一陣開會", cleaned)
    }

    @Test
    fun `router and container expose local whisper mode hints cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppContainer.ensureInitialized(context)

        val localHint = AppContainer.transcriptionRouter.resolvePlannedEngineHint(
            sourceType = AudioSourceType.STUDIO_RECORD,
            routingMode = RoutingMode.LOCAL_ONLY
        )
        assertTrue(
            "Expected local model hint, got: $localHint",
            localHint.contains("Local") || localHint.contains("Whisper")
        )
        assertEquals("Local", RoutingMode.LOCAL_ONLY.label)
        assertNotNull(AppContainer.whisperModelStore)
        assertNotNull(AppContainer.localWhisperEngine)
    }
}
