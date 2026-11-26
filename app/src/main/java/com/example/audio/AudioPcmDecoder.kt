package com.example.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.example.model.PreparedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val TAG = "AudioPcmDecoder"

/**
 * Decodes CantoScribe audio inputs ([PreparedAudio] containers such as `.opus`, `.ogg`, `.m4a`,
 * `.mp3`, `.wav`, `.flac`, `.webm` as well as live PCM16 buffers) into normalized 16 kHz mono
 * `FloatArray` samples (`[-1.0f, 1.0f]`) required by Whisper GGML inference.
 */
class AudioPcmDecoder(private val appContext: Context) {

    companion object {
        const val TARGET_SAMPLE_RATE_HZ = 16_000
        private const val CODEC_TIMEOUT_US = 10_000L
        // Guard against extreme multi-hour files exhausting heap (30 min @ 16 kHz = 28.8M floats)
        private const val MAX_OUTPUT_SAMPLES = 16_000 * 60 * 30

        /**
         * Fast pure-Kotlin RIFF/WAV parser supporting PCM8, PCM16, PCM24, PCM32, and IEEE Float32,
         * downmixing to mono and linearly resampling to 16 kHz.
         * Returns null if [bytes] is not a valid uncompressed RIFF/WAV stream.
         */
        fun decodeWavBytesTo16kMonoFloat(bytes: ByteArray): FloatArray? {
            if (bytes.size < 44) return null
            if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) {
                return null
            }
            if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
                bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
            ) {
                return null
            }

            var audioFormat = -1
            var numChannels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataLength = 0

            var pos = 12
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = readIntLE(bytes, pos + 4)
                val payloadStart = pos + 8
                if (chunkSize < 0 || payloadStart + chunkSize > bytes.size) break

