# Kenga — Weight-Lifting Companion App: Architecture Plan

**Date:** 2026-10-24
**Status:** DRAFT for user approval
**Workspace:** `/home/twain/AndroidStudioProjects/Kenga` (fresh Compose template, package `com.example.kenga`)
**Reference apps:** Octo (YouTube→analysis pipeline, body map, stats, theme) and Tail (weights habit type, PB engine, IPC protocol)

---

## 1. Product Summary

Kenga is a weight-lifting companion:

1. **Share YouTube Shorts → download → analyse** exactly like Octo, but analysis extracts **muscle groups AND exercise names** (not just body parts).
2. **Full Tail integration via the Weights special habit type.** Each muscle group maps to one Tail weights habit. Logging a set in Kenga writes a real weights entry (exercise name + weight + reps + machine/free) into Tail.
3. **Full read-back from Tail:** exercise names, history, and per-exercise personal bests flow back into Kenga; Kenga shows current PBs and flashes when a PB is broken (same semantics as Tail).
4. **Body screen as home** — a kangaroo body map with Octo's exact tier coloring system (sets logged per muscle group).
5. **Stats page** structured like Octo's (ranges, tier colors, streaks), plus per-exercise filtering like Tail's graph screen.

### Confirmed design decisions (user)
- **Muscle split:** one Tail weights habit **per muscle group** (e.g. habits "Chest", "Back", "Legs"); Kenga maps muscle group → Tail habit and logs each set to that habit.
- **Tail extension approved:** add a new Tail broadcast (`log weights entry` incl. exercise name) and a provider read endpoint for weights history/PBs. Tail-side work is part of this project.
- **Logging UX:** Tail-style log dialog (exercise chips + muscle group, kg/lb × reps, Machine/Free) available anywhere via the Log tab; mirrors to the mapped muscle-group habit.

---

## 2. What we reuse from Octo (port, rebrand)

| Octo component | Kenga use | Change |
|---|---|---|
| `ShareIngestActivity` + `YoutubeUrlParser` (regex, video-id extraction) | identical | package rename |
| `VideoDownloadManager` + `DownloadVideoWorker` (NewPipeExtractor via JitPack, muxed-first MP4, dual-stream fallback) | identical | package rename |
| `YoutubeMetaFetcher` (oEmbed) + `TranscriptFetcher` (timedtext json3 → extractor subtitles → title/desc) | identical | package rename |
| `VideoFrameExtractor` + `LlmClient` (OpenAI-compatible; default z.ai `glm-4.6`; chunking; `json_object` response) | identical transport | new prompt/schema (§4) |
| `BodyPartAnalysisPipeline` + `ReanalyzeLibraryWorker` | extended | muscles + exercises |
| Media3 player + `SessionPlayerManager` | optional playback | videos are reference material; no session→habit increments (weights logging replaces it) |
| Room DB pattern (`OctoDatabase`, migrations) | reused shape | new entities (§5) |
| Theme (`GlassCard`, `OctoBackground`, `OctoHeader`, `TwoRowChips`) + dark/light | reused | outback color palette, kangaroo branding |
| `BodyTierColors` tier system (idle red→orange→green→blue→pink→yellow→white; `usageTierColor` ramp) | **identical values** | none — same color semantics |
| Stats (`StatsScreen`, `StatsViewModel`, `StatsMath` ranges/streaks) | reused shape | metrics per muscle group / exercise |
| WorkManager factory, keep rules, settings (LLM base/key/model + Test) | identical | + Tail mapping + unit |

**Stack (match Octo):** AGP 9.4.1, Kotlin 2.2.10, Compose BOM 2026.02.01, compileSdk/target 37, minSdk 26, Java 11; deps: Room, WorkManager, Navigation-Compose, Media3, DataStore, kotlinx-serialization, Coil, OkHttp, NewPipeExtractor `v0.26.5` (JitPack repo).

---

## 3. Muscle-group taxonomy

