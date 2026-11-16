package com.example.kenga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SetLogDao {

    @Insert
    suspend fun insert(log: SetLogEntity): Long

    @Update
    suspend fun update(log: SetLogEntity)

    @Query("SELECT * FROM set_logs WHERE id = :id")
    suspend fun get(id: Long): SetLogEntity?

    @Query("SELECT * FROM set_logs WHERE date = :date ORDER BY ts")
    fun observeByDate(date: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE date BETWEEN :from AND :to ORDER BY ts")
    fun observeBetween(from: String, to: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs ORDER BY ts")
    fun observeAll(): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE tailSyncState = :state")
    suspend fun bySyncState(state: TailSyncState): List<SetLogEntity>

    @Query("DELETE FROM set_logs WHERE id = :id")
    suspend fun delete(id: Long)
}
