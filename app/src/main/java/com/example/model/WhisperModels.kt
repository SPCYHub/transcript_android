package com.example.model

enum class WhisperModelTier(val label: String) {
    TINY("Tiny"),
    BASE("Base"),
    SMALL("Small"),
    TURBO("Turbo"),
    CUSTOM("Custom")
}

data class WhisperModelSpec(
    val id: String,
    val tier: WhisperModelTier,
    val displayName: String,
    val shortName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val approxRamMb: Int,
    val description: String,
    val isRecommended: Boolean,
    val engineId: TranscriptionEngineId
)

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : ModelInstallState {
        val progress: Float
            get() = if (totalBytes > 0L) {
                (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }
    data object Validating : ModelInstallState
    data class Installed(
        val sizeBytes: Long,
        val filePath: String
    ) : ModelInstallState
    data class Error(val message: String) : ModelInstallState
}

data class CustomWhisperModelMeta(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val addedAtMillis: Long
)

data class WhisperModelUiItem(
    val id: String,
    val tier: WhisperModelTier,
    val displayName: String,
    val shortName: String,
    val sizeLabel: String,
    val ramLabel: String,
    val description: String,
    val isRecommended: Boolean,
    val isCustom: Boolean,
    val installState: ModelInstallState,
    val isActive: Boolean
) {
    val isInstalled: Boolean
        get() = installState is ModelInstallState.Installed
}

data class ActiveWhisperModel(
    val id: String,
    val displayName: String,
    val shortName: String,
    val filePath: String,
    val engineId: TranscriptionEngineId
)

data class WhisperManagerState(
    val models: List<WhisperModelUiItem> = emptyList(),
    val activeModelId: String? = null,
    val activeModelName: String? = null,
    val hasInstalledModel: Boolean = false,
    val isImportingCustom: Boolean = false,
    val deviceTotalRamMb: Long = 0L,
    val statusBanner: String? = null
)

object WhisperModelCatalog {
    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val builtInSpecs: List<WhisperModelSpec> = listOf(
        WhisperModelSpec(
            id = "whisper_tiny_q5_1",
            tier = WhisperModelTier.TINY,
            displayName = "Whisper Tiny (Q5_1)",
            shortName = "Tiny",
            fileName = "ggml-tiny-q5_1.bin",
            downloadUrl = "$HF_BASE/ggml-tiny-q5_1.bin",
            expectedBytes = 32_200_000L,
            approxRamMb = 275,
            description = "Fastest offline speed & smallest footprint for quick keyboard bursts.",
            isRecommended = false,
            engineId = TranscriptionEngineId.WHISPER_LOCAL_TINY
        ),
        WhisperModelSpec(
            id = "whisper_base_q5_1",
            tier = WhisperModelTier.BASE,
            displayName = "Whisper Base (Q5_1)",
            shortName = "Base",
            fileName = "ggml-base-q5_1.bin",
            downloadUrl = "$HF_BASE/ggml-base-q5_1.bin",
            expectedBytes = 59_700_000L,
            approxRamMb = 390,
            description = "Balanced latency and Cantonese accuracy for everyday offline use.",
            isRecommended = true,
            engineId = TranscriptionEngineId.WHISPER_LOCAL_BASE
        ),
        WhisperModelSpec(
            id = "whisper_small_q5_1",
            tier = WhisperModelTier.SMALL,
            displayName = "Whisper Small (Q5_1)",
            shortName = "Small",
            fileName = "ggml-small-q5_1.bin",
            downloadUrl = "$HF_BASE/ggml-small-q5_1.bin",
            expectedBytes = 190_000_000L,
            approxRamMb = 850,
            description = "Stronger Cantonese vocabulary & HK English code-switching accuracy.",
            isRecommended = false,
            engineId = TranscriptionEngineId.WHISPER_LOCAL_SMALL
        ),
        WhisperModelSpec(
            id = "whisper_turbo_q5_0",
            tier = WhisperModelTier.TURBO,
            displayName = "Whisper Large-v3 Turbo (Q5_0)",
            shortName = "Turbo",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            downloadUrl = "$HF_BASE/ggml-large-v3-turbo-q5_0.bin",
            expectedBytes = 574_000_000L,
            approxRamMb = 1600,
            description = "Highest local Cantonese accuracy; best on 6 GB+ RAM devices.",
            isRecommended = false,
            engineId = TranscriptionEngineId.WHISPER_LOCAL_TURBO
        )
    )

    fun findBuiltIn(id: String): WhisperModelSpec? = builtInSpecs.firstOrNull { it.id == id }
}
