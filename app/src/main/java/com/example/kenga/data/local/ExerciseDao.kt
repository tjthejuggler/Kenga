package com.example.kenga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exercise: ExerciseEntity)

    @Query("SELECT * FROM exercises ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<ExerciseEntity>>

    // muscleGroupId is a CSV of ids (primary first) — match the id at any slot.
    @Query(
        "SELECT * FROM exercises WHERE muscleGroupId = :muscleGroupId " +
            "OR muscleGroupId LIKE :muscleGroupId || ',%' " +
            "OR muscleGroupId LIKE '%,' || :muscleGroupId " +
            "OR muscleGroupId LIKE '%,' || :muscleGroupId || ',%' " +
            "ORDER BY lastUsedAt DESC"
    )
    fun observeByMuscleGroup(muscleGroupId: String): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE name = :nameCollateCi COLLATE NOCASE LIMIT 1")
    suspend fun find(nameCollateCi: String): ExerciseEntity?

    @Query("DELETE FROM exercises WHERE name = :name")
    suspend fun delete(name: String)
}
