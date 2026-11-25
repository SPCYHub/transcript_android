package com.example.model

import java.util.UUID

enum class AudioSourceType(val label: String) {
    WHATSAPP_SHARE("WhatsApp Voice"),
    AUDIO_IMPORT("Audio Import"),
    STUDIO_RECORD("Studio Mic"),
    KEYBOARD_LIVE("Voice Keyboard")
}

enum class CantoneseFormat(val label: String) {
    SPOKEN_CANTONESE("口語 Spoken"),
    WRITTEN_CHINESE("書面語 Written"),
    DUAL_VIEW("Dual View")
}

enum class RoutingMode(val label: String) {
    AUTO("Auto"),
    GEMINI_ONLY("Gemini"),
    OPENAI_ONLY("OpenAI")
}

enum class ThemePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class TranscriptionEngineId(val displayName: String, val modelTag: String) {
    GEMINI_LIVE("Gemini 3.5 Transcribe Live", "gemini-3.5-transcribe-live"),
    GEMINI_DIRECT_LIVE("Gemini Direct Live", "gemini-3.5-transcribe-live"),
    GEMINI_FLASH("Gemini 4 Hydrogen", "gemini-4-hydrogen"),
    GEMINI_FLASH_LITE("Gemini 3.8 Flash", "gemini-3.8-flash"),
    GEMINI_DIRECT_FLASH("Gemini 4 Hydrogen", "gemini-4-hydrogen"),
    GEMINI_DIRECT_LITE("Gemini 3.8 Flash", "gemini-3.8-flash"),
    OPENAI_GPT_TRANSCRIBE("OpenAI Transcribe", "gpt-transcribe"),
    OPENAI_MINI_FALLBACK("OpenAI Mini", "gpt-4o-mini-transcribe")
}

data class ApiKeyStatus(
    val hasUserGeminiKey: Boolean = false,
    val maskedGeminiKey: String? = null,
    val usePersonalGeminiKey: Boolean = true,
    val customGeminiModel: String = "",
    val hasUserOpenAiKey: Boolean = false,
    val maskedOpenAiKey: String? = null,
    val hasBuildOpenAiKey: Boolean = false
) {
    val isUsingDirectGemini: Boolean
        get() = hasUserGeminiKey

    val isOpenAiConfigured: Boolean
        get() = hasUserOpenAiKey || hasBuildOpenAiKey

    val geminiProviderLabel: String
        get() = if (hasUserGeminiKey) "Personal Key" else "Not Set"

    val openAiProviderLabel: String
        get() = when {
            hasUserOpenAiKey -> "Personal Key"
            hasBuildOpenAiKey -> "Build Secret"
            else -> "Not Set"
        }
}

sealed interface KeyTestResult {
    data object Idle : KeyTestResult
    data object Testing : KeyTestResult
    data class Valid(val detail: String) : KeyTestResult
    data class Invalid(val error: String) : KeyTestResult
}

data class PreparedAudio(
    val bytes: ByteArray,
    val fileName: String,
    val geminiMimeType: String,
    val openAiFileName: String,
    val openAiMimeType: String,
    val durationMillis: Long,
    val sizeBytes: Long,
    val sha256: String,
    val sourceType: AudioSourceType,
    val codecLabel: String,
    val hadSpeechActivity: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedAudio) return false
        return sha256 == other.sha256 && sourceType == other.sourceType && sizeBytes == other.sizeBytes
    }

    override fun hashCode(): Int {
        var result = sha256.hashCode()
        result = 31 * result + sourceType.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        return result
    }
}

data class DeepReviewResult(
    val refinedSpoken: String,
    val refinedWritten: String,
    val cantoneseNotes: List<String>,
    val keyPoints: List<String>,
    val suggestedReplies: List<String>,
    val latencyMillis: Long
)

data class TranscriptRecord(
    val id: String = UUID.randomUUID().toString(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val sourceType: AudioSourceType,
    val fileName: String,
    val codecLabel: String,
    val durationMillis: Long,
    val audioSizeBytes: Long,
    val audioSha256: String,
    val spokenCantonese: String,
    val writtenChinese: String?,
    val detectedLanguageMix: String,
    val engineUsed: TranscriptionEngineId,
    val latencyMillis: Long,
    val fromCache: Boolean = false,
    val fallbackNote: String? = null,
    val deepReview: DeepReviewResult? = null
)

data class UserPreferences(
    val routingMode: RoutingMode = RoutingMode.AUTO,
    val defaultFormat: CantoneseFormat = CantoneseFormat.SPOKEN_CANTONESE,
    val includeWrittenInAuto: Boolean = true,
    val autoCopyOnFinish: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val customGlossary: String = "",
    val themePreference: ThemePreference = ThemePreference.SYSTEM
)

data class AudioCaptureMetrics(
    val elapsedMillis: Long = 0L,
    val normalizedLevel: Float = 0f,
    val speechFrames: Int = 0,
    val isCurrentlySilent: Boolean = true,
    val waveformHistory: List<Float> = emptyList()
)

sealed interface StudioUiState {
    data object Idle : StudioUiState
    data class LiveStreaming(
        val confirmedText: String,
        val partialText: String,
        val engineLabel: String,
        val isConnecting: Boolean,
        val isHoldToTalk: Boolean,
        val metrics: AudioCaptureMetrics,
        val isTranscribingChunk: Boolean = false,
        val warningMessage: String? = null
    ) : StudioUiState
    data class Processing(
        val stageLabel: String,
        val fileName: String,
        val codecLabel: String,
        val sizeBytes: Long,
        val activeEngineHint: String
    ) : StudioUiState
    data class Success(
        val record: TranscriptRecord,
        val isConvertingWritten: Boolean = false,
        val isRunningDeepReview: Boolean = false,
        val deepReviewError: String? = null
    ) : StudioUiState
    data class Error(
        val message: String,
        val canRetryWithAlternateEngine: Boolean = false,
        val suggestedEngine: RoutingMode? = null,
        val isAppCheckOrKeyError: Boolean = false
    ) : StudioUiState
}
