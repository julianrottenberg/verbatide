
# Troubleshooting

## Fixed issues (context)

(for context)

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

## Common missteps (don't repeat)

(don't repeat)

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

## Open / TODO

- APK rename is workflow-copied, not gradle-native — clean up when AGP API stabilizes (or pin to workflow rename).
- Bottom-bar wiring is in `MainActivity.kt` but the bottom bar currently renders at the top on some devices (reported). Might need `FrameLayout` + `BottomNavigationView` anchored with `CoordinatorLayout` or constraint, not a bare `LinearLayout` vertical.
- v0.9.12 is latest green; v0.9.6–v0.9.7 double-`v` APK already fixed.
- Check `gh pr list --repo julianrottenberg/verbatide` — Dependabot groups `gradle`/`github-actions` (11 PRs typical). Don't bulk-merge gradle majors.
