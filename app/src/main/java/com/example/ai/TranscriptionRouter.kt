package com.example.ai

import com.example.data.SessionTranscriptStore
import com.example.model.AudioSourceType
import com.example.model.CantoneseFormat
import com.example.model.DeepReviewResult
import com.example.model.PreparedAudio
import com.example.model.RoutingMode
import com.example.model.TranscriptRecord
import com.example.model.UserPreferences

/**
 * Efficiency-first transcription coordinator routing across the user's saved Direct Gemini API
 * (`generativelanguage.googleapis.com`) and OpenAI (`/v1/audio/transcriptions`).
 *
 * Optimization highlights:
 * - Instant 0ms SHA-256 session deduplication cache.
 * - Pre-flight VAD silence check to avoid empty API round trips.
 * - Smart Auto-Routing based on whichever personal API keys the user has saved, with transparent
 *   cross-engine failover when both keys are configured.
 */
class TranscriptionRouter(
    private val geminiEngine: GeminiTranscriptionEngine,
    private val openAiEngine: OpenAiTranscriptionEngine,
    private val sessionStore: SessionTranscriptStore
) {

    val isGeminiReady: Boolean
        get() = geminiEngine.isConfigured

    val isOpenAiReady: Boolean
        get() = openAiEngine.isConfigured

    fun resolvePlannedEngineHint(
        sourceType: AudioSourceType,
        routingMode: RoutingMode
    ): String {
        return when (routingMode) {
            RoutingMode.GEMINI_ONLY ->
                if (isGeminiReady) {
                    if (sourceType == AudioSourceType.KEYBOARD_LIVE) "Gemini Direct Lite" else "Gemini Direct API"
                } else {
                    "Gemini (Key Needed)"
                }
            RoutingMode.OPENAI_ONLY ->
                if (isOpenAiReady) "OpenAI gpt-4o-transcribe" else "OpenAI (Key Needed)"
            RoutingMode.AUTO -> when {
                sourceType == AudioSourceType.KEYBOARD_LIVE && isOpenAiReady -> "Auto -> OpenAI gpt-4o-transcribe"
                isGeminiReady && sourceType == AudioSourceType.KEYBOARD_LIVE -> "Auto -> Gemini Direct Lite"
                isGeminiReady -> "Auto -> Gemini Direct API"
                isOpenAiReady -> "Auto -> OpenAI gpt-4o-transcribe"
                else -> "API Key Needed (Keys Tab)"
            }
        }
    }

    suspend fun transcribe(
        audio: PreparedAudio,
        preferences: UserPreferences,
        overrideMode: RoutingMode? = null,
        saveToSession: Boolean = true
    ): Result<TranscriptRecord> {
        if (!audio.hadSpeechActivity) {
            return Result.failure(
                IllegalArgumentException("No clear speech detected in recording. Please speak closer to the mic.")
            )
        }

        val activeMode = overrideMode ?: preferences.routingMode

        // 1. Instant SHA-256 deduplication hit for re-shared WhatsApp clips or repeated imports.
        if (overrideMode == null) {
            sessionStore.findCachedByHash(audio.sha256)?.let { cached ->
                val hitRecord = cached.copy(
                    fromCache = true,
                    latencyMillis = 0L
                )
                return Result.success(hitRecord)
            }
        }

        // 2. Dispatch according to routing strategy.
        val result: Result<TranscriptRecord> = when (activeMode) {
            RoutingMode.GEMINI_ONLY -> runGemini(audio, preferences, fallbackNote = null)
            RoutingMode.OPENAI_ONLY -> runOpenAi(audio, preferences, fallbackNote = null)
            RoutingMode.AUTO -> runSmartAuto(audio, preferences)
        }

        result.onSuccess { record ->
            if (saveToSession) {
                sessionStore.addOrUpdate(record)
            }
        }
        return result
    }

    suspend fun ensureWrittenChinese(record: TranscriptRecord): Result<TranscriptRecord> {
        if (!record.writtenChinese.isNullOrBlank()) {
            return Result.success(record)
        }
        return geminiEngine.convertSpokenToWritten(record.spokenCantonese).map { converted ->
            sessionStore.updateWrittenChinese(record.id, converted)
                ?: record.copy(writtenChinese = converted)
        }
    }

    suspend fun runDeepReview(
        record: TranscriptRecord,
        audioContext: PreparedAudio?,
        customGlossary: String
    ): Result<Pair<TranscriptRecord, DeepReviewResult>> {
        return geminiEngine.deepReviewWithHighThinking(
            spokenCantonese = record.spokenCantonese,
            writtenChinese = record.writtenChinese,
            audioContext = audioContext,
            customGlossary = customGlossary
        ).map { review ->
            val updated = sessionStore.attachDeepReview(record.id, review)
                ?: record.copy(
                    deepReview = review,
                    writtenChinese = record.writtenChinese ?: review.refinedWritten
                )
            updated to review
        }
    }

    private suspend fun runSmartAuto(
        audio: PreparedAudio,
        preferences: UserPreferences
    ): Result<TranscriptRecord> {
        if (!isGeminiReady && !isOpenAiReady) {
            return Result.failure(
                IllegalStateException(
                    "No API key configured. Add your Gemini or OpenAI API key in the Keys tab."
                )
            )
        }

        val isShortKeyboardBurst = audio.sourceType == AudioSourceType.KEYBOARD_LIVE &&
            audio.durationMillis in 0L..20_000L

        // Short live keyboard bursts use OpenAI when configured, or route straight to OpenAI if Gemini key isn't set.
        if ((isShortKeyboardBurst && isOpenAiReady) || (!isGeminiReady && isOpenAiReady)) {
            val openAiResult = runOpenAi(audio, preferences, fallbackNote = null)
            if (openAiResult.isSuccess || !isGeminiReady) return openAiResult

            val err = openAiResult.exceptionOrNull()?.message ?: "OpenAI unavailable"
            return runGemini(
                audio = audio,
                preferences = preferences,
                fallbackNote = "Auto fallback to Gemini ($err)"
            )
        }

        // WhatsApp shares, studio recordings, and long files route to Direct Gemini API first.
        val geminiResult = runGemini(audio, preferences, fallbackNote = null)
        if (geminiResult.isSuccess) return geminiResult

        // Automatic fallback to OpenAI if configured.
        if (isOpenAiReady) {
            val err = geminiResult.exceptionOrNull()?.message ?: "Gemini unavailable"
            return runOpenAi(
                audio = audio,
                preferences = preferences,
                fallbackNote = "Auto fallback to OpenAI ($err)"
            )
        }
        return geminiResult
    }

    private suspend fun runGemini(
        audio: PreparedAudio,
        preferences: UserPreferences,
        fallbackNote: String?
    ): Result<TranscriptRecord> {
        val outputResult = if (audio.sourceType == AudioSourceType.KEYBOARD_LIVE) {
            geminiEngine.transcribeFastDictation(
                audio = audio,
                format = preferences.defaultFormat,
                customGlossary = preferences.customGlossary
            )
        } else {
            geminiEngine.transcribeFullAudio(
                audio = audio,
                includeWritten = preferences.includeWrittenInAuto ||
                    preferences.defaultFormat != CantoneseFormat.SPOKEN_CANTONESE,
                customGlossary = preferences.customGlossary
            )
        }

        return outputResult.map { out ->
            TranscriptRecord(
                sourceType = audio.sourceType,
                fileName = audio.fileName,
                codecLabel = audio.codecLabel,
                durationMillis = audio.durationMillis,
                audioSizeBytes = audio.sizeBytes,
                audioSha256 = audio.sha256,
                spokenCantonese = out.spokenCantonese,
                writtenChinese = out.writtenChinese,
                detectedLanguageMix = out.detectedLanguageMix,
                engineUsed = out.engineId,
                latencyMillis = out.latencyMillis,
                fallbackNote = fallbackNote
            )
        }
    }

    private suspend fun runOpenAi(
        audio: PreparedAudio,
        preferences: UserPreferences,
        fallbackNote: String?
    ): Result<TranscriptRecord> {
        return openAiEngine.transcribe(
            audio = audio,
            customGlossary = preferences.customGlossary
        ).map { out ->
            TranscriptRecord(
                sourceType = audio.sourceType,
                fileName = audio.fileName,
                codecLabel = audio.codecLabel,
                durationMillis = audio.durationMillis,
                audioSizeBytes = audio.sizeBytes,
                audioSha256 = audio.sha256,
                spokenCantonese = out.text,
                writtenChinese = null,
                detectedLanguageMix = "Cantonese (OpenAI)",
                engineUsed = out.engineId,
                latencyMillis = out.latencyMillis,
                fallbackNote = fallbackNote
            )
        }
    }
}
