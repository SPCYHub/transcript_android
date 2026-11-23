package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.data.SecureKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "OpenAiRealtimeStream"
private const val REALTIME_TRANSCRIPTION_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
// 24kHz * 2 bytes/sample * 5 seconds max pre-connect audio buffer
private const val MAX_PRE_CONNECT_BUFFER_BYTES = 24_000 * 2 * 5
// OpenAI requires >= 100ms (4,800 bytes at 24kHz PCM16) to commit a buffer without error.
private const val MIN_COMMIT_AUDIO_BYTES = 24_000 * 2 * 120 / 1000

private data class RealtimeSessionProfile(
    val modelId: String,
    val useServerVad: Boolean,
    val legacyBetaSchema: Boolean = false
)

private val SESSION_PROFILES = listOf(
    // 1. Standard GA Realtime transcription with server VAD + streaming deltas
    RealtimeSessionProfile(modelId = "gpt-4o-transcribe", useServerVad = true, legacyBetaSchema = false),
    // 2. Dedicated gpt-live-transcribe (turn_detection = null per OpenAI spec, client commits)
    RealtimeSessionProfile(modelId = "gpt-live-transcribe", useServerVad = false, legacyBetaSchema = false),
    // 3. Fast mini realtime transcription with server VAD
    RealtimeSessionProfile(modelId = "gpt-4o-mini-transcribe", useServerVad = true, legacyBetaSchema = false),
    // 4. Legacy beta schema fallback (`transcription_session.update`)
    RealtimeSessionProfile(modelId = "gpt-4o-transcribe", useServerVad = true, legacyBetaSchema = true)
)

interface RealtimeTranscriptListener {
    fun onSessionReady(modelName: String, usesServerVad: Boolean)
    fun onPartialDelta(delta: String)
    fun onSegmentCompleted(transcript: String)
    fun onError(message: String)
    fun onClosed()
}

