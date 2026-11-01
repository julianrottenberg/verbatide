<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Verbatide Logo">
</p>

# Verbatide — Phone Whisper evolved

> **Built on [Phone Whisper](https://github.com/kafkasl/phone-whisper) by [kafkasl](https://github.com/kafkasl)** — a push-to-talk dictation app for Android. Verbatide is a community fork that keeps 100% of Phone Whisper's core (floating overlay, accessibility injection, local `sherpa-onnx`, upstream bugfixes) and extends it with multi-provider cloud, new models, history, dictionary, and ongoing security hardening. If you like Verbatide, please also ⭐ and consider [sponsoring upstream](https://github.com/sponsors/kafkasl).

Verbatide lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.

**What's new in this fork:**

- **Groq** — `whisper-large-v3-turbo` STT + `llama-3.3-70b-versatile` chat, via `api.groq.com/openai/v1`
- **OpenRouter** — proxy STT + chat through OpenRouter (`openrouter.ai/api/v1`) with configurable model slugs
- **Together**, **Venice**, **NanoGPT**, **fal.ai** (Wizper STT) — one-click OpenAI-compatible entries
- **Mistral** — `voxtral-mini-latest` STT + `mistral-small-latest` cleanup via `api.mistral.ai`. A **Mistral Vibe subscription works here**: Mistral plans are global, so the same included monthly usage and API key cover Vibe, Studio, and the plain API (`docs.mistral.ai/admin/billing-usage/subscriptions`). Create a key on La Plateforme, pick **Mistral** in Settings, done.
- **MiniMax** — `asr-1.0` speech-to-text (`api.minimax.io/v1/speech_to_text`) + `MiniMax-M3.1-Flash` cleanup (`/v1/chat/completions`, OpenAI-compatible). Auth is the JWT from your MiniMax platform account. Two quirks are handled for you: ASR takes the language as an HTTP header, and M3.1 always thinks — cleanup pins `reasoning_effort=low`, the fastest setting.
- **Custom** — point STT and chat at any OpenAI-compatible base URL (self-hosted, proxy, other provider) with per-endpoint model names. **https only**, except `http://localhost` and `http://127.0.0.1` — the app's network policy refuses cleartext to anything else, so a LAN box needs TLS (or a tunnel).
- **Provider picker in Settings** — radio group + detail rows; API key hint/label adapts to the selected provider
- **Provider chains** — transcription and cleanup each get their own ordered list (e.g. `Mistral → MiniMax → Groq`), so one dry account rolls over to the next instead of handing you an error. Chains are independent: you can fail over for STT while cleanup stays on your favourite model.
- **Daily usage check** — re-probes every chat-capable chain entry at most once per 24 h (one tiny chat request each) and un-blocks it as soon as a top-up went through. It runs when the overlay service starts, when a dictation begins, and when you open Settings — never on a transcription's critical path, so it cannot cost you latency. Transcription-only providers (`fal.ai`) have no chat endpoint to probe and recover on their 24 h timer instead. The **Usage check** row shows the last result and has a **Check now** button.
- **Activity log** — a row in Settings lists the last 60 dictations with provider, fallback depth, timings and the text that was actually inserted. **Diagnostic log** is the companion for "why did that fail": one line per provider attempt with the HTTP status and the raw error, plus what the usage check saw.
- **Keep audio & re-transcribe** — opt in and each dictation's WAV is kept (cap configurable) so **Re-transcribe** in History can re-run the exact audio through your current chain, e.g. after switching to a better model. Off by default; the files are deleted when the entry leaves the history.
- **Benchmark** — Settings replays one of your own dictations (turn **Keep audio** on first) against every provider in your chains — the kept WAV to each transcription entry, the resulting text to each cleanup entry — and prints a ranked table with success, latency and error. Useful before deciding which entry deserves to be primary.
- **Portable settings** — Export writes one JSON with every endpoint, prompt, tone, provider chain and retention preference (and, if you ask for it, your API keys — flagged as a plaintext file); Import restores it, and refuses a file that would both move a custom endpoint and reshuffle the provider selection.

The underlying HTTP contract is identical across all providers (`/v1/audio/transcriptions` multipart + `/v1/chat/completions` JSON with `Bearer` auth), so switching is just a base-URL + model-name swap.

Upstream features preserved as-is:

- **Local on-device transcription** with sherpa-onnx
- **Floating overlay bubble** + accessibility text injection
- **Optional cleanup** (configurable prompt) via chat completions

If this fork saves you time, consider sponsoring upstream: [github.com/sponsors/kafkasl](https://github.com/sponsors/kafkasl)

---

## Why this fork

See upstream's [Why I built this](#why-i-built-this) below. The short version: the original app only spoke to `api.openai.com`. Groq and OpenRouter are OpenAI-compatible but need different base URLs + model names — and some people want to hit a self-hosted endpoint entirely. This fork makes that a Settings toggle.

---

## Provider setup

1. In **Settings**, pick the STT and cleanup providers (**OpenAI**, **Groq**, **OpenRouter**, **Together**, **Venice**, **Mistral**, **MiniMax**, **NanoGPT**, **fal.ai**, or **Custom**).
2. For **Custom**, tap through the four endpoint/model rows to set:
   - STT endpoint (default: OpenAI's)
   - STT model name
   - Chat endpoint
   - Chat model name
   Leave any field blank to fall back to the OpenAI default for that slot.
3. Set your **API key** — the row label + hint (`sk-…` / `gsk_…` / `sk-or-…` / MiniMax's `JWT from platform.minimax.io`) adapts to the selected provider. Keys are stored per provider and per half of the pipeline (transcription, cleanup), so each chain entry can live on a different account. A key saved before per-provider keys existed keeps working as the primary provider's key.
4. Toggle **Use cloud transcription** on and pick your cleanup prompt under **Post-Processing** as usual.

Groq model defaults: STT `whisper-large-v3-turbo`, chat `llama-3.3-70b-versatile`.
OpenRouter defaults: STT `openai/whisper-large-v3`, chat `openai/gpt-4o-mini` (override with any OpenRouter slug).

### Provider chains (fallback)

Running out of credits mid-day should not hand you an error instead of your transcript.

1. Under **Transcription** and **Cleanup**, tap the chain row to see the ordered list. The first entry is the one used for every request.
2. **Add provider** appends to the end of that chain; the per-entry menu has **Make primary**, **Move up**, **Move down** and **Remove**. A chain always keeps at least one entry, and only providers that speak that half of the pipeline are offered (`fal.ai` does STT only).
3. Give each entry a key (each provider row has its own key dialog) — each provider keeps a separate key for transcription and for cleanup, so a revoked STT key never takes that provider's cleanup down with it.

Every dictation walks the chain in order and retries on the next entry only for failures a different account can actually fix: `402` (out of quota), `401`/`403` (key rejected), or a provider that answered with nothing at all. The empty-result case switches without pausing the provider, since a quiet room looks the same as an ASR that dropped the audio. Rate limits (`429`) and server errors do **not** switch — those are transient. You get a short "Mistral unavailable — trying MiniMax" toast when it happens, and nothing extra in your way when it doesn't.

A provider that hit a wall is skipped for 24 h, so later dictations go straight to the next entry instead of paying for a request that is certain to fail. The daily usage check probes every chat-capable chain entry — on service start, when a dictation begins, or when you open Settings, never on the transcription path itself — and un-blocks it as soon as a top-up landed. **Usage check** shows when it last ran, and **Check now** runs it immediately.

---

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed locally or in the cloud (selected provider's `/v1/audio/transcriptions`)
5. Optionally, the transcript is cleaned up via the provider's `/v1/chat/completions`
6. The text is inserted into the focused text field; clipboard fallback if injection fails

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/julianrottenberg/verbatide/releases). CI builds a signed release APK on every release.

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/julianrottenberg/verbatide.git && cd verbatide
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## Setup

### First-time setup

1. Open **Verbatide**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: pick a provider and paste your API key

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Verbatide uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

This fork does not run a backend. In cloud mode, requests go straight from your phone to your selected provider using your own API key.

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to the configured provider's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to the configured provider's chat API
- Your API key and custom endpoints are stored locally on-device in app storage
- **Optional audio retention** (off by default): when enabled, each dictation's WAV stays in app storage so it can be re-transcribed. Files are capped, pruned with the history, and deleted when the entry is removed — but they are not encrypted.

Full upstream policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.julianrottenberg.verbatide/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

Verbatide works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Verbatide falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Verbatide in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own API key

---

# Upstream

The sections below are from upstream's README.

## Why I built this (upstream)

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Support the project

If Verbatide saves you time, you can sponsor the upstream project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it. See upstream [LICENSE](LICENSE) if present.
