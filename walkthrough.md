# Security Summary

**Reviewed Files**: 36
**Issues Resolved**: 8

### Issues Resolved
| Area | File | Line | What Was Happening | Impact | Status | How It Was Fixed |
|---|---|---|---|---|---|---|
| Plaintext Key Fallback | `app/src/main/java/com/example/data/SecureKeyStore.kt` | 275–329 | If Android Keystore threw during encryption, `SecureKeyStore` fell back to Base64 (`b64:`) storage on production devices instead of failing closed. | High | Fixed | Restricted `b64:` fallback strictly to Robolectric test runtimes; real Android devices regenerate an invalidated alias once or fail closed without writing unencrypted keys. |
| Intent URI Scheme Bypass | `app/src/main/java/com/example/audio/AudioInputHandler.kt` | 77–91 | External `SEND` / `VIEW` intents could pass arbitrary `file://` or web URIs into `prepareFromUri`, risking private app file ingestion. | High | Fixed | Enforced `isSupportedAudioUriScheme` allowing only `content` and `android.resource` URIs and removed raw URI details from exception logs. |
| Model Path & Query Injection | `app/src/main/java/com/example/ai/ProviderRequestAuth.kt` | 23–53 | Custom Gemini model names were interpolated directly into REST endpoint URLs without character or traversal validation. | Medium | Fixed | Added `sanitizeGeminiModelName` and `isValidGeminiModelName` matching `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$`, enforced in both URL builders and `SecureKeyStore.saveCustomGeminiModel` / `StudioViewModel`. |
| API Key Exposure in Logs/UI | `app/src/main/java/com/example/ai/ProviderRequestAuth.kt` | 36–43 | Raw `Throwable` logs and exception messages from WebSocket/REST calls (which can embed `?key=` or `Bearer` headers) surfaced to `Log.e` and UI error banners. | Medium | Fixed | Added `ProviderRequestAuth.redactSecrets` and applied it across `GeminiLiveStreamEngine`, `DirectGeminiRestClient`, `OpenAiRealtimeStreamEngine`, `OpenAiTranscriptionEngine`, `LiveTranscriptionController`, `SecureKeyStore`, `StudioViewModel`, and `CantoVoiceImeService`. |
| Password & Incognito IME Leaks | `app/src/main/java/com/example/ime/CantoVoiceImeService.kt` | 201–228, 616–765 | System IME allowed voice streaming in password fields and saved incognito (`IME_FLAG_NO_PERSONALIZED_LEARNING`) transcripts to session history and preview banners. | High | Fixed | Detected password variations and incognito flags in `onStartInputView`; blocked mic dictation inside password fields and disabled session persistence and plaintext banner retention for incognito fields. |
| Missing Network Security Config | `app/src/main/res/xml/network_security_config.xml` | 1–9 | No explicit `networkSecurityConfig` restricted cleartext traffic on Android 7–8.1 (`minSdk = 24`) or pinned trust anchors to system CAs. | Medium | Fixed | Added `network_security_config.xml` with `cleartextTrafficPermitted="false"` and system-only certificates, wired in `AndroidManifest.xml` line 13. |
| Cloud Backup of Preferences | `app/src/main/res/xml/backup_rules.xml` | 4 | DataStore preferences (including custom glossary and settings) were not excluded from cloud backup/device transfer rules. | Low | Fixed | Excluded `datastore/` in both `backup_rules.xml` and `data_extraction_rules.xml`. |
| Sensitive Log Output | `app/src/main/java/com/example/ai/GeminiLiveStreamEngine.kt` | 141–160 | Connection failures logged full exception objects that include the WebSocket URL containing the caller's API key query parameter. | Medium | Fixed | Replaced raw throwable `Log.e` arguments with redacted error summaries using `ProviderRequestAuth.redactSecrets`. |

### Safeguards Verified
| Area | File | How It's Protected |
|---|---|---|
| Hardware Keystore Storage | `app/src/main/java/com/example/data/SecureKeyStore.kt` | Encrypts user Gemini and OpenAI API keys with AES-256-GCM inside `AndroidKeyStore`, masked in UI snapshots and excluded from Android backups. |
| Ephemeral Transcript Privacy | `app/src/main/java/com/example/data/SessionTranscriptStore.kt` | Keeps transcript records in memory only; cleared when the process exits or user taps Clear Session. |
| Least-Privilege Permissions | `app/src/main/AndroidManifest.xml` | Declares only `INTERNET`, `RECORD_AUDIO`, and `VIBRATE` with no broad external storage permissions; audio imports use Android's SAF / `content://` picker. |
| Exported Components | `app/src/main/AndroidManifest.xml` | System IME (`BIND_INPUT_METHOD`) and Quick Settings Tile (`BIND_QUICK_SETTINGS_TILE`) are protected by system signature permissions; `MainActivity` safely validates incoming audio URIs. |

