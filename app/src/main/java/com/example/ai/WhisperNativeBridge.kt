package com.example.ai

/**
 * Kotlin JNI bridge to `libwhisper_jni.so` (`app/src/main/cpp/whisper_jni.cpp`).
 *
 * Loading is guarded so pure-JVM unit tests and Robolectric environments without native `.so`
 * binaries can still construct [LocalWhisperEngine] and [com.example.core.AppContainer] safely.
 */
object WhisperNativeBridge {

    private val loadThrowable: Throwable? = try {
        System.loadLibrary("whisper_jni")
        null
    } catch (t: Throwable) {
        t
    }

    fun isNativeAvailable(): Boolean = loadThrowable == null

    fun nativeLoadError(): Throwable? = loadThrowable

    external fun nativeInitFromFile(modelPath: String): Long

    external fun nativeFreeContext(contextPtr: Long)

    external fun nativeTranscribe(
        contextPtr: Long,
        audioSamples: FloatArray,
        numThreads: Int,
        language: String,
        initialPrompt: String,
        singleSegment: Boolean,
        noContext: Boolean
    ): String
}
