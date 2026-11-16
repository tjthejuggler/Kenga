package com.example.kenga.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-exercise personal best mirror (§5) — computed from Tail history plus
 * pending local logs so the PR flash fires even when Tail is absent.
 * PK is the lowercased exercise name (case-insensitive unique).
 */
@Entity(tableName = "pbs")
data class PbEntity(
    @PrimaryKey val exerciseName: String, // lowercase canonical key
    val habitName: String,
    val bestWeightGrams: Int,
    val bestReps: Int,
    val bestWeightDate: String?, // yyyy-MM-dd
    val bestRepsDate: String?, // yyyy-MM-dd
    val lastDate: String? = null, // yyyy-MM-dd — last day this exercise was done (v4)
)
