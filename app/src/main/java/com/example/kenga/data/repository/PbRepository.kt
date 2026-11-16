package com.example.kenga.data.repository

import com.example.kenga.data.local.PbDao
import com.example.kenga.data.local.PbEntity
import com.example.kenga.data.local.SetLogDao
import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.local.TailHabitEntryDao
import com.example.kenga.data.local.TailHabitEntryEntity
import com.example.kenga.data.local.TailSnapshotDao
import com.example.kenga.data.local.TailSnapshotEntity
import com.example.kenga.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * One exercise's PB + last-set readout (§6.2) — the union of the Tail PB
 * mirror and locally logged sets (including PENDING ones, so the Log tab's
 * live readout works even when Tail is absent).
 */
data class ExercisePb(
    val exerciseName: String, // display casing (local log casing preferred)
    val habitName: String,
    val bestWeightGrams: Int,
    val bestWeightDate: String?,
    val bestReps: Int,
    val bestRepsDate: String?,
    val lastWeightGrams: Int,
    val lastReps: Int,
    val lastDate: String?,
)

/**
 * Pure union/dedup logic behind [PbRepository] — JVM-testable with no Android
 * or Room dependencies (entities are plain data classes).
 */
object PbLogic {

    /**
     * Per-exercise PB + last set, keyed by lowercase exercise name.
     *
     * Best fields: max of (Tail PB mirror, local logs evaluated with the same
     * day-max-weight-joined-to-exercise semantics — per (exercise, date) the
     * day max weight is the max over that day's sets). Last set: whichever of
     * (latest local log, latest Tail snapshot day naming the exercise) has
     * the later date.
     */
    fun unionPbs(
        tailPbs: List<PbEntity>,
        localLogs: List<SetLogEntity>,
        snapshots: List<TailSnapshotEntity>,
    ): Map<String, ExercisePb> {
        val out = LinkedHashMap<String, ExercisePb>()

        tailPbs.forEach { pb ->
            out[pb.exerciseName.lowercase()] = ExercisePb(
                exerciseName = pb.exerciseName,
                habitName = pb.habitName,
                bestWeightGrams = pb.bestWeightGrams,
                bestWeightDate = pb.bestWeightDate,
                bestReps = pb.bestReps,
                bestRepsDate = pb.bestRepsDate,
                lastWeightGrams = 0,
                lastReps = 0,
                lastDate = null,
            )
        }

        // Day-max weight per (exercise, date) from local logs (all sync states —
        // PENDING logs count so the PR flash fires without Tail).
        val dayMax = localLogs
            .filter { it.weightGrams > 0 }
            .groupBy { it.exerciseName.lowercase() to it.date }
            .mapValues { (_, rows) -> rows.maxOf { it.weightGrams } }
        localLogs.groupBy { it.exerciseName.lowercase() }.forEach { (key, logs) ->
            val existing = out[key]
            val localBestWeight = dayMax.filterKeys { it.first == key }.maxByOrNull { it.value }
            val localBestReps = logs.maxByOrNull { it.reps }
            val mergedBestWeight = maxOf(
                existing?.bestWeightGrams ?: 0,
                localBestWeight?.value ?: 0,
            )
            val tailWinsWeight = (existing?.bestWeightGrams ?: 0) >= (localBestWeight?.value ?: 0)
            val mergedBestReps = maxOf(existing?.bestReps ?: 0, localBestReps?.reps ?: 0)
            val tailWinsReps = (existing?.bestReps ?: 0) >= (localBestReps?.reps ?: 0)

            // Last set: latest local log vs current value (snapshot merge below).
            val lastLog = logs.maxByOrNull { it.ts }
            val lastFromLog = Triple(lastLog?.weightGrams ?: 0, lastLog?.reps ?: 0, lastLog?.date)
            val lastFromExisting = Triple(
                existing?.lastWeightGrams ?: 0,
                existing?.lastReps ?: 0,
                existing?.lastDate,
            )
            val last = if ((lastFromLog.third ?: "") >= (lastFromExisting.third ?: "")) {
                lastFromLog
            } else {
                lastFromExisting
            }

            out[key] = ExercisePb(
                exerciseName = lastLog?.exerciseName ?: existing?.exerciseName ?: key,
                habitName = existing?.habitName ?: "",
                bestWeightGrams = mergedBestWeight,
                bestWeightDate = if (tailWinsWeight) existing?.bestWeightDate else localBestWeight?.key?.second,
                bestReps = mergedBestReps,
                bestRepsDate = if (tailWinsReps) existing?.bestRepsDate else localBestReps?.date,
                lastWeightGrams = last.first,
                lastReps = last.second,
                lastDate = last.third,
            )
        }

        // Snapshot-derived last sets for exercises that only Tail knows about
        // (day-max weight joined to the per-day exercise name, per slot).
        snapshots.forEach { snap ->
            listOfNotNull(
                snap.machineExercise?.let { it to false },
                snap.freeExercise?.let { it to true },
            ).forEach { (name, _) ->
                val key = name.trim().lowercase()
                if (key.isEmpty()) return@forEach
                val existing = out[key]
                val takeSnap = (snap.date) >= (existing?.lastDate ?: "")
                if (takeSnap || existing == null) {
                    val machineSide = snap.machineExercise?.lowercase() == key
                    out[key] = ExercisePb(
                        exerciseName = existing?.exerciseName ?: name,
                        habitName = existing?.habitName ?: snap.habitName,
                        bestWeightGrams = existing?.bestWeightGrams
                            ?: if (machineSide) snap.machineWeightG else snap.freeWeightG,
                        bestWeightDate = existing?.bestWeightDate ?: snap.date,
                        bestReps = existing?.bestReps
                            ?: if (machineSide) snap.machineReps else snap.freeReps,
                        bestRepsDate = existing?.bestRepsDate ?: snap.date,
                        lastWeightGrams = if (machineSide) snap.machineWeightG else snap.freeWeightG,
                        lastReps = if (machineSide) snap.machineReps else snap.freeReps,
                        lastDate = snap.date,
                    )
                }
            }
        }

        return out
    }

