package com.example.ai

import android.util.Log
import com.example.audio.LivePcmStreamRecorder
import com.example.audio.PcmAudioChunk
import com.example.data.SecureKeyStore
import com.example.data.SessionTranscriptStore
import com.example.model.AudioCaptureMetrics
import com.example.model.AudioSourceType
import com.example.model.CantoneseFormat
import com.example.model.RoutingMode
import com.example.model.TranscriptRecord
import com.example.model.TranscriptionEngineId
import com.example.model.UserPreferences
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val TAG = "LiveTranscriptionCtrl"
private const val DEFAULT_LIVE_GEMINI_MODEL = "gemini-3.5-flash-lite"

data class LiveStreamState(
    val isActive: Boolean = false,
    val isConnecting: Boolean = false,
    val confirmedText: String = "",
    val partialText: String = "",
    val engineLabel: String = "Gemini Live Stream",
    val engineId: TranscriptionEngineId = TranscriptionEngineId.GEMINI_FLASH_LITE,
    val metrics: AudioCaptureMetrics = AudioCaptureMetrics(),
    val lastChunkLatencyMs: Long = 0L,
    val totalAudioBytes: Long = 0L,
    val errorMessage: String? = null
) {
    val combinedText: String
        get() = buildString {
            append(confirmedText)
            if (partialText.isNotEmpty()) {
                if (confirmedText.isNotEmpty() && needsSpaceBetween(confirmedText.last(), partialText.first())) {
                    append(' ')
                }
                append(partialText)
            }
        }
}

private fun needsSpaceBetween(prev: Char, next: Char): Boolean {
    return prev.isLetterOrDigit() && prev.code < 128 && next.isLetterOrDigit() && next.code < 128
}

/**
 * Real-time streaming transcription controller supporting:
 * - OpenAI Realtime WebSocket (`gpt-live-transcribe`) when OpenAI key is configured.
 * - Gemini Live VAD Streaming via either the user's personal Gemini API key (Direct SSE)
 *   or built-in Firebase AI Logic (`Firebase.ai`).
 */
