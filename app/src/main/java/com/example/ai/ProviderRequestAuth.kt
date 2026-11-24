package com.example.ai

/**
 * Shared request authentication helpers for direct provider APIs.
 *
 * Keys are always supplied by the caller (typically [com.example.data.SecureKeyStore]).
 * Nothing here embeds, defaults, or reads a build-time App Check token.
 */
object ProviderRequestAuth {
    const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
    const val GEMINI_REST_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    const val GEMINI_BIDI_WS_BASE =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val OPENAI_REALTIME_TRANSCRIPTION_URL =
        "wss://api.openai.com/v1/realtime?intent=transcription"

    fun geminiGenerateContentUrl(modelName: String): String =
        "$GEMINI_REST_BASE/$modelName:generateContent"

    fun geminiStreamGenerateContentUrl(modelName: String): String =
        "$GEMINI_REST_BASE/$modelName:streamGenerateContent?alt=sse"

    fun geminiLiveWebSocketUrl(apiKey: String): String =
        "$GEMINI_BIDI_WS_BASE?key=$apiKey"

    fun openAiAuthorization(apiKey: String): String = "Bearer $apiKey"
}