    /**
     * Resolves the muscle-group id for a Tail exercise name via the catalog
     * (name → group, filled by the LLM ingestor or the user), else "".
     */
    private fun slotGroup(
        catalog: Map<String, String>,
        exerciseName: String?,
    ): String =
        exerciseName?.trim()?.lowercase()?.let { catalog[it] }.orEmpty()

    /**
     * One "done in Tail" day-slot aggregate surfaced on the Log tab (§7.3):
     * activity logged directly in Tail that Kenga has no local mirror row for.
     */
    data class TailTodaySet(
        val muscleGroupId: String, // CSV of canonical ids, primary first ("" = unmapped)
        val habitName: String,
        val exerciseName: String,
        val weightGrams: Int,
        val reps: Int,
        val machine: Boolean,
    ) {
        /** ALL credited canonical ids — one set can train several muscle groups. */
        val groupIds: List<String>
            get() = muscleGroupId.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Sets logged today per muscle-group id (§7.1 tier coloring + region
     * dialogs): local rows (Kenga-authored truth) + every Tail-done set from
     * [tailSetsToday], one count per set, attributed per exercise via the
     * catalog — figure color, dialog count and dialog set list can never
     * disagree because they share this one source.
     */
    /**
     * Sets logged today per muscle-group id (§7.1 tier coloring + region
     * dialogs): local rows (Kenga-authored truth, single logged group) + every
     * Tail-done set from [tailSetsToday] credited to EACH of its associated
     * groups (a push-up set colors chest, shoulders and triceps alike) —
     * figure color, dialog count and dialog set list can never disagree
     * because they share this one source.
     */
    fun todaySetCounts(
        localLogsToday: List<SetLogEntity>,
        tailSetsToday: List<TailTodaySet>,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        localLogsToday.forEach { counts[it.muscleGroupId] = (counts[it.muscleGroupId] ?: 0) + 1 }
        tailSetsToday.forEach { set ->
            set.groupIds.forEach { groupId ->
                counts[groupId] = (counts[groupId] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * Today's Tail-done sets — the single source for the Log tab's "done in
     * Tail" rows, the body-figure counts AND the region dialogs:
     *
     *  1. Per-set sidecar rows (`/v3/weights/sets`) — primary signal, ONE
     *     [TailTodaySet] per individually-logged set, so 2× Lat Pulldown +
     *     1× Seated Cable Row on one habit reads as exactly 3 sets, each
     *     attributed to its own catalog (LLM/user) muscle group.
     *  2. Day-aggregated snapshot slots — fallback ONLY for habits with no
     *     sidecar rows today (older data / provider edge).
     *  3. Body-weight habit entries — one set per (habit, subtype) day row.
     *
     * No-double-count rule: sidecar/slot rows whose exercise name matches a
     * local SENT row today are skipped — that set is already mirrored locally.
     */
    fun tailSetsToday(
        today: String,
        localLogsToday: List<SetLogEntity>,
        snapshotsToday: List<TailSnapshotEntity>,
        weightSetsToday: List<com.example.kenga.data.local.TailWeightSetEntity>,
        catalog: Map<String, String>, // lowercase exercise → groupId
        bodyweightToday: List<TailHabitEntryEntity> = emptyList(),
        bodyweightMap: Map<String, String> = emptyMap(), // habitName → exercise name
    ): List<TailTodaySet> {
        val mirrored = localLogsToday
            .filter { it.tailSyncState == com.example.kenga.data.local.TailSyncState.SENT }
            .map { it.exerciseName.trim().lowercase() }
            .toSet()

        val out = mutableListOf<TailTodaySet>()
        val sidecarHabits = HashSet<String>()

        // 1. Per-set sidecar rows — every row is one set.
        weightSetsToday
            .filter { it.date == today }
            .forEach { set ->
                sidecarHabits += set.habitName
                val name = set.exerciseName.trim()
                if (name.lowercase() in mirrored) return@forEach
                out += TailTodaySet(
                    muscleGroupId = catalog[name.lowercase()].orEmpty(),
                    habitName = set.habitName,
                    exerciseName = name.ifBlank { set.habitName },
                    weightGrams = set.weightGrams,
                    reps = set.reps,
                    machine = set.machine,
                )
            }

        // 2. Snapshot fallback for habits the sidecar did not cover today.
        snapshotsToday
            .filter { it.date == today && it.habitName !in sidecarHabits }
            .forEach { snap ->
                if (snap.machineWeightG > 0 || snap.machineReps > 0) {
                    val name = snap.machineExercise?.trim().orEmpty()
                    if (name.lowercase() !in mirrored) {
                        out += TailTodaySet(
                            muscleGroupId = slotGroup(catalog, name),
                            habitName = snap.habitName,
                            exerciseName = name.ifBlank { snap.habitName },
                            weightGrams = snap.machineWeightG,
                            reps = snap.machineReps,
                            machine = true,
                        )
                    }
                }
                if (snap.freeWeightG > 0 || snap.freeReps > 0) {
                    val name = snap.freeExercise?.trim().orEmpty()
                    if (name.lowercase() !in mirrored) {
                        out += TailTodaySet(
                            muscleGroupId = slotGroup(catalog, name),
                            habitName = snap.habitName,
                            exerciseName = name.ifBlank { snap.habitName },
                            weightGrams = snap.freeWeightG,
                            reps = snap.freeReps,
                            machine = false,
                        )
                    }
                }
            }

        // 3. Body-weight habit entries.
        out += bodyweightTodaySets(today, bodyweightToday, bodyweightMap, catalog)
        return out
    }

    /**
     * Today's body-weight habit entries as display rows + set counts: each
     * (habit, subtype) day row becomes one TailTodaySet whose exercise name
     * comes from the user's habit → exercise mapping ("Pull-Up (Wide Grip)"
     * for sub-category rows) and whose group comes from the catalog.
     */
    fun bodyweightTodaySets(
        today: String,
        entriesToday: List<TailHabitEntryEntity>,
        bodyweightMap: Map<String, String>, // habitName → exercise display name
        catalog: Map<String, String>, // lowercase exercise → groupId
    ): List<TailTodaySet> =
        entriesToday
            .filter { it.date == today && it.value > 0 }
            .mapNotNull { entry ->
                val exercise = bodyweightMap[entry.habitName] ?: return@mapNotNull null
                val name = if (entry.subtype.isBlank()) exercise else "$exercise (${entry.subtype})"
                TailTodaySet(
                    muscleGroupId = catalog[name.trim().lowercase()].orEmpty(),
                    habitName = entry.habitName,
                    exerciseName = name,
                    weightGrams = 0,
                    reps = entry.value,
                    machine = false,
                )
            }
}

/**
 * PB engine (§6.2): flows over the union of Tail's PB mirror and local logs.
 */
class PbRepository(
    private val pbDao: PbDao,
    private val setLogDao: SetLogDao,
    private val tailSnapshotDao: TailSnapshotDao,
    private val exerciseDao: com.example.kenga.data.local.ExerciseDao,
    private val tailHabitEntryDao: TailHabitEntryDao,
    private val settingsRepository: SettingsRepository,
    private val tailWeightSetDao: com.example.kenga.data.local.TailWeightSetDao,
) {

    /** (a) Per-exercise PB + last set — union of Tail mirror and local logs. */
    fun observeExercisePbs(): Flow<Map<String, ExercisePb>> = combine(
        pbDao.observeAll(),
        setLogDao.observeAll(),
        tailSnapshotDao.observeAll(),
    ) { pbs, logs, snapshots -> PbLogic.unionPbs(pbs, logs, snapshots) }

    /** PB readout for one exercise (case-insensitive). */
    fun observeExercisePb(exerciseName: String): Flow<ExercisePb?> =
        observeExercisePbs().map { it[exerciseName.trim().lowercase()] }

    /** (b) Sets logged today per canonical muscle-group id. */
    fun observeTodaySetCounts(today: String): Flow<Map<String, Int>> =
        combine(setLogDao.observeByDate(today), observeTailTodaySets(today)) { logs, tailSets ->
            PbLogic.todaySetCounts(logs, tailSets)
        }

    /** (c) Recent exercise names for autocomplete, most recently used first. */
    fun observeRecentExerciseNames(limit: Int = 20): Flow<List<String>> =
        exerciseDao.observeAll().map { all -> all.take(limit).map { it.name } }

    /**
     * (d) Today's Tail-done sets — the single source behind the Log tab's
     * "done in Tail" rows, the body-figure counts and the region dialogs:
     * per-set sidecar rows + snapshot fallback + body-weight habits.
     */
    fun observeTailTodaySets(today: String): Flow<List<PbLogic.TailTodaySet>> = combine(
        listOf(
            setLogDao.observeByDate(today),
            tailSnapshotDao.observeBetween(today, today),
            tailWeightSetDao.observeAll(),
            exerciseDao.observeAll(),
            tailHabitEntryDao.observeBetween(today, today),
            settingsRepository.bodyweightHabitMap,
        ),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        PbLogic.tailSetsToday(
            today = today,
            localLogsToday = values[0] as List<SetLogEntity>,
            snapshotsToday = values[1] as List<TailSnapshotEntity>,
            weightSetsToday = (values[2] as List<com.example.kenga.data.local.TailWeightSetEntity>)
                .filter { it.date == today },
            catalog = (values[3] as List<com.example.kenga.data.local.ExerciseEntity>)
                .filter { it.muscleGroupId.isNotBlank() }
                .associate { it.name.lowercase() to it.muscleGroupId },
            bodyweightToday = values[4] as List<TailHabitEntryEntity>,
            bodyweightMap = values[5] as Map<String, String>,
        )
    }
}
