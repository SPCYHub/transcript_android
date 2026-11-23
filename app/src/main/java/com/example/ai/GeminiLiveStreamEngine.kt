package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.data.SecureKeyStore
import com.example.model.CantoneseFormat
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiLiveStreamEngine"
private const val PRIMARY_TRANSCRIBE_LIVE_MODEL = "gemini-3.5-transcribe-live"
private const val FALLBACK_LIVE_MODEL = "gemini-3.1-flash-live-preview"
private const val GEMINI_BIDI_WS_BASE =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
private const val MAX_PRE_CONNECT_PCM_BYTES = 16_000 * 2 * 5 // 5s at 16kHz PCM16

private data class DirectLiveProfile(
    val modelName: String,
    val isDedicatedTranscribeModel: Boolean,
    val includeLanguageHint: Boolean
)

interface GeminiLiveTranscriptListener {
    fun onSessionReady(modelName: String, isDirectKey: Boolean)
    /** Replaces the live partial hypothesis (e.g. `interimInputTranscription` from `gemini-3.5-transcribe-live`). */
    fun onInterimTranscript(text: String)
    /** Commits a finalized utterance segment (e.g. `inputTranscription` from `gemini-3.5-transcribe-live`). */
    fun onFinalTranscript(text: String)
    /** Streams incremental delta chunks (e.g. `modelTurn.parts` or fallback `gemini-3.1-flash-live-preview`). */
    fun onDeltaTranscript(delta: String, isTurnFinished: Boolean)
    fun onError(message: String)
    fun onClosed()
}

/**
 * Dedicated real-time streaming speech-to-text client powered by Google's **`gemini-3.5-transcribe-live`**
 * (with automatic fallback to `gemini-3.1-flash-live-preview` if needed):
 *
 * - **Built-in Firebase AI Logic**: Uses `Firebase.ai.liveModel("gemini-3.5-transcribe-live")` with
 *   `ResponseModality.TEXT` + `AudioTranscriptionConfig()`, streaming raw 16kHz PCM16 audio via
 *   `sendAudioRealtime(InlineData(...))`.
 * - **Personal Gemini API Key (BYOK)**: Connects directly to `BidiGenerateContent` WebSocket with
 *   `models/gemini-3.5-transcribe-live`, `responseModalities: ["TEXT"]`, and Cantonese `yue-Hant-HK`
 *   biasing, handling `interimInputTranscription`, `inputTranscription`, and `modelTurn` text streams.
 */
