package com.example.kenga.data.local

import androidx.room.Entity

/**
 * Cached Tail weights history for one habit + day (§5) — read from the
 * provider v3 endpoints so the body screen and stats work offline.
 */
@Entity(tableName = "tail_snapshots", primaryKeys = ["habitName", "date"])
data class TailSnapshotEntity(
    val habitName: String,
    val date: String, // yyyy-MM-dd
    val machineWeightG: Int = 0,
    val machineReps: Int = 0,
    val freeWeightG: Int = 0,
    val freeReps: Int = 0,
    val machineExercise: String? = null,
    val freeExercise: String? = null,
)
