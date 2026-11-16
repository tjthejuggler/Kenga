package com.example.kenga.data.repository

import android.util.Log
import com.example.kenga.data.local.TailHabitEntryEntity
import com.example.kenga.data.settings.SettingsRepository
import com.example.kenga.ipc.TailClient
import com.example.kenga.llm.TailExerciseIngestor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Full Tail READ sync (§6.2), one entry point for the worker and the Log tab:
 *
 *  1. `refreshWeightsSnapshot` — weights history + PBs + recent exercise
 *     names into the local mirror tables (exactly as before).
 *  2. Body-weight habits — for every habit the user mapped to a Kenga
 *     exercise name in Settings, pull `/v2/habits/{name}/entries` since
 *     [SYNC_WINDOW_DAYS] and cache the day-aggregates (incl. sub-category
 *     rows). Mapped exercise names (+ sub-category variants) are seeded into
 *     the catalog.
 *  3. [TailExerciseIngestor] — associate every catalog name that still has a
 *     blank muscle group via the LLM (or heuristics), so Tail-done activity
 *     reaches the body figure, PB readouts and stats.
 *
 * Every step is fail-safe: unreachable endpoints leave the cached data
 * untouched and the sync degrades to what worked.
 */
class TailSynchronizer(
    private val tailClient: TailClient,
    private val db: com.example.kenga.data.local.KengaDatabase,
    private val settingsRepository: SettingsRepository,
    private val ingestor: TailExerciseIngestor,
) {

    /** True when at least one Tail endpoint was reachable. */
    suspend fun fullRefresh(): Boolean = withContext(Dispatchers.IO) {
        var anyReachable = false

        // 0. One-time multi-group upgrade: catalog rows mapped by the machine
        // under the old single-group schema get a fresh LLM/heuristic pass
        // (only when an API key exists — heuristics alone add nothing here).
        // User MANUAL mappings are never touched.
        if (!settingsRepository.catalogMultiGroupMigrated.first() &&
            settingsRepository.settings.first().llmApiKey.isNotBlank()
        ) {
            runCatching { ingestor.reclassify() }
                .onFailure { Log.i(TAG, "Multi-group reclassify failed", it) }
            settingsRepository.setCatalogMultiGroupMigrated()
        }

        // 1. weights snapshot (history + PBs + autocomplete naming)
        if (tailClient.refreshWeightsSnapshot() != null) anyReachable = true

        // 2. per-set weights sidecar — the day-aggregated snapshot cannot
        // distinguish multiple exercises done the same day on one slot.
        val setsFrom = LocalDate.now().minusDays(SYNC_WINDOW_DAYS).toString()
        val weightSets = tailClient.queryWeightSetsSince(setsFrom)
        if (weightSets != null) {
            anyReachable = true
            db.tailWeightSetDao().deleteBefore(setsFrom)
            db.tailWeightSetDao().upsertAll(
                weightSets.map {
                    com.example.kenga.data.local.TailWeightSetEntity(
                        habitName = it.habitName,
                        date = it.date,
                        time = it.time,
                        exerciseName = it.exerciseName,
                        machine = it.machine,
                        weightGrams = it.weightGrams,
                        reps = it.reps,
                    )
                }
            )
            Log.i(TAG, "Weight sets cached: %d rows".format(weightSets.size))
        }

        // 3. body-weight habit entries
        val bwMap = settingsRepository.bodyweightHabitMap.first()
            .filterValues { it.isNotBlank() }
        if (bwMap.isNotEmpty()) {
            val from = LocalDate.now().minusDays(SYNC_WINDOW_DAYS).toString()
            // v2 habits carry each habit's sub-category NAMES up-front, so
            // "Exercise (Subtype)" catalog rows exist (and get LLM-associated)
            // even before any entry using that sub-category is ever logged.
            val v2Subtypes = tailClient.queryV2Habits()
            if (v2Subtypes != null) anyReachable = true
            val rows = mutableListOf<TailHabitEntryEntity>()
            bwMap.forEach { (habit, exerciseName) ->
                // Catalog seed happens even when Tail is unreachable, so the
                // LLM ingest below can associate the mapped exercise name
                // with a muscle group from its own knowledge.
                ingestor.ensureCatalogEntry(exerciseName)
                val declaredSubtypes = v2Subtypes
                    ?.firstOrNull { it.habitName == habit }?.subtypeNames.orEmpty()
                declaredSubtypes.forEach {
                    ingestor.ensureCatalogEntry("$exerciseName ($it)")
                }
                val entries = tailClient.queryHabitEntriesSince(habit, from)
                if (entries != null) {
                    anyReachable = true
                    rows += entries.map {
                        TailHabitEntryEntity(
                            habitName = it.habitName,
                            subtype = it.subtype,
                            date = it.date,
                            value = it.value,
                            ts = it.ts,
                        )
                    }
                    // Sub-categories observed in history but not declared in
                    // the v2 habit list still get their catalog variant.
                    entries.map { it.subtype }.filter { it.isNotBlank() }
                        .distinct().filterNot { it in declaredSubtypes }.forEach {
                            ingestor.ensureCatalogEntry("$exerciseName ($it)")
                        }
                }
            }
            if (rows.isNotEmpty()) {
                db.tailHabitEntryDao().upsertAll(rows)
                Log.i(TAG, "Body-weight entries cached: %d rows, %d habits".format(rows.size, bwMap.size))
            }
        }

        // 4. LLM/heuristic muscle-group association for new Tail names
        runCatching { ingestor.ingestMissing() }
            .onFailure { Log.i(TAG, "Exercise ingest failed", it) }

        anyReachable
    }

    private companion object {
        const val TAG = "TailSynchronizer"
        const val SYNC_WINDOW_DAYS = 30L
    }
}
