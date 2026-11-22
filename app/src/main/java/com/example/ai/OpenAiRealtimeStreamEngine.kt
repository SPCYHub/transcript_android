package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.data.SecureKeyStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAiRealtimeStream"
private const val REALTIME_TRANSCRIPTION_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
private const val REALTIME_TRANSCRIBE_MODEL = "gpt-live-transcribe"

interface RealtimeTranscriptListener {
    fun onConnected()
    fun onPartialDelta(delta: String)
    fun onSegmentCompleted(transcript: String)
    fun onError(message: String)
    fun onClosed()
}

/**
 * Official OpenAI Realtime Transcription WebSocket client.
 * Reads user-entered OpenAI API key from [SecureKeyStore] first, falling back to BuildConfig.
 */
class OpenAiRealtimeStreamEngine(
    private val keyStore: SecureKeyStore
) {

    val isConfigured: Boolean
        get() = keyStore.hasEffectiveOpenAiKey()

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private var activeSocket: WebSocket? = null
    @Volatile
    private var isOpen = false

    fun connect(
        customGlossary: String,
        preferWritten: Boolean,
        listener: RealtimeTranscriptListener
    ): Boolean {
        val effectiveKey = keyStore.resolveEffectiveOpenAiKey()
        if (effectiveKey.isNullOrBlank()) {
            listener.onError("OpenAI API key not configured. Add it in the Keys tab.")
            return false
        }

        disconnect()

        val request = Request.Builder()
            .url(REALTIME_TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $effectiveKey")
            .build()

        val prompt = CantonesePrompts.buildOpenAiPrompt(
            customGlossary = customGlossary,
            preferWritten = preferWritten
        )

        activeSocket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isOpen = true
                    val sessionUpdate = buildSessionUpdatePayload(prompt)
                    webSocket.send(sessionUpdate)
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerEvent(text, listener)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isOpen = false
                    val detail = response?.message?.takeIf { it.isNotBlank() }
                        ?: t.localizedMessage
                        ?: "Realtime connection failed"
                    Log.e(TAG, "OpenAI Realtime WebSocket failure: $detail", t)
                    listener.onError("OpenAI Realtime: $detail")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isOpen = false
                    listener.onClosed()
                }
            }
        )
        return true
    }

    fun sendPcmChunk(pcm24kBytes: ByteArray) {
        if (!isOpen || pcm24kBytes.isEmpty()) return
        val encoded = Base64.encodeToString(pcm24kBytes, Base64.NO_WRAP)
        val payload = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", encoded)
            .toString()
        activeSocket?.send(payload)
    }

    fun commitBuffer() {
        if (!isOpen) return
        val payload = JSONObject()
            .put("type", "input_audio_buffer.commit")
            .toString()
        activeSocket?.send(payload)
    }

    fun disconnect() {
        isOpen = false
        try {
            activeSocket?.close(1000, "Session finished")
        } catch (_: Exception) {
        } finally {
            activeSocket = null
        }
    }

    private fun buildSessionUpdatePayload(prompt: String): String {
        val formatObj = JSONObject()
            .put("type", "audio/pcm")
            .put("rate", 24000)

        val transcriptionObj = JSONObject()
            .put("model", REALTIME_TRANSCRIBE_MODEL)
            .put("prompt", prompt)

        val turnDetectionObj = JSONObject()
            .put("type", "server_vad")
            .put("threshold", 0.5)
            .put("prefix_padding_ms", 250)
            .put("silence_duration_ms", 450)

        val inputObj = JSONObject()
            .put("format", formatObj)
            .put("transcription", transcriptionObj)
            .put("turn_detection", turnDetectionObj)

        val audioObj = JSONObject().put("input", inputObj)

        val sessionObj = JSONObject()
            .put("type", "transcription")
            .put("audio", audioObj)

        return JSONObject()
            .put("type", "session.update")
            .put("session", sessionObj)
            .toString()
    }

    private fun handleServerEvent(rawJson: String, listener: RealtimeTranscriptListener) {
        try {
            val json = JSONObject(rawJson)
            when (json.optString("type")) {
                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        listener.onPartialDelta(delta)
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val completed = json.optString("transcript", "").trim()
                    if (completed.isNotEmpty()) {
                        listener.onSegmentCompleted(completed)
                    }
                }
                "error" -> {
                    val errMsg = json.optJSONObject("error")?.optString("message")
                        ?: "OpenAI Realtime error"
                    Log.e(TAG, "Server error event: $errMsg")
                    listener.onError(errMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing OpenAI Realtime event", e)
        }
    }
}
