package com.example.kenga.data.local

import androidx.room.Entity

/**
 * Cached Tail body-weight habit entry (§6.2 v2 READ path): one day-aggregate
 * row per (habit, subtype) — e.g. "Pull-ups / wide grip / 2026-11-10 / 12".
 * Habits themselves are plain Tail counter/subtyped habits; the user maps
 * habit names to Kenga exercise names in Settings (editable), and the LLM
 * associates those exercise names (incl. sub-category variants) with muscle
 * groups via the catalog.
 */
@Entity(tableName = "tail_habit_entries", primaryKeys = ["habitName", "subtype", "date"])
data class TailHabitEntryEntity(
    val habitName: String,
    val subtype: String, // "" for plain counter habits
    val date: String, // yyyy-MM-dd
    val value: Int, // day count (reps for body-weight exercises)
    val ts: Long, // epoch millis of the entry timestamp
)
