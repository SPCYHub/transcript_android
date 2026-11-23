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
private const val FALLBACK_LIVE_GEMINI_MODEL = "gemini-3.8-flash"

private enum class LivePipelineMode {
    OPENAI_REALTIME,
    OPENAI_CHUNKED_REST,
    GEMINI_LIVE_API,
    GEMINI_VAD_STREAM
}

sealed interface LiveFinalizeResult {
    data class Success(val record: TranscriptRecord) : LiveFinalizeResult
    data class Error(val message: String) : LiveFinalizeResult
    data object Empty : LiveFinalizeResult
}

data class LiveStreamState(
    val isActive: Boolean = false,
    val isConnecting: Boolean = false,
    val isTranscribingChunk: Boolean = false,
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
 * 1. OpenAI Realtime WebSocket (`gpt-4o-transcribe` / `gpt-live-transcribe` / `gpt-4o-mini-transcribe`)
 *    with automatic client commit cadence for continuous speech and awaited finalization.
 * 2. OpenAI Chunked Live REST fallback (`/v1/audio/transcriptions`) if Realtime WebSocket is
 *    unavailable in `OPENAI_ONLY` mode so OpenAI still streams phrase-by-phrase.
 * 3. Gemini Live Transcribe API (`gemini-3.5-transcribe-live`, fallback `gemini-3.1-flash-live-preview`)
 *    true full-duplex streaming over Firebase AI Logic (`Firebase.ai.liveModel`) or Personal Key
 *    (`BidiGenerateContent` WebSocket).
 * 4. Gemini VAD Chunked Streaming fallback (`gemini-3.5-flash-lite` / `gemini-3.8-flash`) with
 *    16kHz PCM16 + 300ms pre-roll + fast 1.25s rolling slices if Live API is unavailable.
 */
class LiveTranscriptionController(
    private val keyStore: SecureKeyStore,
    private val directGeminiRest: DirectGeminiRestClient,
    private val geminiLive: GeminiLiveStreamEngine,
    private val openAiRealtime: OpenAiRealtimeStreamEngine,
    private val openAiRest: OpenAiTranscriptionEngine,
    private val sessionStore: SessionTranscriptStore
) {

    companion object {
        // Fast live cadence so words appear while talking (~1.25s rolling slices or 360ms micro-pause)
        private const val CHUNK_VAD_PAUSE_MS = 360L
        private const val CHUNK_MAX_WINDOW_MS = 1_250L
        private const val CHUNK_MIN_SEGMENT_BYTES = 16_000 * 2 * 250 / 1000 // 250ms at 16kHz PCM16
        private const val PRE_ROLL_MAX_FRAMES = 3 // ~300ms pre-roll so opening syllables aren't clipped
        private const val STREAM_CHUNK_TIMEOUT_MS = 30_000L

        // OpenAI Realtime continuous-speech commit cadence so long monologues stream without waiting for silence
        private const val REALTIME_MANUAL_PAUSE_MS = 380L
        private const val REALTIME_MANUAL_WINDOW_MS = 1_350L
        private const val REALTIME_SERVER_VAD_MAX_CONTINUOUS_MS = 2_100L

        // Capped fallback audio buffer (up to ~16s) so short/quiet utterances can still be finalized on stop
        private const val MAX_FALLBACK_PCM_BYTES = 24_000 * 2 * 16
        private const val MIN_FALLBACK_PCM_BYTES = 16_000 * 2 * 300 / 1000
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

    private val geminiLiveFallbackModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FALLBACK_LIVE_GEMINI_MODEL,
            generationConfig = generationConfig {
                temperature = 0.1f
            }
        )
    }

    private val _state = MutableStateFlow(LiveStreamState())
    val state: StateFlow<LiveStreamState> = _state.asStateFlow()

    private var activeScope: CoroutineScope? = null
    private var metricsJob: Job? = null
    private var chunkConsumerJob: Job? = null
    private var segmentQueue: Channel<ByteArray>? = null

    @Volatile
    private var pipelineMode = LivePipelineMode.GEMINI_VAD_STREAM
    @Volatile
    private var isFinalizing = false
    @Volatile
    private var activeSampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ
    @Volatile
    private var realtimeUsesServerVad = true
    @Volatile
    private var realtimeSpeechStartedMs = 0L
    @Volatile
    private var realtimeLastSpeechMs = 0L
    @Volatile
    private var realtimeHasPendingSpeech = false

    private var activePrefs = UserPreferences()
    private var activeSourceType = AudioSourceType.STUDIO_RECORD
    private var sessionStartedAtMs = 0L

    private val segmentBufferLock = Any()
    private val preRollFrames = ArrayDeque<ByteArray>(PRE_ROLL_MAX_FRAMES + 1)
    private val segmentPcmBuffer = ByteArrayOutputStream(64 * 1024)
    private val sessionFallbackPcm = ByteArrayOutputStream(128 * 1024)
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
            startGeminiLiveApiSession(scope, preferences)
        }
    }

    suspend fun stopAndFinalize(saveToSession: Boolean = true): LiveFinalizeResult {
        val wasActive = _state.value.isActive
        if (!wasActive) return LiveFinalizeResult.Empty

        isFinalizing = true
        pcmRecorder.stop()
        metricsJob?.cancel()
        metricsJob = null

        val modeAtStop = pipelineMode
        val sampleRateAtStop = activeSampleRateHz
        var fallbackError: String? = null

        when (modeAtStop) {
            LivePipelineMode.OPENAI_REALTIME -> {
                openAiRealtime.commitAndAwaitCompletion(timeoutMs = 4_500L)
                openAiRealtime.disconnect()

                // Safety net: if Realtime WS produced zero text but we captured audio (and no auth failure),
                // transcribe the recorded PCM once via OpenAI REST so short utterances never fail silently.
                val currentText = _state.value.combinedText.trim()
                val currentErr = _state.value.errorMessage
                if (currentText.isEmpty() && !isAuthError(currentErr)) {
                    val fallbackBytes = drainFallbackPcm()
                    if (fallbackBytes.size >= MIN_FALLBACK_PCM_BYTES) {
                        val wavBytes = wrapPcm16AsWav(fallbackBytes, sampleRateAtStop)
                        val restRes = openAiRest.transcribeWavChunk(
                            wavBytes = wavBytes,
                            customGlossary = activePrefs.customGlossary,
                            preferWritten = activePrefs.defaultFormat == CantoneseFormat.WRITTEN_CHINESE
                        )
                        restRes.onSuccess { out ->
                            _state.update { cur ->
                                cur.copy(
                                    confirmedText = appendTranscriptSegment(cur.confirmedText, out.text),
                                    partialText = "",
                                    engineId = out.engineId,
                                    lastChunkLatencyMs = out.latencyMillis,
                                    errorMessage = null
                                )
                            }
                        }.onFailure { err ->
                            fallbackError = err.message ?: currentErr
                        }
                    }
                }
            }

            LivePipelineMode.GEMINI_LIVE_API -> {
                geminiLive.finishAndAwaitTrailingTranscript(maxWaitMs = 2_400L)

                // Safety net: if Gemini Live produced zero text (e.g., very short press-release
                // before handshake completed) and we captured audio without auth/App Check errors,
                // transcribe the buffered PCM once via Gemini Flash-Lite / Flash.
                val currentText = _state.value.combinedText.trim()
                val currentErr = _state.value.errorMessage
                if (currentText.isEmpty() && !isAuthError(currentErr) && !isAppCheckError(currentErr)) {
                    val fallbackBytes = drainFallbackPcm()
                    if (fallbackBytes.size >= MIN_FALLBACK_PCM_BYTES) {
                        streamGeminiSegment(fallbackBytes, activePrefs)
                    }
                }
            }

            LivePipelineMode.OPENAI_CHUNKED_REST,
            LivePipelineMode.GEMINI_VAD_STREAM -> {
                flushRemainingChunkSegment(forceFallbackIfEmpty = _state.value.combinedText.isBlank())
                segmentQueue?.close()
                chunkConsumerJob?.join()
                chunkConsumerJob = null
                segmentQueue = null
            }
        }

        val finalState = _state.value
        val fullText = finalState.combinedText.trim()
        val finalError = fallbackError ?: finalState.errorMessage
        val elapsedMs = (System.currentTimeMillis() - sessionStartedAtMs).coerceAtLeast(0L)

        clearBuffers()
        isFinalizing = false
        _state.value = LiveStreamState()
        onLivePartialUpdate = null
        onLiveSegmentCommit = null

        if (fullText.isBlank()) {
            return if (!finalError.isNullOrBlank()) {
                LiveFinalizeResult.Error(finalError)
            } else {
                LiveFinalizeResult.Empty
            }
        }

        val isWritten = activePrefs.defaultFormat == CantoneseFormat.WRITTEN_CHINESE
        val sha256 = sha256OfText("$sessionStartedAtMs:$fullText")

        val codecLabel = when (modeAtStop) {
            LivePipelineMode.OPENAI_REALTIME -> "Live PCM16 24kHz WS"
            LivePipelineMode.OPENAI_CHUNKED_REST -> "OpenAI Live PCM16 16kHz"
            LivePipelineMode.GEMINI_LIVE_API -> "Gemini 3.5 Transcribe Live 16kHz WS"
            LivePipelineMode.GEMINI_VAD_STREAM -> "Gemini Live PCM16 16kHz"
        }

        val record = TranscriptRecord(
            sourceType = activeSourceType,
            fileName = if (activeSourceType == AudioSourceType.KEYBOARD_LIVE) {
                "Live Keyboard Stream"
            } else {
                "Live Studio Stream"
            },
            codecLabel = codecLabel,
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
        return LiveFinalizeResult.Success(record)
    }

    fun stopAndDiscard() {
        isFinalizing = false
        pcmRecorder.stop()
        metricsJob?.cancel()
        metricsJob = null
        openAiRealtime.disconnect()
        geminiLive.disconnect()
        segmentQueue?.close()
        segmentQueue = null
        chunkConsumerJob?.cancel()
        chunkConsumerJob = null
        clearBuffers()
        _state.value = LiveStreamState()
        onLivePartialUpdate = null
        onLiveSegmentCommit = null
    }

    // --- Pipeline 1: OpenAI Realtime WebSocket ---

    private fun startOpenAiRealtimeSession(
        scope: CoroutineScope,
        preferences: UserPreferences
    ): Result<Unit> {
        pipelineMode = LivePipelineMode.OPENAI_REALTIME
        activeSampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_OPENAI_REALTIME_HZ
        realtimeUsesServerVad = true
        realtimeSpeechStartedMs = 0L
        realtimeLastSpeechMs = 0L
        realtimeHasPendingSpeech = false
        clearBuffers()

        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = true,
            engineLabel = "OpenAI Realtime · Connecting…",
            engineId = TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE
        )

        val preferWritten = preferences.defaultFormat == CantoneseFormat.WRITTEN_CHINESE
        openAiRealtime.connect(
            customGlossary = preferences.customGlossary,
            preferWritten = preferWritten,
            listener = object : RealtimeTranscriptListener {
                override fun onSessionReady(modelName: String, usesServerVad: Boolean) {
                    realtimeUsesServerVad = usesServerVad
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            engineLabel = "OpenAI Live · $modelName",
                            errorMessage = null
                        )
                    }
                }

                override fun onPartialDelta(delta: String) {
                    _state.update { cur ->
                        val nextPartial = cur.partialText + delta
                        scope.launch(Dispatchers.Main.immediate) {
                            onLivePartialUpdate?.invoke(nextPartial)
                        }
                        cur.copy(
                            partialText = nextPartial,
                            isTranscribingChunk = true,
                            errorMessage = null
                        )
                    }
                }

                override fun onSegmentCompleted(transcript: String) {
                    _state.update { cur ->
                        val nextConfirmed = appendTranscriptSegment(cur.confirmedText, transcript)
                        cur.copy(
                            confirmedText = nextConfirmed,
                            partialText = "",
                            isTranscribingChunk = false,
                            errorMessage = null
                        )
                    }
                    scope.launch(Dispatchers.Main.immediate) {
                        onLiveSegmentCommit?.invoke(transcript)
                    }
                }

                override fun onError(message: String) {
                    if (!_state.value.isActive || isFinalizing) return
                    val existingConfirmed = _state.value.combinedText

                    if (preferences.routingMode == RoutingMode.AUTO) {
                        Log.w(TAG, "OpenAI Realtime error ($message); failing over live to Gemini Live API")
                        openAiRealtime.disconnect()
                        pcmRecorder.stop()
                        startGeminiLiveApiSession(scope, preferences, carryOverText = existingConfirmed)
                    } else if (!isAuthError(message)) {
                        // In OPENAI_ONLY mode, fail over from Realtime WebSocket to OpenAI Chunked REST
                        // streaming so OpenAI STILL transcribes live phrase-by-phrase!
                        Log.w(TAG, "OpenAI Realtime unavailable ($message); switching live to OpenAI Chunked REST")
                        openAiRealtime.disconnect()
                        pcmRecorder.stop()
                        startOpenAiChunkedRestSession(
                            scope = scope,
                            preferences = preferences,
                            carryOverText = existingConfirmed
                        )
                    } else {
                        _state.update { it.copy(isConnecting = false, isTranscribingChunk = false, errorMessage = message) }
                    }
                }

                override fun onClosed() {
                    _state.update { it.copy(isConnecting = false, isTranscribingChunk = false) }
                }
            }
        )

        startMetricsRelay(scope)

        return pcmRecorder.start(
            scope = scope,
            sampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_OPENAI_REALTIME_HZ,
            onChunk = { chunk ->
                handleOpenAiRealtimeChunk(chunk)
            },
            onError = { err ->
                _state.update { it.copy(errorMessage = err.localizedMessage ?: "Mic capture error") }
            }
        )
    }

    private fun handleOpenAiRealtimeChunk(chunk: PcmAudioChunk) {
        openAiRealtime.sendPcmChunk(chunk.pcmBytes)
        appendFallbackPcm(chunk.pcmBytes)
        _state.update { it.copy(totalAudioBytes = it.totalAudioBytes + chunk.pcmBytes.size) }

        if (chunk.isSpeech) {
            if (!realtimeHasPendingSpeech) {
                realtimeHasPendingSpeech = true
                realtimeSpeechStartedMs = chunk.timestampMs
            }
            realtimeLastSpeechMs = chunk.timestampMs
        }

        if (realtimeHasPendingSpeech && openAiRealtime.hasUncommittedAudio()) {
            val silenceMs = chunk.timestampMs - realtimeLastSpeechMs
            val continuousMs = chunk.timestampMs - realtimeSpeechStartedMs

            val shouldCommit = if (!realtimeUsesServerVad) {
                // Dedicated gpt-live-transcribe (turn_detection = null) relies on client commits
                silenceMs >= REALTIME_MANUAL_PAUSE_MS || continuousMs >= REALTIME_MANUAL_WINDOW_MS
            } else {
                // Even with server_vad, commit periodically during non-stop speech so text streams live
                continuousMs >= REALTIME_SERVER_VAD_MAX_CONTINUOUS_MS
            }

            if (shouldCommit) {
                if (openAiRealtime.commitBuffer()) {
                    realtimeHasPendingSpeech = false
                    realtimeSpeechStartedMs = 0L
                    _state.update { it.copy(isTranscribingChunk = true) }
                }
            }
        }
    }

    // --- Pipeline 2: OpenAI Chunked Live REST Fallback ---

    private fun startOpenAiChunkedRestSession(
        scope: CoroutineScope,
        preferences: UserPreferences,
        carryOverText: String = ""
    ): Result<Unit> {
        pipelineMode = LivePipelineMode.OPENAI_CHUNKED_REST
        activeSampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ
        clearBuffers()

        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = false,
            confirmedText = carryOverText,
            partialText = "",
            engineLabel = "OpenAI Live · Chunked STT",
            engineId = TranscriptionEngineId.OPENAI_GPT_TRANSCRIBE
        )

        val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        segmentQueue = queue

        chunkConsumerJob?.cancel()
        chunkConsumerJob = scope.launch(Dispatchers.IO) {
            for (pcmSegment in queue) {
                transcribeOpenAiChunkSegment(pcmSegment, preferences)
            }
        }

        startMetricsRelay(scope)

        return pcmRecorder.start(
            scope = scope,
            sampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ,
            onChunk = { chunk ->
                handleChunkedPcm(chunk, queue)
            },
            onError = { err ->
                _state.update { it.copy(errorMessage = err.localizedMessage ?: "Mic capture error") }
            }
        )
    }

    private suspend fun transcribeOpenAiChunkSegment(pcmBytes: ByteArray, preferences: UserPreferences) {
        val wavBytes = wrapPcm16AsWav(pcmBytes, LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ)
        val contextTail = _state.value.confirmedText.takeLast(48)
        _state.update { it.copy(isTranscribingChunk = true) }

        val res = openAiRest.transcribeWavChunk(
            wavBytes = wavBytes,
            customGlossary = preferences.customGlossary,
            preferWritten = preferences.defaultFormat == CantoneseFormat.WRITTEN_CHINESE,
            contextTail = contextTail
        )
        res.onSuccess { out ->
            val cleaned = out.text.trim()
            if (cleaned.isNotEmpty()) {
                _state.update { cur ->
                    cur.copy(
                        confirmedText = appendTranscriptSegment(cur.confirmedText, cleaned),
                        partialText = "",
                        isTranscribingChunk = false,
                        engineId = out.engineId,
                        lastChunkLatencyMs = out.latencyMillis,
                        errorMessage = null
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    onLiveSegmentCommit?.invoke(cleaned)
                }
            } else {
                _state.update { it.copy(isTranscribingChunk = false) }
            }
        }.onFailure { err ->
            Log.e(TAG, "OpenAI live REST chunk failed", err)
            _state.update {
                it.copy(
                    isTranscribingChunk = false,
                    errorMessage = err.localizedMessage ?: "OpenAI chunk error"
                )
            }
        }
    }

    // --- Pipeline 3: Gemini 3.5 Transcribe Live API (True Full-Duplex Bidirectional Streaming) ---

    private fun startGeminiLiveApiSession(
        scope: CoroutineScope,
        preferences: UserPreferences,
        carryOverText: String = ""
    ): Result<Unit> {
        pipelineMode = LivePipelineMode.GEMINI_LIVE_API
        activeSampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ
        clearBuffers()

        val usingDirectKey = keyStore.resolveDirectGeminiKey() != null
        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = true,
            confirmedText = carryOverText,
            partialText = "",
            engineLabel = if (usingDirectKey) "Gemini Direct Live · Connecting…" else "Gemini 3.5 Transcribe Live · Connecting…",
            engineId = if (usingDirectKey) TranscriptionEngineId.GEMINI_DIRECT_LIVE else TranscriptionEngineId.GEMINI_LIVE
        )

        var lastChunkTs = System.currentTimeMillis()
        geminiLive.connect(
            scope = scope,
            format = preferences.defaultFormat,
            customGlossary = preferences.customGlossary,
            listener = object : GeminiLiveTranscriptListener {
                override fun onSessionReady(modelName: String, isDirectKey: Boolean) {
                    if (!_state.value.isActive || pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return
                    lastChunkTs = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            engineLabel = if (isDirectKey) "Gemini Direct Live · $modelName" else "Gemini Live · $modelName",
                            engineId = if (isDirectKey) TranscriptionEngineId.GEMINI_DIRECT_LIVE else TranscriptionEngineId.GEMINI_LIVE,
                            errorMessage = null
                        )
                    }
                }

                override fun onInterimTranscript(text: String) {
                    if (!_state.value.isActive || pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return
                    val clean = text.trim()
                    if (clean.isEmpty()) return
                    val now = System.currentTimeMillis()
                    val latency = (now - lastChunkTs).coerceIn(0L, 10_000L)
                    lastChunkTs = now

                    _state.update { cur ->
                        cur.copy(
                            partialText = clean,
                            isTranscribingChunk = true,
                            lastChunkLatencyMs = latency,
                            errorMessage = null
                        )
                    }
                    scope.launch(Dispatchers.Main.immediate) {
                        onLivePartialUpdate?.invoke(clean)
                    }
                }

                override fun onFinalTranscript(text: String) {
                    if (!_state.value.isActive || pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return
                    val committed = text.trim()
                    if (committed.isEmpty()) return
                    val now = System.currentTimeMillis()
                    val latency = (now - lastChunkTs).coerceIn(0L, 10_000L)
                    lastChunkTs = now

                    _state.update { cur ->
                        cur.copy(
                            confirmedText = appendTranscriptSegment(cur.confirmedText, committed),
                            partialText = "",
                            isTranscribingChunk = false,
                            lastChunkLatencyMs = latency,
                            errorMessage = null
                        )
                    }
                    scope.launch(Dispatchers.Main.immediate) {
                        onLiveSegmentCommit?.invoke(committed)
                    }
                }

                override fun onDeltaTranscript(delta: String, isTurnFinished: Boolean) {
                    if (!_state.value.isActive || pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return
                    val now = System.currentTimeMillis()
                    val latency = (now - lastChunkTs).coerceIn(0L, 10_000L)
                    if (delta.isNotEmpty()) {
                        lastChunkTs = now
                    }

                    var committedToNotify: String? = null
                    var partialToNotify: String? = null

                    _state.update { cur ->
                        val nextPartial = cur.partialText + delta
                        if (isTurnFinished) {
                            val committed = nextPartial.trim()
                            if (committed.isNotEmpty()) {
                                committedToNotify = committed
                                cur.copy(
                                    confirmedText = appendTranscriptSegment(cur.confirmedText, committed),
                                    partialText = "",
                                    isTranscribingChunk = false,
                                    lastChunkLatencyMs = latency,
                                    errorMessage = null
                                )
                            } else {
                                cur.copy(
                                    partialText = "",
                                    isTranscribingChunk = false
                                )
                            }
                        } else if (delta.isNotEmpty()) {
                            partialToNotify = nextPartial.trim()
                            cur.copy(
                                partialText = nextPartial,
                                isTranscribingChunk = true,
                                lastChunkLatencyMs = latency,
                                errorMessage = null
                            )
                        } else {
                            cur
                        }
                    }

                    committedToNotify?.let { segment ->
                        scope.launch(Dispatchers.Main.immediate) {
                            onLiveSegmentCommit?.invoke(segment)
                        }
                    } ?: partialToNotify?.takeIf { it.isNotEmpty() }?.let { partial ->
                        scope.launch(Dispatchers.Main.immediate) {
                            onLivePartialUpdate?.invoke(partial)
                        }
                    }
                }

                override fun onError(message: String) {
                    if (!_state.value.isActive || isFinalizing || pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return

                    if (isAppCheckError(message)) {
                        val friendly = "Firebase App Check rejected this build. Add your personal Gemini API key in the Keys tab."
                        _state.update { it.copy(isConnecting = false, isTranscribingChunk = false, errorMessage = friendly) }
                        return
                    }

                    if (isAuthError(message)) {
                        _state.update { it.copy(isConnecting = false, isTranscribingChunk = false, errorMessage = message) }
                        return
                    }

                    val existingText = _state.value.combinedText
                    val bufferedPcm = drainFallbackPcm()
                    Log.w(TAG, "Gemini Live API error ($message); failing over seamlessly to Gemini VAD chunked streaming")
                    geminiLive.disconnect()
                    pcmRecorder.stop()
                    startGeminiVadChunkSession(
                        scope = scope,
                        preferences = preferences,
                        carryOverText = existingText,
                        initialBufferedPcm = bufferedPcm.takeIf { it.size >= CHUNK_MIN_SEGMENT_BYTES }
                    )
                }

                override fun onClosed() {
                    if (pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return
                    _state.update { it.copy(isConnecting = false, isTranscribingChunk = false) }
                }
            }
        )

        startMetricsRelay(scope)

        return pcmRecorder.start(
            scope = scope,
            sampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ,
            onChunk = { chunk ->
                geminiLive.sendPcm16kChunk(chunk.pcmBytes)
                appendFallbackPcm(chunk.pcmBytes)
                _state.update { it.copy(totalAudioBytes = it.totalAudioBytes + chunk.pcmBytes.size) }
            },
            onError = { err ->
                _state.update { it.copy(errorMessage = err.localizedMessage ?: "Mic capture error") }
            }
        )
    }

    // --- Pipeline 4: Gemini Live VAD Streaming Fallback ---

    private fun startGeminiVadChunkSession(
        scope: CoroutineScope,
        preferences: UserPreferences,
        carryOverText: String = "",
        initialBufferedPcm: ByteArray? = null
    ): Result<Unit> {
        pipelineMode = LivePipelineMode.GEMINI_VAD_STREAM
        activeSampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ
        clearBuffers()

        val usingDirectKey = keyStore.resolveDirectGeminiKey() != null
        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = false,
            confirmedText = carryOverText,
            partialText = "",
            engineLabel = if (usingDirectKey) "Gemini Direct · VAD Stream" else "Gemini Flash-Lite · VAD Stream",
            engineId = if (usingDirectKey) TranscriptionEngineId.GEMINI_DIRECT_LITE else TranscriptionEngineId.GEMINI_FLASH_LITE
        )

        val queue = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        segmentQueue = queue
        if (initialBufferedPcm != null && initialBufferedPcm.isNotEmpty()) {
            queue.trySend(initialBufferedPcm)
        }

        chunkConsumerJob?.cancel()
        chunkConsumerJob = scope.launch(Dispatchers.IO) {
            for (pcmSegment in queue) {
                streamGeminiSegment(pcmSegment, preferences)
            }
        }

        startMetricsRelay(scope)

        return pcmRecorder.start(
            scope = scope,
            sampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ,
            onChunk = { chunk ->
                handleChunkedPcm(chunk, queue)
            },
            onError = { err ->
                _state.update { it.copy(errorMessage = err.localizedMessage ?: "Mic capture error") }
            }
        )
    }

    private fun handleChunkedPcm(chunk: PcmAudioChunk, queue: Channel<ByteArray>) {
        appendFallbackPcm(chunk.pcmBytes)
        _state.update { it.copy(totalAudioBytes = it.totalAudioBytes + chunk.pcmBytes.size) }

        var readySegment: ByteArray? = null
        synchronized(segmentBufferLock) {
            if (chunk.isSpeech) {
                if (!segmentHasSpeech) {
                    segmentHasSpeech = true
                    segmentStartedAtMs = chunk.timestampMs
                    // Prepend pre-roll frames so the beginning of the first syllable is preserved.
                    while (preRollFrames.isNotEmpty()) {
                        segmentPcmBuffer.write(preRollFrames.removeFirst())
                    }
                }
                lastSpeechSeenAtMs = chunk.timestampMs
                segmentPcmBuffer.write(chunk.pcmBytes)
            } else if (segmentHasSpeech) {
                segmentPcmBuffer.write(chunk.pcmBytes)
            } else {
                // Keep a rolling 300ms pre-roll buffer during silence.
                if (preRollFrames.size >= PRE_ROLL_MAX_FRAMES) {
                    preRollFrames.removeFirst()
                }
                preRollFrames.addLast(chunk.pcmBytes)
            }

            if (segmentHasSpeech) {
                val silenceDuration = chunk.timestampMs - lastSpeechSeenAtMs
                val windowDuration = chunk.timestampMs - segmentStartedAtMs

                val shouldDispatch = (silenceDuration >= CHUNK_VAD_PAUSE_MS &&
                    segmentPcmBuffer.size() >= CHUNK_MIN_SEGMENT_BYTES) ||
                    windowDuration >= CHUNK_MAX_WINDOW_MS

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

    private fun flushRemainingChunkSegment(forceFallbackIfEmpty: Boolean) {
        val tail: ByteArray? = synchronized(segmentBufferLock) {
            when {
                segmentPcmBuffer.size() >= CHUNK_MIN_SEGMENT_BYTES -> {
                    segmentPcmBuffer.toByteArray().also {
                        segmentPcmBuffer.reset()
                        segmentHasSpeech = false
                    }
                }
                forceFallbackIfEmpty && sessionFallbackPcm.size() >= MIN_FALLBACK_PCM_BYTES -> {
                    sessionFallbackPcm.toByteArray().also {
                        sessionFallbackPcm.reset()
                        segmentPcmBuffer.reset()
                        segmentHasSpeech = false
                    }
                }
                else -> {
                    segmentPcmBuffer.reset()
                    segmentHasSpeech = false
                    null
                }
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
        _state.update { it.copy(isTranscribingChunk = true) }

        try {
            withTimeout(STREAM_CHUNK_TIMEOUT_MS) {
                if (directKey != null) {
                    val primaryModel = keyStore.resolveCustomGeminiModel() ?: DEFAULT_LIVE_GEMINI_MODEL
                    try {
                        directGeminiRest.streamAudioSegment(
                            apiKey = directKey,
                            modelName = primaryModel,
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
                    } catch (primaryErr: Exception) {
                        if (primaryErr is CancellationException) throw primaryErr
                        val fallbackModel = if (primaryModel == DEFAULT_LIVE_GEMINI_MODEL) {
                            FALLBACK_LIVE_GEMINI_MODEL
                        } else {
                            DEFAULT_LIVE_GEMINI_MODEL
                        }
                        Log.w(TAG, "Direct Gemini live stream ($primaryModel) failed; retrying with $fallbackModel", primaryErr)
                        segmentBuilder.clear()
                        directGeminiRest.streamAudioSegment(
                            apiKey = directKey,
                            modelName = fallbackModel,
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
                    }
                } else {
                    try {
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
                    } catch (primaryErr: Exception) {
                        if (primaryErr is CancellationException) throw primaryErr
                        Log.w(TAG, "Firebase AI live stream ($DEFAULT_LIVE_GEMINI_MODEL) failed; retrying with $FALLBACK_LIVE_GEMINI_MODEL", primaryErr)
                        segmentBuilder.clear()
                        geminiLiveFallbackModel.generateContentStream(
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
            }

            val finalized = segmentBuilder.toString().trim()
            val latency = System.currentTimeMillis() - startedAt
            if (finalized.isNotEmpty()) {
                _state.update { cur ->
                    cur.copy(
                        confirmedText = appendTranscriptSegment(cur.confirmedText, finalized),
                        partialText = "",
                        isTranscribingChunk = false,
                        lastChunkLatencyMs = latency,
                        errorMessage = null
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    onLiveSegmentCommit?.invoke(finalized)
                }
            } else {
                _state.update { it.copy(partialText = "", isTranscribingChunk = false) }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini live chunk timed out", e)
            _state.update {
                it.copy(
                    partialText = "",
                    isTranscribingChunk = false,
                    errorMessage = "Live chunk timed out; still listening…"
                )
            }
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
                    isTranscribingChunk = false,
                    errorMessage = friendly
                )
            }
        }
    }

    private fun appendFallbackPcm(pcmBytes: ByteArray) {
        synchronized(segmentBufferLock) {
            if (sessionFallbackPcm.size() + pcmBytes.size <= MAX_FALLBACK_PCM_BYTES) {
                sessionFallbackPcm.write(pcmBytes)
            }
        }
    }

    private fun drainFallbackPcm(): ByteArray {
        return synchronized(segmentBufferLock) {
            sessionFallbackPcm.toByteArray().also { sessionFallbackPcm.reset() }
        }
    }

    private fun clearBuffers() {
        synchronized(segmentBufferLock) {
            preRollFrames.clear()
            segmentPcmBuffer.reset()
            sessionFallbackPcm.reset()
            segmentHasSpeech = false
            segmentStartedAtMs = 0L
            lastSpeechSeenAtMs = 0L
        }
    }

    private fun isAuthError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains("401") ||
            message.contains("invalid_api_key", ignoreCase = true) ||
            message.contains("Incorrect API key", ignoreCase = true) ||
            message.contains("API key not valid", ignoreCase = true) ||
            message.contains("authentication", ignoreCase = true)
    }

    private fun isAppCheckError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains("App Check", ignoreCase = true) || message.contains("403")
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
