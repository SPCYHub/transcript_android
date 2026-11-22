package com.example.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "DirectGeminiRest"
private const val GEMINI_REST_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

/**
 * Direct Google Gemini REST API client (`generativelanguage.googleapis.com`) used ONLY when
 * the end user explicitly provides and enables their own personal Gemini API key in-app.
 *
 * Completely bypasses Firebase App Check so exported/sideloaded standalone APKs work seamlessly.
 */
class DirectGeminiRestClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun generateContent(
        apiKey: String,
        modelName: String,
        promptText: String,
        audioBytes: ByteArray? = null,
        audioMimeType: String? = null,
        temperature: Float? = 0.1f,
        responseJsonSchema: JSONObject? = null,
        enableHighThinking: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = buildRequestPayload(
                promptText = promptText,
                audioBytes = audioBytes,
                audioMimeType = audioMimeType,
                temperature = temperature,
                responseJsonSchema = responseJsonSchema,
                enableHighThinking = enableHighThinking
            )

            val url = "$GEMINI_REST_BASE/$modelName:generateContent"
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = parseRestError(response.code, rawBody, modelName)
                    return@withContext Result.failure(IllegalStateException(err))
                }
                val text = extractTextFromResponseJson(rawBody)
                if (text.isBlank()) {
                    Result.failure(IllegalStateException("Gemini API returned an empty response."))
                } else {
                    Result.success(text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Direct Gemini REST call failed ($modelName)", e)
            Result.failure(e)
        }
    }

    fun streamAudioSegment(
        apiKey: String,
        modelName: String,
        promptText: String,
        audioBytes: ByteArray,
        audioMimeType: String
    ): Flow<String> = flow {
        val payload = buildRequestPayload(
            promptText = promptText,
            audioBytes = audioBytes,
            audioMimeType = audioMimeType,
            temperature = 0.1f,
            responseJsonSchema = null,
            enableHighThinking = false
        )

        val url = "$GEMINI_REST_BASE/$modelName:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                throw IllegalStateException(parseRestError(response.code, errBody, modelName))
            }

            val source = response.body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data.isNotEmpty() && data != "[DONE]") {
                        val piece = extractTextFromResponseJson(data)
                        if (piece.isNotEmpty()) {
                            emit(piece)
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestPayload(
        promptText: String,
        audioBytes: ByteArray?,
        audioMimeType: String?,
        temperature: Float?,
        responseJsonSchema: JSONObject?,
        enableHighThinking: Boolean
    ): JSONObject {
        val parts = JSONArray()
        if (audioBytes != null && !audioMimeType.isNullOrBlank()) {
            val inlineData = JSONObject()
                .put("mime_type", audioMimeType)
                .put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP))
            parts.put(JSONObject().put("inline_data", inlineData))
        }
        parts.put(JSONObject().put("text", promptText))

        val userContent = JSONObject()
            .put("role", "user")
            .put("parts", parts)

        val genConfig = JSONObject()
        if (temperature != null && !enableHighThinking) {
            genConfig.put("temperature", temperature.toDouble())
        }
        if (responseJsonSchema != null) {
            genConfig.put("responseMimeType", "application/json")
            genConfig.put("responseSchema", responseJsonSchema)
        }
        if (enableHighThinking) {
            genConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", "HIGH")
            )
        }

        return JSONObject()
            .put("contents", JSONArray().put(userContent))
            .put("generationConfig", genConfig)
    }

    private fun extractTextFromResponseJson(rawJson: String): String {
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    val text = part.optString("text", "")
                    if (text.isNotEmpty()) append(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing Gemini REST JSON", e)
            ""
        }
    }

    private fun parseRestError(httpCode: Int, rawBody: String, modelName: String): String {
        val apiMessage = runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()

        return when {
            apiMessage.isNotBlank() -> "Gemini API ($httpCode): $apiMessage"
            httpCode == 400 -> "Gemini API rejected the request (400). Verify your API key or custom model '$modelName'."
            httpCode == 401 || httpCode == 403 -> "Invalid Gemini API key ($httpCode). Check your personal key in Keys."
            httpCode == 404 -> "Gemini model '$modelName' was not found (404). Update your custom model in Keys."
            httpCode == 429 -> "Gemini API quota or rate limit reached (429). Retry shortly or switch engine."
            else -> "Gemini API request failed (HTTP $httpCode)."
        }
    }

    companion object {
        fun audioTranscriptRestSchema(): JSONObject = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "spokenCantonese",
                        JSONObject().put("type", "STRING")
                            .put("description", "Verbatim Hong Kong colloquial Cantonese in Traditional Chinese with natural English code-switching")
                    )
                    .put(
                        "writtenChinese",
                        JSONObject().put("type", "STRING")
                            .put("description", "Polished Standard Written Chinese in Traditional Chinese, or empty string if omitted")
                    )
                    .put(
                        "detectedLanguageMix",
                        JSONObject().put("type", "STRING")
                            .put("description", "Short label describing the detected language mix, e.g. Cantonese + English")
                    )
            )
            .put("required", JSONArray(listOf("spokenCantonese", "writtenChinese", "detectedLanguageMix")))

        fun deepReviewRestSchema(): JSONObject = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("refinedSpoken", JSONObject().put("type", "STRING"))
                    .put("refinedWritten", JSONObject().put("type", "STRING"))
                    .put(
                        "cantoneseNotes",
                        JSONObject()
                            .put("type", "ARRAY")
                            .put("items", JSONObject().put("type", "STRING"))
                    )
                    .put(
                        "keyPoints",
                        JSONObject()
                            .put("type", "ARRAY")
                            .put("items", JSONObject().put("type", "STRING"))
                    )
                    .put(
                        "suggestedReplies",
                        JSONObject()
                            .put("type", "ARRAY")
                            .put("items", JSONObject().put("type", "STRING"))
                    )
            )
            .put(
                "required",
                JSONArray(listOf("refinedSpoken", "refinedWritten", "cantoneseNotes", "keyPoints", "suggestedReplies"))
            )
    }
}
