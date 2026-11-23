package com.example.core

import android.content.Context
import com.example.ai.DirectGeminiRestClient
import com.example.ai.GeminiLiveStreamEngine
import com.example.ai.GeminiTranscriptionEngine
import com.example.ai.LiveTranscriptionController
import com.example.ai.OpenAiRealtimeStreamEngine
import com.example.ai.OpenAiTranscriptionEngine
import com.example.ai.TranscriptionRouter
import com.example.audio.AudioInputHandler
import com.example.data.PreferencesRepository
import com.example.data.SecureKeyStore
import com.example.data.SessionTranscriptStore

/**
 * Lightweight manual dependency graph shared cleanly between MainActivity and CantoVoiceImeService.
 * Uses applicationContext exclusively to prevent Activity memory leaks.
 */
object AppContainer {

    @Volatile
    private var initialized = false

    lateinit var preferencesRepository: PreferencesRepository
        private set

    lateinit var secureKeyStore: SecureKeyStore
        private set

    lateinit var sessionStore: SessionTranscriptStore
        private set

    lateinit var audioInputHandler: AudioInputHandler
        private set

    lateinit var directGeminiRestClient: DirectGeminiRestClient
        private set

    lateinit var geminiEngine: GeminiTranscriptionEngine
        private set

    lateinit var geminiLiveEngine: GeminiLiveStreamEngine
        private set

    lateinit var openAiEngine: OpenAiTranscriptionEngine
        private set

    lateinit var openAiRealtimeEngine: OpenAiRealtimeStreamEngine
        private set

    lateinit var transcriptionRouter: TranscriptionRouter
        private set

    lateinit var liveTranscriptionController: LiveTranscriptionController
        private set

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            preferencesRepository = PreferencesRepository(appCtx)
            secureKeyStore = SecureKeyStore(appCtx)
            sessionStore = SessionTranscriptStore()
            audioInputHandler = AudioInputHandler(appCtx)
            directGeminiRestClient = DirectGeminiRestClient()
            geminiEngine = GeminiTranscriptionEngine(
                keyStore = secureKeyStore,
                directRestClient = directGeminiRestClient
            )
            geminiLiveEngine = GeminiLiveStreamEngine(keyStore = secureKeyStore)
            openAiEngine = OpenAiTranscriptionEngine(keyStore = secureKeyStore)
            openAiRealtimeEngine = OpenAiRealtimeStreamEngine(keyStore = secureKeyStore)
            transcriptionRouter = TranscriptionRouter(
                geminiEngine = geminiEngine,
                openAiEngine = openAiEngine,
                sessionStore = sessionStore
            )
            liveTranscriptionController = LiveTranscriptionController(
                keyStore = secureKeyStore,
                directGeminiRest = directGeminiRestClient,
                geminiLive = geminiLiveEngine,
                openAiRealtime = openAiRealtimeEngine,
                openAiRest = openAiEngine,
                sessionStore = sessionStore
            )
            initialized = true
        }
    }
}
