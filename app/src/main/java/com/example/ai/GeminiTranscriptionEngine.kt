package com.example.ai

import android.util.Log
import com.example.data.SecureKeyStore
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
private const val DEFAULT_FLASH_MODEL = "gemini-3.8-flash"
private const val DEFAULT_FAST_MODEL = "gemini-3.5-flash-lite"
private const val DEFAULT_DEEP_MODEL = "gemini-4-argon"

data class GeminiAudioOutput(
    val spokenCantonese: String,
    val writtenChinese: String?,
    val detectedLanguageMix: String,
    val engineId: TranscriptionEngineId,
    val latencyMillis: Long
)

/**
 * Dual-path Gemini transcription & Deep Review engine:
 * - **Default (Built-in)**: Uses Firebase AI Logic (`Firebase.ai` via App Check, zero API key needed).
 * - **Personal Key (BYOK)**: When the user saves & enables their own Gemini API key in Keys,
 *   routes directly to `generativelanguage.googleapis.com` so standalone exported APKs work
 *   without requiring Firebase App Check tokens.
 */
class GeminiTranscriptionEngine(
    private val keyStore: SecureKeyStore,
    private val directRestClient: DirectGeminiRestClient
) {

    private val flashAudioModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_FLASH_MODEL,
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = CantonesePrompts.audioTranscriptSchema
            }
        )
    }

    private val fastDictationModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_FAST_MODEL,
            generationConfig = generationConfig {
                temperature = 0.1f
            }
        )
    }

    private val deepThinkingModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_DEEP_MODEL,
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
        val promptText = CantonesePrompts.buildGeminiAudioPrompt(
            includeWritten = includeWritten,
            customGlossary = customGlossary
        )

        val directKey = keyStore.resolveDirectGeminiKey()
        if (directKey != null) {
            return transcribeFullAudioDirect(
                apiKey = directKey,
                audio = audio,
                promptText = promptText,
                startedAt = startedAt
            )
        }

        return try {
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                flashAudioModel.generateContent(
                    content("user") {
                        inlineData(audio.bytes, audio.geminiMimeType)
                        text(promptText)
                    }
                )
            }
            parseFullAudioResponse(
                rawJson = response.text?.trim(),
                engineId = TranscriptionEngineId.GEMINI_FLASH,
                startedAt = startedAt
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
        val promptText = CantonesePrompts.buildFastKeyboardPrompt(format, customGlossary)
        val isWritten = format == CantoneseFormat.WRITTEN_CHINESE

        val directKey = keyStore.resolveDirectGeminiKey()
        if (directKey != null) {
            val modelName = keyStore.resolveCustomGeminiModel() ?: DEFAULT_FAST_MODEL
            return try {
                val restResult = withTimeout(INFERENCE_TIMEOUT_MS) {
                    directRestClient.generateContent(
                        apiKey = directKey,
                        modelName = modelName,
                        promptText = promptText,
                        audioBytes = audio.bytes,
                        audioMimeType = audio.geminiMimeType,
                        temperature = 0.1f
                    )
                }
                restResult.map { raw ->
                    val text = raw.trim()
                    GeminiAudioOutput(
                        spokenCantonese = text,
                        writtenChinese = if (isWritten) text else null,
                        detectedLanguageMix = "Cantonese (Direct Key)",
                        engineId = TranscriptionEngineId.GEMINI_DIRECT_LITE,
                        latencyMillis = System.currentTimeMillis() - startedAt
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Result.failure(IllegalStateException("Live voice dictation timed out. Please try again."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        return try {
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
        val prompt = CantonesePrompts.buildSpokenToWrittenPrompt(spokenCantonese)
        val directKey = keyStore.resolveDirectGeminiKey()
        if (directKey != null) {
            val modelName = keyStore.resolveCustomGeminiModel() ?: DEFAULT_FAST_MODEL
            return try {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    directRestClient.generateContent(
                        apiKey = directKey,
                        modelName = modelName,
                        promptText = prompt,
                        temperature = 0.1f
                    )
                }.map { it.trim() }
            } catch (e: TimeoutCancellationException) {
                Result.failure(IllegalStateException("Conversion timed out. Please retry."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        return try {
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                fastDictationModel.generateContent(prompt)
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
        val promptText = CantonesePrompts.buildDeepReviewPrompt(
            spokenCantonese = spokenCantonese,
            writtenChinese = writtenChinese,
            customGlossary = customGlossary
        )
        val attachableAudio = audioContext?.takeIf { it.sizeBytes <= 10L * 1024L * 1024L }

        val directKey = keyStore.resolveDirectGeminiKey()
        if (directKey != null) {
            return deepReviewDirect(
                apiKey = directKey,
                promptText = promptText,
                audioContext = attachableAudio,
                spokenCantonese = spokenCantonese,
                writtenChinese = writtenChinese,
                startedAt = startedAt
            )
        }

        return try {
            val response = withTimeout(INFERENCE_TIMEOUT_MS) {
                if (attachableAudio != null) {
                    deepThinkingModel.generateContent(
                        content("user") {
                            inlineData(attachableAudio.bytes, attachableAudio.geminiMimeType)
                            text(promptText)
                        }
                    )
                } else {
                    deepThinkingModel.generateContent(promptText)
                }
            }
            parseDeepReviewResponse(
                rawJson = response.text?.trim(),
                spokenCantonese = spokenCantonese,
                writtenChinese = writtenChinese,
                startedAt = startedAt
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini Deep Review timed out", e)
            Result.failure(
                IllegalStateException("Deep Review timed out after 60 seconds. Please try again.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Deep Review failed", e)
            Result.failure(e)
        }
    }

    private suspend fun transcribeFullAudioDirect(
        apiKey: String,
        audio: PreparedAudio,
        promptText: String,
        startedAt: Long
    ): Result<GeminiAudioOutput> {
        val primaryModel = keyStore.resolveCustomGeminiModel() ?: DEFAULT_FLASH_MODEL
        return try {
            val restResult = withTimeout(INFERENCE_TIMEOUT_MS) {
                directRestClient.generateContent(
                    apiKey = apiKey,
                    modelName = primaryModel,
                    promptText = promptText,
                    audioBytes = audio.bytes,
                    audioMimeType = audio.geminiMimeType,
                    temperature = 0.1f,
                    responseJsonSchema = DirectGeminiRestClient.audioTranscriptRestSchema()
                )
            }
            restResult.fold(
                onSuccess = { raw ->
                    parseFullAudioResponse(
                        rawJson = raw.trim(),
                        engineId = TranscriptionEngineId.GEMINI_DIRECT_FLASH,
                        startedAt = startedAt
                    )
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Gemini API transcription timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun deepReviewDirect(
        apiKey: String,
        promptText: String,
        audioContext: PreparedAudio?,
        spokenCantonese: String,
        writtenChinese: String?,
        startedAt: Long
    ): Result<DeepReviewResult> {
        val customModel = keyStore.resolveCustomGeminiModel()
        val primaryModel = customModel ?: DEFAULT_DEEP_MODEL
        return try {
            val primaryResult = withTimeout(INFERENCE_TIMEOUT_MS) {
                directRestClient.generateContent(
                    apiKey = apiKey,
                    modelName = primaryModel,
                    promptText = promptText,
                    audioBytes = audioContext?.bytes,
                    audioMimeType = audioContext?.geminiMimeType,
                    temperature = null,
                    responseJsonSchema = DirectGeminiRestClient.deepReviewRestSchema(),
                    enableHighThinking = customModel == null
                )
            }

            val finalResult = if (primaryResult.isFailure && customModel == null) {
                val msg = primaryResult.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("404") || msg.contains("not found", ignoreCase = true) || msg.contains("thinking", ignoreCase = true)) {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        directRestClient.generateContent(
                            apiKey = apiKey,
                            modelName = DEFAULT_FLASH_MODEL,
                            promptText = promptText,
                            audioBytes = audioContext?.bytes,
                            audioMimeType = audioContext?.geminiMimeType,
                            temperature = 0.2f,
                            responseJsonSchema = DirectGeminiRestClient.deepReviewRestSchema(),
                            enableHighThinking = false
                        )
                    }
                } else {
                    primaryResult
                }
            } else {
                primaryResult
            }

            finalResult.fold(
                onSuccess = { raw ->
                    parseDeepReviewResponse(
                        rawJson = raw.trim(),
                        spokenCantonese = spokenCantonese,
                        writtenChinese = writtenChinese,
                        startedAt = startedAt
                    )
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Deep Review timed out after 60 seconds. Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseFullAudioResponse(
        rawJson: String?,
        engineId: TranscriptionEngineId,
        startedAt: Long
    ): Result<GeminiAudioOutput> {
        if (rawJson.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Gemini returned an empty transcript response."))
        }
        val json = JSONObject(rawJson)
        val spoken = json.optString("spokenCantonese", "").trim()
        val written = json.optString("writtenChinese", "").trim().takeIf { it.isNotEmpty() }
        val langMix = json.optString("detectedLanguageMix", "Cantonese").ifBlank { "Cantonese" }

        if (spoken.isEmpty()) {
            return Result.failure(IllegalStateException("Gemini returned an empty Cantonese transcript."))
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
