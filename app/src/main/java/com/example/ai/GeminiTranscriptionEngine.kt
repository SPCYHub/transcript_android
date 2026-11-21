package com.example.ai

import android.util.Log
import com.example.model.CantoneseFormat
import com.example.model.DeepReviewResult
import com.example.model.PreparedAudio
import com.example.model.TranscriptionEngineId
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiTranscriptionEngine"
private const val INFERENCE_TIMEOUT_MS = 60_000L

data class GeminiAudioOutput(
    val spokenCantonese: String,
    val writtenChinese: String?,
    val detectedLanguageMix: String,
    val engineId: TranscriptionEngineId,
    val latencyMillis: Long
)

/**
 * Gemini transcription and high-thinking analysis engine powered by Firebase AI Logic (`Firebase.ai`).
 *
 * - `gemini-3.8-flash`: Single-pass structured Spoken + Written Cantonese audio transcription.
 * - `gemini-3.5-flash-lite`: Ultra-low-latency voice keyboard bursts and fast text conversion.
 * - `gemini-4-carbon` (`ThinkingLevel.HIGH`, no `maxOutputTokens`): Deep Cantonese disambiguation,
 *   homophone/Chinglish review, key takeaways, and quick reply generation.
 */
class GeminiTranscriptionEngine {

    private val flashAudioModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = CantonesePrompts.audioTranscriptSchema
            }
        )
    }

    private val fastDictationModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.1f
            }
        )
    }

    // High-thinking model for complex/noisy Cantonese deep analysis.
    // Explicitly uses gemini-4-carbon with ThinkingLevel.HIGH and NO maxOutputTokens.
    private val deepThinkingModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-carbon",
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
                responseMimeType = "application/json"
                responseSchema = CantonesePrompts.deepReviewSchema
            }
        )
    }

    suspend fun transcribeFullAudio(
        audio: PreparedAudio,
        includeWritten: Boolean,
        customGlossary: String
    ): Result<GeminiAudioOutput> {
        val startedAt = System.currentTimeMillis()
        return try {
            val promptText = CantonesePrompts.buildGeminiAudioPrompt(
                includeWritten = includeWritten,
                customGlossary = customGlossary
            )
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                flashAudioModel.generateContent(
                    content("user") {
                        inlineData(audio.bytes, audio.geminiMimeType)
                        text(promptText)
                    }
                )
            }
            val rawJson = response.text?.trim()
                ?: return Result.failure(IllegalStateException("Gemini returned an empty transcript response."))

            val json = JSONObject(rawJson)
            val spoken = json.optString("spokenCantonese", "").trim()
            val written = json.optString("writtenChinese", "").trim().takeIf { it.isNotEmpty() }
            val langMix = json.optString("detectedLanguageMix", "Cantonese").ifBlank { "Cantonese" }

            if (spoken.isEmpty()) {
                return Result.failure(IllegalStateException("Gemini returned an empty Cantonese transcript."))
            }

            Result.success(
                GeminiAudioOutput(
                    spokenCantonese = spoken,
                    writtenChinese = written,
                    detectedLanguageMix = langMix,
                    engineId = TranscriptionEngineId.GEMINI_FLASH,
                    latencyMillis = System.currentTimeMillis() - startedAt
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini audio transcription timed out", e)
            Result.failure(
                IllegalStateException("Gemini transcription timed out after 60 seconds. Try a shorter clip or retry.")
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
        val startedAt = System.currentTimeMillis()
        return try {
            val promptText = CantonesePrompts.buildFastKeyboardPrompt(format, customGlossary)
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                fastDictationModel.generateContent(
                    content("user") {
                        inlineData(audio.bytes, audio.geminiMimeType)
                        text(promptText)
                    }
                )
            }
            val text = response.text?.trim().orEmpty()
            if (text.isEmpty()) {
                return Result.failure(IllegalStateException("Gemini returned empty dictation text."))
            }

            val isWritten = format == CantoneseFormat.WRITTEN_CHINESE
            Result.success(
                GeminiAudioOutput(
                    spokenCantonese = text,
                    writtenChinese = if (isWritten) text else null,
                    detectedLanguageMix = "Cantonese (Fast)",
                    engineId = TranscriptionEngineId.GEMINI_FLASH_LITE,
                    latencyMillis = System.currentTimeMillis() - startedAt
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini fast dictation timed out", e)
            Result.failure(IllegalStateException("Live voice dictation timed out. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini fast dictation failed", e)
            Result.failure(e)
        }
    }

    suspend fun convertSpokenToWritten(spokenCantonese: String): Result<String> {
        return try {
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                fastDictationModel.generateContent(
                    CantonesePrompts.buildSpokenToWrittenPrompt(spokenCantonese)
                )
            }
            val converted = response.text?.trim().orEmpty()
            if (converted.isEmpty()) {
                Result.failure(IllegalStateException("Empty Written Chinese conversion response."))
            } else {
                Result.success(converted)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Spoken-to-written conversion timed out", e)
            Result.failure(IllegalStateException("Conversion timed out. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Spoken-to-written conversion failed", e)
            Result.failure(e)
        }
    }

    suspend fun deepReviewWithHighThinking(
        spokenCantonese: String,
        writtenChinese: String?,
        audioContext: PreparedAudio?,
        customGlossary: String
    ): Result<DeepReviewResult> {
        val startedAt = System.currentTimeMillis()
        return try {
            val promptText = CantonesePrompts.buildDeepReviewPrompt(
                spokenCantonese = spokenCantonese,
                writtenChinese = writtenChinese,
                customGlossary = customGlossary
            )

            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                if (audioContext != null && audioContext.sizeBytes <= 10L * 1024L * 1024L) {
                    deepThinkingModel.generateContent(
                        content("user") {
                            inlineData(audioContext.bytes, audioContext.geminiMimeType)
                            text(promptText)
                        }
                    )
                } else {
                    deepThinkingModel.generateContent(promptText)
                }
            }

            val rawJson = response.text?.trim()
                ?: return Result.failure(IllegalStateException("Deep Review returned an empty response."))

            val json = JSONObject(rawJson)
            val refinedSpoken = json.optString("refinedSpoken", spokenCantonese).ifBlank { spokenCantonese }
            val refinedWritten = json.optString("refinedWritten", writtenChinese.orEmpty())
            val notes = json.optJSONArray("cantoneseNotes").toStringList()
            val keyPoints = json.optJSONArray("keyPoints").toStringList()
            val replies = json.optJSONArray("suggestedReplies").toStringList()

            Result.success(
                DeepReviewResult(
                    refinedSpoken = refinedSpoken,
                    refinedWritten = refinedWritten,
                    cantoneseNotes = notes,
                    keyPoints = keyPoints,
                    suggestedReplies = replies,
                    latencyMillis = System.currentTimeMillis() - startedAt
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini 4 Carbon Deep Review timed out", e)
            Result.failure(
                IllegalStateException("Deep Review timed out after 60 seconds. Please try again.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini 4 Carbon Deep Review failed", e)
            Result.failure(e)
        }
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
