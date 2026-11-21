package com.example.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.TranscriptionRouter
import com.example.audio.AudioInputHandler
import com.example.core.AppContainer
import com.example.data.PreferencesRepository
import com.example.data.SessionTranscriptStore
import com.example.model.AudioSourceType
import com.example.model.CantoneseFormat
import com.example.model.PreparedAudio
import com.example.model.RoutingMode
import com.example.model.StudioUiState
import com.example.model.TranscriptRecord
import com.example.model.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "StudioViewModel"

data class SandboxState(
    val text: String = "",
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val liveLevel: Float = 0.08f,
    val elapsedMillis: Long = 0L,
    val statusNote: String = "Tap mic to test live Cantonese keyboard dictation",
    val lastInsertedLength: Int = 0
)

class StudioViewModel(
    private val audioHandler: AudioInputHandler = AppContainer.audioInputHandler,
    private val router: TranscriptionRouter = AppContainer.transcriptionRouter,
    private val preferencesRepo: PreferencesRepository = AppContainer.preferencesRepository,
    private val sessionStore: SessionTranscriptStore = AppContainer.sessionStore
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepo.preferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences()
    )

    val sessionRecords: StateFlow<List<TranscriptRecord>> = sessionStore.records

    val isOpenAiConfigured: Boolean
        get() = router.isOpenAiReady

    private val _uiState = MutableStateFlow<StudioUiState>(StudioUiState.Idle)
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _displayFormat = MutableStateFlow(CantoneseFormat.SPOKEN_CANTONESE)
    val displayFormat: StateFlow<CantoneseFormat> = _displayFormat.asStateFlow()

    private val _sandboxState = MutableStateFlow(SandboxState())
    val sandboxState: StateFlow<SandboxState> = _sandboxState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var lastPreparedAudio: PreparedAudio? = null
    private var recordingMetricsJob: Job? = null
    private var activeTranscriptionJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesRepo.preferencesFlow.collectLatest { prefs ->
                _displayFormat.value = prefs.defaultFormat
            }
        }
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    fun postToast(message: String) {
        _snackbarMessage.value = message
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            preferencesRepo.setRoutingMode(mode)
        }
    }

    fun setCustomGlossary(glossary: String) {
        viewModelScope.launch {
            preferencesRepo.setCustomGlossary(glossary)
            _snackbarMessage.value = "Cantonese glossary hints saved"
        }
    }

    fun selectDisplayFormat(format: CantoneseFormat) {
        _displayFormat.value = format
        viewModelScope.launch {
            preferencesRepo.setDefaultFormat(format)
        }

        // If user switched to Written or Dual and current record came from OpenAI without 書面語 yet,
        // convert seamlessly via Gemini 3.5 Lite.
        val current = _uiState.value
        if (current is StudioUiState.Success &&
            format != CantoneseFormat.SPOKEN_CANTONESE &&
            current.record.writtenChinese.isNullOrBlank() &&
            !current.isConvertingWritten
        ) {
            convertCurrentRecordToWritten(current.record)
        }
    }

    fun handleSharedAudioUri(uri: Uri, sourceType: AudioSourceType = AudioSourceType.WHATSAPP_SHARE) {
        activeTranscriptionJob?.cancel()
        activeTranscriptionJob = viewModelScope.launch {
            val prefs = preferencesRepo.currentPreferences()
            _uiState.value = StudioUiState.Processing(
                stageLabel = "Streaming & sniffing audio codec…",
                fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Shared voice message",
                codecLabel = "Detecting…",
                sizeBytes = 0L,
                activeEngineHint = router.resolvePlannedEngineHint(sourceType, prefs.routingMode)
            )

            val preparedResult = audioHandler.prepareFromUri(uri, sourceType)
            val audio = preparedResult.getOrElse { err ->
                _uiState.value = StudioUiState.Error(
                    message = err.message ?: "Unable to read the shared audio file."
                )
                return@launch
            }

            transcribePreparedAudio(audio, overrideMode = null)
        }
    }

    fun startStudioRecording() {
        if (_sandboxState.value.isRecording) {
            cancelSandboxRecording()
        }
        val started = audioHandler.startRecording(viewModelScope)
        if (started.isFailure) {
            _uiState.value = StudioUiState.Error(
                message = started.exceptionOrNull()?.localizedMessage ?: "Failed to start microphone capture."
            )
            return
        }

        recordingMetricsJob?.cancel()
        recordingMetricsJob = viewModelScope.launch {
            audioHandler.captureMetrics.collectLatest { metrics ->
                _uiState.value = StudioUiState.Recording(metrics)
            }
        }
    }

    fun stopStudioRecordingAndTranscribe() {
        recordingMetricsJob?.cancel()
        recordingMetricsJob = null

        activeTranscriptionJob?.cancel()
        activeTranscriptionJob = viewModelScope.launch {
            val prefs = preferencesRepo.currentPreferences()
            _uiState.value = StudioUiState.Processing(
                stageLabel = "Finalizing 16 kHz AAC payload…",
                fileName = "Studio Recording",
                codecLabel = "AAC 16kHz Mono",
                sizeBytes = 0L,
                activeEngineHint = router.resolvePlannedEngineHint(AudioSourceType.STUDIO_RECORD, prefs.routingMode)
            )

            val captured = audioHandler.stopRecording(AudioSourceType.STUDIO_RECORD)
            val audio = captured.getOrElse { err ->
                _uiState.value = StudioUiState.Error(
                    message = err.message ?: "Recording failed."
                )
                return@launch
            }

            transcribePreparedAudio(audio, overrideMode = null)
        }
    }

    fun cancelStudioRecording() {
        recordingMetricsJob?.cancel()
        recordingMetricsJob = null
        audioHandler.cancelRecording()
        _uiState.value = StudioUiState.Idle
    }

    fun retryLastAudio(overrideMode: RoutingMode? = null) {
        val audio = lastPreparedAudio ?: return
        activeTranscriptionJob?.cancel()
        activeTranscriptionJob = viewModelScope.launch {
            if (overrideMode != null) {
                preferencesRepo.setRoutingMode(overrideMode)
            }
            transcribePreparedAudio(audio, overrideMode = overrideMode)
        }
    }

    fun openSessionRecord(record: TranscriptRecord) {
        _uiState.value = StudioUiState.Success(record = record)
    }

    fun removeSessionRecord(recordId: String) {
        sessionStore.removeById(recordId)
        val current = _uiState.value
        if (current is StudioUiState.Success && current.record.id == recordId) {
            _uiState.value = StudioUiState.Idle
        }
    }

    fun clearAllSessionHistory() {
        sessionStore.clearAll()
        lastPreparedAudio = null
        _uiState.value = StudioUiState.Idle
        _snackbarMessage.value = "Session history cleared from memory"
    }

    fun runDeepReviewOnCurrent() {
        val current = _uiState.value as? StudioUiState.Success ?: return
        if (current.isRunningDeepReview) return

        viewModelScope.launch {
            _uiState.value = current.copy(isRunningDeepReview = true, deepReviewError = null)
            val audioContext = lastPreparedAudio?.takeIf { it.sha256 == current.record.audioSha256 }
            val glossary = preferencesRepo.currentPreferences().customGlossary

            val result = router.runDeepReview(
                record = current.record,
                audioContext = audioContext,
                customGlossary = glossary
            )

            result.onSuccess { (updatedRecord, _) ->
                _uiState.value = StudioUiState.Success(
                    record = updatedRecord,
                    isConvertingWritten = false,
                    isRunningDeepReview = false
                )
                _snackbarMessage.value = "Deep Cantonese Review complete (Gemini 4 Carbon)"
            }.onFailure { err ->
                Log.e(TAG, "Deep review failed", err)
                val latest = _uiState.value as? StudioUiState.Success ?: return@onFailure
                _uiState.value = latest.copy(
                    isRunningDeepReview = false,
                    deepReviewError = err.message ?: "Deep Review failed. Please retry."
                )
            }
        }
    }

    private fun convertCurrentRecordToWritten(record: TranscriptRecord) {
        viewModelScope.launch {
            val current = _uiState.value as? StudioUiState.Success ?: return@launch
            _uiState.value = current.copy(isConvertingWritten = true)

            router.ensureWrittenChinese(record).onSuccess { updated ->
                val latest = _uiState.value as? StudioUiState.Success ?: return@onSuccess
                _uiState.value = latest.copy(record = updated, isConvertingWritten = false)
            }.onFailure { err ->
                val latest = _uiState.value as? StudioUiState.Success ?: return@onFailure
                _uiState.value = latest.copy(isConvertingWritten = false)
                _snackbarMessage.value = err.message ?: "Could not convert to Written Chinese"
            }
        }
    }

    private suspend fun transcribePreparedAudio(
        audio: PreparedAudio,
        overrideMode: RoutingMode?
    ) {
        lastPreparedAudio = audio
        val prefs = preferencesRepo.currentPreferences()
        val effectiveMode = overrideMode ?: prefs.routingMode

        _uiState.value = StudioUiState.Processing(
            stageLabel = "Transcribing Cantonese audio…",
            fileName = audio.fileName,
            codecLabel = audio.codecLabel,
            sizeBytes = audio.sizeBytes,
            activeEngineHint = router.resolvePlannedEngineHint(audio.sourceType, effectiveMode)
        )

        try {
            val result = router.transcribe(
                audio = audio,
                preferences = prefs,
                overrideMode = overrideMode,
                saveToSession = true
            )

            result.onSuccess { record ->
                _uiState.value = StudioUiState.Success(record = record)
                if (_displayFormat.value != CantoneseFormat.SPOKEN_CANTONESE &&
                    record.writtenChinese.isNullOrBlank()
                ) {
                    convertCurrentRecordToWritten(record)
                }
            }.onFailure { err ->
                val alternate = when (effectiveMode) {
                    RoutingMode.OPENAI_ONLY -> RoutingMode.GEMINI_ONLY
                    RoutingMode.GEMINI_ONLY -> if (isOpenAiConfigured) RoutingMode.OPENAI_ONLY else null
                    RoutingMode.AUTO -> if (isOpenAiConfigured) RoutingMode.GEMINI_ONLY else null
                }
                _uiState.value = StudioUiState.Error(
                    message = err.message ?: "Transcription failed.",
                    canRetryWithAlternateEngine = alternate != null,
                    suggestedEngine = alternate
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during transcription", e)
            _uiState.value = StudioUiState.Error(
                message = e.localizedMessage ?: "Unexpected transcription error."
            )
        }
    }

    // --- Live Keyboard In-App Sandbox Controls ---

    fun updateSandboxText(newText: String) {
        _sandboxState.value = _sandboxState.value.copy(text = newText)
    }

    fun appendSandboxSymbol(symbol: String) {
        val current = _sandboxState.value
        _sandboxState.value = current.copy(text = current.text + symbol)
    }

    fun undoSandboxLastDictation() {
        val current = _sandboxState.value
        if (current.lastInsertedLength <= 0 || current.text.length < current.lastInsertedLength) return
        _sandboxState.value = current.copy(
            text = current.text.dropLast(current.lastInsertedLength),
            lastInsertedLength = 0,
            statusNote = "Undid last dictation"
        )
    }

    fun clearSandbox() {
        _sandboxState.value = _sandboxState.value.copy(
            text = "",
            lastInsertedLength = 0,
            statusNote = "Sandbox cleared"
        )
    }

    fun toggleSandboxRecording() {
        val current = _sandboxState.value
        if (current.isTranscribing) return
        if (current.isRecording) {
            stopSandboxRecordingAndInsert()
        } else {
            startSandboxRecording()
        }
    }

    private fun startSandboxRecording() {
        if (_uiState.value is StudioUiState.Recording) {
            cancelStudioRecording()
        }
        val started = audioHandler.startRecording(viewModelScope)
        if (started.isFailure) {
            _sandboxState.value = _sandboxState.value.copy(
                statusNote = started.exceptionOrNull()?.localizedMessage ?: "Could not access microphone"
            )
            return
        }

        _sandboxState.value = _sandboxState.value.copy(
            isRecording = true,
            statusNote = "Listening (16 kHz mono AAC)…"
        )

        recordingMetricsJob?.cancel()
        recordingMetricsJob = viewModelScope.launch {
            audioHandler.captureMetrics.collectLatest { metrics ->
                _sandboxState.value = _sandboxState.value.copy(
                    liveLevel = metrics.normalizedLevel,
                    elapsedMillis = metrics.elapsedMillis
                )
            }
        }
    }

    private fun stopSandboxRecordingAndInsert() {
        recordingMetricsJob?.cancel()
        recordingMetricsJob = null

        _sandboxState.value = _sandboxState.value.copy(
            isRecording = false,
            isTranscribing = true,
            statusNote = "Transcribing live Cantonese…"
        )

        viewModelScope.launch {
            val captured = audioHandler.stopRecording(AudioSourceType.KEYBOARD_LIVE)
            val audio = captured.getOrElse { err ->
                _sandboxState.value = _sandboxState.value.copy(
                    isTranscribing = false,
                    liveLevel = 0.08f,
                    statusNote = err.message ?: "Recording cancelled"
                )
                return@launch
            }

            val prefs = preferencesRepo.currentPreferences()
            val result = router.transcribe(
                audio = audio,
                preferences = prefs,
                saveToSession = true
            )

            result.onSuccess { record ->
                val snippet = if (prefs.defaultFormat == CantoneseFormat.WRITTEN_CHINESE &&
                    !record.writtenChinese.isNullOrBlank()
                ) {
                    record.writtenChinese
                } else {
                    record.spokenCantonese
                }
                val existing = _sandboxState.value.text
                val separator = if (existing.isNotEmpty() && !existing.endsWith(" ") && !existing.endsWith("\n")) " " else ""
                val inserted = separator + snippet
                _sandboxState.value = _sandboxState.value.copy(
                    text = existing + inserted,
                    isTranscribing = false,
                    liveLevel = 0.08f,
                    lastInsertedLength = inserted.length,
                    statusNote = "${record.engineUsed.displayName} · ${record.latencyMillis}ms"
                )
            }.onFailure { err ->
                _sandboxState.value = _sandboxState.value.copy(
                    isTranscribing = false,
                    liveLevel = 0.08f,
                    statusNote = err.message ?: "Transcription failed"
                )
            }
        }
    }

    private fun cancelSandboxRecording() {
        recordingMetricsJob?.cancel()
        recordingMetricsJob = null
        audioHandler.cancelRecording()
        _sandboxState.value = _sandboxState.value.copy(
            isRecording = false,
            liveLevel = 0.08f,
            statusNote = "Cancelled"
        )
    }

    override fun onCleared() {
        audioHandler.cancelRecording()
        super.onCleared()
    }
}
