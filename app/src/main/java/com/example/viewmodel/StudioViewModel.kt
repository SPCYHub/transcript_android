package com.example.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.LiveTranscriptionController
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
    val livePartial: String = "",
    val isStreaming: Boolean = false,
    val isHoldToTalk: Boolean = false,
    val liveLevel: Float = 0.08f,
    val elapsedMillis: Long = 0L,
    val statusNote: String = "Hold to speak or tap for hands-free live Cantonese",
    val lastInsertedLength: Int = 0
)

class StudioViewModel(
    private val audioHandler: AudioInputHandler = AppContainer.audioInputHandler,
    private val router: TranscriptionRouter = AppContainer.transcriptionRouter,
    private val liveController: LiveTranscriptionController = AppContainer.liveTranscriptionController,
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

    private val _autoCopyTextEvent = MutableStateFlow<String?>(null)
    val autoCopyTextEvent: StateFlow<String?> = _autoCopyTextEvent.asStateFlow()

    private var lastPreparedAudio: PreparedAudio? = null
    private var liveCollectorJob: Job? = null
    private var activeFileTranscriptionJob: Job? = null
    private var studioHoldMode = false

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

    fun consumeAutoCopyEvent() {
        _autoCopyTextEvent.value = null
    }

    fun postToast(message: String) {
        _snackbarMessage.value = message
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            preferencesRepo.setRoutingMode(mode)
        }
    }

    fun setAutoCopyOnFinish(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepo.setAutoCopyOnFinish(enabled)
            _snackbarMessage.value = if (enabled) {
                "Auto-copy enabled: finished transcripts copy automatically"
            } else {
                "Auto-copy disabled"
            }
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

        val current = _uiState.value
        if (current is StudioUiState.Success &&
            format != CantoneseFormat.SPOKEN_CANTONESE &&
            current.record.writtenChinese.isNullOrBlank() &&
            !current.isConvertingWritten
        ) {
            convertCurrentRecordToWritten(current.record)
        }
    }

    // --- Real-Time Live Studio Streaming (No Post-Recording Wait) ---

    fun startStudioLive(isHoldToTalk: Boolean) {
        if (_sandboxState.value.isStreaming) {
            cancelSandboxLive()
        }
        if (_uiState.value is StudioUiState.LiveStreaming) return

        studioHoldMode = isHoldToTalk
        viewModelScope.launch {
            val prefs = preferencesRepo.currentPreferences()
            val started = liveController.startLiveStream(
                scope = viewModelScope,
                preferences = prefs,
                sourceType = AudioSourceType.STUDIO_RECORD
            )
            if (started.isFailure) {
                _uiState.value = StudioUiState.Error(
                    message = started.exceptionOrNull()?.message ?: "Unable to start live stream.",
                    canRetryWithAlternateEngine = prefs.routingMode == RoutingMode.OPENAI_ONLY,
                    suggestedEngine = if (prefs.routingMode == RoutingMode.OPENAI_ONLY) RoutingMode.GEMINI_ONLY else null
                )
                return@launch
            }

            liveCollectorJob?.cancel()
            liveCollectorJob = viewModelScope.launch {
                liveController.state.collectLatest { live ->
                    if (live.isActive) {
                        _uiState.value = StudioUiState.LiveStreaming(
                            confirmedText = live.confirmedText,
                            partialText = live.partialText,
                            engineLabel = live.engineLabel,
                            isConnecting = live.isConnecting,
                            isHoldToTalk = studioHoldMode,
                            metrics = live.metrics,
                            warningMessage = live.errorMessage
                        )
                    }
                }
            }
        }
    }

    fun lockStudioHandsFree() {
        studioHoldMode = false
        val current = _uiState.value
        if (current is StudioUiState.LiveStreaming) {
            _uiState.value = current.copy(isHoldToTalk = false)
        }
    }

    fun stopStudioLive() {
        if (_uiState.value !is StudioUiState.LiveStreaming) return
        viewModelScope.launch {
            val prefs = preferencesRepo.currentPreferences()
            val record = liveController.stopAndFinalize(saveToSession = true)
            liveCollectorJob?.cancel()
            liveCollectorJob = null

            if (record != null) {
                _uiState.value = StudioUiState.Success(record = record)

                if (prefs.autoCopyOnFinish) {
                    val textToCopy = if (_displayFormat.value == CantoneseFormat.WRITTEN_CHINESE &&
                        !record.writtenChinese.isNullOrBlank()
                    ) {
                        record.writtenChinese
                    } else {
                        record.spokenCantonese
                    }
                    _autoCopyTextEvent.value = textToCopy
                }

                if (_displayFormat.value != CantoneseFormat.SPOKEN_CANTONESE &&
                    record.writtenChinese.isNullOrBlank()
                ) {
                    convertCurrentRecordToWritten(record)
                }
            } else {
                _uiState.value = StudioUiState.Idle
                _snackbarMessage.value = "No speech captured — speak while holding or in hands-free mode"
            }
        }
    }

    fun cancelStudioLive() {
        liveCollectorJob?.cancel()
        liveCollectorJob = null
        liveController.stopAndDiscard()
        _uiState.value = StudioUiState.Idle
    }

    // --- WhatsApp Share & External Audio Intake ---

    fun handleSharedAudioUri(uri: Uri, sourceType: AudioSourceType = AudioSourceType.WHATSAPP_SHARE) {
        if (_uiState.value is StudioUiState.LiveStreaming) {
            cancelStudioLive()
        }
        activeFileTranscriptionJob?.cancel()
        activeFileTranscriptionJob = viewModelScope.launch {
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

    fun retryLastAudio(overrideMode: RoutingMode? = null) {
        val audio = lastPreparedAudio
        if (audio == null) {
            if (overrideMode != null) {
                viewModelScope.launch {
                    preferencesRepo.setRoutingMode(overrideMode)
                    _uiState.value = StudioUiState.Idle
                }
            } else {
                _uiState.value = StudioUiState.Idle
            }
            return
        }
        activeFileTranscriptionJob?.cancel()
        activeFileTranscriptionJob = viewModelScope.launch {
            if (overrideMode != null) {
                preferencesRepo.setRoutingMode(overrideMode)
            }
            transcribePreparedAudio(audio, overrideMode = overrideMode)
        }
    }

    fun openSessionRecord(record: TranscriptRecord) {
        if (_uiState.value is StudioUiState.LiveStreaming) {
            cancelStudioLive()
        }
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
                if (prefs.autoCopyOnFinish) {
                    _autoCopyTextEvent.value = record.spokenCantonese
                }
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

    // --- Real-Time Live Keyboard In-App Sandbox Controls ---

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
            statusNote = "Undid last live phrase"
        )
    }

    fun clearSandbox() {
        _sandboxState.value = _sandboxState.value.copy(
            text = "",
            livePartial = "",
            lastInsertedLength = 0,
            statusNote = "Sandbox cleared"
        )
    }

    fun startSandboxLive(isHoldToTalk: Boolean) {
        if (_uiState.value is StudioUiState.LiveStreaming) {
            cancelStudioLive()
        }
        if (_sandboxState.value.isStreaming) return

        viewModelScope.launch {
            val prefs = preferencesRepo.currentPreferences()
            val started = liveController.startLiveStream(
                scope = viewModelScope,
                preferences = prefs,
                sourceType = AudioSourceType.KEYBOARD_LIVE,
                onPartial = { partial ->
                    _sandboxState.value = _sandboxState.value.copy(livePartial = partial)
                },
                onCommitSegment = { segment ->
                    val current = _sandboxState.value
                    val sep = if (current.text.isNotEmpty() &&
                        current.text.last().code < 128 &&
                        segment.first().code < 128 &&
                        !current.text.endsWith(" ")
                    ) " " else ""
                    val inserted = sep + segment
                    _sandboxState.value = current.copy(
                        text = current.text + inserted,
                        livePartial = "",
                        lastInsertedLength = inserted.length
                    )
                }
            )

            if (started.isFailure) {
                _sandboxState.value = _sandboxState.value.copy(
                    statusNote = started.exceptionOrNull()?.message ?: "Unable to start live keyboard mic"
                )
                return@launch
            }

            _sandboxState.value = _sandboxState.value.copy(
                isStreaming = true,
                isHoldToTalk = isHoldToTalk,
                livePartial = "",
                statusNote = "Streaming live Cantonese…"
            )

            liveCollectorJob?.cancel()
            liveCollectorJob = viewModelScope.launch {
                liveController.state.collectLatest { live ->
                    if (live.isActive) {
                        _sandboxState.value = _sandboxState.value.copy(
                            liveLevel = live.metrics.normalizedLevel,
                            elapsedMillis = live.metrics.elapsedMillis,
                            statusNote = live.errorMessage
                                ?: if (live.isConnecting) "Connecting live stream…" else "LIVE · ${live.engineLabel}"
                        )
                    }
                }
            }
        }
    }

    fun lockSandboxHandsFree() {
        _sandboxState.value = _sandboxState.value.copy(isHoldToTalk = false)
    }

    fun stopSandboxLive() {
        if (!_sandboxState.value.isStreaming) return
        viewModelScope.launch {
            val record = liveController.stopAndFinalize(saveToSession = true)
            liveCollectorJob?.cancel()
            liveCollectorJob = null
            _sandboxState.value = _sandboxState.value.copy(
                isStreaming = false,
                isHoldToTalk = false,
                livePartial = "",
                liveLevel = 0.08f,
                statusNote = if (record != null) {
                    "Live stream complete · ${record.engineUsed.displayName}"
                } else {
                    "Hold to speak or tap for hands-free live Cantonese"
                }
            )
        }
    }

    private fun cancelSandboxLive() {
        liveCollectorJob?.cancel()
        liveCollectorJob = null
        liveController.stopAndDiscard()
        _sandboxState.value = _sandboxState.value.copy(
            isStreaming = false,
            isHoldToTalk = false,
            livePartial = "",
            liveLevel = 0.08f,
            statusNote = "Cancelled"
        )
    }

    override fun onCleared() {
        liveController.stopAndDiscard()
        audioHandler.cancelRecording()
        super.onCleared()
    }
}
