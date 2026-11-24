package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.data.SecureKeyStore
import com.example.model.CantoneseFormat
import kotlinx.coroutines.delay
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
private const val FALLBACK_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
private const val SECONDARY_LIVE_MODEL = "gemini-3.1-flash-live-preview"
private const val MAX_PRE_CONNECT_PCM_BYTES = 16_000 * 2 * 5 // 5s at 16kHz PCM16

private data class DirectLiveProfile(
    val modelName: String,
    val isDedicatedTranscribeModel: Boolean,
    val includeLanguageHint: Boolean,
    val includeVadTuning: Boolean
)

interface GeminiLiveTranscriptListener {
    fun onSessionReady(modelName: String, isDirectKey: Boolean)
    /** Replaces the live partial hypothesis (e.g. `interimInputTranscription`). */
    fun onInterimTranscript(text: String)
    /** Commits a finalized utterance segment (e.g. `inputTranscription`). */
    fun onFinalTranscript(text: String)
    /** Streams incremental delta chunks. */
    fun onDeltaTranscript(delta: String, isTurnFinished: Boolean)
    fun onError(message: String)
    fun onClosed()
}

/**
 * Direct full-duplex streaming Gemini Live API (`BidiGenerateContent` WebSocket) client.
 *
 * Exclusively uses the user's personal Gemini API key saved in [SecureKeyStore] (no Firebase).
 * Implements official Gemini Live API optimizations:
 * - `realtimeInputConfig.automaticActivityDetection` tuned for crisp Cantonese voice dictation
 *   (`START_SENSITIVITY_HIGH`, `END_SENSITIVITY_HIGH`, `prefixPaddingMs = 200`, `silenceDurationMs = 500`).
 * - `inputAudioTranscription` with `yue-Hant-HK` language hint and graceful fallback if a model
 *   uses a simpler schema.
 * - Automatic profile fallback across dedicated transcribe-live and native-audio live models.
 */
