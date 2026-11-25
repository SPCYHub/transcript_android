package com.example.ai

import android.util.Log
import com.example.data.SecureKeyStore
import com.example.model.AudioSourceType
import com.example.model.DeepReviewResult
import com.example.model.PreparedAudio
import com.example.model.TranscriptionEngineId
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
private const val OPENAI_TIMEOUT_MS = 50_000L

private data class OpenAiModelCandidate(
    val modelId: String,
    val engineId: TranscriptionEngineId
)

/**
 * Latest OpenAI Speech-to-Text model chain for `/v1/audio/transcriptions`:
 * - `gpt-transcribe`: Latest SOTA OpenAI audio transcription model.
 * - `gpt-4o-transcribe` / `gpt-4o-mini-transcribe`: Legacy compatibility fallbacks.
 */
private val OPENAI_REST_MODEL_CHAIN = listOf(
    OpenAiModelCandidate("gpt-transcribe", TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE),
    OpenAiModelCandidate("gpt-4o-transcribe", TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE),
    OpenAiModelCandidate("gpt-4o-mini-transcribe", TranscriptionEngineId.OPENAI_MINI_FALLBACK)
)

/**
 * Latest OpenAI "Medium" / "Flash" tier text models (GPT-6 family) for Written Chinese conversion
 * and Deep Cantonese Review:
 * - `gpt-6.2-sol`: GPT-6 Medium tier balancing intelligence and cost efficiency for everyday text tasks.
 * - `gpt-6.1-terra`: Fast, efficient GPT-6 tier optimized for natural text generation.
 * - `gpt-6.1-sol` / `gpt-5.2` / `gpt-5-mini` / `gpt-4.1-mini`: Progressive fallbacks.
 */
