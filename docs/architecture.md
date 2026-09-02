# Architecture

Excerpt from `HANDOFF.md` §2 (authoritative — this page is a readable mirror).

(read these files first)

- `app/build.gradle.kts` — `namespace`/`applicationId` = `com.julianrottenberg.verbatide`, `versionCode`/`versionName` (MUST bump), `signingConfigs.debug` uses `debug.p12`, `androidComponents` APK rename (currently broken — see §6), `ndk abiFilters arm64-v8a`, deps include `libs/sherpa-onnx-1.13.5.aar` + `androidx.security:security-crypto`.
- `app/src/main/AndroidManifest.xml` — `allowBackup=false` + `backup_rules`/`dataExtractionRules`, `networkSecurityConfig`, `RECORD_AUDIO`+`INTERNET` only, `WhisperAccessibilityService` with `BIND_ACCESSIBILITY_SERVICE`, `MainActivity`/`HistoryActivity`/`DictionaryActivity`.
- `app/src/main/res/xml/{network_security_config,backup_rules,data_extraction_rules,accessibility_service_config}.xml`
- Kotlin package: `com.julianrottenberg.verbatide` (moved from `com.kafkasl.phonewhisper` at rebrand)
  - `WhisperAccessibilityService.kt` — state machine `IDLE/RECORDING/TRANSCRIBING`, `AudioRecord`, `WavWriter`, `showOverlay`/`FAB`, `onAccessibilityEvent` focus-aware bubble, `transcribeApi` dispatch, `handleTranscriptionResult` → `PostProcessor` → `injectText`/`HistoryManager`
  - `TranscriberClient.kt` — generic OpenAI-compatible `multipart /v1/audio/transcriptions` + `verbose_json` handling, language param, `currentCall`/`cancel()`, 25 MB WAV cap, `response_format` omitted for `together.ai`
  - `FalTranscriber.kt` — queue-based `https://rest.fal.ai/storage/upload/initiate?storage_type=fal-cdn-v3` → `PUT` → `https://queue.fal.run/fal-ai/wizper` + polling, `language: null` for `auto`, `Base64` fallback removed (wizper rejects `data:` with `Unsupported data URL`)
  - `PostProcessor.kt` — chat `/v1/chat/completions` with `reasoning` param, `withLanguageGuard`, `DEV_PROMPT`/`SIMPLE_PROMPT`, response parsing, size guards, `currentCall`/`cancel()`
  - `ProviderConfig.kt` — `Provider` enum (`OPENAI/GROQ/OPENROUTER/TOGETHER/VENICE/MISTRAL/NANOGPT/FAL/CUSTOM`), split `stt_provider`/`chat_provider` prefs + legacy `provider` fallback, `Defaults` per provider, `sttUrl/sttModel/chatUrl/chatModel` resolvers, `sttModelOverride`/`chatModelOverride` keys + `save*Override`
  - `SecurePrefs.kt` — `EncryptedSharedPreferences` (`phonewhisper_secure`) for `stt_api_key`/`chat_api_key`/`api_key`, plain `phonewhisper` for the rest, `MasterKey AES256_GCM`
  - `TranscriptionHistory.kt` — `filesDir/transcription_history.json`, capped by `50 MB / 90 days`, `HistoryManager`
  - `Dictionary.kt` / `DictionaryActivity.kt` / `HistoryActivity.kt` — user dictionary (prompt hint for Whisper-family STT)
  - `MainActivity.kt` — all settings UI, `registerForActivityResult` SAF backup, provider picker, language picker, reasoning picker, model override rows, history/dictionary launchers
  - `WavWriter.kt`, `ModelDownloader.kt`, `LocalTranscriber.kt`
- `settings.gradle.kts` — `rootProject.name = "verbatide"`
- `Makefile` — `APK := verbatide-v$(VER).apk`, uses `run-as com.julianrottenberg.verbatide`
- `app/src/main/res/values/strings.xml` — `app_name=Verbatide`, `app_name_subtitle=Built on Phone Whisper`

## Package layout

- `app/src/main/kotlin/com/julianrottenberg/verbatide/` — all app code (moved from `com.kafkasl.phonewhisper` at rebrand).
- `app/src/main/res/xml/` — `network_security_config.xml` + `backup_rules.xml` + `data_extraction_rules.xml` + `accessibility_service_config.xml`.
- `app/libs/sherpa-onnx-1.13.5.aar` — native Whisper/Piper runtime.
- `app/debug.p12` — committed PKCS12 debug keystore (alias `phonewhisper`, pass `android`).
