# Verbatide — Handoff for fresh AI instances

> **Read this file + `README.md` + the GitHub repo before doing anything.** This is the single source of truth for continuing work on Verbatide with zero prior context.

## 0. Quick start (new chat checklist)

1. **Repo:** `https://github.com/julianrottenberg/verbatide` — `git clone` it. Local persistent checkout is `~/projects/verbatide` (the old `/tmp/pwf` was ephemeral and died on reboot).
2. **Read:** this `HANDOFF.md`, `README.md`, `SECURITY.md`, `PRIVACY.md`.
3. **Builds:** No JDK/Android SDK on the user's host (secureblue, hardened Fedora). **All builds are GitHub Actions** (`.github/workflows/build.yml`). Bump `app/build.gradle.kts` `versionCode`+`versionName`, push + tag `vX.Y.Z`, watch `gh run list --repo julianrottenberg/verbatide`.
4. **Runtime host:** `secureblue` — uses `run0` not `sudo`, `helium` browser, `zsh+prezto`, `homebrew` at `/home/linuxbrew/.linuxbrew`, `gh` authenticated as `julianrottenberg`.
5. **Signing:** committed `app/debug.p12` (`PKCS12`, alias `phonewhisper`, pass `android`). Every CI build signs identically → in-place updates. **Do not lose it.** Version codes must be monotonic or Android rejects install.
6. **Identity:** App is **Verbatide** — `com.julianrottenberg.verbatide`, APK `verbatide-vX.Y.Z.apk`. It is explicitly a **fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper)** — keep the "Fork of Phone Whisper by kafkasl" row under `Clear history` and the `Built on Phone Whisper` subtitle in `strings.xml`.

## 1. What Verbatide is

- **Verbatide** is a community fork of Phone Whisper — a push-to-talk dictation app for Android.
- Core (from upstream): floating overlay bubble via Accessibility Service, cross-app text injection (clipboard fallback), optional local on-device transcription via `sherpa-onnx`, chat-based cleanup.
- **Extended in this fork:** multi-provider cloud STT + chat, new STT models, per-provider model overrides, transcription history, user dictionary, encrypted API keys, ongoing security hardening.
- Installs from `https://github.com/julianrottenberg/verbatide/releases` (`verbatide-v*.apk`, single file per release since `v0.9.12`). `phone-whisper-fork` URL 301s to `verbatide`.
- Upstream sponsor: https://github.com/sponsors/kafkasl — keep the attribution visible.

## 2. Architecture (read these files first)

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

## 3. Providers (one-click, split STT/chat, per-provider model override)

Providers speak either:
- **OpenAI-compatible multipart** (`/v1/audio/transcriptions` + `/v1/chat/completions`, `Bearer`): OpenAI, Groq, OpenRouter, Together, Venice, Mistral (`voxtral-mini-latest` STT, model override editable), NanoGPT, Custom.
- **Queue-based** (`fal.ai Wizper`): `FalTranscriber` only, STT-only, `Key` auth, storage upload path.

Defaults currently:
- OpenAI: `openai/whisper-large-v3` STT / `openai/gpt-4o-mini` chat
- Groq: `whisper-large-v3-turbo` / `llama-3.3-70b`
- Together: `openai/whisper-large-v3` / `openai/gpt-oss-20b` (chat was switched via persistent override — check `ProviderConfig.TOGETHER_DEFAULTS` vs actual prefs `chat_model_override`)
- Venice/Mistral/NanoGPT: one-click entries added after Groq developer signups paused; Venice claims `$0.0001/s` for Wizper.
- `app Id` collisions checked with `web_search` for `openwhisper`, `speechlace`, `komorebi` — `Verbatide` was picked as untaken.

Model override: visible rows `STT model` + `Chat model` (tags `sttModelOverrideRow`/`chatModelOverrideRow`) + `Custom STT/Chat` URL rows when `provider==CUSTOM`. They write `stt_model_override`/`chat_model_override` — read *unconditionally before* the `CUSTOM` branch so they survive provider switches (early bug: only read when `p==CUSTOM`).