private val OPENAI_TEXT_MEDIUM_MODELS = listOf(
    "gpt-6.2-sol",
    "gpt-6.1-terra",
    "gpt-6.1-sol",
    "gpt-5.2",
    "gpt-5-mini",
    "gpt-4.1-mini"
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
 * OpenAI Speech-to-Text (`/v1/audio/transcriptions`) and Text-Processing (`/v1/chat/completions`) engine:
 * - Audio STT: `gpt-transcribe` -> `gpt-4o-transcribe` -> `gpt-4o-mini-transcribe` with
 *   deterministic `temperature = 0`, strictly non-instructional `prompt`, and `TranscriptSanitizer`.
 * - Text tasks (Spoken-to-Written Chinese & Deep Review): GPT-6 Medium tier (`gpt-6.2-sol` ->
 *   `gpt-6.1-terra`) with automatic fallback chain.
 */
class OpenAiTranscriptionEngine(
    private val keyStore: SecureKeyStore
) {

    val isConfigured: Boolean
        get() = keyStore.hasEffectiveOpenAiKey()

    @Volatile
    private var preferredModelIndex = 0

    @Volatile
    private var preferredTextModel: String? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val api: OpenAiAudioApi by lazy {
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        Retrofit.Builder()
            .baseUrl(ProviderRequestAuth.OPENAI_REST_BASE)
            .client(httpClient)
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
            return Result.failure(missingKeyException())
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
                    if (!isModelFallbackError(errMsg)) {
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
            val safeMsg = ProviderRequestAuth.redactSecrets(e.localizedMessage)
                .ifBlank { "OpenAI transcription request failed." }
            Log.e(TAG, "OpenAI transcription request failed: $safeMsg")
            Result.failure(IllegalStateException(safeMsg))
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

    /**
     * Converts Spoken Cantonese to Standard Written Chinese (書面語) using the latest OpenAI
     * Medium-tier text model (`gpt-6.2-sol` -> `gpt-6.1-terra` fallback chain).
     */
    suspend fun convertSpokenToWritten(spokenCantonese: String): Result<String> {
        val apiKey = keyStore.resolveEffectiveOpenAiKey()
            ?: return Result.failure(missingKeyException())

        val systemPrompt = CantonesePrompts.buildSpokenToWrittenSystemInstruction()
        val userPrompt = CantonesePrompts.buildSpokenToWrittenUserPrompt(spokenCantonese)

        return callChatCompletions(
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            expectJsonObject = false
        ).mapCatching { text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw IllegalStateException("OpenAI returned an empty Written Chinese conversion.")
            trimmed
        }
    }

    /**
     * Runs a Deep Cantonese Review (refined spoken/written, idiom notes, key points, replies)
     * using the latest OpenAI Medium-tier text model (`gpt-6.2-sol` -> `gpt-6.1-terra`).
     */
    suspend fun deepReview(
        spokenCantonese: String,
        writtenChinese: String?,
        customGlossary: String
    ): Result<DeepReviewResult> {
        val apiKey = keyStore.resolveEffectiveOpenAiKey()
            ?: return Result.failure(missingKeyException())

        val startedAt = System.currentTimeMillis()
        val systemPrompt = CantonesePrompts.buildDeepReviewSystemInstruction(customGlossary) +
            "\nReturn strictly valid JSON with keys: refinedSpoken (string), refinedWritten (string), " +
            "cantoneseNotes (string array), keyPoints (string array), suggestedReplies (string array)."
        val userPrompt = CantonesePrompts.buildDeepReviewUserPrompt(
            spokenCantonese = spokenCantonese,
            writtenChinese = writtenChinese
        )

        return callChatCompletions(
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            expectJsonObject = true
        ).mapCatching { rawJson ->
            val json = JSONObject(rawJson.trim())
            DeepReviewResult(
                refinedSpoken = json.optString("refinedSpoken", spokenCantonese).ifBlank { spokenCantonese },
                refinedWritten = json.optString("refinedWritten", writtenChinese.orEmpty()),
                cantoneseNotes = json.optJSONArray("cantoneseNotes").toStringList(),
                keyPoints = json.optJSONArray("keyPoints").toStringList(),
                suggestedReplies = json.optJSONArray("suggestedReplies").toStringList(),
                latencyMillis = System.currentTimeMillis() - startedAt
            )
        }
    }

    private suspend fun callChatCompletions(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        expectJsonObject: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        val candidates = buildList {
            preferredTextModel?.let { add(it) }
            addAll(OPENAI_TEXT_MEDIUM_MODELS)
        }.distinct()

        try {
            withTimeout(OPENAI_TIMEOUT_MS) {
                var lastFailure: Result<String>? = null
                for (modelId in candidates) {
                    val attempt = executeChatRequest(
                        apiKey = apiKey,
                        modelId = modelId,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        expectJsonObject = expectJsonObject,
                        includeTemperature = true
                    ).let { res ->
                        // Some newer reasoning-capable GPT models reject custom `temperature` with 400; retry cleanly without it.
                        val err = res.exceptionOrNull()?.message.orEmpty()
                        if (res.isFailure && err.contains("(400)") && err.contains("temperature", ignoreCase = true)) {
                            executeChatRequest(
                                apiKey = apiKey,
                                modelId = modelId,
                                systemPrompt = systemPrompt,
                                userPrompt = userPrompt,
                                expectJsonObject = expectJsonObject,
                                includeTemperature = false
                            )
                        } else {
                            res
                        }
                    }

                    if (attempt.isSuccess) {
                        preferredTextModel = modelId
                        return@withTimeout attempt
                    }

                    lastFailure = attempt
                    val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
                    if (!isModelFallbackError(errMsg)) {
                        return@withTimeout attempt
                    }
                    Log.w(TAG, "OpenAI text model $modelId unavailable ($errMsg); trying next model.")
                }
                lastFailure ?: Result.failure(IllegalStateException("OpenAI text generation failed."))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("OpenAI text request timed out. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeChatRequest(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        expectJsonObject: Boolean,
        includeTemperature: Boolean
    ): Result<String> {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))

        val bodyJson = JSONObject()
            .put("model", modelId)
            .put("messages", messages)
        if (includeTemperature) {
            bodyJson.put("temperature", 0.1)
        }
        if (expectJsonObject) {
            bodyJson.put("response_format", JSONObject().put("type", "json_object"))
        }

        val request = Request.Builder()
            .url(ProviderRequestAuth.OPENAI_CHAT_COMPLETIONS_URL)
            .header("Authorization", ProviderRequestAuth.openAiAuthorization(apiKey))
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@use Result.failure(
                    IllegalStateException(extractOpenAiErrorMessage(response.code, rawBody))
                )
            }
            val root = JSONObject(rawBody)
            val choices = root.optJSONArray("choices")
            val content = choices?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            if (content.isEmpty()) {
                Result.failure(IllegalStateException("OpenAI returned an empty text response."))
            } else {
                Result.success(content)
            }
        }
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
            authorization = ProviderRequestAuth.openAiAuthorization(apiKey),
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

    private fun isModelFallbackError(errMsg: String): Boolean {
        return errMsg.contains("model", ignoreCase = true) ||
            errMsg.contains("404") ||
            errMsg.contains("400") ||
            errMsg.contains("does not exist", ignoreCase = true) ||
            errMsg.contains("not found", ignoreCase = true) ||
            errMsg.contains("unsupported", ignoreCase = true) ||
            errMsg.contains("not have access", ignoreCase = true)
    }

    private fun missingKeyException(): IllegalStateException = IllegalStateException(
        "OpenAI API key is not configured. Add your OpenAI API key in the Keys tab, or switch to Auto / Gemini."
    )

    private fun extractOpenAiErrorMessage(httpCode: Int, rawErrorBody: String?): String {
        if (!rawErrorBody.isNullOrBlank()) {
            try {
                val root = JSONObject(rawErrorBody)
                val errObj = root.optJSONObject("error")
                val msg = ProviderRequestAuth.redactSecrets(errObj?.optString("message").orEmpty())
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

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                val item = optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }
}