Reuse Octo's 16 canonical ids (`neck, shoulders, chest, upper_back, lower_back, core, obliques, lats, hips_glutes, groin_adductors, quads, hamstrings, calves, ankles_feet, wrists_forearms, biceps_triceps`) — they map 1:1 onto kangaroo body-map zones and stay LLM-compatible with Octo's proven prompt. For **Tail habit mapping** the settings screen offers all 16, with sensible defaults suggested for the big six (chest, upper_back/lats, shoulders, biceps_triceps, core, quads/hamstrings) — user maps whichever groups they keep as weights habits in Tail.

---

## 4. LLM analysis — muscles + exercises

Prompt (canonical, weights-oriented):

- Input: transcript (+ title/channel), **plus the known-exercise list for the detected muscle groups pulled from Tail** (recent exercise names per mapped habit, from the new read endpoint) so the LLM prefers the user's own naming.
- Output JSON (schema-validated, unknown muscle ids dropped):
```json
{
  "muscle_groups": ["chest","triceps"],
  "exercises": [{"name":"Bench Press","muscle_group":"chest","confidence":0.9}],
  "confidence": 0.85,
  "notes": ""
}
```
- Chunked transcripts union muscles; exercises deduped case-insensitively.
- Fallback with no LLM key: keyword heuristic over transcript/title for both muscles and common exercise names.
- **Exercise renaming is first-class:** video detail shows detected exercises as editable chips (autocomplete from Tail's known names + free text); edits persist to the video row and optionally merge into the local catalog. One tap to fix a wrong name.

---

## 5. Kenga data model (Room)

- `VideoEntity` — videoId, url, title, channel, durationMs, thumbnail/video file paths, downloadedAt, analysis state, `muscleGroups` CSV, `exercises` JSON.
- `SetLogEntity` — id, ts, muscleGroupId, exerciseName, weightGrams, reps, machine, date, tailSyncState (PENDING/SENT/FAILED/offline).
- `ExerciseEntity` — name (unique, ci), muscleGroupId, source (LLD/TAIl/MANUAL), lastUsedAt, aliases.
- `TailSnapshotEntity` — habitName, date, machineWeightG, machineReps, freeWeightG, freeReps, machineExercise, freeExercise (cached Tail history for offline stats/body screen).
- `PbEntity` — exerciseName (ci), habitName, bestWeightGrams, bestReps, bestWeightDate, bestRepsDate (mirror computed from Tail history + local logs).

DB version 1; export schemas like Octo; migration tests optional later.

---

## 6. Tail integration — protocol v6 (extends the Wags/Octo/JugCoach protocol)

### 6.1 Tail-side changes (in `/home/twain/AndroidStudioProjects/tail`, ipc package)

**New receiver `WeightsEntryReceiver`** (mirrors `JugCoachSessionReceiver` precedent):
- Action `com.example.tail.ACTION_LOG_WEIGHTS_ENTRY`, guarded by `TAIL_INTEGRATION` signature permission.
- Extras: `EXTRA_HABIT_ID` (habit name), `EXTRA_EXERCISE_NAME` (String), `EXTRA_WEIGHT_GRAMS` (Int), `EXTRA_REPS` (Int), `EXTRA_MACHINE` (Boolean), `EXTRA_DATE` (optional `yyyy-MM-dd`, default today), `EXTRA_SOURCE` (informational "kenga").
- Behaviour — exactly the semantics of `HabitViewModel.saveWeightsEntry` but via repositories (receivers can't touch the VM): validate habit is a weights habit (`settings.weightsHabits`), apply slot writes via `HabitsRepository.applyWeightsSlotsToDb` (weight max-merge, reps accumulate, +1 count), `WeightsExerciseRepository.setExerciseName`, then `WeightsRecordsRepository.evaluateAndRecord` for the PR check and fire the in-app PR flash via `HabitIncrementBus` / `HabitViewModel` event path used by broadcasts (follow how JugCoachSessionReceiver notifies UI). Emits existing `ACTION_ENTRY_ADDED` change broadcast.
- Manifest: register receiver, exported, permission-guarded.

**New provider endpoint (v3 weights read)** in `HabitsContentProvider`:
- `content://com.example.tail.provider/v3/weights` → columns: habit_name, date, machine_weight_grams, machine_reps, free_weight_grams, free_reps, machine_exercise, free_exercise. Full history for weights habits only.
- `content://com.example.tail.provider/v3/weights/pbs` → per (habit, machine, exercise): best_weight_grams, best_weight_date, best_reps, best_reps_date, last_weight_grams, last_reps, last_date — computed by the same join `getWeightsExerciseStats` uses.
- `content://com.example.tail.provider/v3/weights/recent_exercises` → habit_name, exercise_name, last_used (the `weightsRecentExercises` map).
- Read-only, same signature permission, registered in `uriMatcher`; bump `v2/capabilities` features list with `"weights_v3"` so Kenga can feature-detect.

> Implementation note: the Tail repo is a sibling directory; if the workspace restricts writes outside Kenga, emit the Tail changes as patch files under `kenga/tail_patches/` with apply instructions.

### 6.2 Kenga-side `ipc/TailClient`

- `queryHabits()` — v1 provider (habit picker).
- `logWeightsEntry(habit, exercise, grams, reps, machine, date)` — v6 broadcast; `SecurityException` → NO_TAIL, entry stays local with PENDING state and a retry/flush when Tail returns (WorkManager backoff).
- `refreshWeightsSnapshot()` — pull v3 endpoints into `TailSnapshotEntity`/`PbEntity`; triggered on app start, after each log, and on `v2/changes` ContentObserver notification.
- **PB evaluation locally too** (union of Tail snapshot + pending local logs) so the PR flash fires even when Tail is absent; when Tail present, Tail's own PR flash fires as well — same record semantics, no double counting (records are derived, not incremented).
- Manifest: `uses-permission TAIL_INTEGRATION`, `<queries>` for tail package/provider (exact Wags pattern); debug keystore already matches; release must share Tail's keystore (checklist item).

### 6.3 Settings → Tail section

Per-muscle-group habit picker (16 rows: muscle label → Tail habit dropdown from `queryHabits()`, default "none"), unit toggle kg/lb, "Test Tail" button, Tail-absent banner. Pulls current exercise names from Tail to seed the autocomplete everywhere.

---

## 7. Screens (bottom nav: Body · Library · Log · Stats · Settings)

### 7.1 Body (home) — kangaroo map
- Port Octo's body-map composable: front/back flip, tap zones, dialog per region.
- Art: kangaroo head + body (front/back), generated one-off via the PPQ Grok scripts pattern (Octo `scripts/gen_head_art*.py`) → committed PNGs; geometry zones defined in code (Octo's `gen_body_geometry.py` pattern).
- Tier coloring identical to Octo: count = sets logged **today** per muscle group (local + Tail read-back).
- Region dialog: today's sets, volume, per-exercise PBs for that group, quick "Log set here" shortcut.

### 7.2 Library
- Octo library pattern: cards with thumbnail, title, muscle chips + exercise chips, analysis state, filter by muscle group/exercise, delete, reanalyse.
- Video detail: player, detected muscles (preview one-tier-ahead coloring like Octo), **editable exercise chips** (rename w/ autocomplete from Tail names), "Log set" quick action pre-selecting the first exercise.

### 7.3 Log (new vs Octo)
- Tail-style dialog port of `WeightsInputDialog`: muscle group selector (defaults from current body focus/most recent), exercise field with chips (recent names from Tail + local catalog), kg/lb toggle, weight, reps, Machine/Free; live PB + last-set readout under the exercise field; confirm → Room insert + Tail broadcast.
- Day list below: today's sets (swipe/undo delete with compensating Tail correction — v6 receiver accepts negative-reps correction entry or a DELETE day extra; simplest: correction broadcast with negative reps and max-merge weight handling documented).
- PR flash overlay (port of `WeightsPrFlash`, gold/purple, 7s auto-dismiss).

### 7.4 Stats
- Octo stats structure: range selector 7D/30D/90D/ALL, per-muscle-group usage bars with `usageTierColor`, streaks, totals.
- Additional series: volume (kg lifted) and sets per muscle group; exercise filter chips (Tail graph pattern) narrowing any series; kg/lb toggle.

### 7.5 Settings
- LLM section (z.ai default, Test button) — Octo port.
- Tail section (§6.3).
- Storage stats, export/import library JSON (Octo pattern), about.

---

## 8. Package structure (`com.example.kenga`)

```
MainActivity.kt / KengaApplication.kt / KengaWorkerFactory.kt
data/local/      KengaDatabase, entities, daos
data/repository/ VideoRepository, SetLogRepository, ExerciseCatalogRepository
data/settings/   SettingsRepository (DataStore)
ipc/             TailClient (v6 + v3), TailSyncWorker
youtube/         ShareIngestActivity, YoutubeUrlParser, YoutubeMetaFetcher, TranscriptFetcher
downloader/      VideoDownloadManager
llm/             LlmClient, ExerciseAnalysisPipeline, LlmHeuristics, VideoFrameExtractor, MuscleGroups.kt
work/            DownloadVideoWorker, ReanalyzeLibraryWorker, TailSyncWorker
player/          PlayerManager (Media3)
ui/body/         BodyScreen, BodyViewModel, BodyTierColors, KangarooMap
ui/library/      LibraryScreen, LibraryViewModel, VideoDetail
ui/log/          LogScreen, LogViewModel, WeightsLogDialog, PrFlash
ui/stats/        StatsScreen, StatsViewModel, StatsMath
ui/settings/     SettingsScreen, SettingsViewModel
ui/navigation/   KengaBottomBar, Destinations, NavHost
ui/components/   GlassCard, KengaBackground, KengaHeader, TwoRowChips
ui/theme/        Color (outback palette), Theme, Type
```

Manifest: INTERNET; TAIL_INTEGRATION uses-permission; `<queries>` tail; ShareIngestActivity SEND/text-plain filter (Octo pattern).

---

## 9. Implementation phases (Orchestrator → CMM-Code tasks only)

1. **Skeleton:** gradle deps (+JitPack), theme (outback palette + Octo tier colors verbatim), nav + bottom bar, Room DB v1, manifest groundwork.
2. **Share-in & library:** ShareIngestActivity, URL parsing, meta fetch, library list UI, Room persistence.
3. **Download & player:** NewPipeExtractor pipeline, DownloadVideoWorker, thumbnails, Media3 player screen, delete/export.
4. **Transcript + LLM:** TranscriptFetcher, LlmClient, ExerciseAnalysisPipeline (muscles+exercises, Tail-name-aware prompt), heuristics fallback, reanalyse worker, settings LLM UI.
5. **Tail v6 (Tail repo):** WeightsEntryReceiver + provider v3 endpoints + capabilities flag + manifest; tests if feasible.
6. **Kenga TailClient:** habits query, v6 log broadcast w/ PENDING queue + retry worker, v3 snapshot refresh, PB engine, settings Tail section + muscle→habit mapping.
7. **Log tab:** Tail-style dialog, day list, PR flash, exercise catalog + rename/autocomplete.
8. **Body screen:** kangaroo art generation scripts + assets, map composable, tier coloring, region dialogs.
9. **Stats:** port + volume/exercise-filter extensions.
10. **Polish & verify:** video-detail exercise editing, export/import, R8 keep rules, process-death sweep, E2E on-device checks (share→download→analyse→log→Tail→PB flash→stats/body colors), docs (`docs/ARCHITECTURE.md`).
