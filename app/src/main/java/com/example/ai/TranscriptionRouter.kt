package com.example.ai

import com.example.data.SessionTranscriptStore
import com.example.model.AudioSourceType
import com.example.model.CantoneseFormat
import com.example.model.DeepReviewResult
import com.example.model.PreparedAudio
import com.example.model.RoutingMode
import com.example.model.TranscriptRecord
import com.example.model.UserPreferences

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Efficiency-first transcription coordinator routing across the user's saved Gemini API
 * (`api-router.alchemys.ai/gemini`), OpenAI (`api-router.alchemys.ai/openai`), and
 * on-device Local Whisper GGML models.
 *
 * Optimization highlights:
 * - Instant 0ms SHA-256 session deduplication cache.
 * - Pre-flight VAD silence check to avoid empty API round trips.
 * - Smart Auto-Routing with automatic offline fallback to Local Whisper when keys are unset
 *   or cloud endpoints fail with connectivity/timeout/server errors.
 */
class TranscriptionRouter(
    private val geminiEngine: GeminiTranscriptionEngine,
    private val openAiEngine: OpenAiTranscriptionEngine,
    private val localWhisperEngine: LocalWhisperEngine,
    private val sessionStore: SessionTranscriptStore,
    private val localOnlyCloudProcessingMessage: String
) {

    val isGeminiReady: Boolean
        get() = geminiEngine.isConfigured

    val isOpenAiReady: Boolean
        get() = openAiEngine.isConfigured

    val isLocalReady: Boolean
        get() = localWhisperEngine.isConfigured

    fun resolvePlannedEngineHint(
        sourceType: AudioSourceType,
        routingMode: RoutingMode
    ): String {
        val localLabel = localWhisperEngine.activeModelShortName ?: "GGML"
        return when (routingMode) {
            RoutingMode.GEMINI_ONLY ->
                if (isGeminiReady) "Gemini 4 Hydrogen / 3.8 Flash" else "Gemini (Key Needed)"
            RoutingMode.OPENAI_ONLY ->
                if (isOpenAiReady) "OpenAI gpt-transcribe" else "OpenAI (Key Needed)"
            RoutingMode.LOCAL_ONLY ->
                if (isLocalReady) "Whisper Local ($localLabel)" else "Local Model Needed (Keys Tab)"
            RoutingMode.AUTO -> when {
                sourceType == AudioSourceType.KEYBOARD_LIVE && isOpenAiReady -> "Auto -> OpenAI gpt-transcribe"
                isGeminiReady -> "Auto -> Gemini 4 Hydrogen / 3.8 Flash"
                isOpenAiReady -> "Auto -> OpenAI gpt-transcribe"
                isLocalReady -> "Auto -> Whisper Local ($localLabel)"
                else -> "Key or Local Model Needed (Keys Tab)"
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
            sessionStore.findCachedByHash(audio.sha256)
                ?.takeIf { activeMode != RoutingMode.LOCAL_ONLY || it.engineUsed.isLocalWhisper }
                ?.let { cached ->
                    return Result.success(cached.copy(fromCache = true, latencyMillis = 0L))
                }
        }

        // 2. Dispatch according to routing strategy.
        val result: Result<TranscriptRecord> = when (activeMode) {
            RoutingMode.GEMINI_ONLY -> runGemini(audio, preferences, fallbackNote = null)
            RoutingMode.OPENAI_ONLY -> runOpenAi(audio, preferences, fallbackNote = null)
            RoutingMode.LOCAL_ONLY -> runLocalWhisper(audio, preferences, fallbackNote = null)
            RoutingMode.AUTO -> runSmartAuto(audio, preferences)
        }

        result.onSuccess { record ->
            if (saveToSession) {
                sessionStore.addOrUpdate(record)
            }
        }
        return result
    }

    suspend fun ensureWrittenChinese(
        record: TranscriptRecord,
        routingMode: RoutingMode
    ): Result<TranscriptRecord> {
        if (!record.writtenChinese.isNullOrBlank()) {
            return Result.success(record)
        }
        if (routingMode == RoutingMode.LOCAL_ONLY) {
            return Result.failure(IllegalStateException(localOnlyCloudProcessingMessage))
        }
        if (!isGeminiReady && !isOpenAiReady) {
            return Result.failure(
                IllegalStateException("Online Written Chinese conversion requires a Gemini or OpenAI API key.")
            )
        }
        val preferOpenAi = routingMode == RoutingMode.OPENAI_ONLY || (!isGeminiReady && isOpenAiReady)
        val conversionResult = if (preferOpenAi) {
            val openAiRes = openAiEngine.convertSpokenToWritten(record.spokenCantonese)
            if (openAiRes.isSuccess || routingMode == RoutingMode.OPENAI_ONLY || !isGeminiReady) {
                openAiRes
            } else {
                geminiEngine.convertSpokenToWritten(record.spokenCantonese)
            }
        } else {
            val geminiRes = geminiEngine.convertSpokenToWritten(record.spokenCantonese)
            if (geminiRes.isSuccess || routingMode == RoutingMode.GEMINI_ONLY || !isOpenAiReady) {
                geminiRes
            } else {
                openAiEngine.convertSpokenToWritten(record.spokenCantonese)
            }
        }

        return conversionResult.map { converted ->
            sessionStore.updateWrittenChinese(record.id, converted)
                ?: record.copy(writtenChinese = converted)
        }
    }

    suspend fun runDeepReview(
        record: TranscriptRecord,
        audioContext: PreparedAudio?,
        customGlossary: String,
        routingMode: RoutingMode
    ): Result<Pair<TranscriptRecord, DeepReviewResult>> {
        if (routingMode == RoutingMode.LOCAL_ONLY) {
            return Result.failure(IllegalStateException(localOnlyCloudProcessingMessage))
        }
        if (!isGeminiReady && !isOpenAiReady) {
            return Result.failure(
                IllegalStateException("Deep Review requires a Gemini or OpenAI API key in the Keys tab.")
            )
        }
        val preferOpenAi = routingMode == RoutingMode.OPENAI_ONLY || (!isGeminiReady && isOpenAiReady)
        val reviewResult = if (preferOpenAi) {
            val openAiRes = openAiEngine.deepReview(
                spokenCantonese = record.spokenCantonese,
                writtenChinese = record.writtenChinese,
                customGlossary = customGlossary
            )
            if (openAiRes.isSuccess || routingMode == RoutingMode.OPENAI_ONLY || !isGeminiReady) {
                openAiRes
            } else {
                geminiEngine.deepReviewWithHighThinking(
                    spokenCantonese = record.spokenCantonese,
                    writtenChinese = record.writtenChinese,
                    audioContext = audioContext,
                    customGlossary = customGlossary
                )
            }
        } else {
            val geminiRes = geminiEngine.deepReviewWithHighThinking(
                spokenCantonese = record.spokenCantonese,
                writtenChinese = record.writtenChinese,
                audioContext = audioContext,
                customGlossary = customGlossary
            )
            if (geminiRes.isSuccess || routingMode == RoutingMode.GEMINI_ONLY || !isOpenAiReady) {
                geminiRes
            } else {
                openAiEngine.deepReview(
                    spokenCantonese = record.spokenCantonese,
                    writtenChinese = record.writtenChinese,
                    customGlossary = customGlossary
                )
            }
        }

        return reviewResult.map { review ->
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
            if (isLocalReady) {
                return runLocalWhisper(audio, preferences, fallbackNote = null)
            }
            return Result.failure(
                IllegalStateException(
                    "No API key or local Whisper model configured. Add a key or download a Local Whisper model in the Keys tab."
                )
            )
        }

        val isShortKeyboardBurst = audio.sourceType == AudioSourceType.KEYBOARD_LIVE &&
            audio.durationMillis in 0L..20_000L

        // Short live keyboard bursts use OpenAI when configured, or route straight to OpenAI if Gemini key isn't set.
        if ((isShortKeyboardBurst && isOpenAiReady) || (!isGeminiReady && isOpenAiReady)) {
            val openAiResult = runOpenAi(audio, preferences, fallbackNote = null)
            if (openAiResult.isSuccess) return openAiResult

            if (isGeminiReady) {
                val err = openAiResult.exceptionOrNull()?.message ?: "OpenAI unavailable"
                val geminiRes = runGemini(
                    audio = audio,
                    preferences = preferences,
                    fallbackNote = "Auto fallback to Gemini ($err)"
                )
                if (geminiRes.isSuccess) return geminiRes
                if (isLocalReady && isConnectivityOrServerFailure(geminiRes.exceptionOrNull())) {
                    val localLabel = localWhisperEngine.activeModelShortName ?: "GGML"
                    return runLocalWhisper(
                        audio = audio,
                        preferences = preferences,
                        fallbackNote = "Offline fallback · Local Whisper ($localLabel)"
                    )
                }
                return geminiRes
            }

            if (isLocalReady && isConnectivityOrServerFailure(openAiResult.exceptionOrNull())) {
                val localLabel = localWhisperEngine.activeModelShortName ?: "GGML"
                return runLocalWhisper(
                    audio = audio,
                    preferences = preferences,
                    fallbackNote = "Offline fallback · Local Whisper ($localLabel)"
                )
            }
            return openAiResult
        }

        // WhatsApp shares, studio recordings, and long files route to Direct Gemini API first.
        val geminiResult = runGemini(audio, preferences, fallbackNote = null)
        if (geminiResult.isSuccess) return geminiResult

        // Automatic fallback to OpenAI if configured.
        if (isOpenAiReady) {
            val err = geminiResult.exceptionOrNull()?.message ?: "Gemini unavailable"
            val openAiRes = runOpenAi(
                audio = audio,
                preferences = preferences,
                fallbackNote = "Auto fallback to OpenAI ($err)"
            )
            if (openAiRes.isSuccess) return openAiRes
            if (isLocalReady && isConnectivityOrServerFailure(openAiRes.exceptionOrNull())) {
                val localLabel = localWhisperEngine.activeModelShortName ?: "GGML"
                return runLocalWhisper(
                    audio = audio,
                    preferences = preferences,
                    fallbackNote = "Offline fallback · Local Whisper ($localLabel)"
                )
            }
            return openAiRes
        }

        if (isLocalReady && isConnectivityOrServerFailure(geminiResult.exceptionOrNull())) {
            val localLabel = localWhisperEngine.activeModelShortName ?: "GGML"
            return runLocalWhisper(
                audio = audio,
                preferences = preferences,
                fallbackNote = "Offline fallback · Local Whisper ($localLabel)"
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

    private suspend fun runLocalWhisper(
        audio: PreparedAudio,
        preferences: UserPreferences,
        fallbackNote: String?
    ): Result<TranscriptRecord> {
        return localWhisperEngine.transcribePreparedAudio(
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
                spokenCantonese = out.spokenCantonese,
                writtenChinese = null,
                detectedLanguageMix = out.detectedLanguageMix,
                engineUsed = out.engineId,
                latencyMillis = out.latencyMillis,
                fallbackNote = fallbackNote
            )
        }
    }

    private fun isConnectivityOrServerFailure(error: Throwable?): Boolean {
        if (error == null) return false
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is UnknownHostException ||
                cursor is ConnectException ||
                cursor is SocketTimeoutException ||
                cursor is SSLException ||
                cursor is IOException
            ) {
                return true
            }
            cursor = cursor.cause
        }
        val msg = error.message.orEmpty().lowercase()
        val isAuthFailure = msg.contains("401") ||
            msg.contains("403") ||
            msg.contains("invalid_api_key") ||
            msg.contains("api key not valid")
        if (isAuthFailure) return false

        return msg.contains("unable to resolve host") ||
            msg.contains("failed to connect") ||
            msg.contains("network") ||
            msg.contains("offline") ||
            msg.contains("timeout") ||
            msg.contains("timed out") ||
            msg.contains("(500)") ||
            msg.contains("(502)") ||
            msg.contains("(503)") ||
            msg.contains("(504)")
    }
}
