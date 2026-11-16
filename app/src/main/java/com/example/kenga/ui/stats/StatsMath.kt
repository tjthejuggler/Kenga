package com.example.kenga.ui.stats

import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.local.TailSnapshotEntity
import com.example.kenga.data.local.TailSyncState
import com.example.kenga.data.local.TailWeightSetEntity
import com.example.kenga.data.weights.WeightUnits
import java.time.LocalDate

/**
 * Selected stats window (§7.4). [days] == null means "all time".
 */
enum class StatsRange(val label: String, val days: Long?) {
    D7("7D", 7),
    D30("30D", 30),
    D90("90D", 90),
    ALL("ALL", null);

    /** Inclusive ISO start date of the window ending on [today]. */
    fun fromDate(today: LocalDate): String =
        days?.let { today.minusDays(it - 1).toString() } ?: "0000-01-01"
}

/** Current + best consecutive-training-day streaks. */
data class Streaks(val current: Int, val best: Int)

/**
 * One training set on one day, after the local/Tail union — the single series
 * every stats aggregate is derived from.
 */
data class DaySet(
    val date: String, // yyyy-MM-dd
    val muscleGroupId: String, // PRIMARY group (legacy compat; attribution uses [groupIds])
    val exerciseName: String,
    val weightGrams: Int,
    val reps: Int,
    /** ALL canonical groups this set is credited to, primary first; empty = [muscleGroupId] only. */
    val groupIds: List<String> = emptyList(),
) {
    /** Distinct groups the set counts toward — [groupIds] when set, else the single primary. */
    val attribution: List<String>
        get() = groupIds.ifEmpty { listOf(muscleGroupId) }
}

/** Per-muscle-group aggregate in the selected window. */
data class GroupStat(
    val muscleGroupId: String,
    val sets: Int,
    val volumeGrams: Int,
    val trainingDays: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    /** Sets / max-sets across groups in range (0..1) — feeds [com.example.kenga.ui.body.usageTierColor]. */
    val usageFraction: Float,
)

/** Whole-body summary cards in the selected window. */
data class OverallStats(
    val totalSets: Int,
    val volumeGrams: Int,
    val trainingDays: Int,
    val currentStreak: Int,
    val bestStreak: Int,
)

/** Everything the stats screen renders for one (range, exercise-filter) selection. */
data class StatsResult(
    val groups: List<GroupStat>,
    val overall: OverallStats,
)

/**
 * Pure stats math for the stats screen (§7.4, Phase 9) — no Android or Compose
 * dependencies so it unit-tests on the JVM (Octo `StatsMath` port adapted to
 * Kenga's combined local-log + Tail-snapshot series). [today] is always passed
 * in explicitly to keep tests deterministic.
 */
object StatsMath {

    /**
     * Combined day-series: local logs (all sync states — PENDING rows are
     * Kenga-authored truth) + Tail-done sets, attributed per exercise via the
     * catalog (LLM/user association) — no per-group habit mapping anymore.
     *
     * Tail sources, in priority order (mirrors [com.example.kenga.data.repository.PbLogic.tailSetsToday]):
     *  1. per-set sidecar rows (`/v3/weights/sets`) — one [DaySet] each;
     *  2. day-aggregated snapshot slots — fallback for (habit, date) pairs the
     *     sidecar does not cover.
     *
     * No-double-count rule: sidecar/slot rows whose exercise name matches a
     * local SENT log on the same date are skipped — the local mirror already
     * counts them.
     */
    fun combinedDaySets(
        localLogs: List<SetLogEntity>,
        weightSets: List<TailWeightSetEntity>,
        snapshots: List<TailSnapshotEntity>,
        catalog: Map<String, String>, // lowercase exercise → groupId
        from: String,
        to: String,
    ): List<DaySet> {
        val local = localLogs
            .filter { it.date in from..to }
            .map {
                DaySet(
                    date = it.date,
                    muscleGroupId = it.muscleGroupId,
                    exerciseName = it.exerciseName,
                    weightGrams = it.weightGrams,
                    reps = it.reps,
                )
            }

        // Catalog values are CSVs (primary first) — one set may credit several
        // groups. The row's PRIMARY id stays DaySet.muscleGroupId so legacy
        // single-group reads (region dialogs, PB grouping) keep working.
        fun csvGroups(csv: String): List<String> =
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        // (date, lowercase exercise) pairs already mirrored by a local SENT row.
        val mirrored = localLogs
            .filter { it.tailSyncState == TailSyncState.SENT && it.date in from..to }
            .map { it.date to it.exerciseName.trim().lowercase() }
            .toSet()

        val fromTail = mutableListOf<DaySet>()

        // 1. Per-set sidecar rows — every row is one set.
        val coveredHabitsByDate = HashSet<Pair<String, String>>()
        weightSets
            .filter { it.date in from..to }
            .forEach { set ->
                coveredHabitsByDate += set.habitName to set.date
                val key = set.exerciseName.trim().lowercase()
                if (key.isEmpty() || (set.date to key) in mirrored) return@forEach
                val groups = catalog[key]?.let(::csvGroups) ?: return@forEach
                fromTail += DaySet(
                    date = set.date,
                    muscleGroupId = groups.firstOrNull().orEmpty(),
                    exerciseName = set.exerciseName.trim(),
                    weightGrams = set.weightGrams,
                    reps = set.reps,
                    groupIds = groups,
                )
            }

        // 2. Snapshot slots — fallback for habits the sidecar doesn't cover.
        snapshots
            .filter { it.date in from..to && (it.habitName to it.date) !in coveredHabitsByDate }
            .forEach { snap ->
                listOfNotNull(
                    snap.machineExercise?.trim()?.takeIf {
                        it.isNotEmpty() && (snap.machineWeightG > 0 || snap.machineReps > 0)
                    }?.let { it to (snap.machineWeightG to snap.machineReps) },
                    snap.freeExercise?.trim()?.takeIf {
                        it.isNotEmpty() && (snap.freeWeightG > 0 || snap.freeReps > 0)
                    }?.let { it to (snap.freeWeightG to snap.freeReps) },
                ).forEach { (name, wr) ->
                    val key = name.lowercase()
                    if ((snap.date to key) in mirrored) return@forEach
                    val groups = catalog[key]?.let(::csvGroups) ?: return@forEach
                    fromTail += DaySet(
                        date = snap.date,
                        muscleGroupId = groups.firstOrNull().orEmpty(),
                        exerciseName = name,
                        weightGrams = wr.first,
                        reps = wr.second,
                        groupIds = groups,
                    )
                }
            }

        return local + fromTail
    }

