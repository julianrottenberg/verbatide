# Overview

> Fork of [Phone Whisper](https://github.com/kafkasl/phone-whisper) — push-to-talk dictation for Android.
> **Verbatide** keeps 100% of Phone Whisper's core and adds multi-provider cloud, new models, history, dictionary, and hardening.
> Upstream sponsor: https://github.com/sponsors/kafkasl

## What it is

- **Floating overlay bubble** via Accessibility Service — shows only on focused editable fields.
- **Cross-app injection** with clipboard fallback.
- **Local STT** via sherpa-onnx (offline), **cloud STT/chat** with split providers.
- **Providers:** OpenAI, Groq, OpenRouter, Together, Venice, Mistral (voxtral-mini-latest), NanoGPT, fal.ai Wizper, Custom OpenAI-compatible.
- **Transcription language** pin (prevents drift-to-English), per-provider **model overrides** (`stt_model_override`/`chat_model_override`).
- **History** (`TranscriptionHistory.kt`, capped 50 MB / 90 days) + **Dictionary** (prompt hint for STT).
- **Security:** `EncryptedSharedPreferences` for `stt_api_key`/`chat_api_key`, `allowBackup=false`, `network_security_config` loopback-only.
- **Rebrand:** Verbatide `com.julianrottenberg.verbatide`, APK `verbatide-vX.Y.Z.apk`, attribution to Phone Whisper by kafkasl.

## Source of truth

- Repo: `https://github.com/julianrottenberg/verbatide`
- Local persistent checkout: `~/projects/verbatide` (the old `/tmp/pwf` was ephemeral).
- Releases: `verbatide-v*.apk` single file per tag since `v0.9.12` (was `app-debug.apk` / `verbatide-.v.versionName.apk`).
- Sponsor upstream if Verbatide saves time.