                when (chunkId) {
                    "fmt " -> if (chunkSize >= 16) {
                        audioFormat = readUShortLE(bytes, payloadStart)
                        numChannels = readUShortLE(bytes, payloadStart + 2)
                        sampleRate = readIntLE(bytes, payloadStart + 4)
                        bitsPerSample = readUShortLE(bytes, payloadStart + 14)
                    }
                    "data" -> {
                        dataOffset = payloadStart
                        dataLength = chunkSize
                        break
                    }
                }
                // RIFF chunks are word-aligned (even offset)
                val paddedSize = chunkSize + (chunkSize and 1)
                pos = payloadStart + paddedSize
            }

            if (dataOffset < 0 || numChannels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
                return null
            }
            if (audioFormat != 1 && audioFormat != 3) {
                return null
            }

            val bytesPerSample = bitsPerSample / 8
            if (bytesPerSample <= 0) return null
            val frameStride = bytesPerSample * numChannels
            val totalFrames = dataLength / frameStride
            if (totalFrames <= 0) return FloatArray(0)

            val mono = FloatArray(totalFrames)
            for (frame in 0 until totalFrames) {
                val frameBase = dataOffset + frame * frameStride
                var sum = 0f
                for (ch in 0 until numChannels) {
                    val sampleOffset = frameBase + ch * bytesPerSample
                    val value = when {
                        audioFormat == 3 && bitsPerSample == 32 -> {
                            val bits = readIntLE(bytes, sampleOffset)
                            java.lang.Float.intBitsToFloat(bits).coerceIn(-1f, 1f)
                        }
                        audioFormat == 1 && bitsPerSample == 8 -> {
                            val unsigned = bytes[sampleOffset].toInt() and 0xFF
                            (unsigned - 128) / 128f
                        }
                        audioFormat == 1 && bitsPerSample == 16 -> {
                            val lo = bytes[sampleOffset].toInt() and 0xFF
                            val hi = bytes[sampleOffset + 1].toInt()
                            val sample = (hi shl 8) or lo
                            sample / 32768f
                        }
                        audioFormat == 1 && bitsPerSample == 24 -> {
                            val b0 = bytes[sampleOffset].toInt() and 0xFF
                            val b1 = bytes[sampleOffset + 1].toInt() and 0xFF
                            val b2 = bytes[sampleOffset + 2].toInt()
                            val sample = (b2 shl 16) or (b1 shl 8) or b0
                            sample / 8_388_608f
                        }
                        audioFormat == 1 && bitsPerSample == 32 -> {
                            readIntLE(bytes, sampleOffset) / 2_147_483_648f
                        }
                        else -> return null
                    }
                    sum += value
                }
                mono[frame] = (sum / numChannels).coerceIn(-1f, 1f)
            }

            return resampleMonoFloat(mono, sampleRate, TARGET_SAMPLE_RATE_HZ)
        }

        /**
         * Converts raw little-endian PCM16 bytes at [sampleRateHz] to 16 kHz mono normalized floats.
         */
        fun pcm16BytesTo16kMonoFloat(
            pcmBytes: ByteArray,
            sampleRateHz: Int = TARGET_SAMPLE_RATE_HZ,
            channels: Int = 1
        ): FloatArray {
            if (pcmBytes.size < 2 || channels <= 0 || sampleRateHz <= 0) return FloatArray(0)
            val frameStride = 2 * channels
            val totalFrames = pcmBytes.size / frameStride
            if (totalFrames <= 0) return FloatArray(0)

            val mono = FloatArray(totalFrames)
            for (frame in 0 until totalFrames) {
                val base = frame * frameStride
                var sum = 0f
                for (ch in 0 until channels) {
                    val idx = base + ch * 2
                    val lo = pcmBytes[idx].toInt() and 0xFF
                    val hi = pcmBytes[idx + 1].toInt()
                    val sample = (hi shl 8) or lo
                    sum += sample / 32768f
                }
                mono[frame] = (sum / channels).coerceIn(-1f, 1f)
            }
            return resampleMonoFloat(mono, sampleRateHz, TARGET_SAMPLE_RATE_HZ)
        }

        /**
         * Linear interpolation resampler for mono float PCM buffers.
         */
        fun resampleMonoFloat(
            samples: FloatArray,
            srcRateHz: Int,
            dstRateHz: Int
        ): FloatArray {
            if (samples.isEmpty() || srcRateHz <= 0 || dstRateHz <= 0) return FloatArray(0)
            if (srcRateHz == dstRateHz) return samples

            val outLength = ((samples.size.toLong() * dstRateHz) / srcRateHz)
                .toInt()
                .coerceAtLeast(1)
                .coerceAtMost(MAX_OUTPUT_SAMPLES)
            val output = FloatArray(outLength)
            val ratio = srcRateHz.toDouble() / dstRateHz.toDouble()

            for (i in 0 until outLength) {
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, samples.lastIndex)
                val idx1 = (idx0 + 1).coerceAtMost(samples.lastIndex)
                val frac = (srcPos - idx0).toFloat()
                output[i] = samples[idx0] + (samples[idx1] - samples[idx0]) * frac
            }
            return output
        }

        private fun readUShortLE(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun readIntLE(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        }
    }

    /**
     * Decodes any supported [PreparedAudio] clip into 16 kHz mono float samples for Whisper.
     */
    suspend fun decodeTo16kMonoFloat(audio: PreparedAudio): Result<FloatArray> = withContext(Dispatchers.IO) {
        try {
            // Fast path for RIFF WAV
            decodeWavBytesTo16kMonoFloat(audio.bytes)?.let { wavSamples ->
                if (wavSamples.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("WAV audio contains no samples."))
                }
                return@withContext Result.success(wavSamples)
            }

            // General container decode via MediaExtractor + MediaCodec
            val ext = audio.openAiFileName.substringAfterLast('.', "audio").take(8)
            val decodeDir = File(appContext.cacheDir, "whisper_decode").apply { mkdirs() }
            val tempFile = File(decodeDir, "dec_${UUID.randomUUID()}.$ext")
            try {
                FileOutputStream(tempFile).use { it.write(audio.bytes) }
                val decoded = decodeFileViaMediaCodec(tempFile)
                if (decoded.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("Decoded audio stream is empty (${audio.codecLabel}).")
                    )
                }
                Result.success(decoded)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decoding ${audio.codecLabel} for local Whisper", e)
            Result.failure(
                IllegalStateException(
                    "Could not decode ${audio.codecLabel} on this device: ${e.message ?: "unsupported format"}",
                    e
                )
            )
        }
    }

    private fun decodeFileViaMediaCodec(file: File): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) {
                throw IllegalStateException("No audio track found in media container.")
            }

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing audio MIME type.")
            extractor.selectTrack(audioTrackIndex)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE_HZ)
            var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val monoChunks = ArrayList<FloatArray>(32)
            var totalMonoSamples = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        sampleRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = outFmt.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunkFloats = extractMonoChunk(outBuffer, channels, pcmEncoding)
                            if (chunkFloats.isNotEmpty()) {
                                monoChunks.add(chunkFloats)
                                totalMonoSamples += chunkFloats.size
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            totalMonoSamples >= MAX_OUTPUT_SAMPLES * 3
                        ) {
                            outputDone = true
                        }
                    }
                }
            }

            if (totalMonoSamples == 0) return FloatArray(0)
            val combined = FloatArray(totalMonoSamples)
            var offset = 0
            for (chunk in monoChunks) {
                chunk.copyInto(combined, destinationOffset = offset)
                offset += chunk.size
            }
            return resampleMonoFloat(combined, sampleRate, TARGET_SAMPLE_RATE_HZ)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractMonoChunk(
        buffer: ByteBuffer,
        channels: Int,
        pcmEncoding: Int
    ): FloatArray {
        val chCount = channels.coerceAtLeast(1)
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuf = ordered.asFloatBuffer()
                val totalFloats = floatBuf.remaining()
                val frames = totalFloats / chCount
                if (frames <= 0) return FloatArray(0)
                val out = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until chCount) {
                        sum += floatBuf.get().coerceIn(-1f, 1f)
                    }
                    out[f] = sum / chCount
                }
                out
            }
            else -> {
                val shortBuf = ordered.asShortBuffer()
                val totalShorts = shortBuf.remaining()
                val frames = totalShorts / chCount
                if (frames <= 0) return FloatArray(0)
                val out = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until chCount) {
                        sum += shortBuf.get() / 32768f
                    }
                    out[f] = (sum / chCount).coerceIn(-1f, 1f)
                }
                out
            }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return if (containsKey(key)) getInteger(key) else default
    }
}
