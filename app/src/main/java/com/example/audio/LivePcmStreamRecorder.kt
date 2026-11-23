package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.model.AudioCaptureMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "LivePcmStreamRecorder"

data class PcmAudioChunk(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val rmsAmplitude: Int,
    val isSpeech: Boolean,
    val timestampMs: Long
)

/**
 * Low-latency, zero-disk-I/O live PCM16 audio capture engine built on Android [AudioRecord].
 *
 * Streams ~100ms mono PCM16 frames directly to real-time transcription pipelines
 * (OpenAI Realtime WebSocket at 24 kHz or Gemini / OpenAI chunked live streaming at 16 kHz)
 * while computing logarithmic VU waveform bars and adaptive noise-floor VAD.
 */
class LivePcmStreamRecorder {

    companion object {
        const val SAMPLE_RATE_GEMINI_HZ = 16_000
        const val SAMPLE_RATE_OPENAI_REALTIME_HZ = 24_000
        private const val CHUNK_DURATION_MS = 100
        private const val MIN_VAD_RMS_THRESHOLD = 115
        private const val INITIAL_NOISE_FLOOR = 65.0
        private const val MAX_NOISE_FLOOR = 240.0
        private const val NOISE_MULTIPLIER = 1.8
        private const val HANGOVER_FRAMES = 2
        private const val MAX_WAVEFORM_BARS = 44
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile
    private var isStreaming = false

    private val _metrics = MutableStateFlow(AudioCaptureMetrics())
    val metrics: StateFlow<AudioCaptureMetrics> = _metrics.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        sampleRateHz: Int,
        onChunk: suspend (PcmAudioChunk) -> Unit,
        onError: (Throwable) -> Unit
    ): Result<Unit> {
        if (isStreaming) return Result.success(Unit)

        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                return Result.failure(
                    IllegalStateException("Device microphone does not support ${sampleRateHz}Hz PCM16 capture.")
                )
            }

            // 100ms PCM16 mono frame size (2 bytes per sample).
            val chunkBytesSize = (sampleRateHz * 2 * CHUNK_DURATION_MS) / 1000
            val bufferSize = maxOf(minBuffer * 2, chunkBytesSize * 4)

            val recorder = createRecorder(sampleRateHz, bufferSize)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return Result.failure(IllegalStateException("Unable to initialize microphone AudioRecord."))
            }

            audioRecord = recorder
            isStreaming = true
            val startedAtMs = System.currentTimeMillis()
            recorder.startRecording()

            captureJob?.cancel()
            captureJob = scope.launch(Dispatchers.IO) {
                val readBuffer = ByteArray(chunkBytesSize)
                val rolling = ArrayDeque<Float>(MAX_WAVEFORM_BARS)
                repeat(MAX_WAVEFORM_BARS) { rolling.addLast(0.08f) }
                var totalSpeechFrames = 0
                var noiseFloor = INITIAL_NOISE_FLOOR
                var hangoverRemaining = 0

                try {
                    while (isActive && isStreaming) {
                        val bytesRead = recorder.read(readBuffer, 0, readBuffer.size)
                        if (bytesRead <= 0) continue

                        val slice = if (bytesRead == readBuffer.size) {
                            readBuffer.copyOf()
                        } else {
                            readBuffer.copyOfRange(0, bytesRead)
                        }

                        val rms = computePcm16Rms(slice)
                        val adaptiveThreshold = maxOf(
                            MIN_VAD_RMS_THRESHOLD,
                            (noiseFloor * NOISE_MULTIPLIER).toInt()
                        )
                        val rawSpeech = rms >= adaptiveThreshold
                        val isSpeech = if (rawSpeech) {
                            hangoverRemaining = HANGOVER_FRAMES
                            true
                        } else if (hangoverRemaining > 0) {
                            hangoverRemaining--
                            true
                        } else {
                            // Gently track ambient noise floor when silent so quiet speech still triggers.
                            noiseFloor = (noiseFloor * 0.92 + rms * 0.08)
                                .coerceIn(35.0, MAX_NOISE_FLOOR)
                            false
                        }
                        if (isSpeech) totalSpeechFrames++

                        val normalized = if (rms <= 40) {
                            0.06f
                        } else {
                            ((log10(rms.toDouble()) - 1.6) / 2.6).toFloat().coerceIn(0.08f, 1f)
                        }
                        if (rolling.size >= MAX_WAVEFORM_BARS) rolling.removeFirst()
                        rolling.addLast(normalized)

                        val now = System.currentTimeMillis()
                        _metrics.value = AudioCaptureMetrics(
                            elapsedMillis = now - startedAtMs,
                            normalizedLevel = normalized,
                            speechFrames = totalSpeechFrames,
                            isCurrentlySilent = !isSpeech,
                            waveformHistory = rolling.toList()
                        )

                        onChunk(
                            PcmAudioChunk(
                                pcmBytes = slice,
                                sampleRateHz = sampleRateHz,
                                rmsAmplitude = rms,
                                isSpeech = isSpeech,
                                timestampMs = now
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Live PCM capture loop failed", e)
                    onError(e)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LivePcmStreamRecorder", e)
            stop()
            Result.failure(e)
        }
    }

    fun stop() {
        isStreaming = false
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        stop()
                    } catch (_: Exception) {
                    }
                }
                release()
            }
        } catch (_: Exception) {
        } finally {
            audioRecord = null
            _metrics.value = AudioCaptureMetrics()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(sampleRateHz: Int, bufferSize: Int): AudioRecord {
        // Prefer standard MIC first for consistent gain across devices/emulators;
        // fall back to VOICE_RECOGNITION if MIC initialization fails.
        return try {
            val mic = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (mic.state == AudioRecord.STATE_INITIALIZED) {
                mic
            } else {
                mic.release()
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }
        } catch (_: Exception) {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
    }

    private fun computePcm16Rms(pcm: ByteArray): Int {
        val sampleCount = pcm.size / 2
        if (sampleCount == 0) return 0
        var sumSq = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += sample.toDouble() * sample.toDouble()
            i += 2
        }
        return sqrt(sumSq / sampleCount).toInt()
    }
}
