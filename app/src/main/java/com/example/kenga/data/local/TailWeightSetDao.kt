package com.example.kenga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TailWeightSetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sets: List<TailWeightSetEntity>)

    @Query("SELECT * FROM tail_weight_sets")
    fun observeAll(): Flow<List<TailWeightSetEntity>>

    @Query("DELETE FROM tail_weight_sets WHERE date < :from")
    suspend fun deleteBefore(from: String)

    @Query("DELETE FROM tail_weight_sets")
    suspend fun clear()
}
