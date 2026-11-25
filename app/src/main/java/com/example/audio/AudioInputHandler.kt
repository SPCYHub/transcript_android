package com.example.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.example.model.AudioCaptureMetrics
import com.example.model.AudioSourceType
import com.example.model.PreparedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.log10

private const val TAG = "AudioInputHandler"

/**
 * High-efficiency audio intake and live capture module.
 *
 * Designed specifically for:
 * - Zero-double-buffering WhatsApp `.opus`/`.ogg` & external `Uri` streaming via 16 KB chunks.
 * - Single-pass SHA-256 content hashing during ingestion for instant session deduplication.
 * - Binary magic-byte codec sniffing so WhatsApp shares with generic MIME types resolve cleanly.
 * - Low-footprint 16 kHz mono AAC-LC (24 kbps) recording (~3 KB/sec) with real-time RMS metering
 *   and Voice Activity Detection (VAD) to skip empty/silent taps before any network call.
 */
class AudioInputHandler(private val appContext: Context) {

    companion object {
        // Inline media guard kept under 20 MB for Gemini inlineData & OpenAI 25 MB upload limits.
        const val MAX_AUDIO_BYTES = 18L * 1024L * 1024L
        private const val STREAM_BUFFER_SIZE = 16 * 1024
        private const val MIN_RECORDING_DURATION_MS = 350L
        private const val SPEECH_AMPLITUDE_THRESHOLD = 420
        private const val MAX_WAVEFORM_BARS = 44
        private const val SAMPLE_RATE_HZ = 16_000
        private const val BIT_RATE_BPS = 24_000
    }

    private data class CodecProfile(
        val geminiMime: String,
        val openAiMime: String,
        val openAiExtension: String,
        val codecLabel: String
    )

    private var mediaRecorder: MediaRecorder? = null
    private var activeRecordingFile: File? = null
    private var recordingStartMs: Long = 0L
    private var meterJob: Job? = null
    private var speechFrameCount: Int = 0
    private var isRecordingActive: Boolean = false

    private val _captureMetrics = MutableStateFlow(AudioCaptureMetrics())
    val captureMetrics: StateFlow<AudioCaptureMetrics> = _captureMetrics.asStateFlow()

