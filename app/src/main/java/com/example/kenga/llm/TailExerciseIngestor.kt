package com.example.kenga.llm

import android.util.Log
import androidx.room.withTransaction
import com.example.kenga.data.local.ExerciseEntity
import com.example.kenga.data.local.ExerciseSource
import com.example.kenga.data.local.KengaDatabase
import com.example.kenga.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Fills the missing muscle-group association of catalog exercises pulled from
 * Tail (§6.2). Tail only tells us exercise NAMES; the LLM (or, without an API
 * key, [LlmHeuristics]) associates each name — including body-weight
 * sub-category variants like "Pull-Up (Wide Grip)" — with ALL the canonical
 * muscle-group ids it trains (primary first), so Tail-done activity reaches
 * the body figure and the per-group PB readouts.
 *
 * [ingestMissing] only writes rows with a BLANK muscleGroupId; [reclassify]
 * also refreshes machine-mapped rows (source TAIL/LLM) with the current
 * multi-group schema. Rows edited by hand (source MANUAL — user's explicit
 * choice) are never touched by either path.
 */
class TailExerciseIngestor(
    private val db: KengaDatabase,
    private val llmClient: LlmClient,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Classifies every catalog row whose muscleGroupId is blank. Returns how
     * many rows were updated (0 when nothing pending or all lookups failed).
     * Never throws.
     */
    suspend fun ingestMissing(): Int = withContext(Dispatchers.IO) {
        val dao = db.exerciseDao()
        val pending = dao.observeAll().first().filter { it.muscleGroupId.isBlank() }
        if (pending.isEmpty()) return@withContext 0

        val groups = classifyNames(pending.map { it.aliases.ifBlank { it.name } })
        var updated = 0
        db.withTransaction {
            pending.forEach { row ->
                val groupIds = groups[row.name.lowercase()]
                    ?: groups[row.aliases.lowercase()]
                    ?: return@forEach
                // CSV of ALL associated groups, primary mover first.
                dao.upsert(row.copy(muscleGroupId = groupIds.joinToString(",")))
                updated++
            }
        }
        Log.i(TAG, "Ingested %d/%d Tail exercises with muscle groups".format(updated, pending.size))
        updated
    }

    /**
     * Re-runs the LLM/heuristic association for every catalog row whose
     * muscle group mapping was machine-authored ([ExerciseSource.TAIL] or
     * [ExerciseSource.LLM]) — used both by the one-time multi-group upgrade
     * ([TailSynchronizer]) and the Settings "Reassociate" action. Rows the
     * user mapped manually (MANUAL source) are never overwritten.
     *
     * @return how many rows were rewritten; never throws.
     */
    suspend fun reclassify(): Int = withContext(Dispatchers.IO) {
        val dao = db.exerciseDao()
        val machineMapped = dao.observeAll().first().filter {
            it.source != ExerciseSource.MANUAL && it.muscleGroupId.isNotBlank()
        }
        if (machineMapped.isEmpty()) return@withContext 0

        val groups = classifyNames(machineMapped.map { it.aliases.ifBlank { it.name } })
        var updated = 0
        db.withTransaction {
            machineMapped.forEach { row ->
                val groupIds = groups[row.name.lowercase()]
                    ?: groups[row.aliases.lowercase()]
                    ?: return@forEach
                dao.upsert(row.copy(muscleGroupId = groupIds.joinToString(",")))
                updated++
            }
        }
        Log.i(TAG, "Reclassified %d/%d machine-mapped exercises".format(updated, machineMapped.size))
        updated
    }

    /** name (lowercase catalog key) → canonical muscle-group ids, primary first; never throws. */
    private suspend fun classifyNames(names: List<String>): Map<String, List<String>> {
        if (names.isEmpty()) return emptyMap()

        val settings = settingsRepository.settings.first()
        if (settings.llmApiKey.isNotBlank()) {
            runCatching {
                val endpoint = LlmClient.Endpoint(
                    baseUrl = settings.llmBaseUrl.ifBlank { SettingsRepository.DEFAULT_LLM_BASE_URL },
                    apiKey = settings.llmApiKey,
                    model = settings.llmModel.ifBlank { SettingsRepository.DEFAULT_LLM_MODEL },
                )
                // Reuse the transcript classifier: the exercise-name list is
                // short, so this stays a single cheap call, and the prompt's
                // canonical-id validation applies unchanged.
                val analysis = llmClient.classifyExercises(
                    endpoint = endpoint,
                    transcript = "Exercise names logged by the user in their training app:\n" +
                        names.joinToString("\n") { "- $it" },
                    isTitleDescriptionOnly = true,
                )
                val byName = analysis.exercises
                    .filter { exercise ->
                        exercise.muscleGroups.any { it in com.example.kenga.llm.MuscleGroupIds.CANONICAL_IDS }
                    }
                    .associate { exercise ->
                        val ids = exercise.muscleGroups
                            .filter { it in com.example.kenga.llm.MuscleGroupIds.CANONICAL_IDS }
                        exercise.name.trim().lowercase() to ids
                    }
                if (byName.isNotEmpty()) return byName
            }.onFailure { Log.i(TAG, "LLM exercise-name classification failed — heuristics fallback", it) }
        }

        // Heuristic fallback: classify each name on its own (thin-text mode).
        return names.mapNotNull { name ->
            val result = LlmHeuristics.classify(name)
            val hit = result.exercises.firstOrNull()
            val ids = hit?.muscleGroups
                // No known exercise keyword — fall back to the top transcript cues.
                ?: result.muscleGroups.take(1).ifEmpty { null }
                ?: return@mapNotNull null
            val canonical = ids.filter { it in MuscleGroupIds.CANONICAL_IDS }
            if (canonical.isEmpty()) return@mapNotNull null
            name.trim().lowercase() to canonical
        }.toMap()
    }

    /**
     * Ensures the catalog contains [displayName] (used for body-weight habit
     * mappings and their sub-category variants) — inserted with a blank group
     * so [ingestMissing] (or the user) fills it. Never overwrites an existing
     * row's group or source.
     */
    suspend fun ensureCatalogEntry(displayName: String) {
        val key = displayName.trim().lowercase()
        if (key.isEmpty()) return
        val dao = db.exerciseDao()
        if (dao.find(key) == null) {
            dao.upsert(
                ExerciseEntity(
                    name = key,
                    muscleGroupId = "",
                    source = ExerciseSource.TAIL,
                    lastUsedAt = System.currentTimeMillis(),
                    aliases = displayName.trim(),
                ),
            )
        }
    }

    private companion object {
        const val TAG = "TailExerciseIngestor"
    }
}
