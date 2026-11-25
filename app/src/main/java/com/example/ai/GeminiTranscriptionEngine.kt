package com.example.ai

import android.util.Log
import com.example.data.SecureKeyStore
import com.example.model.CantoneseFormat
import com.example.model.DeepReviewResult
import com.example.model.PreparedAudio
import com.example.model.TranscriptionEngineId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiTranscriptionEngine"
private const val INFERENCE_TIMEOUT_MS = 60_000L

private val FULL_AUDIO_MODELS = listOf(
    "gemini-4-hydrogen",
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash"
)

private val FAST_DICTATION_MODELS = listOf(
    "gemini-4-hydrogen",
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash-lite"
)

/**
 * Latest Gemini "Flash" / "Medium" tier models for text-only tasks (Written Chinese conversion,
 * Cantonese refinement, and Deep Review).
 * - `gemini-4-hydrogen`: Gemini 4 family's speed/efficiency (Flash/Medium equivalent) model.
 * - `gemini-3.8-flash`: Latest official Gemini Flash workhorse model.
 */
private val TEXT_FLASH_MEDIUM_MODELS = listOf(
    "gemini-4-hydrogen",
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash"
)

private val DEEP_REVIEW_MODELS = listOf(
    "gemini-4-hydrogen",
    "gemini-3.8-flash",
    "gemini-4-argon",
    "gemini-2.5-flash"
)

data class GeminiAudioOutput(
    val spokenCantonese: String,
    val writtenChinese: String?,
    val detectedLanguageMix: String,
    val engineId: TranscriptionEngineId,
    val latencyMillis: Long
)

/**
 * Direct Google Gemini API transcription & text-processing engine.
 *
 * Exclusively uses the personal Gemini API key saved by the user in [SecureKeyStore]
 * via [DirectGeminiRestClient] (`generativelanguage.googleapis.com`) — zero Firebase dependencies.
 *
 * Model tiering (late 2026):
 * - Text & Multimodal workhorse (Flash / Medium tier): `gemini-4-hydrogen` -> `gemini-3.8-flash`
 *   -> `gemini-3.5-flash-lite` -> `gemini-2.5-flash`.
 * - Dictation enhancements per Gemini API documentation:
 *   - Top-level `systemInstruction` separating rules from user audio.
 *   - Deterministic `temperature = 0.0` to prevent creative drift/hallucinations.
 *   - `mediaResolution = "MEDIA_RESOLUTION_HIGH"` for WhatsApp/studio audio clarity.
 *   - `thinkingBudget = 0` on fast keyboard dictation and text conversion for lowest latency.
 *   - Automatic model fallback with session memory of the first working model for your API key.
 */
