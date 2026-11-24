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
├── core/                    # AppContainer (manual DI), QuickTileService, StudioHaptics
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

Copy `.env.example` to `.env` if you want a local secrets file, but leave the OpenAI
placeholder unchanged for published debug APKs. Prefer adding keys in the app
(Settings → Keys).

Debug package: `gradlew assembleDebug` (signed with `debug.keystore`, auto-generated).
Unit tests: `gradlew testDebugUnitTest` (Robolectric + JUnit).
Release package: `gradlew assembleRelease` (needs KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD env).

Release signing expects `KEYSTORE_PATH` (default `<repo>/my-upload-key.jks`),
`STORE_PASSWORD`, and `KEY_PASSWORD` environment variables — see `app/build.gradle.kts`.

No App Check debug token and no `adb` registration are required. Sideload the debug APK,
open Settings → Keys, and paste a Gemini and/or OpenAI API key.

## Configuration

| Variable | Where | Purpose |
|---|---|---|
| In-app Gemini / OpenAI keys | Settings → Keys (`SecureKeyStore`) | Required for transcription; stored in the hardware-backed Android Keystore |
| `OPENAI_API_KEY` | `.env` (Secrets Gradle Plugin) | Optional build-time OpenAI fallback only; keep the placeholder in published APKs. User keys take priority |

Firebase AI Logic and App Check are not used at runtime. Direct Gemini REST
(`DirectGeminiRestClient`), Gemini Live WebSocket, and OpenAI are called with the
user-supplied keys, so exported / sideloaded APKs work without Firebase or a debug token.

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