`MainActivity` refresh() also toggles `promptContainer/promptRow/reasoningRow` visibility when post-processing is on and updates both provider cards with `findViewWithTag`.

## 4. Build & CI (remote-only)

- **Host has no JDK/SDK** — `make build`/`./gradlew` on host will fail. Use `gh` + Actions.
- Workflow: `.github/workflows/build.yml` (`build` + `release`, `tags ["v*"]`, `workflow_dispatch`). Build: `assembleDebug` + `testDebugUnitTest` + `upload-artifact`. Release: same build + `softprops/action-gh-release@v2` attaching APK.
- **APK naming is currently broken** — `app/build.gradle.kts` `androidComponents` used `verbatide-v${'$'}{v.versionName}` which literalized to `verbatide-.v.versionName.apk` (v0.9.7). Attempted fix to `verbatide-` + `variant.versionName.get()` hit `Unresolved reference: versionName`. Reverted to a workflow copy step `cp app-debug.apk verbatide-v${VER}.apk` before upload/release. Latest releases are single-file `verbatide-v*.apk` since v0.9.12 (v0.9.11 shipped both `app-debug.apk` + `verbatide-v0.9.11.apk`). Revisit by fixing the AGP `androidComponents` correctly or keeping the `cp` rename — don't re-introduce the literal.
- Branch protection: repo is `julianrottenberg/verbatide` (redirect from `phone-whisper-fork`), `main` has ruleset `protect-main` — blocks deletion/`non_fast_forward` + requires `build` check. Solves earlier `Dependabot Updates` 404 on push (push checks are `build`, not dynamic Dependabot name).
- `main` currently `v0.9.12` (`27`) is `Latest`. Some tags had versionCode regressions (`v0.9.3` shipped `16/0.8.0` after a `git reset --hard 6ca4eed` rewind) — keep monotonic.

## 5. Fixed issues & decisions (for context)

