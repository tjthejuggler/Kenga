package com.example.kenga.data.repository

import com.example.kenga.data.local.PbDao
import com.example.kenga.data.local.TailSnapshotDao
import com.example.kenga.data.local.TailWeightSetDao
import kotlinx.coroutines.flow.first

/**
 * Automatic Tail weights-habit selection for the WRITE path (v6 broadcast) —
 * replaces the old 16 per-muscle-group Settings pickers.
 *
 * Tail weights habits are plain counter habits with secondary-value slots, so
 * the reliable signal is where an exercise was ACTUALLY logged before:
 *  1. the per-exercise PB mirror (`/v3/weights/pbs` → habitName per exercise);
 *  2. the per-set sidecar (`/v3/weights/sets` → most recent row for the name);
 *  3. the most recent sidecar row overall (single-habit setups);
 *  4. the sole snapshot habit, when exactly one weights habit exists.
 *
 * "" = unresolvable → the row is stored OFFLINE (local-only truth) exactly as
 * an unmapped group was before.
 */
class WeightsHabitResolver(
    private val pbDao: PbDao,
    private val tailWeightSetDao: TailWeightSetDao,
    private val tailSnapshotDao: TailSnapshotDao,
) {

    /** Best Tail weights-habit name for [exerciseName], "" when unresolvable. */
    suspend fun resolve(exerciseName: String): String {
        val key = exerciseName.trim().lowercase()
        if (key.isNotEmpty()) {
            pbDao.observeAll().first()
                .firstOrNull { it.exerciseName.lowercase() == key }
                ?.habitName?.takeIf { it.isNotBlank() }
                ?.let { return it }

            tailWeightSetDao.observeAll().first()
                .filter { it.exerciseName.trim().lowercase() == key }
                .maxByOrNull { it.date + " " + it.time }
                ?.habitName?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // Fallbacks: latest activity anywhere, then a single-habit setup.
        tailWeightSetDao.observeAll().first()
            .maxByOrNull { it.date + " " + it.time }
            ?.habitName?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val snapshotHabits = tailSnapshotDao.observeAll().first()
            .map { it.habitName }
            .distinct()
        return snapshotHabits.singleOrNull().orEmpty()
    }
}
