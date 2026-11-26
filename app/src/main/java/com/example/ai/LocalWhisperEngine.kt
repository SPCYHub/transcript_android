package com.example.ai

import android.util.Log
import com.example.audio.AudioPcmDecoder
import com.example.data.WhisperModelStore
import com.example.model.ActiveWhisperModel
import com.example.model.PreparedAudio
import com.example.model.TranscriptionEngineId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LocalWhisperEngine"

/**
 * On-device Cantonese speech-to-text engine powered by whisper.cpp GGML models.
 *
 * - Thread-safe native context caching protected by a coroutine [Mutex].
 * - Automatically unloads and switches contexts when the user changes active model or deletes it.
 * - Uses Traditional Hong Kong Cantonese `initial_prompt` + `"yue"` (`"zh"` fallback in JNI) and
 *   passes results through [TranscriptSanitizer] to suppress silence hallucinations & prompt echo.
 */
class LocalWhisperEngine(
    private val modelStore: WhisperModelStore,
    private val pcmDecoder: AudioPcmDecoder
) {

    private val engineMutex = Mutex()
    private var loadedModelId: String? = null
    private var loadedFilePath: String? = null
    private var nativeContextPtr: Long = 0L

    init {
        modelStore.setBeforeModelDeletedHook { modelId ->
            unloadModel(modelId)
        }
    }

    val isConfigured: Boolean
        get() = modelStore.hasInstalledModel

    val activeModelShortName: String?
        get() = modelStore.resolveActiveModel()?.shortName

    val activeEngineId: TranscriptionEngineId
        get() = modelStore.resolveActiveModel()?.engineId
            ?: TranscriptionEngineId.WHISPER_LOCAL_BASE

    suspend fun unloadModel(modelId: String? = null) = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            if (modelId == null || modelId == loadedModelId) {
                releaseLoadedContextLocked()
            }
        }
    }

    suspend fun transcribePreparedAudio(
        audio: PreparedAudio,
        customGlossary: String
    ): Result<GeminiAudioOutput> {
        val activeModel = modelStore.resolveActiveModel()
            ?: return Result.failure(
                IllegalStateException(
                    "No offline Whisper model installed. Download or import one in Keys -> Local Whisper."
                )
            )
        if (!WhisperNativeBridge.isNativeAvailable()) {
            val detail = WhisperNativeBridge.nativeLoadError()?.message ?: "native library missing"
            return Result.failure(
                IllegalStateException("Local Whisper native engine unavailable ($detail).")
            )
        }

        val startedMs = System.currentTimeMillis()
        val samples = pcmDecoder.decodeTo16kMonoFloat(audio).getOrElse { err ->
            return Result.failure(err)
        }
        val prompt = CantonesePrompts.buildLocalWhisperPrompt(customGlossary = customGlossary)

        return runInference(
            activeModel = activeModel,
            samples = samples,
            prompt = prompt,
            customGlossary = customGlossary,
            singleSegment = false,
            noContext = true
        ).map { cleanText ->
            GeminiAudioOutput(
                spokenCantonese = cleanText,
                writtenChinese = null,
                detectedLanguageMix = "Cantonese (Local · ${activeModel.shortName})",
                engineId = activeModel.engineId,
                latencyMillis = (System.currentTimeMillis() - startedMs).coerceAtLeast(1L)
            )
        }
    }

    suspend fun transcribePcm16(
        pcmBytes: ByteArray,
        sampleRateHz: Int,
        customGlossary: String,
        contextTail: String,
        singleSegment: Boolean
    ): Result<String> {
        val activeModel = modelStore.resolveActiveModel()
            ?: return Result.failure(
                IllegalStateException(
                    "No offline Whisper model installed. Download or import one in Keys -> Local Whisper."
                )
            )
        if (!WhisperNativeBridge.isNativeAvailable()) {
            val detail = WhisperNativeBridge.nativeLoadError()?.message ?: "native library missing"
            return Result.failure(
                IllegalStateException("Local Whisper native engine unavailable ($detail).")
            )
        }

        val samples = AudioPcmDecoder.pcm16BytesTo16kMonoFloat(
            pcmBytes = pcmBytes,
            sampleRateHz = sampleRateHz,
            channels = 1
        )
        if (samples.isEmpty()) return Result.success("")

        val prompt = CantonesePrompts.buildLocalWhisperPrompt(
            customGlossary = customGlossary,
            contextTail = contextTail
        )

        return runInference(
            activeModel = activeModel,
            samples = samples,
            prompt = prompt,
            customGlossary = customGlossary,
            singleSegment = singleSegment,
            noContext = true
        )
    }

    private suspend fun runInference(
        activeModel: ActiveWhisperModel,
        samples: FloatArray,
        prompt: String,
        customGlossary: String,
        singleSegment: Boolean,
        noContext: Boolean
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val rawText = engineMutex.withLock {
                val ptr = ensureContextLocked(activeModel)
                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
                WhisperNativeBridge.nativeTranscribe(
                    contextPtr = ptr,
                    audioSamples = samples,
                    numThreads = threads,
                    language = "yue",
                    initialPrompt = prompt,
                    singleSegment = singleSegment,
                    noContext = noContext
                )
            }
            val cleaned = TranscriptSanitizer.sanitizeSegment(
                rawText = rawText,
                activePrompt = prompt,
                customGlossary = customGlossary
            )
            Result.success(cleaned)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Local Whisper inference failed", e)
            Result.failure(e)
        }
    }

    private fun ensureContextLocked(model: ActiveWhisperModel): Long {
        if (nativeContextPtr != 0L &&
            loadedModelId == model.id &&
            loadedFilePath == model.filePath
        ) {
            return nativeContextPtr
        }
        releaseLoadedContextLocked()
        val ptr = WhisperNativeBridge.nativeInitFromFile(model.filePath)
        if (ptr == 0L) {
            throw IllegalStateException(
                "Failed to load ${model.displayName}. The GGML file may be corrupt or unsupported."
            )
        }
        nativeContextPtr = ptr
        loadedModelId = model.id
        loadedFilePath = model.filePath
        Log.i(TAG, "Loaded Whisper GGML context for ${model.displayName}")
        return ptr
    }

    private fun releaseLoadedContextLocked() {
        val ptr = nativeContextPtr
        if (ptr != 0L) {
            try {
                WhisperNativeBridge.nativeFreeContext(ptr)
            } catch (e: Exception) {
                Log.w(TAG, "Error freeing Whisper context", e)
            }
        }
        nativeContextPtr = 0L
        loadedModelId = null
        loadedFilePath = null
    }
}
