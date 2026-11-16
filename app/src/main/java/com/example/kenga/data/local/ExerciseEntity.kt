package com.example.kenga.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Where a catalog exercise name came from. */
enum class ExerciseSource { LLM, TAIL, MANUAL }

/**
 * Local exercise catalog (§5). The PK is the lowercased exercise name, giving
 * the case-insensitive uniqueness the plan requires.
 */
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val name: String, // lowercase canonical key (case-insensitive unique)
    val muscleGroupId: String, // CSV of canonical muscle-group ids, primary FIRST ("" = unmapped)
    val source: ExerciseSource,
    val lastUsedAt: Long, // epoch millis
    val aliases: String = "", // CSV of alternate spellings (incl. original casing)
) {
    /** All associated canonical muscle-group ids, primary first. */
    val muscleGroupIds: List<String>
        get() = muscleGroupId.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The primary (first) muscle-group id, or "" when unmapped. */
    val primaryMuscleGroupId: String
        get() = muscleGroupIds.firstOrNull().orEmpty()
}
