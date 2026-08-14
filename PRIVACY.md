# Privacy Policy for Phone Whisper (fork: Groq / OpenRouter / Custom providers)

Phone Whisper is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

> This fork is based on [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper). Where this page says "provider," it means whichever cloud provider you have selected in Settings (OpenAI, Groq, OpenRouter, or a custom OpenAI-compatible endpoint). Upstream's policy is otherwise unchanged.

## Data handling

Phone Whisper supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to your selected provider's transcription API (`/v1/audio/transcriptions`) to generate text.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to your selected provider's chat API (`/v1/chat/completions`) to improve punctuation, capitalization, and clarity.

The specific host contacted depends on your **Cloud provider** setting in the app:

- **OpenAI** → `api.openai.com`
- **Groq** → `api.groq.com`
- **OpenRouter** → `openrouter.ai`
- **Custom** → the base URLs you entered

## API keys and custom endpoints

If you use cloud features, your API key (and, for the Custom provider, your STT/chat base URLs and model names) are stored locally on your device in app storage and used to authenticate requests sent directly to your selected provider.

I do not operate a relay server for these requests.

## Accessibility Service

Phone Whisper uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

Phone Whisper is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for Phone Whisper and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use (OpenAI, Groq, OpenRouter, or your custom endpoint) may process data according to their own terms and privacy policies.

## Contact (upstream)

For questions about the original app's privacy, contact upstream: pol.avms@gmail.com

For questions about this fork, open an issue at https://github.com/julianrottenberg/phone-whisper-fork/issues