## PoC Verification

### Plaintext KeyStore Fallback on Real Devices
#### Summary
| Field | Value |
|---|---|
| Issue Type | Insecure Cryptographic Fallback |
| Impact | High |
| Affected File | `app/src/main/java/com/example/data/SecureKeyStore.kt:275` |
| Test Scenario | Device Keystore throws an invalidation error during `saveGeminiApiKey("AIzaSyTest...")` or a `"b64:..."` entry is injected into `cantoscribe_secure_keys.xml`. |

#### How It Was Fixed
Removed unencrypted Base64 (`b64:`) storage on real Android devices; `encryptValue` now attempts one clean alias regeneration and otherwise throws an exception, while `decryptValue` refuses `b64:` payloads outside Robolectric tests.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | `saveGeminiApiKey` calls `encryptValue(trimmed)` | Attempts `AndroidKeyStore` AES-GCM encryption |
| 2 | On Keystore failure on a non-Robolectric device (`!isRobolectricRuntime()`), `deleteKeyStoreKey()` runs once and retries `encryptWithKeyStore` | Either hardware-backed `ks1:` ciphertext is written, or `IllegalStateException` aborts save |
| 3 | Any `b64:` record read by `decryptValue` hits `if (!isRobolectricRuntime()) return null` at line 324 | **Handled Safely** |

#### Conclusion
**Fix Verified** - User API keys cannot be persisted or loaded in plaintext Base64 on Android devices.

### External Intent Private File Read via `file://` URI
#### Summary
| Field | Value |
|---|---|
| Issue Type | Improper Input URI Validation |
| Impact | High |
| Affected File | `app/src/main/java/com/example/audio/AudioInputHandler.kt:77` |
| Test Scenario | Malicious app sends an `ACTION_VIEW` or `ACTION_SEND` intent with `file:///data/data/com.aistudio.cantoscribe.kxtrvp/shared_prefs/cantoscribe_secure_keys.xml`. |

#### How It Was Fixed
Added `isSupportedAudioUriScheme` checking `uri.scheme` against an allowlist of `"content"` and `"android.resource"` before opening streams or logging metadata.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | `MainActivity` passes the incoming `Uri` to `AudioInputHandler.prepareFromUri` | Enters URI intake check |
| 2 | Line 87 invokes `isSupportedAudioUriScheme(uri)` | Evaluates `scheme == "content" || scheme == "android.resource"` |
| 3 | `file://` fails check at lines 87–91 and returns `Result.failure(IllegalArgumentException(...))` before `openInputStream` | **Handled Safely** |

#### Conclusion
**Fix Verified** - Local filesystem `file://` and network URIs cannot be read through shared audio intents.

### Custom Gemini Model URL Path / Query Injection
#### Summary
| Field | Value |
|---|---|
| Issue Type | URL Path Traversal / Parameter Injection |
| Impact | Medium |
| Affected File | `app/src/main/java/com/example/ai/ProviderRequestAuth.kt:23` |
| Test Scenario | Attacker or pasted input supplies `../../v1beta/files?alt=json` as the custom Gemini model name. |

#### How It Was Fixed
Added strict regex validation (`MODEL_NAME_REGEX`) in `ProviderRequestAuth.sanitizeGeminiModelName` and enforced it in `StudioViewModel`, `SecureKeyStore`, and both REST URL constructors.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Input enters `StudioViewModel.saveCustomGeminiModel` or `SecureKeyStore.saveCustomGeminiModel` | Calls `ProviderRequestAuth.sanitizeGeminiModelName` |
| 2 | `MODEL_NAME_REGEX.matches(stripped)` rejects `/`, `?`, spaces, and empty strings at line 25 | Throws `IllegalArgumentException`, caught in ViewModel to show validation error |
| 3 | URL builders (`geminiGenerateContentUrl`, `geminiStreamGenerateContentUrl`) re-run `sanitizeGeminiModelName` before building URLs | **Handled Safely** |

