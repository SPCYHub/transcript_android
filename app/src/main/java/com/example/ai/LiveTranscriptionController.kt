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
private val LIVE_GEMINI_STREAM_MODELS = listOf(
    "gemini-4-hydrogen",
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "gemini-2.5-flash-lite"
)

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
    val engineId: TranscriptionEngineId = TranscriptionEngineId.GEMINI_DIRECT_LITE,
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
 * Real-time streaming transcription controller powered exclusively by the user's saved API keys
 * (Direct Gemini API + OpenAI Realtime / REST — zero Firebase dependencies):
 *
 * 1. OpenAI Realtime WebSocket (`gpt-4o-transcribe` / `gpt-4o-mini-transcribe` / `gpt-live-transcribe`)
 *    with `near_field` noise reduction, clean server VAD (no colliding client commits!), and
 *    post-transcription sanitization + boundary deduplication.
 * 2. OpenAI Chunked Live REST fallback (`/v1/audio/transcriptions`) with `temperature = 0`.
 * 3. Gemini Direct Live API (`BidiGenerateContent` WebSocket) with VAD sensitivity tuning.
 * 4. Gemini Direct VAD Chunked SSE Streaming (`streamGenerateContent`) with top-level
 *    `systemInstruction`, `temperature = 0`, and `thinkingBudget = 0`.
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
        private const val CHUNK_VAD_PAUSE_MS = 380L
        private const val CHUNK_MAX_WINDOW_MS = 1_450L
        private const val CHUNK_MIN_SEGMENT_BYTES = 16_000 * 2 * 250 / 1000 // 250ms at 16kHz PCM16
        private const val PRE_ROLL_MAX_FRAMES = 3 // ~300ms pre-roll so opening syllables aren't clipped
        private const val STREAM_CHUNK_TIMEOUT_MS = 30_000L

        // Used ONLY when turn_detection == null (e.g. gpt-live-transcribe); never collides with server_vad
        private const val REALTIME_MANUAL_PAUSE_MS = 450L
        private const val REALTIME_MANUAL_WINDOW_MS = 3_200L

        // Capped fallback audio buffer (up to ~16s) so short/quiet utterances can still be finalized on stop
        private const val MAX_FALLBACK_PCM_BYTES = 24_000 * 2 * 16
        private const val MIN_FALLBACK_PCM_BYTES = 16_000 * 2 * 300 / 1000
    }

    private val pcmRecorder = LivePcmStreamRecorder()

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
    @Volatile
    private var preferredStreamGeminiModel: String? = null

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

        val hasOpenAi = openAiRealtime.isConfigured
        val hasGemini = keyStore.hasDirectGeminiKey()

        val useOpenAi = when (preferences.routingMode) {
            RoutingMode.OPENAI_ONLY -> {
                if (!hasOpenAi) {
                    val msg = "OpenAI API key is not configured. Add your OpenAI key in the Keys tab, or switch to Auto / Gemini."
                    _state.value = LiveStreamState(errorMessage = msg)
                    return Result.failure(IllegalStateException(msg))
                }
                true
            }
            RoutingMode.GEMINI_ONLY -> {
                if (!hasGemini) {
                    val msg = "Gemini API key is not configured. Add and save your Gemini API key in the Keys tab."
                    _state.value = LiveStreamState(errorMessage = msg)
                    return Result.failure(IllegalStateException(msg))
                }
                false
            }
            RoutingMode.AUTO -> {
                when {
                    hasOpenAi -> true
                    hasGemini -> false
                    else -> {
                        val msg = "No API key configured. Add your Gemini or OpenAI API key in the Keys tab to start live dictation."
                        _state.value = LiveStreamState(errorMessage = msg)
                        return Result.failure(IllegalStateException(msg))
                    }
                }
            }
        }

        return if (useOpenAi) {
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
                            customGlossary = activePrefs.customGlossary
                        )
                        restRes.onSuccess { out ->
                            val deduped = commitSanitizedSegment(
                                rawSegment = out.text,
                                activePrompt = "",
                                engineIdOverride = out.engineId,
                                latencyOverride = out.latencyMillis
                            )
                            if (deduped.isNotEmpty()) {
                                withContext(Dispatchers.Main.immediate) {
                                    onLiveSegmentCommit?.invoke(deduped)
                                }
                            }
                        }.onFailure { err ->
                            fallbackError = err.message ?: currentErr
                        }
                    }
                }
            }

            LivePipelineMode.GEMINI_LIVE_API -> {
                geminiLive.finishAndAwaitTrailingTranscript(maxWaitMs = 2_400L)

                val currentText = _state.value.combinedText.trim()
                val currentErr = _state.value.errorMessage
                if (currentText.isEmpty() && !isAuthError(currentErr) && keyStore.hasDirectGeminiKey()) {
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
            LivePipelineMode.GEMINI_LIVE_API -> "Gemini Direct Live 16kHz WS"
            LivePipelineMode.GEMINI_VAD_STREAM -> "Gemini Direct PCM16 16kHz"
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
            writtenChinese = if (isWritten && modeAtStop != LivePipelineMode.OPENAI_REALTIME && modeAtStop != LivePipelineMode.OPENAI_CHUNKED_REST) {
                fullText
            } else {
                null
            },
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

        openAiRealtime.connect(
            customGlossary = preferences.customGlossary,
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
                    var sanitizedPartial: String? = null
                    _state.update { cur ->
                        val rawPartial = cur.partialText + delta
                        val cleaned = TranscriptSanitizer.sanitizeSegment(
                            rawText = rawPartial,
                            activePrompt = openAiRealtime.currentPromptUsed,
                            customGlossary = activePrefs.customGlossary
                        )
                        sanitizedPartial = cleaned
                        cur.copy(
                            partialText = cleaned,
                            isTranscribingChunk = cleaned.isNotEmpty(),
                            errorMessage = null
                        )
                    }
                    sanitizedPartial?.takeIf { it.isNotEmpty() }?.let { partial ->
                        scope.launch(Dispatchers.Main.immediate) {
                            onLivePartialUpdate?.invoke(partial)
                        }
                    }
                }

                override fun onSegmentCompleted(transcript: String) {
                    val netNew = commitSanitizedSegment(
                        rawSegment = transcript,
                        activePrompt = openAiRealtime.currentPromptUsed
                    )
                    if (netNew.isNotEmpty()) {
                        scope.launch(Dispatchers.Main.immediate) {
                            onLiveSegmentCommit?.invoke(netNew)
                        }
                    } else {
                        // Clear any composing partial if the completed segment turned out to be empty/duplicate
                        scope.launch(Dispatchers.Main.immediate) {
                            onLivePartialUpdate?.invoke("")
                        }
                    }
                }

                override fun onError(message: String) {
                    if (!_state.value.isActive || isFinalizing) return
                    val existingConfirmed = _state.value.combinedText

                    if (preferences.routingMode == RoutingMode.AUTO && keyStore.hasDirectGeminiKey()) {
                        Log.w(TAG, "OpenAI Realtime error (${ProviderRequestAuth.redactSecrets(message)}); failing over live to Gemini Direct Live")
                        openAiRealtime.disconnect()
                        pcmRecorder.stop()
                        startGeminiLiveApiSession(scope, preferences, carryOverText = existingConfirmed)
                    } else if (!isAuthError(message)) {
                        Log.w(TAG, "OpenAI Realtime unavailable (${ProviderRequestAuth.redactSecrets(message)}); switching live to OpenAI Chunked REST")
                        openAiRealtime.disconnect()
                        pcmRecorder.stop()
                        startOpenAiChunkedRestSession(
                            scope = scope,
                            preferences = preferences,
                            carryOverText = existingConfirmed
                        )
                    } else {
                        _state.update { it.copy(isConnecting = false, isTranscribingChunk = false, errorMessage = ProviderRequestAuth.redactSecrets(message)) }
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

        // Only manually commit buffers when server_vad is DISABLED (e.g. gpt-live-transcribe).
        // When server_vad is active, let OpenAI's server VAD cleanly commit utterances at natural
        // pauses so we never slice mid-word or trigger double-commits / prompt hallucinations!
        if (!realtimeUsesServerVad && realtimeHasPendingSpeech && openAiRealtime.hasUncommittedAudio()) {
            val silenceMs = chunk.timestampMs - realtimeLastSpeechMs
            val continuousMs = chunk.timestampMs - realtimeSpeechStartedMs
            val shouldCommit = silenceMs >= REALTIME_MANUAL_PAUSE_MS || continuousMs >= REALTIME_MANUAL_WINDOW_MS

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
            contextTail = contextTail
        )
        res.onSuccess { out ->
            val netNew = commitSanitizedSegment(
                rawSegment = out.text,
                activePrompt = contextTail,
                engineIdOverride = out.engineId,
                latencyOverride = out.latencyMillis
            )
            if (netNew.isNotEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    onLiveSegmentCommit?.invoke(netNew)
                }
            }
        }.onFailure { err ->
            val safeMsg = ProviderRequestAuth.redactSecrets(err.localizedMessage ?: "OpenAI chunk error")
            Log.e(TAG, "OpenAI live REST chunk failed: $safeMsg")
            _state.update {
                it.copy(
                    isTranscribingChunk = false,
                    errorMessage = safeMsg
                )
            }
        }
    }

    // --- Pipeline 3: Gemini Direct Live API (User API Key WebSocket) ---

    private fun startGeminiLiveApiSession(
        scope: CoroutineScope,
        preferences: UserPreferences,
        carryOverText: String = ""
    ): Result<Unit> {
        pipelineMode = LivePipelineMode.GEMINI_LIVE_API
        activeSampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ
        clearBuffers()

        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = true,
            confirmedText = carryOverText,
            partialText = "",
            engineLabel = "Gemini Direct Live · Connecting…",
            engineId = TranscriptionEngineId.GEMINI_DIRECT_LIVE
        )

        var lastChunkTs = System.currentTimeMillis()
        val connected = geminiLive.connect(
            format = preferences.defaultFormat,
            customGlossary = preferences.customGlossary,
            listener = object : GeminiLiveTranscriptListener {
                override fun onSessionReady(modelName: String, isDirectKey: Boolean) {
                    if (!_state.value.isActive || pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return
                    lastChunkTs = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            engineLabel = "Gemini Direct Live · $modelName",
                            engineId = TranscriptionEngineId.GEMINI_DIRECT_LIVE,
                            errorMessage = null
                        )
                    }
                }

                override fun onInterimTranscript(text: String) {
                    if (!_state.value.isActive || pipelineMode != LivePipelineMode.GEMINI_LIVE_API) return
                    val clean = TranscriptSanitizer.sanitizeSegment(
                        rawText = text,
                        customGlossary = activePrefs.customGlossary
                    )
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
                    val now = System.currentTimeMillis()
                    val latency = (now - lastChunkTs).coerceIn(0L, 10_000L)
                    lastChunkTs = now

                    val netNew = commitSanitizedSegment(
                        rawSegment = text,
                        activePrompt = "",
                        latencyOverride = latency
                    )
                    if (netNew.isNotEmpty()) {
                        scope.launch(Dispatchers.Main.immediate) {
                            onLiveSegmentCommit?.invoke(netNew)
                        }
                    } else {
                        scope.launch(Dispatchers.Main.immediate) {
                            onLivePartialUpdate?.invoke("")
                        }
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
                            val sanitized = TranscriptSanitizer.sanitizeSegment(
                                rawText = nextPartial,
                                customGlossary = activePrefs.customGlossary
                            )
                            val netNew = TranscriptSanitizer.deduplicateAgainstExisting(cur.confirmedText, sanitized)
                            if (netNew.isNotEmpty()) {
                                committedToNotify = netNew
                                cur.copy(
                                    confirmedText = appendTranscriptSegment(cur.confirmedText, netNew),
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

                    if (isAuthError(message)) {
                        _state.update { it.copy(isConnecting = false, isTranscribingChunk = false, errorMessage = ProviderRequestAuth.redactSecrets(message)) }
                        return
                    }

                    val existingText = _state.value.combinedText
                    val bufferedPcm = drainFallbackPcm()
                    Log.w(TAG, "Gemini Live API error (${ProviderRequestAuth.redactSecrets(message)}); failing over seamlessly to Gemini VAD chunked streaming")
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

        if (!connected) {
            val msg = "Gemini API key is not configured. Add and save your Gemini API key in the Keys tab."
            _state.value = LiveStreamState(errorMessage = msg)
            return Result.failure(IllegalStateException(msg))
        }

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

    // --- Pipeline 4: Gemini Direct VAD Chunked SSE Streaming Fallback ---

    private fun startGeminiVadChunkSession(
        scope: CoroutineScope,
        preferences: UserPreferences,
        carryOverText: String = "",
        initialBufferedPcm: ByteArray? = null
    ): Result<Unit> {
        pipelineMode = LivePipelineMode.GEMINI_VAD_STREAM
        activeSampleRateHz = LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ
        clearBuffers()

        _state.value = LiveStreamState(
            isActive = true,
            isConnecting = false,
            confirmedText = carryOverText,
            partialText = "",
            engineLabel = "Gemini Direct · VAD Stream",
            engineId = TranscriptionEngineId.GEMINI_DIRECT_LITE
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
                    while (preRollFrames.isNotEmpty()) {
                        segmentPcmBuffer.write(preRollFrames.removeFirst())
                    }
                }
                lastSpeechSeenAtMs = chunk.timestampMs
                segmentPcmBuffer.write(chunk.pcmBytes)
            } else if (segmentHasSpeech) {
                segmentPcmBuffer.write(chunk.pcmBytes)
            } else {
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
        val directKey = keyStore.resolveDirectGeminiKey()
        if (directKey.isNullOrBlank()) {
            _state.update {
                it.copy(
                    isTranscribingChunk = false,
                    errorMessage = "Gemini API key is not configured. Add it in the Keys tab."
                )
            }
            return
        }

        val startedAt = System.currentTimeMillis()
        val wavBytes = wrapPcm16AsWav(pcmBytes, LivePcmStreamRecorder.SAMPLE_RATE_GEMINI_HZ)
        val contextTail = _state.value.confirmedText.takeLast(48)

        val systemInstruction = CantonesePrompts.buildFastKeyboardSystemInstruction(
            format = preferences.defaultFormat,
            customGlossary = preferences.customGlossary
        )
        val userPrompt = CantonesePrompts.buildFastKeyboardUserPrompt(contextTail = contextTail)
        val candidates = buildList {
            keyStore.resolveCustomGeminiModel()?.let { add(it) }
            preferredStreamGeminiModel?.let { add(it) }
            addAll(LIVE_GEMINI_STREAM_MODELS)
        }.distinct()

        val segmentBuilder = StringBuilder()
        _state.update { it.copy(isTranscribingChunk = true) }

        try {
            withTimeout(STREAM_CHUNK_TIMEOUT_MS) {
                var lastErr: Exception? = null
                var succeeded = false

                for (modelName in candidates) {
                    segmentBuilder.clear()
                    try {
                        directGeminiRest.streamAudioSegment(
                            apiKey = directKey,
                            modelName = modelName,
                            promptText = userPrompt,
                            systemInstruction = systemInstruction,
                            audioBytes = wavBytes,
                            audioMimeType = "audio/wav",
                            disableThinking = true
                        ).collect { piece ->
                            if (piece.isNotEmpty()) {
                                segmentBuilder.append(piece)
                                val cleanPartial = TranscriptSanitizer.sanitizeSegment(
                                    rawText = segmentBuilder.toString(),
                                    customGlossary = preferences.customGlossary
                                )
                                if (cleanPartial.isNotEmpty()) {
                                    _state.update { it.copy(partialText = cleanPartial, errorMessage = null) }
                                    withContext(Dispatchers.Main.immediate) {
                                        onLivePartialUpdate?.invoke(cleanPartial)
                                    }
                                }
                            }
                        }
                        preferredStreamGeminiModel = modelName
                        succeeded = true
                        break
                    } catch (err: Exception) {
                        if (err is CancellationException) throw err
                        lastErr = err
                        val msg = err.message.orEmpty()
                        val isModelErr = msg.contains("(404)") || msg.contains("not found", ignoreCase = true)
                        if (!isModelErr) throw err
                        Log.w(TAG, "Direct Gemini stream ($modelName) unavailable (${ProviderRequestAuth.redactSecrets(msg)}); trying next model")
                    }
                }

                if (!succeeded && lastErr != null) {
                    throw lastErr
                }
            }

            val latency = System.currentTimeMillis() - startedAt
            val netNew = commitSanitizedSegment(
                rawSegment = segmentBuilder.toString(),
                activePrompt = contextTail,
                latencyOverride = latency
            )
            if (netNew.isNotEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    onLiveSegmentCommit?.invoke(netNew)
                }
            } else {
                withContext(Dispatchers.Main.immediate) {
                    onLivePartialUpdate?.invoke("")
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini live chunk timed out")
            _state.update { it.copy(isTranscribingChunk = false, partialText = "", errorMessage = "Live chunk timed out") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeMsg = ProviderRequestAuth.redactSecrets(e.localizedMessage ?: "Gemini live chunk error")
            Log.e(TAG, "Gemini live chunk failed: $safeMsg")
            _state.update {
                it.copy(
                    isTranscribingChunk = false,
                    partialText = "",
                    errorMessage = safeMsg
                )
            }
        }
    }

    /**
     * Sanitizes, deduplicates against confirmed text, updates [_state], and returns ONLY the
     * net-new text that should be committed into a host IME / sandbox editor.
     */
    private fun commitSanitizedSegment(
        rawSegment: String,
        activePrompt: String,
        engineIdOverride: TranscriptionEngineId? = null,
        latencyOverride: Long? = null
    ): String {
        val sanitized = TranscriptSanitizer.sanitizeSegment(
            rawText = rawSegment,
            activePrompt = activePrompt,
            customGlossary = activePrefs.customGlossary
        )
        var netNewToCommit = ""
        _state.update { cur ->
            val netNew = TranscriptSanitizer.deduplicateAgainstExisting(cur.confirmedText, sanitized)
            netNewToCommit = netNew
            if (netNew.isNotEmpty()) {
                cur.copy(
                    confirmedText = appendTranscriptSegment(cur.confirmedText, netNew),
                    partialText = "",
                    isTranscribingChunk = false,
                    engineId = engineIdOverride ?: cur.engineId,
                    lastChunkLatencyMs = latencyOverride ?: cur.lastChunkLatencyMs,
                    errorMessage = null
                )
            } else {
                cur.copy(
                    partialText = "",
                    isTranscribingChunk = false,
                    engineId = engineIdOverride ?: cur.engineId,
                    lastChunkLatencyMs = latencyOverride ?: cur.lastChunkLatencyMs
                )
            }
        }
        return netNewToCommit
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
            message.contains("403") ||
            message.contains("invalid_api_key", ignoreCase = true) ||
            message.contains("Incorrect API key", ignoreCase = true) ||
            message.contains("API key not valid", ignoreCase = true) ||
            message.contains("authentication", ignoreCase = true)
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
