package com.example.data

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.model.ActiveWhisperModel
import com.example.model.CustomWhisperModelMeta
import com.example.model.ModelInstallState
import com.example.model.TranscriptionEngineId
import com.example.model.WhisperManagerState
import com.example.model.WhisperModelCatalog
import com.example.model.WhisperModelSpec
import com.example.model.WhisperModelTier
import com.example.model.WhisperModelUiItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "WhisperModelStore"

/**
 * Manages on-device Whisper GGML models (built-in catalog downloads + user-imported `.bin` files).
 *
 * Files are stored privately under `filesDir/whisper_models/`:
 * - `catalog/<spec.fileName>` for Tiny / Base / Small / Turbo downloads
 * - `custom/<id>.bin` + `custom_models.json` for user-imported local files (SAF `OpenDocument`)
 *
 * Validated using GGML binary magic (`0x67676d6c`, little-endian `'lmgg'`) and atomic `.part`
 * promotion so corrupt or partial downloads never break native inference.
 */
class WhisperModelStore(
    private val appContext: Context,
    private val preferencesRepository: PreferencesRepository,
    private val httpClient: OkHttpClient = defaultDownloadClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val GGML_MAGIC_LE = 0x67676d6c
        const val MIN_VALID_MODEL_BYTES = 1_000_000L
        private const val COPY_BUFFER_BYTES = 32 * 1024

        fun defaultDownloadClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun isValidGgmlMagic(header: ByteArray): Boolean {
            if (header.size < 4) return false
            val magic = (header[0].toInt() and 0xFF) or
                ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 24)
            return magic == GGML_MAGIC_LE
        }

        fun isValidGgmlFile(
            file: File,
            minBytes: Long = MIN_VALID_MODEL_BYTES
        ): Boolean {
            if (!file.exists() || !file.isFile || file.length() < minBytes) return false
            return try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(4)
                    val read = input.read(header)
                    read == 4 && isValidGgmlMagic(header)
                }
            } catch (_: Exception) {
                false
            }
        }

        fun formatMegabytes(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 100.0) {
                String.format(Locale.US, "%.0f MB", mb)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }

    private val storeScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val rootDir = File(appContext.filesDir, "whisper_models").apply { mkdirs() }
    private val catalogDir = File(rootDir, "catalog").apply { mkdirs() }
    private val customDir = File(rootDir, "custom").apply { mkdirs() }
    private val customManifestFile = File(rootDir, "custom_models.json")

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val transientStates = ConcurrentHashMap<String, ModelInstallState>()
    private val customModelsLock = Any()

    @Volatile
    private var beforeModelDeletedHook: (suspend (String) -> Unit)? = null

    @Volatile
    private var persistedActiveModelId: String? = null

    @Volatile
    private var isImportingCustomFlag: Boolean = false

    @Volatile
    private var statusBannerText: String? = null

    private val deviceTotalRamMb: Long = readDeviceTotalRamMb()

    private val _state = MutableStateFlow(buildSnapshot())
    val state: StateFlow<WhisperManagerState> = _state.asStateFlow()

    init {
        storeScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                persistedActiveModelId = prefs.activeWhisperModelId
                reconcileActiveModelAndPublish()
            }
        }
    }

    fun setBeforeModelDeletedHook(hook: suspend (String) -> Unit) {
        beforeModelDeletedHook = hook
    }

    val hasInstalledModel: Boolean
        get() = resolveActiveModel() != null

    fun resolveActiveModel(): ActiveWhisperModel? {
        val current = _state.value
        val activeId = current.activeModelId ?: return null
        return resolveModelById(activeId)
    }

    fun resolveModelById(modelId: String): ActiveWhisperModel? {
        val builtIn = WhisperModelCatalog.findBuiltIn(modelId)
        if (builtIn != null) {
            val file = File(catalogDir, builtIn.fileName)
            if (file.exists() && file.length() > 0L) {
                return ActiveWhisperModel(
                    id = builtIn.id,
                    displayName = builtIn.displayName,
                    shortName = builtIn.shortName,
                    filePath = file.absolutePath,
                    engineId = builtIn.engineId
                )
            }
            return null
        }

        val custom = loadCustomMetadata().firstOrNull { it.id == modelId } ?: return null
        val file = File(customDir, custom.fileName)
        if (!file.exists() || file.length() <= 0L) return null
        return ActiveWhisperModel(
            id = custom.id,
            displayName = custom.displayName,
            shortName = custom.displayName.take(16),
            filePath = file.absolutePath,
            engineId = TranscriptionEngineId.WHISPER_LOCAL_CUSTOM
        )
    }

    fun downloadModel(modelId: String) {
        val spec = WhisperModelCatalog.findBuiltIn(modelId) ?: return
        if (downloadJobs[modelId]?.isActive == true) return

        val finalFile = File(catalogDir, spec.fileName)
        if (finalFile.exists() && isValidGgmlFile(finalFile)) {
            transientStates.remove(modelId)
            reconcileActiveModelAndPublish()
            return
        }

        val job = storeScope.launch {
            val partFile = File(catalogDir, "${spec.fileName}.part")
            try {
                statusBannerText = null
                transientStates[modelId] = ModelInstallState.Downloading(0L, spec.expectedBytes)
                publishSnapshot()

                val request = Request.Builder()
                    .url(spec.downloadUrl)
                    .header("User-Agent", "CantoScribe-Android/1.0")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Download failed (HTTP ${response.code})")
                    }
                    val body = response.body
                        ?: throw IOException("Empty download stream from model host")
                    val totalBytes = body.contentLength().takeIf { it > 0L } ?: spec.expectedBytes

                    var downloaded = 0L
                    var lastEmitMs = 0L
                    FileOutputStream(partFile, false).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= 160L || downloaded >= totalBytes) {
                                    lastEmitMs = now
                                    transientStates[modelId] = ModelInstallState.Downloading(
                                        bytesDownloaded = downloaded,
                                        totalBytes = maxOf(totalBytes, downloaded)
                                    )
                                    publishSnapshot()
                                }
                            }
                        }
                    }

                    if (!isActive) {
                        throw CancellationException("Download cancelled")
                    }
                }

                transientStates[modelId] = ModelInstallState.Validating
                publishSnapshot()

                if (!isValidGgmlFile(partFile)) {
                    partFile.delete()
                    throw IllegalStateException(
                        "Downloaded file is not a valid GGML Whisper model."
                    )
                }

                if (finalFile.exists()) {
                    finalFile.delete()
                }
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                transientStates.remove(modelId)
                // Automatically activate if no active model is installed yet
                if (persistedActiveModelId == null || resolveModelById(persistedActiveModelId!!) == null) {
                    persistedActiveModelId = spec.id
                    preferencesRepository.setActiveWhisperModelId(spec.id)
                }
                statusBannerText = "${spec.displayName} ready for offline transcription."
                reconcileActiveModelAndPublish()
            } catch (ce: CancellationException) {
                partFile.delete()
                transientStates.remove(modelId)
                publishSnapshot()
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Model download error for $modelId: ${e.message}")
                partFile.delete()
                transientStates[modelId] = ModelInstallState.Error(
                    e.message ?: "Download failed. Check connection and try again."
                )
                publishSnapshot()
            } finally {
                downloadJobs.remove(modelId)
            }
        }
        downloadJobs[modelId] = job
    }

    fun cancelDownload(modelId: String) {
        downloadJobs.remove(modelId)?.cancel()
        transientStates.remove(modelId)
        WhisperModelCatalog.findBuiltIn(modelId)?.let { spec ->
            File(catalogDir, "${spec.fileName}.part").delete()
        }
        publishSnapshot()
    }

    suspend fun selectModel(modelId: String): Result<Unit> = withContext(ioDispatcher) {
        val resolved = resolveModelById(modelId)
            ?: return@withContext Result.failure(IllegalStateException("Selected model is not installed."))
        persistedActiveModelId = resolved.id
        preferencesRepository.setActiveWhisperModelId(resolved.id)
        statusBannerText = "Active offline model: ${resolved.shortName}"
        reconcileActiveModelAndPublish()
        Result.success(Unit)
    }

    suspend fun deleteModel(modelId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            cancelDownload(modelId)
            beforeModelDeletedHook?.invoke(modelId)

            val builtIn = WhisperModelCatalog.findBuiltIn(modelId)
            if (builtIn != null) {
                File(catalogDir, builtIn.fileName).delete()
                File(catalogDir, "${builtIn.fileName}.part").delete()
            } else {
                val remaining = synchronized(customModelsLock) {
                    val current = loadCustomMetadata()
                    val target = current.firstOrNull { it.id == modelId }
                    if (target != null) {
                        File(customDir, target.fileName).delete()
                    }
                    val updated = current.filterNot { it.id == modelId }
                    saveCustomMetadata(updated)
                    updated
                }
                Log.d(TAG, "Remaining custom models after delete: ${remaining.size}")
            }

            transientStates.remove(modelId)
            if (persistedActiveModelId == modelId) {
                val fallback = firstInstalledModelId()
                persistedActiveModelId = fallback
                preferencesRepository.setActiveWhisperModelId(fallback)
            }
            statusBannerText = null
            reconcileActiveModelAndPublish()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importCustomModelFromUri(uri: Uri): Result<WhisperModelUiItem> = withContext(ioDispatcher) {
        isImportingCustomFlag = true
        statusBannerText = null
        publishSnapshot()

        val tempFile = File(customDir, "import_${UUID.randomUUID()}.part")
        try {
            val rawDisplayName = queryDisplayName(uri) ?: "custom_whisper.bin"
            val id = "custom_${UUID.randomUUID().toString().take(8)}"
            val destFileName = "$id.bin"
            val destFile = File(customDir, destFileName)

            var copiedBytes = 0L
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                    }
                }
            } ?: throw IOException("Unable to read selected model file.")

            if (!isValidGgmlFile(tempFile)) {
                tempFile.delete()
                throw IllegalArgumentException(
                    "Invalid model file. Pick a Whisper GGML `.bin` model starting with the GGML binary header."
                )
            }

            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            val cleanTitle = rawDisplayName
                .substringBeforeLast('.')
                .replace('_', ' ')
                .ifBlank { "Custom Whisper Model" }
                .take(32)

            val meta = CustomWhisperModelMeta(
                id = id,
                displayName = cleanTitle,
                fileName = destFileName,
                sizeBytes = destFile.length(),
                addedAtMillis = System.currentTimeMillis()
            )
            synchronized(customModelsLock) {
                val updated = loadCustomMetadata() + meta
                saveCustomMetadata(updated)
            }

            persistedActiveModelId = meta.id
            preferencesRepository.setActiveWhisperModelId(meta.id)
            statusBannerText = "Imported \"$cleanTitle\" and set as active offline model."
            reconcileActiveModelAndPublish()

            val item = _state.value.models.firstOrNull { it.id == meta.id }
                ?: throw IllegalStateException("Imported model missing from state.")
            Result.success(item)
        } catch (ce: CancellationException) {
            tempFile.delete()
            throw ce
        } catch (e: Exception) {
            tempFile.delete()
            statusBannerText = e.message ?: "Failed to import Whisper model."
            publishSnapshot()
            Result.failure(e)
        } finally {
            isImportingCustomFlag = false
            publishSnapshot()
        }
    }

    fun dismissBanner() {
        statusBannerText = null
        publishSnapshot()
    }

    private fun reconcileActiveModelAndPublish() {
        val candidate = persistedActiveModelId?.takeIf { resolveModelById(it) != null }
        if (candidate == null) {
            val firstInstalled = firstInstalledModelId()
            if (firstInstalled != persistedActiveModelId) {
                persistedActiveModelId = firstInstalled
                storeScope.launch {
                    preferencesRepository.setActiveWhisperModelId(firstInstalled)
                }
            }
        }
        publishSnapshot()
    }

    private fun firstInstalledModelId(): String? {
        for (spec in WhisperModelCatalog.builtInSpecs) {
            val file = File(catalogDir, spec.fileName)
            if (file.exists() && file.length() > 0L) return spec.id
        }
        for (custom in loadCustomMetadata()) {
            val file = File(customDir, custom.fileName)
            if (file.exists() && file.length() > 0L) return custom.id
        }
        return null
    }

    private fun publishSnapshot() {
        _state.value = buildSnapshot()
    }

    private fun buildSnapshot(): WhisperManagerState {
        val activeId = persistedActiveModelId?.takeIf { isModelFilePresent(it) }
            ?: firstInstalledModelId()

        val builtInItems = WhisperModelCatalog.builtInSpecs.map { spec ->
            val file = File(catalogDir, spec.fileName)
            val transient = transientStates[spec.id]
            val installState = when {
                transient != null -> transient
                file.exists() && file.length() > 0L ->
                    ModelInstallState.Installed(file.length(), file.absolutePath)
                else -> ModelInstallState.NotInstalled
            }
            val sizeLabel = if (installState is ModelInstallState.Installed) {
                formatMegabytes(installState.sizeBytes)
            } else {
                "~${formatMegabytes(spec.expectedBytes)}"
            }
            WhisperModelUiItem(
                id = spec.id,
                tier = spec.tier,
                displayName = spec.displayName,
                shortName = spec.shortName,
                sizeLabel = sizeLabel,
                ramLabel = "~${spec.approxRamMb} MB RAM",
                description = spec.description,
                isRecommended = spec.isRecommended,
                isCustom = false,
                installState = installState,
                isActive = activeId == spec.id && installState is ModelInstallState.Installed
            )
        }

        val customItems = loadCustomMetadata().mapNotNull { custom ->
            val file = File(customDir, custom.fileName)
            if (!file.exists() || file.length() <= 0L) return@mapNotNull null
            WhisperModelUiItem(
                id = custom.id,
                tier = WhisperModelTier.CUSTOM,
                displayName = custom.displayName,
                shortName = custom.displayName.take(14),
                sizeLabel = formatMegabytes(file.length()),
                ramLabel = "Custom GGML",
                description = "Imported local GGML `.bin` model (${custom.fileName}).",
                isRecommended = false,
                isCustom = true,
                installState = ModelInstallState.Installed(file.length(), file.absolutePath),
                isActive = activeId == custom.id
            )
        }

        val allItems = builtInItems + customItems
        val activeItem = allItems.firstOrNull { it.isActive }
        val hasInstalled = allItems.any { it.isInstalled }

        return WhisperManagerState(
            models = allItems,
            activeModelId = activeItem?.id,
            activeModelName = activeItem?.shortName,
            hasInstalledModel = hasInstalled,
            isImportingCustom = isImportingCustomFlag,
            deviceTotalRamMb = deviceTotalRamMb,
            statusBanner = statusBannerText
        )
    }

    private fun isModelFilePresent(modelId: String): Boolean {
        val spec = WhisperModelCatalog.findBuiltIn(modelId)
        if (spec != null) {
            val file = File(catalogDir, spec.fileName)
            return file.exists() && file.length() > 0L
        }
        val custom = loadCustomMetadata().firstOrNull { it.id == modelId } ?: return false
        val file = File(customDir, custom.fileName)
        return file.exists() && file.length() > 0L
    }

    private fun loadCustomMetadata(): List<CustomWhisperModelMeta> = synchronized(customModelsLock) {
        if (!customManifestFile.exists()) return emptyList()
        return try {
            val raw = customManifestFile.readText()
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val displayName = obj.optString("displayName").takeIf { it.isNotBlank() } ?: continue
                    val fileName = obj.optString("fileName").takeIf { it.isNotBlank() } ?: continue
                    val sizeBytes = obj.optLong("sizeBytes", 0L)
                    val addedAtMillis = obj.optLong("addedAtMillis", 0L)
                    add(
                        CustomWhisperModelMeta(
                            id = id,
                            displayName = displayName,
                            fileName = fileName,
                            sizeBytes = sizeBytes,
                            addedAtMillis = addedAtMillis
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading custom_models.json", e)
            emptyList()
        }
    }

    private fun saveCustomMetadata(models: List<CustomWhisperModelMeta>) {
        val array = JSONArray()
        for (model in models) {
            array.put(
                JSONObject().apply {
                    put("id", model.id)
                    put("displayName", model.displayName)
                    put("fileName", model.fileName)
                    put("sizeBytes", model.sizeBytes)
                    put("addedAtMillis", model.addedAtMillis)
                }
            )
        }
        customManifestFile.writeText(array.toString())
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }

    private fun readDeviceTotalRamMb(): Long {
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(0L)
    }
}