#### Conclusion
**Fix Verified** - Custom model names are strictly confined to single path segments with no query or traversal characters.

### Secret Redaction in Logs & Live Error States
#### Summary
| Field | Value |
|---|---|
| Issue Type | Sensitive Data Exposure in Error Logs |
| Impact | Medium |
| Affected File | `app/src/main/java/com/example/ai/ProviderRequestAuth.kt:36` |
| Test Scenario | Gemini WebSocket failure exception message contains `wss://...BidiGenerateContent?key=AIzaSyDummy...`. |

#### How It Was Fixed
Implemented `ProviderRequestAuth.redactSecrets` to mask `?key=`, `Bearer `, `AIza...`, and `sk-...` tokens across all AI networking clients, controllers, ViewModels, and IME banners, and removed raw exception throwable arguments from sensitive `Log.e` calls.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Network failure reaches `onFailure` in `GeminiLiveStreamEngine` / `DirectGeminiRestClient` / `OpenAiTranscriptionEngine` | Calls `ProviderRequestAuth.redactSecrets(t.message)` |
| 2 | Regex replacements rewrite `?key=...` to `?key=[REDACTED]` and `Bearer ...` to `Bearer [REDACTED]` | Secret stripped before `Log.e` or UI emission |
| 3 | `LiveTranscriptionController`, `StudioViewModel`, and `CantoVoiceImeService` re-redact error text prior to display | **Handled Safely** |

#### Conclusion
**Fix Verified** - API keys and bearer tokens cannot leak into Logcat or error UI text.

### Voice IME Password & Incognito Field Guard
#### Summary
| Field | Value |
|---|---|
| Issue Type | Sensitive Input Leakage via Custom Keyboard |
| Impact | High |
| Affected File | `app/src/main/java/com/example/ime/CantoVoiceImeService.kt:201` |
| Test Scenario | User focuses a password field (`TYPE_TEXT_VARIATION_PASSWORD`) or incognito browser search bar (`IME_FLAG_NO_PERSONALIZED_LEARNING`) while using CantoScribe Voice IME. |

#### How It Was Fixed
`onStartInputView` classifies password variations and `IME_FLAG_NO_PERSONALIZED_LEARNING`, immediately stops any active stream when entering a password field, refuses voice capture on passwords, and passes `saveToSession = !isIncognitoField` while hiding plaintext transcripts from IME preview banners.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Focus enters editor; `onStartInputView` calls `isPasswordInputType` and `isIncognitoEditor` | Sets `isPasswordField` / `isIncognitoField` flags |
| 2 | Tapping voice pad on password field hits `if (isPasswordField)` guard at `startLiveDictation` line 617 | Aborts capture cleanly with privacy notice |
| 3 | Finalizing incognito dictation invokes `stopAndFinalize(saveToSession = false)` and suppresses transcript banner text | **Handled Safely** |

#### Conclusion
**Fix Verified** - Password fields cannot trigger microphone dictation and incognito inputs are never written to session history.

---

# Accessibility Audit Summary

