<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper — Fork (Groq / OpenRouter / Custom providers)

> Fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) — push-to-talk dictation for Android with support for **OpenAI**, **Groq**, **OpenRouter**, and any **custom OpenAI-compatible** endpoint.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.

**What's new in this fork:**

- **Groq** — `whisper-large-v3-turbo` STT + `llama-3.3-70b-versatile` chat, via `api.groq.com/openai/v1`
- **OpenRouter** — proxy STT + chat through OpenRouter (`openrouter.ai/api/v1`) with configurable model slugs
- **Custom** — point STT and chat at any OpenAI-compatible base URL (self-hosted, proxy, other provider) with per-endpoint model names
- **Provider picker in Settings** — radio group + detail rows; API key hint/label adapts to the selected provider

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

1. In **Settings → Cloud provider**, pick **OpenAI**, **Groq**, **OpenRouter**, or **Custom**.
2. For **Custom**, tap through the four endpoint/model rows to set:
   - STT endpoint (default: OpenAI's)
   - STT model name
   - Chat endpoint
   - Chat model name
   Leave any field blank to fall back to the OpenAI default for that slot.
3. Set your **API key** — the row label + hint (`sk-…` / `gsk_…` / `sk-or-…`) adapts to the provider. One key is used for both STT and cleanup (use a single provider for both, or Custom if you want to split them across URLs).
4. Toggle **Use cloud transcription** on and pick your cleanup prompt under **Post-Processing** as usual.

Groq model defaults: STT `whisper-large-v3-turbo`, chat `llama-3.3-70b-versatile`.
OpenRouter defaults: STT `openai/whisper-large-v3`, chat `openai/gpt-4o-mini` (override with any OpenRouter slug).

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

Grab the latest APK from [GitHub Releases](https://github.com/julianrottenberg/phone-whisper-fork/releases) (once CI is wired up).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/julianrottenberg/phone-whisper-fork.git && cd phone-whisper-fork
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

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: pick a provider and paste your API key

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

This fork does not run a backend. In cloud mode, requests go straight from your phone to your selected provider using your own API key.

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to the configured provider's transcription API
- **Optional cleanup**: transcript text is sent directly from your device to the configured provider's chat API
- Your API key and custom endpoints are stored locally on-device in app storage

Full upstream policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
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

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

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

If Phone Whisper saves you time, you can sponsor the upstream project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it. See upstream [LICENSE](LICENSE) if present.
