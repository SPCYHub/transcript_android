package com.example.ai

import android.util.Log
import com.example.data.SecureKeyStore
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
private const val PRIMARY_MODEL = "gpt-transcribe"
private const val FALLBACK_MODEL = "gpt-4o-mini-transcribe"

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
        @Part("prompt") prompt: RequestBody?
    ): Response<OpenAiTranscriptionResponse>
}

data class OpenAiAudioOutput(
    val text: String,
    val engineId: TranscriptionEngineId,
    val latencyMillis: Long
)

/**
 * OpenAI Speech-to-Text engine targeting `/v1/audio/transcriptions`.
 * Resolves the user's personal key from [SecureKeyStore] first, falling back to BuildConfig.
 */
class OpenAiTranscriptionEngine(
    private val keyStore: SecureKeyStore
) {

    val isConfigured: Boolean
        get() = keyStore.hasEffectiveOpenAiKey()

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
        preferWritten: Boolean = false
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
        val prompt = CantonesePrompts.buildOpenAiPrompt(
            customGlossary = customGlossary,
            preferWritten = preferWritten
        )

        return try {
            withTimeout(OPENAI_TIMEOUT_MS) {
                val primaryResult = executeRequest(
                    apiKey = effectiveKey,
                    modelId = PRIMARY_MODEL,
                    engineId = TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE,
                    audio = audio,
                    prompt = prompt,
                    startedAt = startedAt
                )
                if (primaryResult.isSuccess) {
                    return@withTimeout primaryResult
                }

                val errMsg = primaryResult.exceptionOrNull()?.message.orEmpty()
                val shouldTryFallback = errMsg.contains("model", ignoreCase = true) ||
                    errMsg.contains("404") ||
                    errMsg.contains("does not exist", ignoreCase = true)

                if (shouldTryFallback) {
                    Log.w(TAG, "Primary model $PRIMARY_MODEL unavailable ($errMsg); retrying with $FALLBACK_MODEL")
                    executeRequest(
                        apiKey = effectiveKey,
                        modelId = FALLBACK_MODEL,
                        engineId = TranscriptionEngineId.OPENAI_MINI_FALLBACK,
                        audio = audio,
                        prompt = prompt,
                        startedAt = startedAt
                    )
                } else {
                    primaryResult
                }
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

    private suspend fun executeRequest(
        apiKey: String,
        modelId: String,
        engineId: TranscriptionEngineId,
        audio: PreparedAudio,
        prompt: String,
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
            prompt = prompt.takeIf { it.isNotBlank() }?.toRequestBody(textPlain)
        )

        if (response.isSuccessful) {
            val transcriptText = response.body()?.text?.trim().orEmpty()
            if (transcriptText.isEmpty()) {
                return Result.failure(IllegalStateException("OpenAI returned an empty transcript."))
            }
            return Result.success(
                OpenAiAudioOutput(
                    text = transcriptText,
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
