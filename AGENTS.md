# CantoScribe — agent notes

Cantonese-first Android transcription studio (Kotlin + Jetpack Compose). Package
`com.aistudio.cantoscribe.kxtrvp`, namespace `com.example`, min SDK 24 / target 36.

Read `README.md` for the feature map and architecture before editing.

## Repo layout

- `app/build.gradle.kts` — module build: Compose, Retrofit/OkHttp/Moshi,
  Secrets Gradle Plugin (`.env` / `.env.example`), KSP (Moshi codegen).
  Firebase AI Logic and App Check libraries are not used; users supply API keys in-app.
- `gradle/libs.versions.toml` — version catalog; keep new dependencies here.
- `app/src/main/java/com/example/` — all app code (`ai/`, `audio/`, `core/`, `data/`, `ime/`,
  `model/`, `ui/`, `viewmodel/`).
- `app/src/test/` — Robolectric (SDK 36) + JUnit; `app/src/androidTest/` — instrumented.

## Conventions

- Kotlin, official code style (`kotlin.code.style=official`). Compose Material 3.
- Manual DI via `core/AppContainer` (applicationContext only — no Activity refs).
- New user-facing strings go in `app/src/main/res/values/strings.xml` (Cantonese/HK flavor:
  spoken Cantonese, not Mandarin).
- API keys never in source: user keys live in `data/SecureKeyStore` (Android Keystore
  AES-256-GCM) and are entered in Settings → Keys. Optional build-time OpenAI fallback
  comes from `.env` via the Secrets plugin into `BuildConfig` and must stay a placeholder
  in published debug APKs. `.env`, `local.properties`, `debug.keystore`, `google-services.json`,
  and `my-upload-key.jks` are gitignored. No App Check debug token is required or embedded.
- Model IDs and routing live in `ai/TranscriptionRouter` and `model/TranscriptModels.kt`.

## Heavy compute (grokvm dev box)

This box is for storage and orchestration only. Never run these locally:

- `gradlew` / `gradle assemble|bundle|test` / full `tsc` / Gradle-Android-Docker builds / long suites

Run them on ASTRAEA instead (the Windows machine with the CPU, Android SDK, and JDK):

```bash
.scripts/run-on-astraea.sh --paths app/ gradle/ -- ./gradlew assembleDebug
.scripts/run-on-astraea.sh --paths app/ gradle/ -- ./gradlew testDebugUnitTest
```

`.scripts/run-on-astraea.sh` syncs this repo's committed HEAD plus the declared uncommitted
paths to ASTRAEA (`C:/Users/spcys/Downloads/transcript_android`), then runs one command there
under Git Bash while holding a lease. Escape hatch: `ALLOW_LOCAL_HEAVY=1` (human override only).

Allowed locally: reading/editing source, light git, short one-off lookups, one test file at a time.

## Session memory

At the end of substantive work run the `organize-memory` skill (`session-log`, or `full` when
memory needs organizing). Memory lives in the shared `/home/box/code/web/.memory` store
(`python3 /home/box/code/web/.tools/memory.py`); search with `memory.py brief`/`search` before
reading `.memory` files.