class GeminiLiveStreamEngine(
    private val keyStore: SecureKeyStore
) {

    val isConfigured: Boolean
        get() = keyStore.hasDirectGeminiKey()

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
    private var isReady = false

    @Volatile
    private var isClosed = true

    @Volatile
    private var lastTranscriptReceivedAtMs = 0L

    private var activeSocket: WebSocket? = null
    private var activeDirectProfiles: List<DirectLiveProfile> = emptyList()
    private var activeDirectProfileIndex = 0
    private var activeDirectApiKey = ""
    private var activeCustomGlossary = ""
    private var activeFallbackSystemPrompt = ""
    private var activeDirectListener: GeminiLiveTranscriptListener? = null

    fun connect(
        format: CantoneseFormat,
        customGlossary: String,
        listener: GeminiLiveTranscriptListener
    ): Boolean {
        disconnect()

        val directKey = keyStore.resolveDirectGeminiKey()
        if (directKey.isNullOrBlank()) {
            listener.onError("Gemini API key is not configured. Add and save your Gemini API key in the Keys tab.")
            return false
        }

        isClosed = false
        isReady = false
        lastTranscriptReceivedAtMs = 0L
        activeCustomGlossary = customGlossary
        activeFallbackSystemPrompt = buildFallbackLiveSystemInstruction(format, customGlossary)

        val primaryModel = resolvePrimaryLiveModel(keyStore.resolveCustomGeminiModel())
        val isDedicated = primaryModel.contains("transcribe", ignoreCase = true)
        activeDirectProfiles = buildList {
            if (isDedicated) {
                add(DirectLiveProfile(primaryModel, isDedicatedTranscribeModel = true, includeLanguageHint = true, includeVadTuning = true))
                add(DirectLiveProfile(primaryModel, isDedicatedTranscribeModel = true, includeLanguageHint = false, includeVadTuning = false))
                add(DirectLiveProfile(FALLBACK_LIVE_MODEL, isDedicatedTranscribeModel = false, includeLanguageHint = false, includeVadTuning = true))
                add(DirectLiveProfile(SECONDARY_LIVE_MODEL, isDedicatedTranscribeModel = false, includeLanguageHint = false, includeVadTuning = false))
            } else {
                add(DirectLiveProfile(primaryModel, isDedicatedTranscribeModel = false, includeLanguageHint = false, includeVadTuning = true))
                add(DirectLiveProfile(primaryModel, isDedicatedTranscribeModel = false, includeLanguageHint = false, includeVadTuning = false))
                add(DirectLiveProfile(PRIMARY_TRANSCRIBE_LIVE_MODEL, isDedicatedTranscribeModel = true, includeLanguageHint = false, includeVadTuning = false))
            }
        }
        activeDirectProfileIndex = 0
        activeDirectApiKey = directKey
        activeDirectListener = listener
        openDirectWebSocketAttempt()
        return true
    }

    fun sendPcm16kChunk(pcmBytes: ByteArray) {
        if (isClosed || pcmBytes.isEmpty()) return

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
    }

    /**
     * Signals end of user audio stream and waits briefly for trailing live transcription
     * segments before closing the session.
     */
    suspend fun finishAndAwaitTrailingTranscript(maxWaitMs: Long = 2_400L) {
        if (isClosed) return

        if (isReady) {
            val endPayload = JSONObject()
                .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                .toString()
            activeSocket?.send(endPayload)
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
    }

    private fun openDirectWebSocketAttempt() {
        if (isClosed) return
        val profile = activeDirectProfiles.getOrNull(activeDirectProfileIndex) ?: return
        val listener = activeDirectListener ?: return

        val url = ProviderRequestAuth.geminiLiveWebSocketUrl(activeDirectApiKey)
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
                        "Direct Gemini Live WS opened; sending setup for ${profile.modelName} (langHint=${profile.includeLanguageHint}, vad=${profile.includeVadTuning})"
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

        if (profile.includeVadTuning) {
            val vadObj = JSONObject()
                .put("disabled", false)
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                .put("prefixPaddingMs", 200)
                .put("silenceDurationMs", 500)
            setupObj.put(
                "realtimeInputConfig",
                JSONObject().put("automaticActivityDetection", vadObj)
            )
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

            // 1. Interim partial transcript
            val interimTx = serverContent.optJSONObject("interimInputTranscription")
                ?.optString("text")
                .orEmpty()
            if (interimTx.isNotEmpty()) {
                val cleanedInterim = TranscriptSanitizer.sanitizeSegment(
                    rawText = interimTx,
                    customGlossary = activeCustomGlossary
                )
                if (cleanedInterim.isNotEmpty()) {
                    lastTranscriptReceivedAtMs = System.currentTimeMillis()
                    listener.onInterimTranscript(cleanedInterim)
                }
            }

            // 2. Finalized inputTranscription
            val inputTxObj = serverContent.optJSONObject("inputTranscription")
            val inputTxText = inputTxObj?.optString("text").orEmpty()
            val txFinished = inputTxObj?.optBoolean("finished", false) == true
            val turnComplete = serverContent.optBoolean("turnComplete", false) ||
                serverContent.optBoolean("generationComplete", false)

            if (inputTxText.isNotEmpty()) {
                val cleanedInput = TranscriptSanitizer.sanitizeSegment(
                    rawText = inputTxText,
                    customGlossary = activeCustomGlossary
                )
                if (cleanedInput.isNotEmpty()) {
                    lastTranscriptReceivedAtMs = System.currentTimeMillis()
                    if (profile.isDedicatedTranscribeModel) {
                        listener.onFinalTranscript(cleanedInput)
                    } else {
                        listener.onDeltaTranscript(cleanedInput, isTurnFinished = txFinished || turnComplete)
                    }
                }
                return
            }

            // 3. Dedicated TEXT modality `modelTurn.parts[].text`
            if (profile.isDedicatedTranscribeModel) {
                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (text.isNotEmpty()) sb.append(text)
                    }
                    val modelText = TranscriptSanitizer.sanitizeSegment(
                        rawText = sb.toString(),
                        customGlossary = activeCustomGlossary
                    )
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
        return if (clean.contains("live", ignoreCase = true) ||
            clean.contains("transcribe", ignoreCase = true) ||
            clean.contains("native-audio", ignoreCase = true)
        ) {
            clean
        } else {
            PRIMARY_TRANSCRIBE_LIVE_MODEL
        }
    }

    private fun buildFallbackLiveSystemInstruction(format: CantoneseFormat, customGlossary: String): String {
        val glossaryPart = if (customGlossary.isNotBlank()) {
            " Bias recognition for these Hong Kong names/terms only if spoken: $customGlossary."
        } else {
            ""
        }
        val style = if (format == CantoneseFormat.WRITTEN_CHINESE) {
            "Traditional Chinese (Hong Kong)"
        } else {
            "verbatim Hong Kong colloquial Cantonese (廣東話口語, Traditional Chinese)"
        }
        return "You are a silent Hong Kong Cantonese live dictation listener for $style.$glossaryPart " +
            "Stay completely silent and do not generate conversational replies."
    }
}
