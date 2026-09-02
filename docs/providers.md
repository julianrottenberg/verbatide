# Providers

(one-click, split STT/chat, per-provider model override)

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

## Adding a new one-click provider

1. Add `Provider` enum entry + `Defaults` in `ProviderConfig.kt`.
2. Extend `fromString`, `sttUrl`/`sttModel`/`chatUrl`/`chatModel` resolvers (remember `stt_model_override`/`chat_model_override` are checked first).
3. Add storage keys if it's queue-based (like `fal.ai Wizper` which needs `POST https://rest.fal.ai/storage/upload/initiate?storage_type=fal-cdn-v3` then `PUT` then `audio_url`).
4. Add UI row in `MainActivity.kt` provider picker and keep `saveCustom`/`save*Override` paths.
5. Update `docs/providers.md` + this table.

## History note on fal Wizper

`fal.ai Wizper` rejects `data:audio/wav;base64,...` with `{detail: Unsupported data URL}` — must use fal Storage signed URL as `audio_url`. See `FalTranscriber.kt`.