@OptIn(PublicPreviewAPI::class)
class GeminiLiveStreamEngine(
    private val keyStore: SecureKeyStore
) {

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val bufferLock = Any()
    private val pendingPcm = ArrayDeque<ByteArray>()
    private var pendingPcmBytes = 0

    @Volatile
    private var isDirectWsMode = false

    @Volatile
    private var isReady = false

    @Volatile
    private var isClosed = true

    @Volatile
    private var lastTranscriptReceivedAtMs = 0L

    // Direct WebSocket state
    private var activeSocket: WebSocket? = null
    private var activeDirectProfiles: List<DirectLiveProfile> = emptyList()
    private var activeDirectProfileIndex = 0
    private var activeDirectApiKey = ""
    private var activeFallbackSystemPrompt = ""
    private var activeDirectListener: GeminiLiveTranscriptListener? = null

    // Firebase AI Logic LiveSession state
    private var activeFirebaseSession: LiveSession? = null
    private var firebaseConnectJob: Job? = null
    private var firebaseReceiveJob: Job? = null
    private var firebaseSendJob: Job? = null
    private var firebaseAudioQueue: Channel<ByteArray>? = null

    fun connect(
        scope: CoroutineScope,
        format: CantoneseFormat,
        customGlossary: String,
        listener: GeminiLiveTranscriptListener
    ) {
        disconnect()
        isClosed = false
        isReady = false
        lastTranscriptReceivedAtMs = 0L

        val directKey = keyStore.resolveDirectGeminiKey()
        val fallbackSystemPrompt = buildFallbackLiveSystemInstruction(format, customGlossary)

        if (directKey != null) {
            isDirectWsMode = true
            val primaryModel = resolvePrimaryLiveModel(keyStore.resolveCustomGeminiModel())
            val isDedicated = primaryModel.contains("transcribe", ignoreCase = true)
            activeDirectProfiles = buildList {
                if (isDedicated) {
                    add(DirectLiveProfile(primaryModel, isDedicatedTranscribeModel = true, includeLanguageHint = true))
                    add(DirectLiveProfile(primaryModel, isDedicatedTranscribeModel = true, includeLanguageHint = false))
                    add(DirectLiveProfile(FALLBACK_LIVE_MODEL, isDedicatedTranscribeModel = false, includeLanguageHint = false))
                } else {
                    add(DirectLiveProfile(primaryModel, isDedicatedTranscribeModel = false, includeLanguageHint = false))
                    add(DirectLiveProfile(PRIMARY_TRANSCRIBE_LIVE_MODEL, isDedicatedTranscribeModel = true, includeLanguageHint = false))
                }
            }
            activeDirectProfileIndex = 0
            activeDirectApiKey = directKey
            activeFallbackSystemPrompt = fallbackSystemPrompt
            activeDirectListener = listener
            openDirectWebSocketAttempt()
        } else {
            isDirectWsMode = false
            connectFirebaseLiveSession(
                scope = scope,
                fallbackSystemPrompt = fallbackSystemPrompt,
                listener = listener
            )
        }
    }

    fun sendPcm16kChunk(pcmBytes: ByteArray) {
        if (isClosed || pcmBytes.isEmpty()) return

        if (isDirectWsMode) {
            val socket = synchronized(bufferLock) {
                if (!isReady) {
                    enqueuePendingPcmLocked(pcmBytes)
                    null
                } else {
                    activeSocket
                }
            }
            if (socket != null) {
                sendDirectAudioFrame(socket, pcmBytes)
            }
        } else {
            val queue = synchronized(bufferLock) {
                if (!isReady) {
                    enqueuePendingPcmLocked(pcmBytes)
                    null
                } else {
                    firebaseAudioQueue
                }
            }
            queue?.trySend(pcmBytes)
        }
    }

    /**
     * Signals end of user audio stream and waits briefly for trailing live transcription
     * segments before closing the session.
     */
    suspend fun finishAndAwaitTrailingTranscript(maxWaitMs: Long = 2_400L) {
        if (isClosed) return

        if (isDirectWsMode) {
            if (isReady) {
                val endPayload = JSONObject()
                    .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                    .toString()
                activeSocket?.send(endPayload)
            }
        } else {
            // Send a short 350ms silence tail so Gemini Live's server VAD flushes final syllables.
            val silenceTail = ByteArray(16_000 * 2 * 350 / 1000)
            firebaseAudioQueue?.trySend(silenceTail)
            firebaseAudioQueue?.close()
        }

        val startedWait = System.currentTimeMillis()
        while (!isClosed && System.currentTimeMillis() - startedWait < maxWaitMs) {
            delay(150L)
            val quietSinceLast = if (lastTranscriptReceivedAtMs > 0L) {
                System.currentTimeMillis() - lastTranscriptReceivedAtMs
            } else {
                System.currentTimeMillis() - startedWait
            }
            if (lastTranscriptReceivedAtMs >= startedWait && quietSinceLast >= 650L) {
                break
            }
            if (lastTranscriptReceivedAtMs < startedWait && quietSinceLast >= 1_150L) {
                break
            }
        }

        disconnect()
    }

    fun disconnect() {
        isClosed = true
        isReady = false
        activeDirectListener = null

        synchronized(bufferLock) {
            pendingPcm.clear()
            pendingPcmBytes = 0
        }

        try {
            activeSocket?.close(1000, "Session finished")
        } catch (_: Exception) {
        } finally {
            activeSocket = null
        }

        firebaseAudioQueue?.close()
        firebaseAudioQueue = null
        firebaseSendJob?.cancel()
        firebaseSendJob = null
        firebaseReceiveJob?.cancel()
        firebaseReceiveJob = null
        firebaseConnectJob?.cancel()
        firebaseConnectJob = null

        val sessionToClose = activeFirebaseSession
        activeFirebaseSession = null
        if (sessionToClose != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { sessionToClose.close() }
            }
        }
    }

    // --- Path A: Built-in Firebase AI Logic Live API ---

    private fun connectFirebaseLiveSession(
        scope: CoroutineScope,
        fallbackSystemPrompt: String,
        listener: GeminiLiveTranscriptListener
    ) {
        firebaseConnectJob = scope.launch(Dispatchers.IO) {
            try {
                // Attempt 1: Dedicated Gemini 3.5 Transcribe Live model (`ResponseModality.TEXT`)
                var connectedModel = PRIMARY_TRANSCRIBE_LIVE_MODEL
                var isDedicatedTranscribe = true
                var session: LiveSession? = tryConnectFirebaseLiveModel(
                    modelName = PRIMARY_TRANSCRIBE_LIVE_MODEL,
                    modality = ResponseModality.TEXT,
                    systemPrompt = null
                )

                // Attempt 2: Fallback to Gemini 3.1 Flash Live (`ResponseModality.AUDIO` + inputAudioTranscription)
                if (session == null && !isClosed) {
                    Log.w(TAG, "Firebase Live ($PRIMARY_TRANSCRIBE_LIVE_MODEL) unavailable; falling back to $FALLBACK_LIVE_MODEL")
                    connectedModel = FALLBACK_LIVE_MODEL
                    isDedicatedTranscribe = false
                    session = tryConnectFirebaseLiveModel(
                        modelName = FALLBACK_LIVE_MODEL,
                        modality = ResponseModality.AUDIO,
                        systemPrompt = fallbackSystemPrompt
                    )
                }

                if (session == null) {
                    throw IllegalStateException("Unable to connect to Gemini Live ($PRIMARY_TRANSCRIBE_LIVE_MODEL).")
                }

                if (isClosed) {
                    runCatching { session.close() }
                    return@launch
                }

                activeFirebaseSession = session
                val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
                firebaseAudioQueue = queue

                val buffered = drainPendingPcm()
                isReady = true
                listener.onSessionReady(modelName = connectedModel, isDirectKey = false)

                firebaseSendJob = scope.launch(Dispatchers.IO) {
                    try {
                        for (chunk in buffered) {
                            if (isClosed) break
                            session.sendAudioRealtime(InlineData(chunk, "audio/pcm;rate=16000"))
                        }
                        for (chunk in queue) {
                            if (isClosed) break
                            session.sendAudioRealtime(InlineData(chunk, "audio/pcm;rate=16000"))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isClosed) {
                            Log.e(TAG, "Firebase Live sendAudioRealtime failed", e)
                        }
                    }
                }

                firebaseReceiveJob = scope.launch(Dispatchers.IO) {
                    try {
                        session.receive().collect { message ->
                            if (isClosed) return@collect
                            if (message is LiveServerContent) {
                                val inputTx = message.inputTranscription?.text.orEmpty()
                                val textParts = message.content?.parts
                                    ?.filterIsInstance<TextPart>()
                                    ?.joinToString("") { it.text }
                                    .orEmpty()
                                val turnDone = message.turnComplete || message.generationComplete

                                if (isDedicatedTranscribe) {
                                    // Dedicated `gemini-3.5-transcribe-live` emits recognized speech via
                                    // `inputTranscription` and/or `content.parts` (TEXT modality).
                                    val recognized = inputTx.ifEmpty { textParts }
                                    if (recognized.isNotEmpty()) {
                                        lastTranscriptReceivedAtMs = System.currentTimeMillis()
                                        if (inputTx.isNotEmpty()) {
                                            listener.onFinalTranscript(inputTx)
                                        } else {
                                            listener.onDeltaTranscript(textParts, turnDone)
                                        }
                                    } else if (turnDone) {
                                        listener.onDeltaTranscript("", isTurnFinished = true)
                                    }
                                } else {
                                    if (inputTx.isNotEmpty() || turnDone) {
                                        if (inputTx.isNotEmpty()) {
                                            lastTranscriptReceivedAtMs = System.currentTimeMillis()
                                        }
                                        listener.onDeltaTranscript(inputTx, isTurnFinished = turnDone)
                                    }
                                }
                            }
                        }
                        if (!isClosed) {
                            listener.onClosed()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isClosed) {
                            Log.e(TAG, "Firebase Live receive loop failed", e)
                            listener.onError(e.localizedMessage ?: "Gemini Live stream error")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isClosed) {
                    Log.e(TAG, "Firebase Live connection failed", e)
                    listener.onError(e.localizedMessage ?: "Unable to connect to Gemini Live")
                }
            }
        }
    }

    private suspend fun tryConnectFirebaseLiveModel(
        modelName: String,
        modality: ResponseModality,
        systemPrompt: String?
    ): LiveSession? {
        return try {
            val liveModel = Firebase.ai.liveModel(
                modelName = modelName,
                generationConfig = liveGenerationConfig {
                    responseModality = modality
                    inputAudioTranscription = AudioTranscriptionConfig()
                },
                systemInstruction = systemPrompt?.let { content { text(it) } }
            )
            withTimeoutOrNull(10_000L) {
                liveModel.connect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Firebase liveModel($modelName) connect attempt failed: ${e.localizedMessage}")
            // Re-throw App Check 403 immediately so caller surfaces the actionable Keys hint
            val msg = e.localizedMessage.orEmpty()
            if (msg.contains("App Check", ignoreCase = true) || msg.contains("403")) {
                throw e
            }
            null
        }
    }

    // --- Path B: Direct Personal Gemini API Key BidiGenerateContent WebSocket ---

    private fun openDirectWebSocketAttempt() {
        if (isClosed) return
        val profile = activeDirectProfiles.getOrNull(activeDirectProfileIndex) ?: return
        val listener = activeDirectListener ?: return

        val url = "$GEMINI_BIDI_WS_BASE?key=$activeDirectApiKey"
        val request = Request.Builder()
            .url(url)
            .build()

        activeSocket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (isClosed) {
                        webSocket.close(1000, "Already closed")
                        return
                    }
                    val setupPayload = buildDirectSetupPayload(profile, activeFallbackSystemPrompt)
                    Log.d(
                        TAG,
                        "Direct Gemini Live WS opened; sending setup for ${profile.modelName} (langHint=${profile.includeLanguageHint})"
                    )
                    webSocket.send(setupPayload)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleDirectServerJson(webSocket, text, profile, listener)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleDirectServerJson(webSocket, bytes.utf8(), profile, listener)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== activeSocket) return
                    val wasReady = isReady
                    isReady = false
                    if (isClosed) return

                    val code = response?.code
                    val msg = response?.message?.takeIf { it.isNotBlank() }
                        ?: t.localizedMessage
                        ?: "Gemini Live WebSocket connection failed"
                    Log.e(TAG, "Direct Gemini Live WS failure (${profile.modelName}): code=$code msg=$msg", t)

                    if (!wasReady && code != 401 && code != 403 && tryAdvanceDirectProfile()) {
                        return
                    }
                    val codePrefix = code?.let { "($it) " } ?: ""
                    listener.onError("Gemini Live $codePrefix$msg".trim())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== activeSocket) return
                    val wasReady = isReady
                    isReady = false
                    if (isClosed) return

                    Log.w(TAG, "Direct Gemini Live WS closed (${profile.modelName}): code=$code reason=$reason")
                    if (!wasReady && code != 1000 && !isDirectAuthError(reason) && tryAdvanceDirectProfile()) {
                        return
                    }
                    if (code != 1000 && reason.isNotBlank()) {
                        listener.onError("Gemini Live closed ($code): $reason")
                    } else {
                        listener.onClosed()
                    }
                }
            }
        )
    }

    private fun tryAdvanceDirectProfile(): Boolean {
        val nextIndex = activeDirectProfileIndex + 1
        if (nextIndex >= activeDirectProfiles.size) return false
        activeDirectProfileIndex = nextIndex
        try {
            activeSocket?.close(1000, "Switching live profile")
        } catch (_: Exception) {
        }
        activeSocket = null
        openDirectWebSocketAttempt()
        return true
    }

    private fun buildDirectSetupPayload(profile: DirectLiveProfile, fallbackSystemPrompt: String): String {
        val fullModel = if (profile.modelName.startsWith("models/")) {
            profile.modelName
        } else {
            "models/${profile.modelName}"
        }

        val setupObj = JSONObject().put("model", fullModel)

        if (profile.isDedicatedTranscribeModel) {
            // `gemini-3.5-transcribe-live` requires TEXT response modality and inputAudioTranscription
            setupObj.put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("TEXT"))
            )
            val txConfig = JSONObject()
            if (profile.includeLanguageHint) {
                txConfig.put("languageCodes", JSONArray().put("yue-Hant-HK"))
            }
            setupObj.put("inputAudioTranscription", txConfig)
        } else {
            // Conversational fallback model (`gemini-3.1-flash-live-preview`)
            setupObj.put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            )
            setupObj.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", fallbackSystemPrompt)))
            )
            setupObj.put("inputAudioTranscription", JSONObject())
        }

        return JSONObject().put("setup", setupObj).toString()
    }

    private fun sendDirectAudioFrame(webSocket: WebSocket, pcmBytes: ByteArray) {
        val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val audioObj = JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", b64)
        val payload = JSONObject()
            .put("realtimeInput", JSONObject().put("audio", audioObj))
            .toString()
        webSocket.send(payload)
    }

    private fun handleDirectServerJson(
        webSocket: WebSocket,
        rawJson: String,
        profile: DirectLiveProfile,
        listener: GeminiLiveTranscriptListener
    ) {
        if (isClosed || webSocket !== activeSocket) return
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                val buffered = drainPendingPcm()
                isReady = true
                Log.d(
                    TAG,
                    "Direct Gemini Live setupComplete (${profile.modelName}); flushing ${buffered.size} buffered frames"
                )
                listener.onSessionReady(modelName = profile.modelName, isDirectKey = true)
                for (chunk in buffered) {
                    sendDirectAudioFrame(webSocket, chunk)
                }
                return
            }

            root.optJSONObject("error")?.let { err ->
                val msg = err.optString("message").ifBlank { "Gemini Live API error" }
                Log.e(TAG, "Direct Gemini Live server error (${profile.modelName}): $msg")
                if (!isReady && !isDirectAuthError(msg) && tryAdvanceDirectProfile()) {
                    return
                }
                listener.onError(msg)
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // 1. Interim partial transcript (`gemini-3.5-transcribe-live` live streaming hypothesis)
            val interimTx = serverContent.optJSONObject("interimInputTranscription")
                ?.optString("text")
                .orEmpty()
            if (interimTx.isNotEmpty()) {
                lastTranscriptReceivedAtMs = System.currentTimeMillis()
                listener.onInterimTranscript(interimTx)
            }

            // 2. Finalized inputTranscription
            val inputTxObj = serverContent.optJSONObject("inputTranscription")
            val inputTxText = inputTxObj?.optString("text").orEmpty()
            val txFinished = inputTxObj?.optBoolean("finished", false) == true
            val turnComplete = serverContent.optBoolean("turnComplete", false) ||
                serverContent.optBoolean("generationComplete", false)

            if (inputTxText.isNotEmpty()) {
                lastTranscriptReceivedAtMs = System.currentTimeMillis()
                if (profile.isDedicatedTranscribeModel) {
                    // `gemini-3.5-transcribe-live` emits finalized phrase segments in `inputTranscription`
                    listener.onFinalTranscript(inputTxText)
                } else {
                    listener.onDeltaTranscript(inputTxText, isTurnFinished = txFinished || turnComplete)
                }
                return
            }

            // 3. Dedicated TEXT modality `modelTurn.parts[].text` (if emitted by transcribe-live)
            if (profile.isDedicatedTranscribeModel) {
                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (text.isNotEmpty()) sb.append(text)
                    }
                    val modelText = sb.toString()
                    if (modelText.isNotEmpty()) {
                        lastTranscriptReceivedAtMs = System.currentTimeMillis()
                        listener.onDeltaTranscript(modelText, isTurnFinished = turnComplete)
                        return
                    }
                }
            }

            if (txFinished || turnComplete) {
                listener.onDeltaTranscript("", isTurnFinished = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing Direct Gemini Live frame", e)
        }
    }

    private fun isDirectAuthError(message: String): Boolean {
        return message.contains("API key not valid", ignoreCase = true) ||
            message.contains("API_KEY_INVALID", ignoreCase = true) ||
            message.contains("401") ||
            message.contains("permission denied", ignoreCase = true)
    }

    private fun enqueuePendingPcmLocked(pcmBytes: ByteArray) {
        pendingPcm.addLast(pcmBytes)
        pendingPcmBytes += pcmBytes.size
        while (pendingPcmBytes > MAX_PRE_CONNECT_PCM_BYTES && pendingPcm.isNotEmpty()) {
            pendingPcmBytes -= pendingPcm.removeFirst().size
        }
    }

    private fun drainPendingPcm(): List<ByteArray> {
        return synchronized(bufferLock) {
            val copy = pendingPcm.toList()
            pendingPcm.clear()
            pendingPcmBytes = 0
            copy
        }
    }

    private fun resolvePrimaryLiveModel(customModel: String?): String {
        val clean = customModel?.trim().orEmpty()
        return if (clean.contains("live", ignoreCase = true) || clean.contains("transcribe", ignoreCase = true)) {
            clean
        } else {
            PRIMARY_TRANSCRIBE_LIVE_MODEL
        }
    }

    private fun buildFallbackLiveSystemInstruction(format: CantoneseFormat, customGlossary: String): String {
        val glossaryPart = if (customGlossary.isNotBlank()) {
            " Prioritize these Hong Kong names/terms: $customGlossary."
        } else {
            ""
        }
        val style = if (format == CantoneseFormat.WRITTEN_CHINESE) {
            "Traditional Chinese (Hong Kong)"
        } else {
            "verbatim Hong Kong colloquial Cantonese (廣東話口語, Traditional Chinese)"
        }
        return "You are a silent Hong Kong Cantonese live dictation listener for $style.$glossaryPart " +
            "Do not speak long replies; stay silent or acknowledge minimally so input transcription stays clean."
    }
}