    /**
     * Every exercise name recorded in the window (chips for the filter row) —
     * case-insensitively deduped, alphabetically sorted.
     */
    fun exerciseNames(daySets: List<DaySet>): List<String> =
        daySets.map { it.exerciseName.trim() }
            .filter { it.isNotEmpty() }
            .groupBy { it.lowercase() }
            .map { (_, names) -> names.first() }
            .sortedBy { it.lowercase() }

    /**
     * Exercise filter (Tail GraphsScreen pattern): an active [exercise]
     * narrows the series to the DAYS logged with that exercise (case
     * insensitive) — every bar and card then aggregates only those days,
     * keeping all sets performed on them.
     */
    fun filterByExercise(daySets: List<DaySet>, exercise: String): List<DaySet> {
        val key = exercise.trim().lowercase()
        if (key.isEmpty()) return daySets
        val daysWith = daySets
            .filter { it.exerciseName.trim().lowercase() == key }
            .map { it.date }
            .toSet()
        return daySets.filter { it.date in daysWith }
    }

    /**
     * Streak = consecutive active days. [Streaks.current] tolerates "today not
     * yet trained" (counts from yesterday instead) — Octo semantics.
     */
    fun computeStreaks(activeDates: Set<String>, today: LocalDate): Streaks {
        if (activeDates.isEmpty()) return Streaks(current = 0, best = 0)
        val days = activeDates
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .filter { !it.isAfter(today) }
            .sorted()

        var current = 0
        var cursor = if (today in days) today else today.minusDays(1)
        while (cursor in days) {
            current++
            cursor = cursor.minusDays(1)
        }

        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return Streaks(current = current, best = best)
    }

    /**
     * Full aggregation for one window: per-group sets / volume / training-days
     * / streaks + relative-usage fractions (sets / busiest group's sets in the
     * window) + the overall summary. Groups sort busiest-first.
     *
     * A set credited to SEVERAL muscle groups ([DaySet.attribution]) counts
     * ONCE toward each of those groups, while overall totals stay deduped to
     * the raw series — a 3-set push-up session reads as 3 chest + 3 shoulders
     * + 3 triceps sets but still 3 total sets.
     */
    fun compute(daySets: List<DaySet>, today: LocalDate): StatsResult {
        // (groupId → set) credit pairs: a multi-group set contributes one
        // entry per credited group so per-group streaks/days see their own
        // attribution.
        val credits = daySets.flatMap { set ->
            set.attribution.map { groupId -> groupId to set }
        }
        val maxSets = credits.groupBy { it.first }
            .values.maxOfOrNull { it.size } ?: 0

        val groups = credits
            .groupBy { it.first }
            .map { (groupId, pairs) ->
                val sets = pairs.map { it.second }
                val streaks = computeStreaks(sets.map { it.date }.toSet(), today)
                GroupStat(
                    muscleGroupId = groupId,
                    sets = sets.size,
                    volumeGrams = sets.sumOf { it.weightGrams * it.reps },
                    trainingDays = sets.map { it.date }.distinct().size,
                    currentStreak = streaks.current,
                    bestStreak = streaks.best,
                    usageFraction = if (maxSets <= 0) 0f else sets.size.toFloat() / maxSets,
                )
            }
            .sortedWith(compareByDescending<GroupStat> { it.sets }.thenBy { it.muscleGroupId })

        val overallStreaks = computeStreaks(daySets.map { it.date }.toSet(), today)
        return StatsResult(
            groups = groups,
            overall = OverallStats(
                totalSets = daySets.size,
                volumeGrams = daySets.sumOf { it.weightGrams * it.reps },
                trainingDays = daySets.map { it.date }.distinct().size,
                currentStreak = overallStreaks.current,
                bestStreak = overallStreaks.best,
            ),
        )
    }

    /** Volume label in [unit] ("142.5 kg"); whole values drop the decimal. */
    fun volumeLabel(volumeGrams: Int, unit: String): String =
        WeightUnits.formatGrams(volumeGrams, unit)
}