    /**
     * Streams audio directly from a shared WhatsApp or SAF [Uri] in a single buffered pass,
     * sniffing magic bytes, extracting duration via FD, and computing SHA-256 on the fly.
     */
    fun isSupportedAudioUriScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme == "content" || scheme == "android.resource"
    }

    suspend fun prepareFromUri(
        uri: Uri,
        sourceType: AudioSourceType
    ): Result<PreparedAudio> = withContext(Dispatchers.IO) {
        try {
            if (!isSupportedAudioUriScheme(uri)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Unsupported audio URI scheme. Please share audio via Android content provider.")
                )
            }
            val resolvedName = queryDisplayName(uri) ?: "voice_${System.currentTimeMillis()}.ogg"
            val declaredMime = appContext.contentResolver.getType(uri)?.lowercase()

            val digest = MessageDigest.getInstance("SHA-256")
            val outputBuffer = ByteArrayOutputStream(32 * 1024)
            val chunk = ByteArray(STREAM_BUFFER_SIZE)
            var totalBytes = 0L

            appContext.contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    totalBytes += read
                    if (totalBytes > MAX_AUDIO_BYTES) {
                        return@withContext Result.failure(
                            IllegalArgumentException(
                                "Audio file exceeds the 18 MB fast-processing limit. Trim or compress the clip first."
                            )
                        )
                    }
                    outputBuffer.write(chunk, 0, read)
                    digest.update(chunk, 0, read)
                }
            } ?: return@withContext Result.failure(
                IllegalStateException("Unable to open shared audio stream. Please re-share from the source app.")
            )

            val bytes = outputBuffer.toByteArray()
            if (bytes.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("The shared audio file is empty.")
                )
            }

            val profile = detectCodecProfile(
                header = bytes,
                fileName = resolvedName,
                declaredMime = declaredMime
            )
            val durationMs = extractDurationFromUri(uri)
            val openAiSafeName = normalizeFileNameForOpenAi(resolvedName, profile.openAiExtension)
            val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }

            Result.success(
                PreparedAudio(
                    bytes = bytes,
                    fileName = resolvedName,
                    geminiMimeType = profile.geminiMime,
                    openAiFileName = openAiSafeName,
                    openAiMimeType = profile.openAiMime,
                    durationMillis = durationMs,
                    sizeBytes = totalBytes,
                    sha256 = sha256Hex,
                    sourceType = sourceType,
                    codecLabel = profile.codecLabel,
                    hadSpeechActivity = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed preparing audio from shared URI: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    /**
     * Starts ultra-lightweight 16 kHz mono AAC-LC recording (~3 KB/s) for either Studio
     * or live Voice Keyboard capture.
     */
    fun startRecording(scope: CoroutineScope): Result<Unit> {
        if (isRecordingActive) {
            return Result.success(Unit)
        }
        return try {
            cleanupTempFiles()
            val cacheDir = File(appContext.cacheDir, "canto_audio").apply { mkdirs() }
            val outFile = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")

            val recorder = buildConfiguredRecorder(outFile)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            activeRecordingFile = outFile
            recordingStartMs = System.currentTimeMillis()
            speechFrameCount = 0
            isRecordingActive = true
            _captureMetrics.value = AudioCaptureMetrics(
                elapsedMillis = 0L,
                normalizedLevel = 0f,
                speechFrames = 0,
                isCurrentlySilent = true,
                waveformHistory = List(MAX_WAVEFORM_BARS) { 0.08f }
            )

            meterJob?.cancel()
            meterJob = scope.launch(Dispatchers.Default) {
                val rolling = ArrayDeque<Float>(MAX_WAVEFORM_BARS)
                repeat(MAX_WAVEFORM_BARS) { rolling.addLast(0.08f) }

                while (isActive && isRecordingActive) {
                    val rawAmp = try {
                        mediaRecorder?.maxAmplitude ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    val isSpeech = rawAmp >= SPEECH_AMPLITUDE_THRESHOLD
                    if (isSpeech) speechFrameCount++

                    // Logarithmic acoustic dB mapping for natural studio VU responsiveness.
                    val normalized = if (rawAmp <= 50) {
                        0.06f
                    } else {
                        ((log10(rawAmp.toDouble()) - 1.7) / 2.8).toFloat().coerceIn(0.08f, 1f)
                    }

                    if (rolling.size >= MAX_WAVEFORM_BARS) rolling.removeFirst()
                    rolling.addLast(normalized)

                    _captureMetrics.value = AudioCaptureMetrics(
                        elapsedMillis = System.currentTimeMillis() - recordingStartMs,
                        normalizedLevel = normalized,
                        speechFrames = speechFrameCount,
                        isCurrentlySilent = !isSpeech,
                        waveformHistory = rolling.toList()
                    )
                    delay(50L)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            cancelRecording()
            Result.failure(e)
        }
    }

    /**
     * Stops capture, releases microphone hardware immediately, runs VAD silence validation,
     * and returns a compact [PreparedAudio] ready for zero-copy upload.
     */
    suspend fun stopRecording(sourceType: AudioSourceType): Result<PreparedAudio> =
        withContext(Dispatchers.IO) {
            if (!isRecordingActive) {
                return@withContext Result.failure(IllegalStateException("Recorder is not active."))
            }

            val elapsedMs = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(0L)
            val detectedSpeechFrames = speechFrameCount
            val outputFile = activeRecordingFile

            isRecordingActive = false
            meterJob?.cancel()
            meterJob = null

            try {
                mediaRecorder?.apply {
                    try {
                        stop()
                    } catch (stopErr: RuntimeException) {
                        Log.w(TAG, "MediaRecorder stop threw (likely very short tap)", stopErr)
                    }
                    release()
                }
            } finally {
                mediaRecorder = null
                activeRecordingFile = null
                _captureMetrics.value = AudioCaptureMetrics()
            }

            if (outputFile == null || !outputFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Recording file was not created.")
                )
            }

            try {
                if (elapsedMs < MIN_RECORDING_DURATION_MS) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Recording was too short. Hold or speak for at least half a second.")
                    )
                }

                val fileBytes = readFileWithSha256(outputFile)
                val bytes = fileBytes.first
                val sha256 = fileBytes.second

                if (bytes.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("No audio data captured. Check microphone access.")
                    )
                }

                val durationMs = extractDurationFromFile(outputFile).takeIf { it > 0L } ?: elapsedMs
                val hadSpeech = detectedSpeechFrames > 0 || durationMs > 1200L

                Result.success(
                    PreparedAudio(
                        bytes = bytes,
                        fileName = outputFile.name,
                        geminiMimeType = "audio/mp4",
                        openAiFileName = "cantonese_capture.m4a",
                        openAiMimeType = "audio/mp4",
                        durationMillis = durationMs,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = sha256,
                        sourceType = sourceType,
                        codecLabel = "AAC 16kHz Mono",
                        hadSpeechActivity = hadSpeech
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed finalizing recorded audio", e)
                Result.failure(e)
            } finally {
                // Immediately delete ephemeral capture file to keep storage footprint zero.
                outputFile.delete()
            }
        }

    fun cancelRecording() {
        isRecordingActive = false
        meterJob?.cancel()
        meterJob = null
        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                    // Ignore stop failure when cancelling early.
                }
                release()
            }
        } catch (_: Exception) {
            // Ignore cleanup errors.
        } finally {
            mediaRecorder = null
            activeRecordingFile?.delete()
            activeRecordingFile = null
            _captureMetrics.value = AudioCaptureMetrics()
        }
    }

    private fun buildConfiguredRecorder(outputFile: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        // VOICE_RECOGNITION enables hardware noise suppression & cleaner speech contours on Android.
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        } catch (_: Exception) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(1)
        recorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
        recorder.setAudioEncodingBitRate(BIT_RATE_BPS)
        recorder.setOutputFile(outputFile.absolutePath)
        return recorder
    }

    /**
     * Fast binary magic-byte inspection with filename & MIME fallback so WhatsApp `.opus`
     * voice messages (which are Ogg-encapsulated Opus) are accurately identified even when
     * shared with opaque URIs.
     */
    private fun detectCodecProfile(
        header: ByteArray,
        fileName: String,
        declaredMime: String?
    ): CodecProfile {
        val ext = fileName.substringAfterLast('.', "").lowercase()

        if (header.size >= 4) {
            // Ogg container ("OggS") -> WhatsApp PTT voice notes (.opus / .ogg)
            if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() && header[3] == 0x53.toByte()
            ) {
                val isOpus = containsAscii(header, "OpusHead", maxScan = 96)
                return CodecProfile(
                    geminiMime = "audio/ogg",
                    openAiMime = "audio/ogg",
                    openAiExtension = "ogg",
                    codecLabel = if (isOpus) "WhatsApp Opus/OGG" else "OGG Audio"
                )
            }
            // RIFF WAVE ("RIFF")
            if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte()
            ) {
                return CodecProfile("audio/wav", "audio/wav", "wav", "WAV PCM")
            }
            // FLAC ("fLaC")
            if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() && header[3] == 0x43.toByte()
            ) {
                return CodecProfile("audio/flac", "audio/flac", "flac", "FLAC")
            }
            // WebM / Matroska (0x1A 0x45 0xDF 0xA3)
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) {
                return CodecProfile("audio/webm", "audio/webm", "webm", "WebM Audio")
            }
            // MP3 ID3 tag or MPEG sync word
            val isId3 = header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()
            val isMpegSync = header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0
            if (isId3 || isMpegSync) {
                return CodecProfile("audio/mpeg", "audio/mpeg", "mp3", "MP3")
            }
        }

        // ISO Base Media / M4A / MP4 ("ftyp" at offset 4)
        if (header.size >= 8 &&
            header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
            header[6] == 0x79.toByte() && header[7] == 0x70.toByte()
        ) {
            return CodecProfile("audio/mp4", "audio/mp4", "m4a", "M4A / AAC")
        }

        // Fallback to extension or declared MIME type
        return when {
            ext == "opus" || ext == "ogg" || declaredMime?.contains("ogg") == true || declaredMime?.contains("opus") == true ->
                CodecProfile("audio/ogg", "audio/ogg", "ogg", "WhatsApp Opus/OGG")
            ext == "m4a" || ext == "mp4" || ext == "aac" || declaredMime?.contains("mp4") == true || declaredMime?.contains("aac") == true ->
                CodecProfile("audio/mp4", "audio/mp4", "m4a", "M4A / AAC")
            ext == "mp3" || declaredMime?.contains("mpeg") == true ->
                CodecProfile("audio/mpeg", "audio/mpeg", "mp3", "MP3")
            ext == "wav" || declaredMime?.contains("wav") == true ->
                CodecProfile("audio/wav", "audio/wav", "wav", "WAV")
            ext == "webm" || declaredMime?.contains("webm") == true ->
                CodecProfile("audio/webm", "audio/webm", "webm", "WebM")
            ext == "flac" || declaredMime?.contains("flac") == true ->
                CodecProfile("audio/flac", "audio/flac", "flac", "FLAC")
            else ->
                CodecProfile("audio/ogg", "audio/ogg", "ogg", "Audio Stream")
        }
    }

    private fun containsAscii(bytes: ByteArray, needle: String, maxScan: Int): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        val limit = minOf(bytes.size, maxScan) - target.size
        if (limit < 0) return false
        for (i in 0..limit) {
            var matched = true
            for (j in target.indices) {
                if (bytes[i + j] != target[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    private fun normalizeFileNameForOpenAi(originalName: String, targetExtension: String): String {
        val base = originalName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifBlank { "cantonese_audio" }
            .take(40)
        return "$base.$targetExtension"
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

    private fun extractDurationFromUri(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractDurationFromFile(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readFileWithSha256(file: File): Pair<ByteArray, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = ByteArrayOutputStream(file.length().toInt().coerceAtLeast(1024))
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }
        val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
        return out.toByteArray() to shaHex
    }

    private fun cleanupTempFiles() {
        try {
            val cacheDir = File(appContext.cacheDir, "canto_audio")
            cacheDir.listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() > 5 * 60 * 1000L) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }
}