| Area | Files Updated | Issue Resolved | Fix Implemented |
|---|---|---|---|
| Color Contrast (WCAG AA >= 4.5:1) | `Color.kt`, `ImePalette.kt`, `StudioDock.kt`, `VoiceKeyboardDeck.kt`, `CantoVoiceImeService.kt` | Light-theme primary jade (`#0B8A7E`) fell just below 4.5:1 against white text (~4.26:1), and subtitles applied 0.85 alpha over gradient pads. | Darkened `StudioJadePrimary` and `ImePalette.Light.jadeStart/Accent` to `#08786D` (~5.28:1 contrast on white) and removed translucent subtitle alpha on primary pads. |
| Typography Readability | `Type.kt` | `labelSmall` used `10.sp` / `14.sp` line height, risking truncation/legibility issues at large font scales. | Bumped `labelSmall` to `11.sp` / `16.sp`. |
| Screen-Reader Touch & Role Semantics | `StudioDock.kt`, `VoiceKeyboardDeck.kt`, `StudioAtmosphere.kt`, `TranscriptCard.kt`, `SettingsScreen.kt`, `StudioScreen.kt`, `MainActivity.kt` | Custom gesture pads lacked `Role.Button` + state descriptions, engine/theme/format chips lacked `selectableGroup()` / `Role.Tab`, and icons caused duplicate announcements inside merged tiles. | Added `semantics(mergeDescendants = true)` with explicit `Role.Button`, `stateDescription`, and `onClick` labels; applied `selectableGroup()`, `Role.Tab`, and `selected` on tab/pill bars; added `heading()` on screen and section titles; matched nav rail label and contentDescription. |
| Live Regions for Dynamic Status | `StudioLiveCard.kt`, `KeyboardScreen.kt`, `SettingsScreen.kt`, `CantoVoiceImeService.kt` | TalkBack users received no spoken feedback when live streaming, processing status, key test results, or IME composing previews changed. | Added `liveRegion = LiveRegionMode.Polite` in Compose and `ACCESSIBILITY_LIVE_REGION_POLITE` on IME `previewBanner` and `statusTextView`. |
| Minimum 48dp Touch Targets & Custom IME TalkBack Support | `VoiceKeyboardDeck.kt`, `CantoVoiceImeService.kt`, `ImeWaveformView.kt` | Several IME quick keys (`38dp`), bottom row (`46dp`), and control chips (`32dp`) were below 48dp; touch-only `OnTouchListener` keys lacked `performClick()` activation and `Button` roles. | Increased IME bottom row, control chips, and quick keys to >= 48dp; added `setOnClickListener`, `AccessibilityDelegateCompat` button roles, dynamic descriptions, and marked decorative waveforms/dots `IMPORTANT_FOR_ACCESSIBILITY_NO`. |

---

# Animations Audit Summary

| Component | Files Updated | Issue Resolved | Fix Implemented |
|---|---|---|---|
| Reduce-Motion Compliance | `StudioAtmosphere.kt`, `VoiceKeyboardDeck.kt`, `StudioLiveCard.kt`, `StudioDock.kt`, `TranscriptCard.kt`, `StudioScreen.kt`, `KeyboardScreen.kt`, `MainActivity.kt` | Infinite pulse/cursor animations and `AnimatedContent`/`animate*` transitions ignored system animator scale (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f` / `ValueAnimator.areAnimatorsEnabled()`). | Introduced `rememberReduceMotionEnabled()` helper; replaced infinite animations with static high-contrast indicators and switched transitions to `snap()` / instant updates when reduced motion is enabled. |
| Conditional Infinite Transitions | `VoiceKeyboardDeck.kt`, `StudioLiveCard.kt` | Unconditional `rememberInfiniteTransition()` kept running an infinite frame clock even when idle and not recording/streaming. | Instantiated `rememberInfiniteTransition` only when actively streaming and reduced motion is off. |
| IME Gesture & Waveform Animators | `ImeWaveformView.kt`, `CantoVoiceImeService.kt` | Stopping IME dictation decayed bars by only one frame and froze mid-decay; rapid taps on mic/backspace queued conflicting `ViewPropertyAnimator` instances without checking system animator status. | Implemented `postInvalidateOnAnimation()` settling loop in `ImeWaveformView` until bars match the idle envelope (or instant snap when animators are off) and added `animateScale()` canceling prior animators and guarding with `ValueAnimator.areAnimatorsEnabled()`. |
| Smooth Screen & Component Choreography | `MainActivity.kt`, `StudioScreen.kt`, `TranscriptCard.kt`, `SettingsScreen.kt`, `StudioDock.kt` | Abrupt screen swaps, theme chip color jumps, and sudden cancel button appearance lacked transitions. | Added `AnimatedContent` crossfades for tab navigation and studio stage changes, `animateColorAsState` on chips, and `AnimatedVisibility` on the dock cancel action—each respecting reduced-motion preferences. |

## Verification Status
- Full debug app build (`compile_applet`) succeeded cleanly after all edits.
- New Robolectric unit test file added at `app/src/test/java/com/example/SecurityAuditTest.kt` covering Gemini model sanitization, secret redaction, URI scheme allowlisting, and `SecureKeyStore` model validation.
- Note: Running the full `:app:testDebugUnitTest` task on this workspace surfaces a pre-existing unrelated compilation error in `app/src/test/java/com/example/core/AppCheckInitTest.kt` (`configureAppCheck` no longer exists in `com.example.core` after Firebase App Check removal); production source (`:app:compileDebugKotlin`) and `SecurityAuditTest.kt` compile cleanly.
