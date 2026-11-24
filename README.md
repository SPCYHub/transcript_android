# CantoScribe

Cantonese-first Android transcription studio with WhatsApp voice-message sharing,
a live Cantonese voice keyboard (IME), and smart Gemini + OpenAI auto-routing.

Package: `com.aistudio.cantoscribe.kxtrvp` · Namespace: `com.example` · Min SDK 24 / Target 36

## Features

- **Studio** — record Cantonese via mic or import audio; live streaming transcription with
  waveform, spoken + written dual view, Deep Review, and copy/share.
- **Voice Keyboard (IME)** — system-wide Cantonese voice dictation with hold-to-talk / tap,
  live waveform, backspace/undo, and quick-switch to the system keyboard.
- **WhatsApp voice messages** — Android share target for `audio/*`, `application/ogg`, and
  `SEND_MULTIPLE`; magic-byte codec sniffing for generic-MIME `.opus`/`.ogg` clips.
- **Smart routing** — Auto / Gemini-only / OpenAI-only with automatic fallback:
  short live keyboard bursts prefer OpenAI `gpt-transcribe` (fast raw dictation) with Gemini
  3.5 Lite fallback; studio recordings and long files route to Gemini 3.8 Flash with OpenAI
  fallback. SHA-256 content-hash dedup for re-shared clips.
- **Cantonese output** — spoken Cantonese (口語) and written Chinese (書面語) dual view,
  custom glossary, and Deep Review (refined spoken + written, notes, key points, replies).
- **Secure keys** — hardware-backed Android Keystore (AES-256-GCM) vault for user-supplied
  Gemini / OpenAI keys, with in-app key verification.
- **Quick Settings Tile** — one-tap live transcription from the notification shade.

## Architecture

```
app/src/main/java/com/example/
├── MainActivity.kt          # Compose shell, nav rail (Studio / Keyboard / Session / Keys),
│                            # WhatsApp/SAF share intent intake, Quick Tile intent
├── core/                    # AppContainer (manual DI), AppCheckInit, QuickTileService, StudioHaptics
├── ai/                      # TranscriptionRouter (auto-routing + fallback),
│                            # GeminiTranscriptionEngine, OpenAiTranscriptionEngine,
│                            # GeminiLiveStreamEngine, OpenAiRealtimeStreamEngine,
│                            # LiveTranscriptionController, DirectGeminiRestClient,
│                            # CantonesePrompts
├── audio/                   # AudioInputHandler (URI ingest, codec sniff, SHA-256, VAD),
│                            # LivePcmStreamRecorder (16 kHz PCM, RMS metering)
├── data/                    # SecureKeyStore (Keystore vault), SessionTranscriptStore (dedup),
│                            # PreferencesRepository (DataStore)
├── ime/                     # CantoVoiceImeService (InputMethodService), ImeWaveformView, ImePalette
├── model/                   # TranscriptModels.kt (data classes + enums)
├── ui/                      # Compose theme, components, screens (Studio/Keyboard/Session/Keys)
└── viewmodel/               # StudioViewModel (state machine for all surfaces)
```

## Build

```bash
# .env — copy and fill in your OpenAI key (optional; user can also add keys in-app)
cp .env.example .env

./gradlew assembleDebug        # debug APK (signed with debug.keystore, auto-generated)
./gradlew testDebugUnitTest    # Robolectric + JUnit unit tests
./gradlew assembleRelease      # release APK (needs KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD env)
```

Release signing expects `KEYSTORE_PATH` (default `<repo>/my-upload-key.jks`),
`STORE_PASSWORD`, and `KEY_PASSWORD` environment variables — see `app/build.gradle.kts`.

## Configuration

| Variable | Where | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | `.env` (Secrets Gradle Plugin) | Build-time OpenAI fallback key; user keys take priority |
| `FIREBASE_APPCHECK_DEBUG_TOKEN` | `.env` (ignored by Secrets plugin) | Debug App Check token for local Firebase AI Logic testing |
| `google-services.json` | `app/` (gitignored, optional) | Firebase project config; absent = Firebase AI Logic unavailable, direct Gemini/OpenAI still work |

Gemini transcription uses Firebase AI Logic via App Check (reCAPTCHA site key in
`res/values/firebase_applet_config.xml`). Direct Gemini REST (`DirectGeminiRestClient`) and
OpenAI are used when the user supplies their own keys in-app (Settings → Keys), so exported /
sideloaded APKs work without Firebase.

## Testing

- `app/src/test/` — Robolectric (SDK 36) + plain JUnit: routing hints, live-stream state,
  AppContainer init, session store, prompts.
- `app/src/androidTest/` — instrumented tests (connectedAndroidTest).

## Heavy builds

Gradle / Android builds are CPU-heavy. On the shared grokvm dev box run them via the
astraea runner (see `AGENTS.md` → *Heavy compute*):

```bash
.scripts/run-on-astraea.sh --paths app/ gradle/ -- ./gradlew assembleDebug
```
