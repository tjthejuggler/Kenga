package com.example.kenga.data.local

import androidx.room.Entity

/**
 * One individually-logged weights set mirrored from Tail's per-set sidecar
 * (`/v3/weights/sets`). Unlike the day-aggregated [TailSnapshotEntity], these
 * rows distinguish multiple exercises done on the same day/slot — which is
 * what "sets today" counting needs (2 different back exercises = 2 sets).
 */
@Entity(
    tableName = "tail_weight_sets",
    primaryKeys = ["habitName", "date", "time", "exerciseName"],
)
data class TailWeightSetEntity(
    val habitName: String,
    val date: String, // yyyy-MM-dd
    val time: String, // HH:mm:ss ("" possible)
    val exerciseName: String,
    val machine: Boolean,
    val weightGrams: Int,
    val reps: Int,
)
