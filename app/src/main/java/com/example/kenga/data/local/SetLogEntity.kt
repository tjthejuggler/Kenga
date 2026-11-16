package com.example.kenga.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Tail-sync lifecycle of a locally logged set. */
enum class TailSyncState { PENDING, SENT, FAILED, OFFLINE }

/** One logged weights set, mirrored into a mapped Tail weights habit (§5). */
@Entity(tableName = "set_logs")
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ts: Long, // epoch millis
    val muscleGroupId: String, // canonical muscle-group id
    val exerciseName: String,
    val weightGrams: Int,
    val reps: Int,
    val machine: Boolean,
    val date: String, // yyyy-MM-dd (Tail entry day)
    val tailSyncState: TailSyncState = TailSyncState.PENDING,
)
