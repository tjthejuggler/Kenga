package com.example.kenga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TailHabitEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<TailHabitEntryEntity>)

    @Query("SELECT * FROM tail_habit_entries WHERE date BETWEEN :from AND :to")
    fun observeBetween(from: String, to: String): Flow<List<TailHabitEntryEntity>>

    @Query("SELECT * FROM tail_habit_entries")
    fun observeAll(): Flow<List<TailHabitEntryEntity>>

    @Query("DELETE FROM tail_habit_entries")
    suspend fun clear()
}