- **Overlays / bubble**: `showOverlay` on wrong density path (`isCloud` swap), `onAccessibilityEvent` empty crash — fixed by bundling `Libs`.
- **CodeQL `DESC`** — added to toggle `sort` on `$defs`.
- **gpton / reasoning leak**: `analysisWe need to clean up...` preamble from `gpt-oss-20b` when `Reasoning=Off` — `applyReasoning` previously sent `reasoning` only for OpenRouter (`if (isOpenRouter)`). Fixed in `v0.9.5` to send `{"reasoning":{"enabled":false}}` for all providers when `Off`, and defaults `off` (was `default` → on-by-default for hybrids per Together's `Qwen3.5-9B`/`Qwen3.6 Plus`/`Gemma 4`/`Cogito`/`DeepSeek V4 Pro`/`MiniMax M3` docs).
- **Translation drift (German→English):** Together omitted `language` for `auto` → defaults to `en`, so even long German clips returned English with cleanup off (STT-level). Fixed to send `language="auto"` explicitly to Together (other providers keep omit). Also added verbose logging line + cleanup `languageHint` to `PostProcessor`, re-select preset note.
- **Transcription via overlay tap `cancelTranscription()`**: tap while `TRANSCRIBING` now cancels all calls + 75s `handler` timeout. `currentCall` tracking for `TranscriberClient`/`PostProcessor` so `cancel()` actually aborts.
- **Backup section**: old single `API key` row removed (keys inside provider sections since `v0.6.0`).
- **History**: `HistoryManager.remove()` didn't exist → `removeByTs(ts)` + private `save`.
- **Theme**: `Theme.Material3.DayNight` already had `values-night` — new History/Dictionary screens were hardcoding `0xFFF7F7FA` — converted to `attrColor`.
- **Bottom bar**: removed `HistoryActivity`/`DictionaryActivity` + manifest entries, embedded history/dictionary pages with `BottomNavigationView` (Material) + `historySearchRef`/`dictListRef` top-level lates. Initial impl blanked the settings page due to embedded `buildHistorySection()` still inside root scroll (`LP_MATCH` conflict) — removed the embedded copy. Final state: 3 tabs `Settings` (scroll)/`History`/`Dictionary` using separate pages in the current `MainActivity` code; the bottom-bar code is still evolving (see §6 open).
- **Rebrand Verbatide**: package move `com.kafkasl.phonewhisper` → `com.julianrottenberg.verbatide`, APK rename, `strings.xml` Verbatide, fork attribution row kept.
- **Renamed repo** `phone-whisper-fork` → `verbatide` (old URL 301s), `main` ruleset added.

## 6. Common missteps (don't repeat)

1. **Don't build locally** — no SDK. Push + tag, watch `gh run list --repo julianrottenberg/verbatide`.
2. **Don't use `/tmp/pwf`** — it was wiped on reboot. Use `~/projects/verbatide`.
3. **Version must bump monotonically** — `versionCode` and `versionName` in `app/build.gradle.kts`. The `v0.9.3` rollback proved it.
4. **Don't re-add `androidComponents` APK rename with `${'$'}` escapes** without testing — use the `cp` rename in the workflow (works) until you have a tested `androidComponents` form with `variant.versionName.get()` correctly scoped.
5. **Don't make `findViewWithTag` calls on the activity** — it's a `View` method. Use `findViewById<View>(android.R.id.content)?.findViewWithTag<...>(` or store row refs.
6. **Don't put helpers as `private fun MainActivity.xxx` outside the class** — they can't see `private fun dp/attrColor`. Keep them inside `MainActivity` or top-level non-private.
7. **Translation fix is two-layer** — STT `language` param + cleanup `languageHint`. Removing either re-introduces drift.
8. **Reasoning `Off` must send for Together** — not just OpenRouter. The `PostProcessor.Reasoning.OFF` label previously said `OpenRouter only; others ignore` — now it's `Disable reasoning`.
9. **History file is JSON array on disk** — `load()` sorts by `ts` desc; `removeByTs` rewrites the whole file. No DB migration.
10. **Keep the fork attribution** — `About Verbatide — Fork of Phone Whisper by kafkasl — tap to view on GitHub` under `Clear history`, plus `Built on Phone Whisper` subtitle.

## 7. Open / TODO

- APK rename is workflow-copied, not gradle-native — clean up when AGP API stabilizes (or pin to workflow rename).
- Bottom-bar wiring is in `MainActivity.kt` but the bottom bar currently renders at the top on some devices (reported). Might need `FrameLayout` + `BottomNavigationView` anchored with `CoordinatorLayout` or constraint, not a bare `LinearLayout` vertical.
- v0.9.12 is latest green; v0.9.6–v0.9.7 double-`v` APK already fixed.
- Check `gh pr list --repo julianrottenberg/verbatide` — Dependabot groups `gradle`/`github-actions` (11 PRs typical). Don't bulk-merge gradle majors.

## 8. How to work on this from GitHub + code only (fresh instance)

- `gh repo clone julianrottenberg/verbatide` — you have read access, push via PAT `gh auth`.
- Edit in `~/projects/verbatide` (or any worktree), keep `applicationId`/`namespace` as `com.julianrottenberg.verbatide` unless the user asks to rename again.
- Any provider change: update `ProviderConfig.kt` `Defaults`, `fromString`, `sttUrl/sttModel/chatUrl/chatModel` resolvers, and `MainActivity.kt` provider rows + `saveCustom` paths.
- Any prompt/cleanup change: `PostProcessor.kt` `DEV_PROMPT`/`SIMPLE_PROMPT` + `withLanguageGuard` + `MainActivity promptPresets()`.
- Any persistence: `SecurePrefs.kt` (keys) or `TranscriptionHistory.kt`/`Dictionary.kt`.
- Workflow: `.github/workflows/build.yml` — remember the `Rename APK to verbatide` copy steps precede `Upload APK`/`Create GitHub Release` and `files:` is `verbatide-*.apk` (with fallback).
- To hand off: push to `main` is allowed (ruleset bypass for admin), tag `vX.Y.Z`, monitor `gh run list`.

