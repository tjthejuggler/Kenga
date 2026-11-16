# Kenga — Architecture (v1.0.0)

**Package:** `com.example.kenga` · **Workspace:** `/home/twain/AndroidStudioProjects/Kenga`
**Plan (source of truth):** [`plans/kenga_architecture.md`](../plans/kenga_architecture.md)
**Reference apps:** Octo (YouTube→analysis pipeline, body map, stats, theme), Tail (weights habits, PB engine, IPC protocol).

## Overview

Kenga is a weight-lifting companion:

1. **Share YouTube videos in** → download (NewPipeExtractor) → transcript → LLM analysis extracting **muscle groups + exercise names**.
2. **Tail integration** — each muscle group maps to one Tail weights habit; logging a set writes a real weights entry into Tail via broadcast (protocol v6) and read-back via the provider (v3 endpoints).
3. **Local-first logging** — sets are always persisted in Room; PENDING rows flush to Tail when it returns; PBs are computed locally (union of Tail snapshot + local logs) so the PR flash works even offline.
4. **Body screen (home)** — kangaroo body map with Octo's tier colouring (sets logged today per muscle group).
5. **Stats** — Octo-style ranges/streaks/totals plus volume + per-exercise filtering.

## Stack

- AGP 9.4.1 · Kotlin 2.2.10 · Compose BOM 2026.02.01 · compileSdk/target 37 · minSdk 26 · Java 11
- Room (v2 schema exported to `app/schemas/`), WorkManager (on-demand init + worker DI), Navigation-Compose, Media3, DataStore Preferences, kotlinx-serialization, Coil, OkHttp, NewPipeExtractor `v0.26.5` (JitPack)
- versionName **1.0.0**, versionCode **1**

## Package map

```
MainActivity / KengaApplication (manual DI: AppContainer) / KengaWorkerFactory
data/local/      KengaDatabase (v2), VideoEntity, SetLogEntity, ExerciseEntity, TailSnapshotEntity, PbEntity, MuscleGroup (canonical 16), DAOs
data/repository/ VideoRepository (+ editing & export/import), SetLogRepository, ExerciseRepository, PbRepository
data/settings/   SettingsRepository (DataStore: LLM, Tail map, unit)
data/weights/    WeightUnits, PrLogic
ipc/             TailClient (v6 log broadcast, v3 provider queries, PB union)
youtube/         ShareIngestActivity, YoutubeUrlParser, YoutubeMetaFetcher, TranscriptFetcher
downloader/      VideoDownloadManager
llm/             LlmClient, ExerciseAnalysisPipeline, LlmHeuristics, VideoFrameExtractor
work/            DownloadVideoWorker, ReanalyzeLibraryWorker, TailSyncWorker, DownloadProgress
player/          PlayerManager (Media3)
ui/body/         BodyScreen, BodyViewModel, KangarooBodyMap, BodyTierColors
ui/library/      LibraryScreen, LibraryViewModel, VideoDetailScreen (editable chips), VideoPlayerPane
ui/log/          LogScreen, LogViewModel, WeightsLogDialog, QuickLog, PrFlash
ui/stats/        StatsScreen, StatsViewModel, StatsMath
ui/settings/     SettingsScreen, SettingsViewModel (LLM, Tail mapping, storage/export)
ui/navigation/   KengaBottomBar, KengaDestinations, KengaNavHost
ui/components/   GlassCard, KengaBackground, KengaHeader, TwoRowChips
ui/theme/        outback palette Color/Theme/Type
```

## Tail v6 protocol summary