class GeminiTranscriptionEngine(
    private val keyStore: SecureKeyStore,
    private val directRestClient: DirectGeminiRestClient
) {

    val isConfigured: Boolean
        get() = keyStore.hasDirectGeminiKey()

    @Volatile
    private var preferredFlashModel: String? = null

    @Volatile
    private var preferredFastModel: String? = null

    @Volatile
    private var preferredTextModel: String? = null

    suspend fun transcribeFullAudio(
        audio: PreparedAudio,
        includeWritten: Boolean,
        customGlossary: String
    ): Result<GeminiAudioOutput> {
        val apiKey = keyStore.resolveDirectGeminiKey()
            ?: return Result.failure(missingKeyException())

        val startedAt = System.currentTimeMillis()
        val systemInstruction = CantonesePrompts.buildGeminiAudioSystemInstruction(
            includeWritten = includeWritten,
            customGlossary = customGlossary
        )
        val userPrompt = CantonesePrompts.buildGeminiAudioUserPrompt()
        val candidates = buildModelCandidates(
            customModel = keyStore.resolveCustomGeminiModel(),
            preferred = preferredFlashModel,
            defaults = FULL_AUDIO_MODELS
        )

        return try {
            var lastFailure: Result<String>? = null
            for (modelName in candidates) {
                val attempt = withTimeout(INFERENCE_TIMEOUT_MS) {
                    directRestClient.generateContent(
                        apiKey = apiKey,
                        modelName = modelName,
                        promptText = userPrompt,
                        systemInstruction = systemInstruction,
                        audioBytes = audio.bytes,
                        audioMimeType = audio.geminiMimeType,
                        temperature = 0.0f,
                        responseJsonSchema = DirectGeminiRestClient.audioTranscriptRestSchema(),
                        mediaResolution = "MEDIA_RESOLUTION_HIGH"
                    )
                }
                if (attempt.isSuccess) {
                    preferredFlashModel = modelName
                    return parseFullAudioResponse(
                        rawJson = attempt.getOrNull()?.trim(),
                        customGlossary = customGlossary,
                        engineId = TranscriptionEngineId.GEMINI_DIRECT_FLASH,
                        startedAt = startedAt
                    )
                }
                lastFailure = attempt
                val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
                if (!isModelFallbackError(errMsg)) {
                    return Result.failure(attempt.exceptionOrNull() ?: IllegalStateException(errMsg))
                }
                Log.w(TAG, "Gemini model $modelName unavailable ($errMsg); trying next model.")
            }
            Result.failure(
                lastFailure?.exceptionOrNull() ?: IllegalStateException("Gemini transcription failed.")
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini audio transcription timed out", e)
            Result.failure(
                IllegalStateException("Gemini API transcription timed out after 60 seconds. Try a shorter clip or retry.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini audio transcription failed", e)
            Result.failure(e)
        }
    }

    suspend fun transcribeFastDictation(
        audio: PreparedAudio,
        format: CantoneseFormat,
        customGlossary: String
    ): Result<GeminiAudioOutput> {
        val apiKey = keyStore.resolveDirectGeminiKey()
            ?: return Result.failure(missingKeyException())

        val startedAt = System.currentTimeMillis()
        val systemInstruction = CantonesePrompts.buildFastKeyboardSystemInstruction(format, customGlossary)
        val userPrompt = CantonesePrompts.buildFastKeyboardUserPrompt()
        val isWritten = format == CantoneseFormat.WRITTEN_CHINESE
        val candidates = buildModelCandidates(
            customModel = keyStore.resolveCustomGeminiModel(),
            preferred = preferredFastModel,
            defaults = FAST_DICTATION_MODELS
        )

        return try {
            var lastFailure: Result<String>? = null
            for (modelName in candidates) {
                val attempt = withTimeout(INFERENCE_TIMEOUT_MS) {
                    directRestClient.generateContent(
                        apiKey = apiKey,
                        modelName = modelName,
                        promptText = userPrompt,
                        systemInstruction = systemInstruction,
                        audioBytes = audio.bytes,
                        audioMimeType = audio.geminiMimeType,
                        temperature = 0.0f,
                        disableThinking = true
                    )
                }
                if (attempt.isSuccess) {
                    preferredFastModel = modelName
                    val text = TranscriptSanitizer.sanitizeSegment(
                        rawText = attempt.getOrNull().orEmpty(),
                        customGlossary = customGlossary
                    )
                    if (text.isEmpty()) {
                        return Result.failure(IllegalStateException("No clear speech detected in audio."))
                    }
                    return Result.success(
                        GeminiAudioOutput(
                            spokenCantonese = text,
                            writtenChinese = if (isWritten) text else null,
                            detectedLanguageMix = "Cantonese (Gemini API)",
                            engineId = TranscriptionEngineId.GEMINI_DIRECT_LITE,
                            latencyMillis = System.currentTimeMillis() - startedAt
                        )
                    )
                }
                lastFailure = attempt
                val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
                if (!isModelFallbackError(errMsg)) {
                    return Result.failure(attempt.exceptionOrNull() ?: IllegalStateException(errMsg))
                }
                Log.w(TAG, "Fast dictation model $modelName unavailable ($errMsg); trying next model.")
            }
            Result.failure(
                lastFailure?.exceptionOrNull() ?: IllegalStateException("Gemini dictation failed.")
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Live voice dictation timed out. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun convertSpokenToWritten(spokenCantonese: String): Result<String> {
        val apiKey = keyStore.resolveDirectGeminiKey()
            ?: return Result.failure(missingKeyException())

        val systemInstruction = CantonesePrompts.buildSpokenToWrittenSystemInstruction()
        val userPrompt = CantonesePrompts.buildSpokenToWrittenUserPrompt(spokenCantonese)
        val candidates = buildModelCandidates(
            customModel = keyStore.resolveCustomGeminiModel(),
            preferred = preferredTextModel,
            defaults = TEXT_FLASH_MEDIUM_MODELS
        )

        return try {
            var lastFailure: Result<String>? = null
            for (modelName in candidates) {
                val attempt = withTimeout(INFERENCE_TIMEOUT_MS) {
                    directRestClient.generateContent(
                        apiKey = apiKey,
                        modelName = modelName,
                        promptText = userPrompt,
                        systemInstruction = systemInstruction,
                        temperature = 0.1f,
                        disableThinking = true
                    )
                }
                if (attempt.isSuccess) {
                    preferredTextModel = modelName
                    val converted = attempt.getOrNull()?.trim().orEmpty()
                    if (converted.isNotEmpty()) return Result.success(converted)
                }
                lastFailure = attempt
                val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
                if (!isModelFallbackError(errMsg)) {
                    return Result.failure(attempt.exceptionOrNull() ?: IllegalStateException(errMsg))
                }
            }
            Result.failure(
                lastFailure?.exceptionOrNull() ?: IllegalStateException("Empty Written Chinese conversion response.")
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Conversion timed out. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deepReviewWithHighThinking(
        spokenCantonese: String,
        writtenChinese: String?,
        audioContext: PreparedAudio?,
        customGlossary: String
    ): Result<DeepReviewResult> {
        val apiKey = keyStore.resolveDirectGeminiKey()
            ?: return Result.failure(missingKeyException())

        val startedAt = System.currentTimeMillis()
        val systemInstruction = CantonesePrompts.buildDeepReviewSystemInstruction(customGlossary)
        val userPrompt = CantonesePrompts.buildDeepReviewUserPrompt(
            spokenCantonese = spokenCantonese,
            writtenChinese = writtenChinese
        )
        val attachableAudio = audioContext?.takeIf { it.sizeBytes <= 10L * 1024L * 1024L }
        val customModel = keyStore.resolveCustomGeminiModel()
        val candidates = buildModelCandidates(
            customModel = customModel,
            preferred = preferredTextModel,
            defaults = DEEP_REVIEW_MODELS
        )

        return try {
            var lastFailure: Result<String>? = null
            for (modelName in candidates) {
                val useHighThinking = modelName.contains("pro", ignoreCase = true) ||
                    modelName.contains("argon", ignoreCase = true)
                val attempt = withTimeout(INFERENCE_TIMEOUT_MS) {
                    directRestClient.generateContent(
                        apiKey = apiKey,
                        modelName = modelName,
                        promptText = userPrompt,
                        systemInstruction = systemInstruction,
                        audioBytes = attachableAudio?.bytes,
                        audioMimeType = attachableAudio?.geminiMimeType,
                        temperature = if (useHighThinking) null else 0.1f,
                        responseJsonSchema = DirectGeminiRestClient.deepReviewRestSchema(),
                        enableHighThinking = useHighThinking
                    )
                }
                if (attempt.isSuccess) {
                    return parseDeepReviewResponse(
                        rawJson = attempt.getOrNull()?.trim(),
                        spokenCantonese = spokenCantonese,
                        writtenChinese = writtenChinese,
                        startedAt = startedAt
                    )
                }
                lastFailure = attempt
                val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
                if (!isModelFallbackError(errMsg)) {
                    return Result.failure(attempt.exceptionOrNull() ?: IllegalStateException(errMsg))
                }
                Log.w(TAG, "Deep Review model $modelName unavailable ($errMsg); trying next model.")
            }
            Result.failure(
                lastFailure?.exceptionOrNull() ?: IllegalStateException("Deep Review failed.")
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Deep Review timed out after 60 seconds. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildModelCandidates(
        customModel: String?,
        preferred: String?,
        defaults: List<String>
    ): List<String> = buildList {
        customModel?.takeIf { it.isNotBlank() }?.let { add(it) }
        preferred?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(defaults)
    }.distinct()

    private fun isModelFallbackError(errMsg: String): Boolean {
        return errMsg.contains("(404)") ||
            errMsg.contains("not found", ignoreCase = true) ||
            errMsg.contains("is not supported", ignoreCase = true) ||
            errMsg.contains("model", ignoreCase = true) && errMsg.contains("(400)")
    }

    private fun missingKeyException(): IllegalStateException = IllegalStateException(
        "Gemini API key is not configured. Add and save your Gemini API key in the Keys tab."
    )

    private fun parseFullAudioResponse(
        rawJson: String?,
        customGlossary: String,
        engineId: TranscriptionEngineId,
        startedAt: Long
    ): Result<GeminiAudioOutput> {
        if (rawJson.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Gemini returned an empty transcript response."))
        }
        val json = JSONObject(rawJson)
        val rawSpoken = json.optString("spokenCantonese", "").trim()
        val spoken = TranscriptSanitizer.sanitizeSegment(
            rawText = rawSpoken,
            customGlossary = customGlossary
        )
        val rawWritten = json.optString("writtenChinese", "").trim()
        val written = TranscriptSanitizer.sanitizeSegment(
            rawText = rawWritten,
            customGlossary = customGlossary
        ).takeIf { it.isNotEmpty() }
        val langMix = json.optString("detectedLanguageMix", "Cantonese").ifBlank { "Cantonese" }

        if (spoken.isEmpty()) {
            return Result.failure(IllegalStateException("No clear Cantonese speech detected in audio."))
        }

        return Result.success(
            GeminiAudioOutput(
                spokenCantonese = spoken,
                writtenChinese = written,
                detectedLanguageMix = langMix,
                engineId = engineId,
                latencyMillis = System.currentTimeMillis() - startedAt
            )
        )
    }

    private fun parseDeepReviewResponse(
        rawJson: String?,
        spokenCantonese: String,
        writtenChinese: String?,
        startedAt: Long
    ): Result<DeepReviewResult> {
        if (rawJson.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Deep Review returned an empty response."))
        }
        val json = JSONObject(rawJson)
        val refinedSpoken = json.optString("refinedSpoken", spokenCantonese).ifBlank { spokenCantonese }
        val refinedWritten = json.optString("refinedWritten", writtenChinese.orEmpty())
        val notes = json.optJSONArray("cantoneseNotes").toStringList()
        val keyPoints = json.optJSONArray("keyPoints").toStringList()
        val replies = json.optJSONArray("suggestedReplies").toStringList()

        return Result.success(
            DeepReviewResult(
                refinedSpoken = refinedSpoken,
                refinedWritten = refinedWritten,
                cantoneseNotes = notes,
                keyPoints = keyPoints,
                suggestedReplies = replies,
                latencyMillis = System.currentTimeMillis() - startedAt
            )
        )
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                val item = optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }
}
