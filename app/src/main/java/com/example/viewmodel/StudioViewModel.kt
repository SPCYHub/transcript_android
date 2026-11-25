package com.example.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.LiveFinalizeResult
import com.example.ai.LiveTranscriptionController
import com.example.ai.ProviderRequestAuth
import com.example.ai.TranscriptionRouter
import com.example.audio.AudioInputHandler
import com.example.core.AppContainer
import com.example.core.StudioHaptics
import com.example.data.PreferencesRepository
import com.example.data.SecureKeyStore
import com.example.data.SessionTranscriptStore
import com.example.model.ApiKeyStatus
import com.example.model.AudioSourceType
import com.example.model.CantoneseFormat
import com.example.model.KeyTestResult
import com.example.model.PreparedAudio
import com.example.model.RoutingMode
import com.example.model.StudioUiState
import com.example.model.ThemePreference
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
    val waveformHistory: List<Float> = emptyList(),
    val elapsedMillis: Long = 0L,
    val statusNote: String = "Hold to speak or quick tap for hands-free live Cantonese",
    val lastInsertedLength: Int = 0
)

class StudioViewModel(
    private val audioHandler: AudioInputHandler = AppContainer.audioInputHandler,
    private val router: TranscriptionRouter = AppContainer.transcriptionRouter,
    private val liveController: LiveTranscriptionController = AppContainer.liveTranscriptionController,
    private val preferencesRepo: PreferencesRepository = AppContainer.preferencesRepository,
    private val keyStore: SecureKeyStore = AppContainer.secureKeyStore,
    private val sessionStore: SessionTranscriptStore = AppContainer.sessionStore,
    private val haptics: StudioHaptics = AppContainer.studioHaptics
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepo.preferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences()
    )

    val apiKeyStatus: StateFlow<ApiKeyStatus> = keyStore.status

    val sessionRecords: StateFlow<List<TranscriptRecord>> = sessionStore.records

    private val _geminiKeyTest = MutableStateFlow<KeyTestResult>(KeyTestResult.Idle)
    val geminiKeyTest: StateFlow<KeyTestResult> = _geminiKeyTest.asStateFlow()

    private val _openAiKeyTest = MutableStateFlow<KeyTestResult>(KeyTestResult.Idle)
    val openAiKeyTest: StateFlow<KeyTestResult> = _openAiKeyTest.asStateFlow()

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
    private var isFinalizingStudioLive = false
    private var isFinalizingSandboxLive = false

    init {
        viewModelScope.launch {
            preferencesRepo.preferencesFlow.collectLatest { prefs ->
                _displayFormat.value = prefs.defaultFormat
                haptics.enabled = prefs.hapticsEnabled
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
                "Auto-copy enabled"
            } else {
                "Auto-copy paused"
            }
        }
    }

    fun setIncludeWrittenInAuto(include: Boolean) {
        viewModelScope.launch {
            preferencesRepo.setIncludeWrittenInAuto(include)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        haptics.enabled = enabled
        viewModelScope.launch {
            preferencesRepo.setHapticsEnabled(enabled)
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            preferencesRepo.setThemePreference(theme)
        }
    }

    fun setCustomGlossary(glossary: String) {
        viewModelScope.launch {
            preferencesRepo.setCustomGlossary(glossary)
            _snackbarMessage.value = "Cantonese vocabulary hints saved"
        }
    }

    // --- End-User API Key Management (Encrypted On-Device Vault) ---

    fun saveGeminiApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        keyStore.saveGeminiApiKey(cleaned)
        _geminiKeyTest.value = KeyTestResult.Idle
        _snackbarMessage.value = if (cleaned.isNotEmpty()) {
            "Gemini API key saved (encrypted on device)"
        } else {
            "Gemini API key cleared"
        }
    }

    fun clearGeminiApiKey() {
        keyStore.clearGeminiApiKey()
        _geminiKeyTest.value = KeyTestResult.Idle
        _snackbarMessage.value = "Personal Gemini key removed"
    }

    fun setUsePersonalGeminiKey(enabled: Boolean) {
        keyStore.setUsePersonalGeminiKey(true)
    }

    fun saveCustomGeminiModel(modelName: String) {
        val clean = modelName.trim()
        if (clean.isNotEmpty() && !ProviderRequestAuth.isValidGeminiModelName(clean)) {
            _snackbarMessage.value = "Invalid model ID. Use letters, numbers, dots, hyphens, or underscores."
            return
        }
        val normalized = if (clean.isEmpty()) "" else ProviderRequestAuth.sanitizeGeminiModelName(clean)
        keyStore.saveCustomGeminiModel(normalized)
        _snackbarMessage.value = if (normalized.isEmpty()) {
            "Gemini model reset to defaults"
        } else {
            "Custom Gemini model saved: $normalized"
        }
    }

    fun saveOpenAiApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        keyStore.saveOpenAiApiKey(cleaned)
        _openAiKeyTest.value = KeyTestResult.Idle
        _snackbarMessage.value = if (cleaned.isNotEmpty()) {
            "OpenAI API key saved (encrypted on device)"
        } else {
            "Personal OpenAI key cleared"
        }
    }

    fun clearOpenAiApiKey() {
        keyStore.clearOpenAiApiKey()
        _openAiKeyTest.value = KeyTestResult.Idle
        _snackbarMessage.value = "Personal OpenAI key removed"
    }

    fun testGeminiKey(candidateKey: String? = null) {
        viewModelScope.launch {
            _geminiKeyTest.value = KeyTestResult.Testing
            val result = keyStore.verifyGeminiKey(candidateKey)
            _geminiKeyTest.value = result
            if (result is KeyTestResult.Valid) haptics.confirm() else if (result is KeyTestResult.Invalid) haptics.error()
        }
    }

    fun testOpenAiKey(candidateKey: String? = null) {
        viewModelScope.launch {
            _openAiKeyTest.value = KeyTestResult.Testing
            val result = keyStore.verifyOpenAiKey(candidateKey)
            _openAiKeyTest.value = result
            if (result is KeyTestResult.Valid) haptics.confirm() else if (result is KeyTestResult.Invalid) haptics.error()
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

    // --- Real-Time Live Studio Streaming ---

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
                val msg = ProviderRequestAuth.redactSecrets(started.exceptionOrNull()?.message)
                    .ifBlank { "Unable to start live stream." }
                _uiState.value = StudioUiState.Error(
                    message = msg,
                    canRetryWithAlternateEngine = prefs.routingMode == RoutingMode.OPENAI_ONLY,
                    suggestedEngine = if (prefs.routingMode == RoutingMode.OPENAI_ONLY) RoutingMode.GEMINI_ONLY else null,
                    isAppCheckOrKeyError = isKeyOrAppCheckError(msg)
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
                            isTranscribingChunk = live.isTranscribingChunk,
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
        val currentLive = _uiState.value as? StudioUiState.LiveStreaming ?: return
        if (isFinalizingStudioLive) return
        isFinalizingStudioLive = true
        viewModelScope.launch {
            try {
                val prefs = preferencesRepo.currentPreferences()
                _uiState.value = currentLive.copy(
                    isHoldToTalk = false,
                    isTranscribingChunk = true,
                    engineLabel = "Finalizing live transcript…"
                )
                val result = liveController.stopAndFinalize(saveToSession = true)
                liveCollectorJob?.cancel()
                liveCollectorJob = null

                when (result) {
                    is LiveFinalizeResult.Success -> {
                        val record = result.record
                        _uiState.value = StudioUiState.Success(record = record)
                        haptics.confirm()

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
                    }

                    is LiveFinalizeResult.Error -> {
                        haptics.error()
                        val alternate = when (prefs.routingMode) {
                            RoutingMode.OPENAI_ONLY -> RoutingMode.GEMINI_ONLY
                            RoutingMode.GEMINI_ONLY -> if (router.isOpenAiReady) RoutingMode.OPENAI_ONLY else null
                            RoutingMode.AUTO -> RoutingMode.GEMINI_ONLY
                        }
                        _uiState.value = StudioUiState.Error(
                            message = result.message,
                            canRetryWithAlternateEngine = alternate != null,
                            suggestedEngine = alternate,
                            isAppCheckOrKeyError = isKeyOrAppCheckError(result.message)
                        )
                    }

                    LiveFinalizeResult.Empty -> {
                        _uiState.value = StudioUiState.Idle
                        _snackbarMessage.value = "No speech captured — speak closer to the mic and try again"
                    }
                }
            } finally {
                isFinalizingStudioLive = false
            }
        }
    }

    fun cancelStudioLive() {
        isFinalizingStudioLive = false
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
            val prefs = preferencesRepo.currentPreferences()

            val result = router.runDeepReview(
                record = current.record,
                audioContext = audioContext,
                customGlossary = prefs.customGlossary,
                routingMode = prefs.routingMode
            )

            result.onSuccess { (updatedRecord, _) ->
                _uiState.value = StudioUiState.Success(
                    record = updatedRecord,
                    isConvertingWritten = false,
                    isRunningDeepReview = false
                )
                haptics.confirm()
                _snackbarMessage.value = "Deep Cantonese Review complete"
            }.onFailure { err ->
                Log.e(TAG, "Deep review failed", err)
                haptics.error()
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
            val routingMode = preferencesRepo.currentPreferences().routingMode

            router.ensureWrittenChinese(record, routingMode = routingMode).onSuccess { updated ->
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
                haptics.confirm()
                if (prefs.autoCopyOnFinish) {
                    _autoCopyTextEvent.value = record.spokenCantonese
                }
                if (_displayFormat.value != CantoneseFormat.SPOKEN_CANTONESE &&
                    record.writtenChinese.isNullOrBlank()
                ) {
                    convertCurrentRecordToWritten(record)
                }
            }.onFailure { err ->
                haptics.error()
                val errMsg = ProviderRequestAuth.redactSecrets(err.message)
                    .ifBlank { "Transcription failed." }
                val openAiReady = router.isOpenAiReady
                val alternate = when (effectiveMode) {
                    RoutingMode.OPENAI_ONLY -> RoutingMode.GEMINI_ONLY
                    RoutingMode.GEMINI_ONLY -> if (openAiReady) RoutingMode.OPENAI_ONLY else null
                    RoutingMode.AUTO -> if (openAiReady) RoutingMode.GEMINI_ONLY else null
                }
                _uiState.value = StudioUiState.Error(
                    message = errMsg,
                    canRetryWithAlternateEngine = alternate != null,
                    suggestedEngine = alternate,
                    isAppCheckOrKeyError = isKeyOrAppCheckError(errMsg)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errMsg = ProviderRequestAuth.redactSecrets(e.localizedMessage)
                .ifBlank { "Unexpected transcription error." }
            Log.e(TAG, "Unexpected error during transcription: $errMsg")
            _uiState.value = StudioUiState.Error(
                message = errMsg,
                isAppCheckOrKeyError = isKeyOrAppCheckError(errMsg)
            )
        }
    }

    private fun isKeyOrAppCheckError(message: String): Boolean {
        return message.contains("App Check", ignoreCase = true) ||
            message.contains("token is invalid", ignoreCase = true) ||
            message.contains("API key", ignoreCase = true) ||
            message.contains("401") ||
            message.contains("403")
    }

    // --- Live Keyboard In-App Sandbox Controls ---

    fun updateSandboxText(newText: String) {
        _sandboxState.value = _sandboxState.value.copy(text = newText)
    }

    fun appendSandboxSymbol(symbol: String) {
        val current = _sandboxState.value
        _sandboxState.value = current.copy(text = current.text + symbol)
    }

    fun backspaceSandbox() {
        val current = _sandboxState.value
        if (current.text.isEmpty()) return
        val nextText = current.text.dropLast(1)
        val nextLastLen = (current.lastInsertedLength - 1).coerceAtLeast(0)
        _sandboxState.value = current.copy(
            text = nextText,
            lastInsertedLength = nextLastLen
        )
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
                    haptics.phraseCommit()
                }
            )

            if (started.isFailure) {
                haptics.error()
                _sandboxState.value = _sandboxState.value.copy(
                    statusNote = started.exceptionOrNull()?.message ?: "Unable to start live keyboard mic"
                )
                return@launch
            }

            _sandboxState.value = _sandboxState.value.copy(
                isStreaming = true,
                isHoldToTalk = isHoldToTalk,
                livePartial = "",
                waveformHistory = emptyList(),
                statusNote = "Streaming live Cantonese…"
            )

            liveCollectorJob?.cancel()
            liveCollectorJob = viewModelScope.launch {
                liveController.state.collectLatest { live ->
                    if (live.isActive) {
                        _sandboxState.value = _sandboxState.value.copy(
                            liveLevel = live.metrics.normalizedLevel,
                            waveformHistory = live.metrics.waveformHistory,
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
        if (!_sandboxState.value.isStreaming || isFinalizingSandboxLive) return
        isFinalizingSandboxLive = true
        viewModelScope.launch {
            try {
                _sandboxState.value = _sandboxState.value.copy(
                    statusNote = "Finalizing live stream…"
                )
                val result = liveController.stopAndFinalize(saveToSession = true)
                liveCollectorJob?.cancel()
                liveCollectorJob = null
                val note = when (result) {
                    is LiveFinalizeResult.Success -> {
                        haptics.confirm()
                        "Live stream complete · ${result.record.engineUsed.displayName}"
                    }
                    is LiveFinalizeResult.Error -> {
                        haptics.error()
                        "Error: ${result.message}"
                    }
                    LiveFinalizeResult.Empty -> "No speech detected — hold to speak or tap for hands-free"
                }
                _sandboxState.value = _sandboxState.value.copy(
                    isStreaming = false,
                    isHoldToTalk = false,
                    livePartial = "",
                    liveLevel = 0.08f,
                    waveformHistory = emptyList(),
                    statusNote = note
                )
            } finally {
                isFinalizingSandboxLive = false
            }
        }
    }

    fun cancelSandboxLive() {
        isFinalizingSandboxLive = false
        liveCollectorJob?.cancel()
        liveCollectorJob = null
        liveController.stopAndDiscard()
        _sandboxState.value = _sandboxState.value.copy(
            isStreaming = false,
            isHoldToTalk = false,
            livePartial = "",
            liveLevel = 0.08f,
            waveformHistory = emptyList(),
            statusNote = "Cancelled"
        )
    }

    override fun onCleared() {
        liveController.stopAndDiscard()
        audioHandler.cancelRecording()
        super.onCleared()
    }
}
