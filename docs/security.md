# Security

See `HANDOFF.md` §5 + `SECURITY.md` for the full audit.

- `app/debug.p12` is a **debug** keystore (pass `android`) committed so CI signs identically → in-place updates. Losing it means a one-time uninstall.
- `EncryptedSharedPreferences` for `stt_api_key` / `chat_api_key` / `api_key` (MasterKey AES256_GCM); plain `phonewhisper` prefs for the rest.
- `allowBackup=false` + `fullBackupContent` + `dataExtractionRules` (minsdk 29+) exclude `phonewhisper.xml` / `phonewhisper_secure.xml`.
- `network_security_config.xml` — cleartext only for loopback.
- Accessibility: narrow flags (`typeViewFocused|typeWindowStateChanged|typeWindowsChanged`, `flagRetrieveInteractiveWindows|flagIncludeNotImportantViews`, `canRetrieveWindowContent=true`).

Keep `SECURITY.md` alongside `HANDOFF.md` for a fresh instance.
