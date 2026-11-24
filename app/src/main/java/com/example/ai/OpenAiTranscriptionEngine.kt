package com.example.ai

import android.util.Log
import com.example.data.SecureKeyStore
import com.example.model.AudioSourceType
import com.example.model.PreparedAudio
import com.example.model.TranscriptionEngineId
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAiTranscriptionEngine"
private const val OPENAI_BASE_URL = "https://api.openai.com/"
private const val OPENAI_TIMEOUT_MS = 50_000L

private data class OpenAiModelCandidate(
    val modelId: String,
    val engineId: TranscriptionEngineId
)

private val OPENAI_REST_MODEL_CHAIN = listOf(
    OpenAiModelCandidate("gpt-4o-transcribe", TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE),
    OpenAiModelCandidate("gpt-4o-mini-transcribe", TranscriptionEngineId.OPENAI_MINI_FALLBACK)
)

@JsonClass(generateAdapter = true)
data class OpenAiTranscriptionResponse(
    @Json(name = "text") val text: String?
)

interface OpenAiAudioApi {
    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun createTranscription(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody,
        @Part("temperature") temperature: RequestBody,
        @Part("prompt") prompt: RequestBody?
    ): Response<OpenAiTranscriptionResponse>
}

data class OpenAiAudioOutput(
    val text: String,
    val engineId: TranscriptionEngineId,
    val latencyMillis: Long
)

/**
 * OpenAI Speech-to-Text engine targeting `/v1/audio/transcriptions` with:
 * - Deterministic `temperature = 0` per OpenAI prompting guide to eliminate repetition loops.
 * - Strictly non-instructional `prompt` (only user glossary or prior context tail; never AI
 *   command sentences that get echoed during pauses).
 * - Post-transcription `TranscriptSanitizer` filtering.
 * - Automatic model fallback (`gpt-4o-transcribe` -> `gpt-4o-mini-transcribe`).
 */
