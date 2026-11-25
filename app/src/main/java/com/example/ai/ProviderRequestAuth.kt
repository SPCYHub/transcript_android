package com.example.ai

/**
 * Shared request authentication and endpoint helpers for provider APIs routed via `api-router.alchemys.ai`.
 *
 * Keys are always supplied by the caller (typically [com.example.data.SecureKeyStore]).
 * Nothing here embeds, defaults, or reads a build-time App Check token, and no router token is used.
 */
object ProviderRequestAuth {
    const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
    const val GEMINI_REST_BASE = "https://api-router.alchemys.ai/gemini/v1beta/models"
    const val GEMINI_MODELS_LIST_URL = "$GEMINI_REST_BASE?pageSize=5"
    const val GEMINI_BIDI_WS_BASE =
        "wss://api-router.alchemys.ai/gemini/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val OPENAI_REST_BASE = "https://api-router.alchemys.ai/openai/"
    const val OPENAI_CHAT_COMPLETIONS_URL = "https://api-router.alchemys.ai/openai/v1/chat/completions"
    const val OPENAI_MODELS_URL = "https://api-router.alchemys.ai/openai/v1/models"
    const val OPENAI_REALTIME_TRANSCRIPTION_URL =
        "wss://api-router.alchemys.ai/openai/v1/realtime?intent=transcription"

    private val MODEL_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")
    private val KEY_QUERY_REGEX = Regex("([?&]key=)[^&\\s\"']+", RegexOption.IGNORE_CASE)
    private val BEARER_REGEX = Regex("(Bearer\\s+)[A-Za-z0-9._\\-+/=]+", RegexOption.IGNORE_CASE)
    private val GOOGLE_KEY_REGEX = Regex("AIza[0-9A-Za-z_-]{20,}")
    private val OPENAI_KEY_REGEX = Regex("sk-[A-Za-z0-9_-]{12,}")

    fun sanitizeGeminiModelName(rawModel: String): String {
        val stripped = rawModel.trim().removePrefix("models/").trim()
        require(MODEL_NAME_REGEX.matches(stripped)) {
            "Invalid Gemini model name. Use letters, numbers, dots, underscores, or hyphens only."
        }
        return stripped
    }

    fun isValidGeminiModelName(rawModel: String): Boolean {
        val stripped = rawModel.trim().removePrefix("models/").trim()
        return MODEL_NAME_REGEX.matches(stripped)
    }

    fun redactSecrets(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text
            .replace(KEY_QUERY_REGEX, "$1[REDACTED]")
            .replace(BEARER_REGEX, "$1[REDACTED]")
            .replace(GOOGLE_KEY_REGEX, "[REDACTED_KEY]")
            .replace(OPENAI_KEY_REGEX, "[REDACTED_KEY]")
    }

    fun geminiGenerateContentUrl(modelName: String): String {
        val safeModel = sanitizeGeminiModelName(modelName)
        return "$GEMINI_REST_BASE/$safeModel:generateContent"
    }

    fun geminiStreamGenerateContentUrl(modelName: String): String {
        val safeModel = sanitizeGeminiModelName(modelName)
        return "$GEMINI_REST_BASE/$safeModel:streamGenerateContent?alt=sse"
    }

    fun geminiLiveWebSocketUrl(apiKey: String): String =
        "$GEMINI_BIDI_WS_BASE?key=$apiKey"

    fun openAiAuthorization(apiKey: String): String = "Bearer $apiKey"
}