/**
 * Resilient OpenAI Realtime Transcription WebSocket client:
 * - Buffers initial PCM16 24kHz chunks while handshaking so opening words are never dropped.
 * - Negotiates `session.update` after `session.created`, automatically stepping through
 *   compatible OpenAI transcription models/schemas (`gpt-4o-transcribe`, `gpt-live-transcribe`,
 *   `gpt-4o-mini-transcribe`) if the account or API version rejects a schema.
 * - Handles `.delta`, `.completed`, and `.failed` transcription events cleanly.
 * - Properly awaits completion on finalize (`commitAndAwaitCompletion`) instead of disconnecting early.
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

    private val stateLock = Any()
    private var activeSocket: WebSocket? = null
    private val pendingChunks = ArrayDeque<ByteArray>()
    private var pendingChunkBytes = 0

    @Volatile
    private var isSocketOpen = false

    @Volatile
    private var isSessionReady = false

    @Volatile
    private var currentProfileIndex = 0

    @Volatile
    private var activePrompt = ""

    @Volatile
    private var uncommittedAudioBytes = 0

    private val inFlightTranscriptions = AtomicInteger(0)

    @Volatile
    private var pendingCompletion: CompletableDeferred<Boolean>? = null

    @Volatile
    private var lastErrorMessage: String? = null

    val usesServerVad: Boolean
        get() = SESSION_PROFILES.getOrNull(currentProfileIndex)?.useServerVad ?: false

    val activeModelId: String
        get() = SESSION_PROFILES.getOrNull(currentProfileIndex)?.modelId ?: "gpt-4o-transcribe"

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

        activePrompt = CantonesePrompts.buildOpenAiPrompt(
            customGlossary = customGlossary,
            preferWritten = preferWritten
        )
        currentProfileIndex = 0
        lastErrorMessage = null

        val request = Request.Builder()
            .url(REALTIME_TRANSCRIPTION_URL)
            .header("Authorization", "Bearer $effectiveKey")
            .build()

        activeSocket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isSocketOpen = true
                    Log.d(TAG, "OpenAI Realtime WebSocket opened; waiting for session.created")
                    // Send initial session.update right away as well (some servers emit session.created
                    // immediately; if session.created arrives we avoid duplicate sends via flag).
                    sendProfileUpdate(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerEvent(webSocket, text, listener)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isSocketOpen = false
                    isSessionReady = false
                    val bodyDetail = runCatching { response?.body?.string() }.getOrNull()
                    val parsedBody = extractErrorFromRaw(bodyDetail)
                    val detail = parsedBody
                        ?: response?.message?.takeIf { it.isNotBlank() }
                        ?: t.localizedMessage
                        ?: "Realtime connection failed"
                    val codePrefix = response?.code?.let { "($it) " } ?: ""
                    val fullMsg = "OpenAI Realtime $codePrefix$detail".trim()
                    lastErrorMessage = fullMsg
                    Log.e(TAG, "OpenAI Realtime WebSocket failure: $fullMsg", t)
                    pendingCompletion?.complete(false)
                    listener.onError(fullMsg)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isSocketOpen = false
                    isSessionReady = false
                    pendingCompletion?.complete(false)
                    listener.onClosed()
                }
            }
        )
        return true
    }

    fun sendPcmChunk(pcm24kBytes: ByteArray) {
        if (pcm24kBytes.isEmpty()) return
        synchronized(stateLock) {
            if (!isSocketOpen || !isSessionReady) {
                pendingChunks.addLast(pcm24kBytes)
                pendingChunkBytes += pcm24kBytes.size
                while (pendingChunkBytes > MAX_PRE_CONNECT_BUFFER_BYTES && pendingChunks.isNotEmpty()) {
                    pendingChunkBytes -= pendingChunks.removeFirst().size
                }
                return
            }
        }
        sendEncodedPcm(activeSocket, pcm24kBytes)
    }

    fun hasUncommittedAudio(): Boolean = uncommittedAudioBytes >= MIN_COMMIT_AUDIO_BYTES

    fun commitBuffer(): Boolean {
        if (!isSocketOpen || !isSessionReady) return false
        if (uncommittedAudioBytes < MIN_COMMIT_AUDIO_BYTES) return false
        val payload = JSONObject()
            .put("type", "input_audio_buffer.commit")
            .toString()
        val sent = activeSocket?.send(payload) ?: false
        if (sent) {
            uncommittedAudioBytes = 0
        }
        return sent
    }

    /**
     * Commits any remaining buffered PCM audio and awaits the server's
     * `conversation.item.input_audio_transcription.completed` event up to [timeoutMs].
     */
    suspend fun commitAndAwaitCompletion(timeoutMs: Long = 4_500L): Boolean {
        if (!isSocketOpen || !isSessionReady) return false
        val hasAudioToCommit = uncommittedAudioBytes >= MIN_COMMIT_AUDIO_BYTES
        if (!hasAudioToCommit && inFlightTranscriptions.get() <= 0) {
            return true
        }

        val deferred = CompletableDeferred<Boolean>()
        pendingCompletion = deferred
        if (hasAudioToCommit) {
            val committed = commitBuffer()
            if (!committed && inFlightTranscriptions.get() <= 0) {
                pendingCompletion = null
                return false
            }
        }
        val finished = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        pendingCompletion = null
        return finished
    }

    fun disconnect() {
        isSocketOpen = false
        isSessionReady = false
        uncommittedAudioBytes = 0
        inFlightTranscriptions.set(0)
        pendingCompletion?.complete(false)
        pendingCompletion = null
        synchronized(stateLock) {
            pendingChunks.clear()
            pendingChunkBytes = 0
        }
        try {
            activeSocket?.close(1000, "Session finished")
        } catch (_: Exception) {
        } finally {
            activeSocket = null
        }
    }

    private fun sendProfileUpdate(webSocket: WebSocket) {
        val profile = SESSION_PROFILES.getOrNull(currentProfileIndex) ?: return
        val payload = buildSessionUpdatePayload(profile, activePrompt)
        Log.d(TAG, "Sending OpenAI session update: model=${profile.modelId}, serverVad=${profile.useServerVad}, legacy=${profile.legacyBetaSchema}")
        webSocket.send(payload)
    }

    private fun flushPendingChunks(webSocket: WebSocket) {
        val toFlush = mutableListOf<ByteArray>()
        synchronized(stateLock) {
            while (pendingChunks.isNotEmpty()) {
                toFlush.add(pendingChunks.removeFirst())
            }
            pendingChunkBytes = 0
        }
        for (chunk in toFlush) {
            sendEncodedPcm(webSocket, chunk)
        }
    }

    private fun sendEncodedPcm(webSocket: WebSocket?, pcm24kBytes: ByteArray) {
        if (webSocket == null || pcm24kBytes.isEmpty()) return
        val encoded = Base64.encodeToString(pcm24kBytes, Base64.NO_WRAP)
        val payload = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", encoded)
            .toString()
        if (webSocket.send(payload)) {
            uncommittedAudioBytes += pcm24kBytes.size
        }
    }

    private fun buildSessionUpdatePayload(profile: RealtimeSessionProfile, prompt: String): String {
        if (profile.legacyBetaSchema) {
            val transcriptionObj = JSONObject()
                .put("model", profile.modelId)
                .put("prompt", prompt)
            val turnDetection: Any = if (profile.useServerVad) {
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.4)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 420)
            } else {
                JSONObject.NULL
            }
            val sessionObj = JSONObject()
                .put("input_audio_format", "pcm16")
                .put("input_audio_transcription", transcriptionObj)
                .put("turn_detection", turnDetection)
            return JSONObject()
                .put("type", "transcription_session.update")
                .put("session", sessionObj)
                .toString()
        }

        val formatObj = JSONObject()
            .put("type", "audio/pcm")
            .put("rate", 24000)

        val transcriptionObj = JSONObject()
            .put("model", profile.modelId)
            .put("prompt", prompt)

        val turnDetection: Any = if (profile.useServerVad) {
            JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.4)
                .put("prefix_padding_ms", 300)
                .put("silence_duration_ms", 420)
        } else {
            JSONObject.NULL
        }

        val inputObj = JSONObject()
            .put("format", formatObj)
            .put("transcription", transcriptionObj)
            .put("turn_detection", turnDetection)

        val audioObj = JSONObject().put("input", inputObj)

        val sessionObj = JSONObject()
            .put("type", "transcription")
            .put("audio", audioObj)

        return JSONObject()
            .put("type", "session.update")
            .put("session", sessionObj)
            .toString()
    }

    private fun handleServerEvent(
        webSocket: WebSocket,
        rawJson: String,
        listener: RealtimeTranscriptListener
    ) {
        try {
            val json = JSONObject(rawJson)
            when (val eventType = json.optString("type")) {
                "session.updated", "transcription_session.updated" -> {
                    val profile = SESSION_PROFILES.getOrNull(currentProfileIndex)
                    isSessionReady = true
                    Log.d(TAG, "OpenAI Realtime session ready ($eventType, model=${profile?.modelId})")
                    flushPendingChunks(webSocket)
                    listener.onSessionReady(
                        modelName = profile?.modelId ?: "gpt-4o-transcribe",
                        usesServerVad = profile?.useServerVad ?: false
                    )
                }

                "input_audio_buffer.committed" -> {
                    uncommittedAudioBytes = 0
                    inFlightTranscriptions.incrementAndGet()
                }

                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        listener.onPartialDelta(delta)
                    }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val remaining = inFlightTranscriptions.updateAndGet { (it - 1).coerceAtLeast(0) }
                    val completed = json.optString("transcript", "").trim()
                    if (completed.isNotEmpty()) {
                        listener.onSegmentCompleted(completed)
                    }
                    if (remaining == 0) {
                        pendingCompletion?.complete(true)
                    }
                }

                "conversation.item.input_audio_transcription.failed" -> {
                    inFlightTranscriptions.updateAndGet { (it - 1).coerceAtLeast(0) }
                    val errMsg = json.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "OpenAI transcription item failed"
                    Log.e(TAG, "OpenAI Realtime transcription failed event: $errMsg")
                    lastErrorMessage = errMsg
                    pendingCompletion?.complete(false)
                    listener.onError(errMsg)
                }

                "error" -> {
                    val errObj = json.optJSONObject("error")
                    val errMsg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "OpenAI Realtime error"
                    val errCode = errObj?.optString("code").orEmpty()
                    Log.e(TAG, "OpenAI Realtime server error ($errCode): $errMsg")

                    // Ignore benign empty-buffer commit warnings
                    if (errCode == "input_audio_buffer_commit_empty" ||
                        errMsg.contains("buffer too small", ignoreCase = true)
                    ) {
                        uncommittedAudioBytes = 0
                        pendingCompletion?.complete(true)
                        return
                    }

                    // If handshake hasn't succeeded yet, step to the next compatible session profile!
                    if (!isSessionReady && currentProfileIndex + 1 < SESSION_PROFILES.size) {
                        currentProfileIndex++
                        Log.w(TAG, "Retrying OpenAI Realtime session with profile #$currentProfileIndex (${activeModelId})")
                        sendProfileUpdate(webSocket)
                        return
                    }

                    lastErrorMessage = errMsg
                    pendingCompletion?.complete(false)
                    listener.onError(errMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing OpenAI Realtime event", e)
        }
    }

    private fun extractErrorFromRaw(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