class OpenAiTranscriptionEngine(
    private val keyStore: SecureKeyStore
) {

    val isConfigured: Boolean
        get() = keyStore.hasEffectiveOpenAiKey()

    @Volatile
    private var preferredModelIndex = 0

    private val api: OpenAiAudioApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        Retrofit.Builder()
            .baseUrl(OPENAI_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAiAudioApi::class.java)
    }

    suspend fun transcribe(
        audio: PreparedAudio,
        customGlossary: String,
        promptOverride: String? = null
    ): Result<OpenAiAudioOutput> {
        val effectiveKey = keyStore.resolveEffectiveOpenAiKey()
        if (effectiveKey.isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "OpenAI API key is not configured. Add your OpenAI API key in the Keys tab, or switch to Auto / Gemini."
                )
            )
        }

        val startedAt = System.currentTimeMillis()
        val prompt = promptOverride ?: CantonesePrompts.buildOpenAiPrompt(
            customGlossary = customGlossary
        )

        return try {
            withTimeout(OPENAI_TIMEOUT_MS) {
                val startIndex = preferredModelIndex.coerceIn(0, OPENAI_REST_MODEL_CHAIN.lastIndex)
                var lastFailure: Result<OpenAiAudioOutput>? = null

                for (idx in startIndex until OPENAI_REST_MODEL_CHAIN.size) {
                    val candidate = OPENAI_REST_MODEL_CHAIN[idx]
                    val attempt = executeRequest(
                        apiKey = effectiveKey,
                        modelId = candidate.modelId,
                        engineId = candidate.engineId,
                        audio = audio,
                        prompt = prompt,
                        customGlossary = customGlossary,
                        startedAt = startedAt
                    )
                    if (attempt.isSuccess) {
                        preferredModelIndex = idx
                        return@withTimeout attempt
                    }

                    lastFailure = attempt
                    val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
                    val shouldTryNext = errMsg.contains("model", ignoreCase = true) ||
                        errMsg.contains("404") ||
                        errMsg.contains("400") ||
                        errMsg.contains("does not exist", ignoreCase = true) ||
                        errMsg.contains("not found", ignoreCase = true) ||
                        errMsg.contains("unsupported", ignoreCase = true)

                    if (!shouldTryNext) {
                        return@withTimeout attempt
                    }
                    Log.w(TAG, "OpenAI model ${candidate.modelId} unavailable ($errMsg); trying next model.")
                }

                lastFailure ?: Result.failure(IllegalStateException("OpenAI transcription failed."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "OpenAI transcription timed out", e)
            Result.failure(
                IllegalStateException("OpenAI transcription timed out. Please retry or switch to Gemini.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI transcription request failed", e)
            Result.failure(e)
        }
    }

    /**
     * Convenience helper for live chunked WAV fallback transcription via OpenAI REST API.
     */
    suspend fun transcribeWavChunk(
        wavBytes: ByteArray,
        customGlossary: String,
        contextTail: String = ""
    ): Result<OpenAiAudioOutput> {
        val prompt = CantonesePrompts.buildOpenAiPrompt(
            customGlossary = customGlossary,
            contextTail = contextTail
        )
        val audio = PreparedAudio(
            bytes = wavBytes,
            fileName = "live_chunk.wav",
            geminiMimeType = "audio/wav",
            openAiFileName = "live_chunk.wav",
            openAiMimeType = "audio/wav",
            durationMillis = 0L,
            sizeBytes = wavBytes.size.toLong(),
            sha256 = "",
            sourceType = AudioSourceType.STUDIO_RECORD,
            codecLabel = "WAV PCM16",
            hadSpeechActivity = true
        )
        return transcribe(
            audio = audio,
            customGlossary = customGlossary,
            promptOverride = prompt
        )
    }

    private suspend fun executeRequest(
        apiKey: String,
        modelId: String,
        engineId: TranscriptionEngineId,
        audio: PreparedAudio,
        prompt: String,
        customGlossary: String,
        startedAt: Long
    ): Result<OpenAiAudioOutput> {
        val textPlain = "text/plain".toMediaType()
        val audioMediaType = audio.openAiMimeType.toMediaType()
        val fileBody = audio.bytes.toRequestBody(audioMediaType)
        val filePart = MultipartBody.Part.createFormData("file", audio.openAiFileName, fileBody)

        val response = api.createTranscription(
            authorization = "Bearer $apiKey",
            file = filePart,
            model = modelId.toRequestBody(textPlain),
            responseFormat = "json".toRequestBody(textPlain),
            temperature = "0".toRequestBody(textPlain),
            prompt = prompt.takeIf { it.isNotBlank() }?.toRequestBody(textPlain)
        )

        if (response.isSuccessful) {
            val rawText = response.body()?.text?.trim().orEmpty()
            val cleaned = TranscriptSanitizer.sanitizeSegment(
                rawText = rawText,
                activePrompt = prompt,
                customGlossary = customGlossary
            )
            if (cleaned.isEmpty()) {
                return Result.failure(IllegalStateException("OpenAI returned no clear speech."))
            }
            return Result.success(
                OpenAiAudioOutput(
                    text = cleaned,
                    engineId = engineId,
                    latencyMillis = System.currentTimeMillis() - startedAt
                )
            )
        }

        val parsedError = extractOpenAiErrorMessage(response.code(), response.errorBody()?.string())
        return Result.failure(IllegalStateException(parsedError))
    }

    private fun extractOpenAiErrorMessage(httpCode: Int, rawErrorBody: String?): String {
        if (!rawErrorBody.isNullOrBlank()) {
            try {
                val root = JSONObject(rawErrorBody)
                val errObj = root.optJSONObject("error")
                val msg = errObj?.optString("message").orEmpty()
                if (msg.isNotBlank()) return "OpenAI ($httpCode): $msg"
            } catch (_: Exception) {
            }
        }
        return when (httpCode) {
            401 -> "OpenAI authentication failed (401). Check your OpenAI API key in the Keys tab."
            429 -> "OpenAI rate limit or quota reached (429). Switch to Gemini or retry shortly."
            413 -> "Audio payload exceeds OpenAI's upload limit (413)."
            else -> "OpenAI request failed with HTTP $httpCode."
        }
    }
}