- **Log (Kenga → Tail):** broadcast `com.example.tail.ACTION_LOG_WEIGHTS_ENTRY`, extras `EXTRA_HABIT_ID` (habit name), `EXTRA_EXERCISE_NAME`, `EXTRA_WEIGHT_GRAMS`, `EXTRA_REPS`, `EXTRA_MACHINE`, `EXTRA_DATE` (`yyyy-MM-dd`), `EXTRA_SOURCE="kenga"`. Delete/correction = same broadcast with negative reps (max-merge weight handling documented in `SetLogRepository`).
- **Read (Tail → Kenga):** provider `content://com.example.tail.provider/v3/weights` (+ `/pbs`, `/recent_exercises`), guarded by the `TAIL_INTEGRATION` signature permission; `v2/capabilities` advertises `"weights_v3"`.
- **Local queue:** Tail-absent `SecurityException` keeps the row PENDING in Room; `TailSyncWorker` (unique one-shot + periodic, KEEP) re-flushes and pulls a fresh snapshot (TailSnapshotEntity/PbEntity) on app start, after each log, and on Tail change broadcasts.
- **⚠️ Release signing:** Kenga must be **signed with Tail's keystore** (the `TAIL_INTEGRATION` permission is signature-level — a differently-signed Kenga build cannot talk to Tail). The debug keystore already matches; release builds must configure Tail's signing config.

## Phase history

1. ✅ Skeleton (gradle + JitPack, outback theme, nav + bottom bar, Room v1, manifest)
2. ✅ Share-in & library (ShareIngestActivity, URL parsing, oEmbed meta, library UI)
3. ✅ Download & player (NewPipeExtractor pipeline, DownloadVideoWorker, Media3 player)
4. ✅ Transcript + LLM (TranscriptFetcher, LlmClient, ExerciseAnalysisPipeline, heuristics fallback, reanalyse worker, settings LLM UI; DB v2)
5. ✅ Tail v6 receiver + provider endpoints (Tail repo)
6. ✅ Kenga TailClient (habits query, v6 log + PENDING queue, v3 snapshot refresh, PB engine, settings Tail section)
7. ✅ Log tab (Tail-style dialog, day list w/ delete+undo, PR flash, exercise catalog)
8. ✅ Body screen (kangaroo art + map composable, tier colouring, region dialogs)
9. ✅ Stats (ranges, streaks, volume, exercise filter chips)
10. ✅ Polish & E2E (video-detail exercise/muscle editing, quick-log wiring, library export/import + storage stats, process-death sweep, docs)

### Art generation

Kangaroo body assets were generated one-off with [`scripts/gen_kanga_art.py`](../scripts/gen_kanga_art.py) (PPQ Grok scripts pattern, see [`docs/kenga_art.md`](kenga_art.md)) and committed; geometry zones are defined in code (`KangarooBodyMap.kt`). The script is not part of the build.

## Robustness (process-death sweep)

- Orphaned `PENDING_DOWNLOAD`/`DOWNLOADING` rows are re-kicked on app start (unique-KEEP per videoId → idempotent).
- Stale `RUNNING` (analyzing) rows are reset to `DOWNLOADED` on app start so reanalysis can run.
- PENDING SetLogs with Tail present are re-flushed by `TailSyncWorker` on start + periodically.
- `QuickLog.open()` ignores duplicate-consecutive requests (no double dialogs).
- Unique work names do not collide: `download_video:<videoId>`, `kenga_reanalyze_library`, `tail_sync_once`, `tail_sync_periodic`.

## Manual E2E checklist (needs a real device + Tail installed)

Cannot be verified without UI automation / live services — run before release:

- [ ] Share a YouTube Short from the YouTube app → Kenga appears in share menu → row lands in Library and downloads.
- [ ] With an LLM key saved: analysis completes → muscle + exercise chips appear on the card and detail.
- [ ] No LLM key: heuristic fallback still labels muscles/exercises.
- [ ] Video detail: rename an exercise (autocomplete works, Tail names first); "apply to all videos" renames every matching row; muscle multi-select rewrites the chips.
- [ ] "Log" chip on a video exercise → Log tab opens with the dialog pre-selected.
- [ ] Log a set with Tail installed → entry appears in the mapped Tail habit; PR flash fires on a record; delete + undo sends the correction.
- [ ] Kill Tail → log sets (stay PENDING) → reinstall/open Tail → sets flush on sync.
- [ ] Body map tiers update today after logging; Stats ranges/exercise filter correct; kg↔lb toggle converts.
- [ ] Settings: Export library → file contains rows; wipe app data → Import → rows return as PENDING_DOWNLOAD and re-download.
- [ ] Kenga **release** build signed with Tail's keystore → Tail IPC still authorized.