class LiveTranscriptionController(
    private val keyStore: SecureKeyStore,
    private val directGeminiRest: DirectGeminiRestClient,
    private val openAiRealtime: OpenAiRealtimeStreamEngine,
    private val sessionStore: SessionTranscriptStore
) {

    companion object {
        private const val GEMINI_VAD_PAUSE_MS = 550L
        private const val GEMINI_MAX_WINDOW_MS = 2_800L
        private const val GEMINI_MIN_SEGMENT_BYTES = 16_000 * 2 * 350 / 1000
        private const val STREAM_CHUNK_TIMEOUT_MS = 30_000L
    }

    private val pcmRecorder = LivePcmStreamRecorder()

    private val geminiLiveModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_LIVE_GEMINI_MODEL,
            generationConfig = generationConfig {
                temperature = 0.1f
            }
        )
    }

    private val _state = MutableStateFlow(LiveStreamState())
    val state: StateFlow<LiveStreamState> = _state.asStateFlow()

    private var activeScope: CoroutineScope? = null
    private var metricsJob: Job? = null
    private var geminiConsumerJob: Job? = null
    private var segmentQueue: Channel<ByteArray>? = null

    private var usingOpenAiRealtime = false
    private var activePrefs = UserPreferences()
    private var activeSourceType = AudioSourceType.STUDIO_RECORD
    private var sessionStartedAtMs = 0L

    private val segmentBufferLock = Any()
    private val segmentPcmBuffer = ByteArrayOutputStream(64 * 1024)
    private var segmentHasSpeech = false
    private var segmentStartedAtMs = 0L
    private var lastSpeechSeenAtMs = 0L

    private var onLiveSegmentCommit: ((String) -> Unit)? = null
    private var onLivePartialUpdate: ((String) -> Unit)? = null

    fun startLiveStream(
        scope: CoroutineScope,
        preferences: UserPreferences,
        sourceType: AudioSourceType,
        onPartial: ((String) -> Unit)? = null,
        onCommitSegment: ((String) -> Unit)? = null
    ): Result<Unit> {
        stopAndDiscard()

        activeScope = scope
        activePrefs = preferences
        activeSourceType = sourceType
        onLivePartialUpdate = onPartial
        onLiveSegmentCommit = onCommitSegment
        sessionStartedAtMs = System.currentTimeMillis()

        val wantOpenAiRealtime = when (preferences.routingMode) {
            RoutingMode.OPENAI_ONLY -> true
            RoutingMode.GEMINI_ONLY -> false
            RoutingMode.AUTO -> openAiRealtime.isConfigured
        }

        if (wantOpenAiRealtime && !openAiRealtime.isConfigured) {
            val msg = "OpenAI API key is not configured. Add your key in the Keys tab, or switch to Auto / Gemini."
            _state.value = LiveStreamState(errorMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }

        return if (wantOpenAiRealtime) {
            startOpenAiRealtimeSession(scope, preferences)
        } else {
            startGeminiLiveSession(scope, preferences)
        }
    }

    suspend fun stopAndFinalize(saveToSession: Boolean = true): TranscriptRecord? {
        val wasActive = _state.value.isActive
        if (!wasActive) return null

        pcmRecorder.stop()
        metricsJob?.cancel()
        metricsJob = null

        if (usingOpenAiRealtime) {
            openAiRealtime.commitBuffer()
            delay(350L)
            openAiRealtime.disconnect()
        } else {
            flushRemainingGeminiSegment()
            segmentQueue?.close()
            geminiConsumerJob?.join()
            geminiConsumerJob = null
            segmentQueue = null
        }

        val finalState = _state.value
        val fullText = finalState.combinedText.trim()
        val elapsedMs = (System.currentTimeMillis() - sessionStartedAtMs).coerceAtLeast(0L)

        _state.value = LiveStreamState()
        onLivePartialUpdate = null
        onLiveSegmentCommit = null

        if (fullText.isBlank()) return null

        val isWritten = activePrefs.defaultFormat == CantoneseFormat.WRITTEN_CHINESE
        val sha256 = sha256OfText("$sessionStartedAtMs:$fullText")

        val record = TranscriptRecord(
            sourceType = activeSourceType,
            fileName = if (activeSourceType == AudioSourceType.KEYBOARD_LIVE) {
                "Live Keyboard Stream"
            } else {
                "Live Studio Stream"
            },
            codecLabel = if (usingOpenAiRealtime) "Live PCM16 24kHz WS" else "Live PCM16 16kHz VAD",
            durationMillis = elapsedMs,
            audioSizeBytes = finalState.totalAudioBytes,
            audioSha256 = sha256,
            spokenCantonese = fullText,
            writtenChinese = if (isWritten) fullText else null,
            detectedLanguageMix = "Cantonese (Live)",
            engineUsed = finalState.engineId,
            latencyMillis = finalState.lastChunkLatencyMs
        )

        if (saveToSession) {
            sessionStore.addOrUpdate(record)
        }
        return record
    }

    fun stopAndDiscard() {
        pcmRecorder.stop()
        metricsJob?.cancel()
        metricsJob = null
        openAiRealtime.disconnect()
        segmentQueue?.close()
        segmentQueue = null
        geminiConsumerJob?.cancel()
        geminiConsumerJob = null
        synchronized(segmentBufferLock) {
            segmentPcmBuffer.reset()
            segmentHasSpeech = false
            segmentStartedAtMs = 0L
            lastSpeechSeenAtMs = 0L
        }
        _state.value = LiveStreamState()
        onLivePartialUpdate = null
        onLiveSegmentCommit = null
    }

    private fun startOpenAiRealtimeSession(
        scope: CoroutineScope,
        preferences: UserPreferences
    ): Result<Unit> {
        usingOpenAiRealtime = true
        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = true,
            engineLabel = "OpenAI Live · gpt-live-transcribe",
            engineId = TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE
        )

        val preferWritten = preferences.defaultFormat == CantoneseFormat.WRITTEN_CHINESE
        openAiRealtime.connect(
            customGlossary = preferences.customGlossary,
            preferWritten = preferWritten,
            listener = object : RealtimeTranscriptListener {
                override fun onConnected() {
                    _state.update { it.copy(isConnecting = false) }
                }

                override fun onPartialDelta(delta: String) {
                    _state.update { cur ->
                        val nextPartial = cur.partialText + delta
                        onLivePartialUpdate?.invoke(nextPartial)
                        cur.copy(partialText = nextPartial, errorMessage = null)
                    }
                }

                override fun onSegmentCompleted(transcript: String) {
                    _state.update { cur ->
                        val nextConfirmed = appendTranscriptSegment(cur.confirmedText, transcript)
                        cur.copy(
                            confirmedText = nextConfirmed,
                            partialText = "",
                            errorMessage = null
                        )
                    }
                    onLiveSegmentCommit?.invoke(transcript)
                }

                override fun onError(message: String) {
                    if (preferences.routingMode == RoutingMode.AUTO && _state.value.isActive) {
                        Log.w(TAG, "OpenAI Realtime error ($message); failing over live to Gemini VAD streaming")
                        val existingConfirmed = _state.value.combinedText
                        openAiRealtime.disconnect()
                        pcmRecorder.stop()
                        startGeminiLiveSession(scope, preferences, carryOverText = existingConfirmed)
                    } else {
                        _state.update { it.copy(isConnecting = false, errorMessage = message) }
                    }
                }

                override fun onClosed() {
                    _state.update { it.copy(isConnecting = false) }
                }
            }
        )

        startMetricsRelay(scope)

        return pcmRecorder.start(
            scope = scope,
            sampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_OPENAI_REALTIME_HZ,
            onChunk = { chunk ->
                openAiRealtime.sendPcmChunk(chunk.pcmBytes)
                _state.update { it.copy(totalAudioBytes = it.totalAudioBytes + chunk.pcmBytes.size) }
            },
            onError = { err ->
                _state.update { it.copy(errorMessage = err.localizedMessage ?: "Mic capture error") }
            }
        )
    }

    private fun startGeminiLiveSession(
        scope: CoroutineScope,
        preferences: UserPreferences,
        carryOverText: String = ""
    ): Result<Unit> {
        usingOpenAiRealtime = false
        synchronized(segmentBufferLock) {
            segmentPcmBuffer.reset()
            segmentHasSpeech = false
            segmentStartedAtMs = 0L
            lastSpeechSeenAtMs = 0L
        }

        val usingDirectKey = keyStore.resolveDirectGeminiKey() != null
        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = false,
            confirmedText = carryOverText,
            partialText = "",
            engineLabel = if (usingDirectKey) "Gemini Direct · Personal Key" else "Gemini Live · Firebase AI",
            engineId = if (usingDirectKey) TranscriptionEngineId.GEMINI_DIRECT_LITE else TranscriptionEngineId.GEMINI_FLASH_LITE
        )

        val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        segmentQueue = queue

        geminiConsumerJob?.cancel()
        geminiConsumerJob = scope.launch(Dispatchers.IO) {
            for (pcmSegment in queue) {
                streamGeminiSegment(pcmSegment, preferences)
            }
        }

        startMetricsRelay(scope)

        return pcmRecorder.start(
            scope = scope,
            sampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ,
            onChunk = { chunk ->
                handleGeminiPcmChunk(chunk, queue)
            },
            onError = { err ->
                _state.update { it.copy(errorMessage = err.localizedMessage ?: "Mic capture error") }
            }
        )
    }

    private fun handleGeminiPcmChunk(chunk: PcmAudioChunk, queue: Channel<ByteArray>) {
        _state.update { it.copy(totalAudioBytes = it.totalAudioBytes + chunk.pcmBytes.size) }

        var readySegment: ByteArray? = null
        synchronized(segmentBufferLock) {
            if (chunk.isSpeech) {
                if (!segmentHasSpeech) {
                    segmentHasSpeech = true
                    segmentStartedAtMs = chunk.timestampMs
                }
                lastSpeechSeenAtMs = chunk.timestampMs
                segmentPcmBuffer.write(chunk.pcmBytes)
            } else if (segmentHasSpeech) {
                segmentPcmBuffer.write(chunk.pcmBytes)
            }

            if (segmentHasSpeech) {
                val silenceDuration = chunk.timestampMs - lastSpeechSeenAtMs
                val windowDuration = chunk.timestampMs - segmentStartedAtMs

                val shouldDispatch = (silenceDuration >= GEMINI_VAD_PAUSE_MS &&
                    segmentPcmBuffer.size() >= GEMINI_MIN_SEGMENT_BYTES) ||
                    windowDuration >= GEMINI_MAX_WINDOW_MS

                if (shouldDispatch) {
                    readySegment = segmentPcmBuffer.toByteArray()
                    segmentPcmBuffer.reset()
                    segmentHasSpeech = false
                    segmentStartedAtMs = 0L
                }
            }
        }

        readySegment?.let { queue.trySend(it) }
    }

    private fun flushRemainingGeminiSegment() {
        val tail: ByteArray? = synchronized(segmentBufferLock) {
            if (segmentHasSpeech && segmentPcmBuffer.size() >= GEMINI_MIN_SEGMENT_BYTES) {
                segmentPcmBuffer.toByteArray().also {
                    segmentPcmBuffer.reset()
                    segmentHasSpeech = false
                }
            } else {
                segmentPcmBuffer.reset()
                segmentHasSpeech = false
                null
            }
        }
        tail?.let { segmentQueue?.trySend(it) }
    }

    private suspend fun streamGeminiSegment(pcmBytes: ByteArray, preferences: UserPreferences) {
        val startedAt = System.currentTimeMillis()
        val wavBytes = wrapPcm16AsWav(pcmBytes, LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ)
        val contextTail = _state.value.confirmedText.takeLast(48)

        val prompt = buildString {
            append(CantonesePrompts.buildFastKeyboardPrompt(preferences.defaultFormat, preferences.customGlossary))
            if (contextTail.isNotBlank()) {
                append("\nContinue seamlessly after the previous words (do NOT repeat them): \"")
                append(contextTail)
                append("\"")
            }
            append("\nIf this slice contains only background noise or silence, output an empty string.")
        }

        val segmentBuilder = StringBuilder()
        val directKey = keyStore.resolveDirectGeminiKey()

        try {
            withTimeout(STREAM_CHUNK_TIMEOUT_MS) {
                if (directKey != null) {
                    val modelName = keyStore.resolveCustomGeminiModel() ?: DEFAULT_LIVE_GEMINI_MODEL
                    directGeminiRest.streamAudioSegment(
                        apiKey = directKey,
                        modelName = modelName,
                        promptText = prompt,
                        audioBytes = wavBytes,
                        audioMimeType = "audio/wav"
                    ).collect { piece ->
                        if (piece.isNotEmpty()) {
                            segmentBuilder.append(piece)
                            val cleanPartial = segmentBuilder.toString().trim()
                            if (cleanPartial.isNotEmpty()) {
                                _state.update { it.copy(partialText = cleanPartial, errorMessage = null) }
                                withContext(Dispatchers.Main.immediate) {
                                    onLivePartialUpdate?.invoke(cleanPartial)
                                }
                            }
                        }
                    }
                } else {
                    geminiLiveModel.generateContentStream(
                        content("user") {
                            inlineData(wavBytes, "audio/wav")
                            text(prompt)
                        }
                    ).collect { chunk ->
                        val piece = chunk.text.orEmpty()
                        if (piece.isNotEmpty()) {
                            segmentBuilder.append(piece)
                            val cleanPartial = segmentBuilder.toString().trim()
                            if (cleanPartial.isNotEmpty()) {
                                _state.update { it.copy(partialText = cleanPartial, errorMessage = null) }
                                withContext(Dispatchers.Main.immediate) {
                                    onLivePartialUpdate?.invoke(cleanPartial)
                                }
                            }
                        }
                    }
                }
            }

            val finalized = segmentBuilder.toString().trim()
            val latency = System.currentTimeMillis() - startedAt
            if (finalized.isNotEmpty()) {
                _state.update { cur ->
                    cur.copy(
                        confirmedText = appendTranscriptSegment(cur.confirmedText, finalized),
                        partialText = "",
                        lastChunkLatencyMs = latency,
                        errorMessage = null
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    onLiveSegmentCommit?.invoke(finalized)
                }
            } else {
                _state.update { it.copy(partialText = "") }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini live chunk timed out", e)
            _state.update { it.copy(partialText = "", errorMessage = "Live chunk timed out; still listening…") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini live chunk failed", e)
            val errMsg = e.localizedMessage ?: "Live stream chunk error"
            val friendly = if (errMsg.contains("App Check", ignoreCase = true) || errMsg.contains("403")) {
                "Firebase App Check rejected this build. Add your personal Gemini API key in the Keys tab."
            } else {
                errMsg
            }
            _state.update {
                it.copy(
                    partialText = "",
                    errorMessage = friendly
                )
            }
        }
    }

    private fun startMetricsRelay(scope: CoroutineScope) {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            pcmRecorder.metrics.collectLatest { m ->
                _state.update { it.copy(metrics = m) }
            }
        }
    }

    private fun appendTranscriptSegment(existing: String, incoming: String): String {
        val clean = incoming.trim()
        if (clean.isEmpty()) return existing
        if (existing.isEmpty()) return clean
        return if (needsSpaceBetween(existing.last(), clean.first())) {
            "$existing $clean"
        } else {
            "$existing$clean"
        }
    }

    private fun wrapPcm16AsWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val result = ByteArray(44 + pcmData.size)
        System.arraycopy(header.array(), 0, result, 0, 44)
        System.arraycopy(pcmData, 0, result, 44, pcmData.size)
        return result
    }

    private fun sha256OfText(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
