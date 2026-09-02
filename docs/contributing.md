
# Contributing — from a fresh checkout

from GitHub + code only (fresh instance)

- `gh repo clone julianrottenberg/verbatide` — you have read access, push via PAT `gh auth`.
- Edit in `~/projects/verbatide` (or any worktree), keep `applicationId`/`namespace` as `com.julianrottenberg.verbatide` unless the user asks to rename again.
- Any provider change: update `ProviderConfig.kt` `Defaults`, `fromString`, `sttUrl/sttModel/chatUrl/chatModel` resolvers, and `MainActivity.kt` provider rows + `saveCustom` paths.
- Any prompt/cleanup change: `PostProcessor.kt` `DEV_PROMPT`/`SIMPLE_PROMPT` + `withLanguageGuard` + `MainActivity promptPresets()`.
- Any persistence: `SecurePrefs.kt` (keys) or `TranscriptionHistory.kt`/`Dictionary.kt`.
- Workflow: `.github/workflows/build.yml` — remember the `Rename APK to verbatide` copy steps precede `Upload APK`/`Create GitHub Release` and `files:` is `verbatide-*.apk` (with fallback).
- To hand off: push to `main` is allowed (ruleset bypass for admin), tag `vX.Y.Z`, monitor `gh run list`.

## After cloning

```bash
gh repo clone julianrottenberg/verbatide
# or: git clone https://github.com/julianrottenberg/verbatide.git
cd verbatide
# edit ~/projects/verbatide — not /tmp/pwf (ephemeral)
```

- Keep `applicationId`/`namespace` as `com.julianrottenberg.verbatide` unless the user asks to rename.
- Any provider change: `ProviderConfig.kt` + `MainActivity.kt`.
- Any prompt/cleanup change: `PostProcessor.kt` + `MainActivity promptPresets()`.
- Any persistence: `SecurePrefs.kt` / `TranscriptionHistory.kt` / `Dictionary.kt`.
- Workflow: `.github/workflows/build.yml` (rename `verbatide-*.apk` copy precedes upload/release).

See `AGENTS.md` / `docs/ARCHITECTURE.md` equivalent in this folder.
