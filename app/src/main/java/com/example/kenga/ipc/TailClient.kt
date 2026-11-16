package com.example.kenga.ipc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.kenga.data.local.ExerciseEntity
import com.example.kenga.data.local.ExerciseSource
import com.example.kenga.data.local.KengaDatabase
import com.example.kenga.data.local.PbEntity
import com.example.kenga.data.local.TailSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Tail habit-app IPC client (plans/kenga_architecture.md §6.2, protocol v6).
 *
 * Octo `TailIntegrationClient` pattern: two channels —
 *  1. WRITE — `ACTION_LOG_WEIGHTS_ENTRY` broadcast (v6): one logged weights
 *     set on a Tail weights habit; negative reps = correction.
 *  2. READ — `/v3/weights*` provider endpoints: full day-slot history,
 *     per-exercise PBs and the recent-exercise quick list, feature-detected
 *     via `weights_v3` in `/v2/capabilities`.
 *
 * All calls are fail-safe: Tail may be absent; every failure degrades to
 * null/empty results with a log line, never an exception to the caller.
 */
class TailClient(
    private val context: Context,
    private val db: KengaDatabase,
) {

    /** UI-facing outcome of a broadcast attempt (Octo SendResult pattern). */
    enum class SendResult {
        /** Broadcast handed to the system without exception (fire-and-forget). */
        SENT,

        /** Tail absent or signature check failed — SecurityException on API 34+. */
        NO_TAIL,

        /** Anything else went wrong; the entry stays local (PENDING). */
        ERROR,
    }

    /** One row of `/v3/weights` — a habit's day-slot aggregates. */
    data class TailWeightsRow(
        val habitName: String,
        val date: String,
        val machineWeightG: Int,
        val machineReps: Int,
        val freeWeightG: Int,
        val freeReps: Int,
        val machineExercise: String?,
        val freeExercise: String?,
    )

    /** One row of `/v3/weights/pbs` — per (habit, machine, exercise). */
    data class TailPbRow(
        val habitName: String,
        val machine: Boolean,
        val exerciseName: String,
        val bestWeightGrams: Int,
        val bestWeightDate: String?,
        val bestReps: Int,
        val bestRepsDate: String?,
        val lastWeightGrams: Int,
        val lastReps: Int,
        val lastDate: String?,
    )

    /** One row of `/v3/weights/recent_exercises` (most-recent-first order). */
    data class TailRecentExercise(val habitName: String, val exerciseName: String)

    /** Result summary of a successful [refreshWeightsSnapshot] for the UI. */
    data class SnapshotSummary(
        val snapshotRows: Int,
        val habits: Int,
        val exercises: Int,
    )

    /** Availability probe result for the Settings status line. */
    data class TailAvailability(val installed: Boolean, val weightsV3: Boolean)

    /** One `/v2/habits` row — type + sub-category names for body-weight mapping. */
    data class TailV2Habit(
        val habitName: String,
        val habitType: String, // "counter" | "subtyped" | …
        val subtypeNames: List<String>,
    )

    /** One cached `/v2/habits/{id}/entries` day-aggregate row. */
    /** One row of Tail's per-set weights sidecar (`/v3/weights/sets`). */
    data class TailWeightSetRow(
        val habitName: String,
        val date: String,
        val machine: Boolean,
        val exerciseName: String,
        val weightGrams: Int,
        val reps: Int,
        val time: String,
    )

    data class TailHabitEntry(
        val habitName: String,
        val subtype: String,
        val date: String, // yyyy-MM-dd
        val value: Int,
        val ts: Long,
    )

    /**
     * Queries Tail's habit list via the v1 read-only provider. Names in Tail's
     * user-defined screen order; the name doubles as the stable habit ID.
     * Empty list when Tail is not installed / provider unreachable.
     */
    suspend fun queryHabits(): List<String> = withContext(Dispatchers.IO) {
        try {
            val habits = mutableListOf<String>()
            context.contentResolver
                .query(HABITS_URI, arrayOf(COL_HABIT_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndexOrThrow(COL_HABIT_NAME)
                    while (cursor.moveToNext()) habits += cursor.getString(idx)
                }
            habits
        } catch (t: Throwable) {
            Log.i(TAG, "Tail habit discovery unavailable (Tail not installed?)", t)
            emptyList()
        }
    }

    /**
     * Feature detection: is Tail installed and does `/v2/capabilities`
     * advertise `weights_v3`? Degrades to `installed=false` when unreachable.
     */
    suspend fun queryAvailability(): TailAvailability = withContext(Dispatchers.IO) {
        try {
            var features = ""
            context.contentResolver
                .query(CAPABILITIES_URI, arrayOf(COL_FEATURES), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(COL_FEATURES)
                        if (idx >= 0) features = cursor.getString(idx) ?: ""
                    }
                }
            if (features.isBlank()) TailAvailability(installed = false, weightsV3 = false)
            else TailAvailability(
                installed = true,
                weightsV3 = runCatching {
                    val arr = JSONArray(features)
                    (0 until arr.length()).any { arr.optString(it) == FEATURE_WEIGHTS_V3 }
                }.getOrDefault(false),
            )
        } catch (t: Throwable) {
            Log.i(TAG, "Tail capabilities unavailable (Tail not installed?)", t)
            TailAvailability(installed = false, weightsV3 = false)
        }
    }

    /**
     * Sends the protocol-v6 weights broadcast: ONE set on [habitName]'s
     * machine/free slot (day max-merge weight, reps accumulate, count +1 on
     * Tail's side). `sendBroadcast(intent, permission)` throws
     * SecurityException on API 34+ when Tail is absent → [SendResult.NO_TAIL].
     */
    fun logWeightsEntry(
        habitName: String,
        exerciseName: String,
        weightGrams: Int,
        reps: Int,
        machine: Boolean,
        date: String? = null,
    ): SendResult = sendWeightsBroadcast(
        habitName = habitName,
        exerciseName = exerciseName,
        weightGrams = weightGrams.coerceAtLeast(0),
        reps = reps,
        machine = machine,
        date = date,
    )

    /**
     * Correction broadcast (v6): `EXTRA_REPS = -n`, `EXTRA_WEIGHT_GRAMS = 0`
     * undoes n previously-logged reps on that date/slot (Tail clamps at 0).
     */
    fun sendCorrection(
        habitName: String,
        exerciseName: String,
        repsToRemove: Int,
        machine: Boolean,
        date: String,
    ): SendResult = sendWeightsBroadcast(
        habitName = habitName,
        exerciseName = exerciseName,
        weightGrams = 0,
        reps = -repsToRemove.coerceAtLeast(0),
        machine = machine,
        date = date,
    )

    private fun sendWeightsBroadcast(
        habitName: String,
        exerciseName: String,
        weightGrams: Int,
        reps: Int,
        machine: Boolean,
        date: String?,
    ): SendResult = try {
        val intent = Intent(ACTION_LOG_WEIGHTS_ENTRY).apply {
            `package` = TAIL_PACKAGE // explicit → reliable on API 26+
            putExtra(EXTRA_HABIT_ID, habitName)
            putExtra(EXTRA_EXERCISE_NAME, exerciseName)
            putExtra(EXTRA_WEIGHT_GRAMS, weightGrams)
            putExtra(EXTRA_REPS, reps)
            putExtra(EXTRA_MACHINE, machine)
            date?.let { putExtra(EXTRA_DATE, it) }
            putExtra(EXTRA_SOURCE, context.packageName)
        }
        context.sendBroadcast(intent, PERMISSION_TAIL)
        Log.i(
            TAG,
            "Tail weights entry sent: habit=%s ex=%s %dg x%d machine=%s date=%s"
                .format(habitName, exerciseName, weightGrams, reps, machine, date ?: "today"),
        )
        SendResult.SENT
    } catch (e: SecurityException) {
        Log.i(TAG, "Tail not installed / permission not granted — entry stays local", e)
        SendResult.NO_TAIL
    } catch (t: Throwable) {
        Log.w(TAG, "Tail weights broadcast failed", t)
        SendResult.ERROR
    }

    /**
     * Pulls the three `/v3/weights*` endpoints and transactionally replaces
     * the `tail_snapshots` + `pbs` tables, upserting fetched recent exercise
     * names into `exercises` (source=TAIL) for autocomplete. Returns null
     * when Tail is unreachable (tables left untouched).
     */
    suspend fun refreshWeightsSnapshot(): SnapshotSummary? = withContext(Dispatchers.IO) {
        val history = queryWeightsHistory()
        val pbs = queryWeightsPbs()
        val recent = queryRecentExercises()
        if (history == null && pbs == null && recent == null) {
            Log.i(TAG, "Tail v3 weights endpoints unreachable — snapshot left untouched")
            return@withContext null
        }

        val now = System.currentTimeMillis()
        db.withTransaction {
            val snapshotDao = db.tailSnapshotDao()
            snapshotDao.clear()
            snapshotDao.upsertAll(history.orEmpty().map { it.toEntity() })

            val pbDao = db.pbDao()
            pbDao.clear()
            pbDao.upsertAll(aggregatePbs(pbs.orEmpty()))

            // Seed/refresh the autocomplete catalog with Tail's naming.
            val exerciseDao = db.exerciseDao()
            recent.orEmpty().forEachIndexed { index, row ->
                val key = row.exerciseName.trim().lowercase()
                if (key.isNotEmpty()) {
                    val existing = exerciseDao.find(key)
                    exerciseDao.upsert(
                        ExerciseEntity(
                            name = key,
                            muscleGroupId = existing?.muscleGroupId ?: "",
                            source = ExerciseSource.TAIL,
                            // Provider order is most-recent-first; keep it monotonic.
                            lastUsedAt = existing?.lastUsedAt?.coerceAtLeast(now - index)
                                ?: (now - index),
                            aliases = existing?.aliases?.ifEmpty { row.exerciseName }
                                ?: row.exerciseName,
                        ),
                    )
                }
            }
        }

        SnapshotSummary(
            snapshotRows = history.orEmpty().size,
            habits = history.orEmpty().map { it.habitName }.distinct().size,
            exercises = recent.orEmpty().map { it.exerciseName.lowercase() }.distinct().size,
        ).also {
            Log.i(TAG, "Tail snapshot refreshed: rows=%d habits=%d exercises=%d"
                .format(it.snapshotRows, it.habits, it.exercises))
        }
    }

    /** Collapses per-(habit,machine,exercise) rows into per-exercise [PbEntity] rows. */
    internal fun aggregatePbs(rows: List<TailPbRow>): List<PbEntity> =
        rows.groupBy { it.exerciseName.trim().lowercase() }
            .map { (key, group) ->
                val bestWeight = group.maxByOrNull { it.bestWeightGrams }!!
                val bestReps = group.maxByOrNull { it.bestReps }!!
                val last = group.maxByOrNull { it.lastDate ?: "" }
                PbEntity(
                    exerciseName = key,
                    habitName = bestWeight.habitName,
                    bestWeightGrams = bestWeight.bestWeightGrams,
                    bestReps = bestReps.bestReps,
                    bestWeightDate = bestWeight.bestWeightDate,
                    bestRepsDate = bestReps.bestRepsDate,
                    lastDate = last?.lastDate,
                )
            }

    /**
     * v2 READ: all habits visible via the Companion Read API with their type
     * and sub-category names (null when the endpoint is unreachable — Tail
     * absent or the Read API disabled).
     */
    suspend fun queryV2Habits(): List<TailV2Habit>? = withContext(Dispatchers.IO) {
        try {
            val out = mutableListOf<TailV2Habit>()
            context.contentResolver
                .query(V2_HABITS_URI, null, null, null, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.string(COL_HABIT_NAME) ?: continue
                        val type = c.string(COL_HABIT_TYPE) ?: "counter"
                        val subtypesJson = c.string(COL_SUBTYPE_NAMES)
                        val subtypes = subtypesJson?.let { json ->
                            runCatching {
                                val arr = JSONArray(json)
                                (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                        out += TailV2Habit(name, type, subtypes)
                    }
                }
            out
        } catch (t: Throwable) {
            Log.i(TAG, "Tail /v2/habits unavailable", t)
            null
        }
    }

    /**
     * v2 READ: day-aggregate entries of ONE habit since [fromDate]
     * ("yyyy-MM-dd"), for body-weight habits the user mapped in Settings.
     * Null when the endpoint is unreachable.
     */
    suspend fun queryHabitEntriesSince(
        habitName: String,
        fromDate: String,
    ): List<TailHabitEntry>? = withContext(Dispatchers.IO) {
        try {
            val uri = V2_HABITS_URI.buildUpon()
                .appendPath(habitName)
                .appendPath("entries")
                .appendQueryParameter("from", "$fromDate 00:00:00")
                .build()
            val out = mutableListOf<TailHabitEntry>()
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val tsRaw = c.string(COL_ENTRY_TS) ?: continue
                    val date = tsRaw.take(10)
                    if (date.length != 10) continue
                    out += TailHabitEntry(
                        habitName = habitName,
                        subtype = c.string(COL_SUBTYPE_ENTRY).orEmpty(),
                        date = date,
                        value = c.int(COL_VALUE),
                        ts = runCatching {
                            java.time.LocalDateTime
                                .parse(tsRaw, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }.getOrDefault(0L),
                    )
                }
            }
            out
        } catch (t: Throwable) {
            Log.i(TAG, "Tail /v2/habits/$habitName/entries unavailable", t)
            null
        }
    }

    /** Per-set weights rows since [from] (yyyy-MM-dd); null when unavailable. */
    suspend fun queryWeightSetsSince(from: String): List<TailWeightSetRow>? =
        withContext(Dispatchers.IO) {
            try {
                val uri = WEIGHTS_SETS_URI.buildUpon().appendQueryParameter("from", from).build()
                val out = mutableListOf<TailWeightSetRow>()
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        out += TailWeightSetRow(
                            habitName = c.string(COL_HABIT_NAME) ?: continue,
                            date = c.string(COL_DATE) ?: continue,
                            machine = c.int(COL_MACHINE) == 1,
                            exerciseName = c.string(COL_EXERCISE_NAME).orEmpty(),
                            weightGrams = c.int(COL_WEIGHT_GRAMS),
                            reps = c.int(COL_SET_REPS),
                            time = c.string(COL_TIME_OF_DAY).orEmpty(),
                        )
                    }
                }
                out
            } catch (t: Throwable) {
                Log.i(TAG, "Tail /v3/weights/sets unavailable", t)
                null
            }
        }

    private fun queryWeightsHistory(): List<TailWeightsRow>? = try {
        val out = mutableListOf<TailWeightsRow>()
        context.contentResolver.query(WEIGHTS_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += TailWeightsRow(
                    habitName = c.string(COL_HABIT_NAME) ?: continue,
                    date = c.string(COL_DATE) ?: continue,
                    machineWeightG = c.int(COL_MACHINE_WEIGHT),
                    machineReps = c.int(COL_MACHINE_REPS),
                    freeWeightG = c.int(COL_FREE_WEIGHT),
                    freeReps = c.int(COL_FREE_REPS),
                    machineExercise = c.string(COL_MACHINE_EXERCISE),
                    freeExercise = c.string(COL_FREE_EXERCISE),
                )
            }
        }
        out
    } catch (t: Throwable) {
        Log.i(TAG, "Tail /v3/weights unavailable", t)
        null
    }

    private fun queryWeightsPbs(): List<TailPbRow>? = try {
        val out = mutableListOf<TailPbRow>()
        context.contentResolver.query(WEIGHTS_PBS_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += TailPbRow(
                    habitName = c.string(COL_HABIT_NAME) ?: continue,
                    machine = c.int(COL_MACHINE) == 1,
                    exerciseName = c.string(COL_EXERCISE_NAME) ?: continue,
                    bestWeightGrams = c.int(COL_BEST_WEIGHT),
                    bestWeightDate = c.string(COL_BEST_WEIGHT_DATE),
                    bestReps = c.int(COL_BEST_REPS),
                    bestRepsDate = c.string(COL_BEST_REPS_DATE),
                    lastWeightGrams = c.int(COL_LAST_WEIGHT),
                    lastReps = c.int(COL_LAST_REPS),
                    lastDate = c.string(COL_LAST_DATE),
                )
            }
        }
        out
    } catch (t: Throwable) {
        Log.i(TAG, "Tail /v3/weights/pbs unavailable", t)
        null
    }

    private fun queryRecentExercises(): List<TailRecentExercise>? = try {
        val out = mutableListOf<TailRecentExercise>()
        context.contentResolver.query(RECENT_EXERCISES_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val habit = c.string(COL_HABIT_NAME) ?: continue
                val name = c.string(COL_EXERCISE_NAME) ?: continue
                out += TailRecentExercise(habit, name)
            }
        }
        out
    } catch (t: Throwable) {
        Log.i(TAG, "Tail /v3/weights/recent_exercises unavailable", t)
        null
    }

    private fun TailWeightsRow.toEntity() = TailSnapshotEntity(
        habitName = habitName,
        date = date,
        machineWeightG = machineWeightG,
        machineReps = machineReps,
        freeWeightG = freeWeightG,
        freeReps = freeReps,
        machineExercise = machineExercise,
        freeExercise = freeExercise,
    )

    private inline fun android.database.Cursor.string(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private inline fun android.database.Cursor.int(column: String): Int {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getInt(idx) else 0
    }

    companion object {
        private const val TAG = "TailClient"

        // ── Exact protocol constants (must match Tail's ipc package) ──
        const val TAIL_PACKAGE = "com.example.tail"
        const val PERMISSION_TAIL = "com.example.tail.permission.TAIL_INTEGRATION"

        val HABITS_URI: Uri = Uri.parse("content://com.example.tail.provider/habits")
        val CAPABILITIES_URI: Uri =
            Uri.parse("content://com.example.tail.provider/v2/capabilities")
        val WEIGHTS_URI: Uri = Uri.parse("content://com.example.tail.provider/v3/weights")
        val WEIGHTS_PBS_URI: Uri =
            Uri.parse("content://com.example.tail.provider/v3/weights/pbs")
        val RECENT_EXERCISES_URI: Uri =
            Uri.parse("content://com.example.tail.provider/v3/weights/recent_exercises")

        const val COL_HABIT_NAME = "habit_name"
        const val COL_FEATURES = "features"
        const val FEATURE_WEIGHTS_V3 = "weights_v3"

        val V2_HABITS_URI: Uri = Uri.parse("content://com.example.tail.provider/v2/habits")
        const val COL_HABIT_TYPE = "habit_type"
        const val COL_SUBTYPE_NAMES = "subtype_names"
        const val COL_ENTRY_TS = "entry_ts"
        const val COL_SUBTYPE_ENTRY = "subtype"
        const val COL_VALUE = "value"

        const val ACTION_LOG_WEIGHTS_ENTRY = "com.example.tail.ACTION_LOG_WEIGHTS_ENTRY"
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        const val EXTRA_WEIGHT_GRAMS = "EXTRA_WEIGHT_GRAMS"
        const val EXTRA_REPS = "EXTRA_REPS"
        const val EXTRA_MACHINE = "EXTRA_MACHINE"
        const val EXTRA_DATE = "EXTRA_DATE"
        const val EXTRA_SOURCE = "EXTRA_SOURCE"

        const val COL_DATE = "date"
        const val COL_MACHINE_WEIGHT = "machine_weight_grams"
        const val COL_MACHINE_REPS = "machine_reps"
        const val COL_FREE_WEIGHT = "free_weight_grams"
        const val COL_FREE_REPS = "free_reps"
        const val COL_MACHINE_EXERCISE = "machine_exercise"
        const val COL_FREE_EXERCISE = "free_exercise"
        const val COL_MACHINE = "machine"
        const val COL_EXERCISE_NAME = "exercise_name"
        const val COL_BEST_WEIGHT = "best_weight_grams"
        const val COL_BEST_WEIGHT_DATE = "best_weight_date"
        const val COL_BEST_REPS = "best_reps"
        const val COL_BEST_REPS_DATE = "best_reps_date"
        const val COL_LAST_WEIGHT = "last_weight_grams"
        const val COL_LAST_REPS = "last_reps"
        const val COL_LAST_DATE = "last_date"

        val WEIGHTS_SETS_URI: Uri =
            Uri.parse("content://com.example.tail.provider/v3/weights/sets")
        const val COL_WEIGHT_GRAMS = "weight_grams"
        const val COL_SET_REPS = "reps"
        const val COL_TIME_OF_DAY = "time_of_day"
    }
}
