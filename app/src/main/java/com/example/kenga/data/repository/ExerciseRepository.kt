package com.example.kenga.data.repository

import com.example.kenga.data.local.ExerciseDao
import com.example.kenga.data.local.ExerciseEntity
import com.example.kenga.data.local.ExerciseSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Local exercise catalog (§5/§6.2): the `exercises` table seeded from Tail's
 * recent-exercise list (source=TAIL) plus LLM/manual entries. Powers the Log
 * tab autocomplete and the LLM known-exercise naming hints.
 */
class ExerciseRepository(private val exerciseDao: ExerciseDao) {

    /** All names, most recently used first. */
    val names: Flow<List<String>> =
        exerciseDao.observeAll().map { all -> all.map { it.name } }

    /** Full catalog rows, most recently used first (Phase 8: exercise→group mapping). */
    val all: Flow<List<ExerciseEntity>> = exerciseDao.observeAll()

    /** Names seeded from Tail — the LLM prompt's preferred vocabulary. */
    suspend fun tailExerciseNames(): List<String> =
        exerciseDao.observeAll().first()
            .filter { it.source == ExerciseSource.TAIL }
            .map { it.name }

    fun observeByMuscleGroup(muscleGroupId: String): Flow<List<ExerciseEntity>> =
        exerciseDao.observeByMuscleGroup(muscleGroupId)

    /**
     * Manual upsert (Log tab / video-detail rename); keeps source=MANUAL.
     * A blank group keeps the existing mapping; a non-blank id is ADDED to
     * the row's muscle-group set (primary-first order preserved) instead of
     * replacing it — logging "Push-Up" under chest never erases the LLM's
     * shoulders/triceps associations.
     */
    suspend fun upsertManual(name: String, muscleGroupId: String) {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return
        val existing = exerciseDao.find(key)
        val groups = if (muscleGroupId.isBlank()) {
            existing?.muscleGroupIds.orEmpty()
        } else {
            ((existing?.muscleGroupIds ?: emptyList()) + muscleGroupId).distinct()
        }
        exerciseDao.upsert(
            ExerciseEntity(
                name = key,
                muscleGroupId = groups.joinToString(","),
                source = ExerciseSource.MANUAL,
                lastUsedAt = System.currentTimeMillis(),
                aliases = existing?.aliases?.ifEmpty { name } ?: name,
            ),
        )
    }

    /**
     * Manual multi-group mapping (Settings associations): REPLACES the row's
     * full group list (empty list = "None", excluded from the body figure).
     */
    suspend fun upsertManualGroups(name: String, groupIds: List<String>) {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return
        val existing = exerciseDao.find(key)
        if (groupIds.isEmpty() && existing == null) return
        exerciseDao.upsert(
            ExerciseEntity(
                name = key,
                muscleGroupId = groupIds.distinct().joinToString(","),
                source = ExerciseSource.MANUAL,
                lastUsedAt = existing?.lastUsedAt ?: System.currentTimeMillis(),
                aliases = existing?.aliases?.ifEmpty { name } ?: name,
            ),
        )
    }
}
