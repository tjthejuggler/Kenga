package com.example.kenga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TailSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(snapshots: List<TailSnapshotEntity>)

    @Query("SELECT * FROM tail_snapshots WHERE habitName = :habitName AND date = :date")
    suspend fun get(habitName: String, date: String): TailSnapshotEntity?

    @Query("SELECT * FROM tail_snapshots WHERE date BETWEEN :from AND :to")
    fun observeBetween(from: String, to: String): Flow<List<TailSnapshotEntity>>

    @Query("SELECT * FROM tail_snapshots ORDER BY date")
    fun observeAll(): Flow<List<TailSnapshotEntity>>

    @Query("DELETE FROM tail_snapshots")
    suspend fun clear()
}
