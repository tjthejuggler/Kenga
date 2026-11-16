package com.example.kenga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PbDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pb: PbEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pbs: List<PbEntity>)

    @Query("SELECT * FROM pbs ORDER BY exerciseName")
    fun observeAll(): Flow<List<PbEntity>>

    @Query("SELECT * FROM pbs WHERE exerciseName = :nameCollateCi COLLATE NOCASE LIMIT 1")
    suspend fun find(nameCollateCi: String): PbEntity?

    @Query("DELETE FROM pbs")
    suspend fun clear()
}
