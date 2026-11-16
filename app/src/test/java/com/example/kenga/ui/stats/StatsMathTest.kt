package com.example.kenga.ui.stats

import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.local.TailSnapshotEntity
import com.example.kenga.data.local.TailSyncState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the Phase 9 stats math (Octo StatsMathTest style). */
class StatsMathTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 15)
    private fun iso(d: LocalDate): String = d.toString()
    private val todayIso = iso(today)

    private val habitMap = mapOf("chest" to "Push", "quads" to "Leg Press")

    private fun log(
        date: String,
        group: String,
        exercise: String,
        grams: Int,
        reps: Int,
        state: TailSyncState = TailSyncState.SENT,
    ) = SetLogEntity(
        ts = 0L,
        muscleGroupId = group,
        exerciseName = exercise,
        weightGrams = grams,
        reps = reps,
        machine = true,
        date = date,
        tailSyncState = state,
    )

    private fun snap(
        habit: String,
        date: String,
        machineGrams: Int = 0,
        machineReps: Int = 0,
        freeGrams: Int = 0,
        freeReps: Int = 0,
        machineExercise: String? = null,
        freeExercise: String? = null,
    ) = TailSnapshotEntity(
        habitName = habit,
        date = date,
        machineWeightG = machineGrams,
        machineReps = machineReps,
        freeWeightG = freeGrams,
        freeReps = freeReps,
        machineExercise = machineExercise,
        freeExercise = freeExercise,
    )

    // ── Range bucketing ──

    @Test
    fun `fixed ranges start inclusive of today`() {
        assertEquals(iso(today.minusDays(6)), StatsRange.D7.fromDate(today))
        assertEquals(iso(today.minusDays(29)), StatsRange.D30.fromDate(today))
        assertEquals(iso(today.minusDays(89)), StatsRange.D90.fromDate(today))
    }

    @Test
    fun `ALL range is open ended`() {
        assertEquals("0000-01-01", StatsRange.ALL.fromDate(today))
    }

    @Test
    fun `combined series drops rows outside the window`() {
        val logs = listOf(
            log(todayIso, "chest", "Bench", 60_000, 5),
            log(iso(today.minusDays(8)), "chest", "Bench", 60_000, 5),
        )
        val sets = StatsMath.combinedDaySets(logs, emptyList(), emptyList(), emptyMap(), StatsRange.D7.fromDate(today), todayIso)
        assertEquals(1, sets.size)
        assertEquals(todayIso, sets[0].date)
    }

    // ── Local / Tail union + no-double-count ──

    @Test
    fun `local logs always count regardless of sync state`() {
        val logs = listOf(
            log(todayIso, "chest", "Bench", 60_000, 5, state = TailSyncState.PENDING),
            log(todayIso, "chest", "Bench", 62_500, 3, state = TailSyncState.OFFLINE),
        )
        val sets = StatsMath.combinedDaySets(logs, emptyList(), emptyList(), emptyMap(), "0000-01-01", todayIso)
        assertEquals(2, sets.size)
    }

    private val catalog =
        mapOf("leg press" to "quads", "dumbbell press" to "chest", "bench" to "chest", "lat pulldown" to "lats")

    private fun wset(
        habit: String,
        date: String,
        exercise: String,
        grams: Int,
        reps: Int,
    ) = com.example.kenga.data.local.TailWeightSetEntity(
        habitName = habit, date = date, time = "10:00:00",
        exerciseName = exercise, machine = true, weightGrams = grams, reps = reps,
    )

    @Test
    fun `sidecar sets contribute with catalog attribution when no local mirror exists`() {
        val sets = StatsMath.combinedDaySets(
            emptyList(),
            listOf(
                wset("Leg Press habit", todayIso, "Leg press", 100_000, 10),
                wset("Push", todayIso, "Dumbbell press", 50_000, 8),
            ),
            emptyList(), catalog, "0000-01-01", todayIso,
        )
        assertEquals(2, sets.size)
        assertTrue(sets.any { it.muscleGroupId == "quads" && it.exerciseName == "Leg press" })
        assertTrue(sets.any { it.muscleGroupId == "chest" && it.exerciseName == "Dumbbell press" })
    }

    @Test
    fun `multi-group catalog csv credits every group without inflating totals`() {
        // Push-up catalog row lists chest, shoulders, biceps_triceps (CSV).
        val multiCatalog = mapOf("push-up" to "chest,shoulders,biceps_triceps")
        val sets = StatsMath.combinedDaySets(
            emptyList(),
            listOf(wset("Bodyweight", todayIso, "Push-Up", 0, 15)),
            emptyList(), multiCatalog, "0000-01-01", todayIso,
        )
        assertEquals(1, sets.size)
        // Per-group credit: the single set counts once toward each group.
        val result = StatsMath.compute(sets, today)
        assertEquals(3, result.groups.size)
        assertTrue(result.groups.all { it.sets == 1 })
        // Overall totals stay deduped to the raw series: still ONE set.
        assertEquals(1, result.overall.totalSets)
    }

    @Test
    fun `sidecar set skipped when a SENT local log mirrors the exercise that day`() {
        val logs = listOf(log(todayIso, "chest", "Bench", 60_000, 5)) // SENT mirror of "Bench"
        val sets = StatsMath.combinedDaySets(
            logs,
            listOf(wset("Push", todayIso, "Bench", 60_000, 5)),
            emptyList(), catalog, "0000-01-01", todayIso,
        )
        assertEquals(1, sets.size)
        assertEquals("Bench", sets[0].exerciseName)
    }

    @Test
    fun `PENDING local log does not suppress the tail mirror`() {
        val logs = listOf(log(todayIso, "chest", "Bench", 60_000, 5, state = TailSyncState.PENDING))
        val sets = StatsMath.combinedDaySets(
            logs,
            listOf(wset("Push", todayIso, "Lat Pulldown", 60_000, 5)),
            emptyList(), catalog, "0000-01-01", todayIso,
        )
        assertEquals(2, sets.size)
    }

    @Test
    fun `unknown exercises and empty snapshot slots contribute nothing`() {
        val snaps = listOf(
            snap("Unknown habit", todayIso, machineGrams = 10_000, machineReps = 3), // no exercise name
            snap("Push", todayIso), // both slots empty
        )
        val sets = StatsMath.combinedDaySets(
            emptyList(), emptyList(), snaps, catalog, "0000-01-01", todayIso,
        )
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `snapshot slots are fallback for habits without sidecar rows`() {
        val snaps = listOf(snap("Leg Press habit", todayIso, machineGrams = 100_000, machineReps = 10, machineExercise = "Leg press"))
        val sets = StatsMath.combinedDaySets(emptyList(), emptyList(), snaps, catalog, "0000-01-01", todayIso)
        assertEquals(1, sets.size)
        assertEquals("quads", sets[0].muscleGroupId)
    }

    // ── Aggregation: sets, volume, training days, relative fractions ──

    @Test
    fun `compute aggregates sets volume days and relative usage`() {
        val daySets = listOf(
            DaySet(todayIso, "chest", "Bench", 60_000, 5), // 300 kg vol
            DaySet(todayIso, "chest", "Bench", 60_000, 3), // 180
            DaySet(iso(today.minusDays(1)), "quads", "Leg press", 100_000, 10), // 1000
        )
        val result = StatsMath.compute(daySets, today)

        val chest = result.groups.first { it.muscleGroupId == "chest" }
        val quads = result.groups.first { it.muscleGroupId == "quads" }
        assertEquals(2, chest.sets)
        assertEquals(480_000, chest.volumeGrams)
        assertEquals(1, chest.trainingDays)
        assertEquals(1f, chest.usageFraction, 1e-6f) // busiest group
        assertEquals(1, quads.sets)
        assertEquals(0.5f, quads.usageFraction, 1e-6f)
        assertEquals(3, result.overall.totalSets)
        assertEquals(1_480_000, result.overall.volumeGrams)
        assertEquals(2, result.overall.trainingDays)
        // groups sort busiest-first
        assertEquals("chest", result.groups.first().muscleGroupId)
    }

    @Test
    fun `relative fractions are zero when there is no data`() {
        val result = StatsMath.compute(emptyList(), today)
        assertTrue(result.groups.isEmpty())
        assertEquals(0, result.overall.totalSets)
        assertEquals(0, result.overall.currentStreak)
    }

    // ── Streaks (incl. gaps) ──

    @Test
    fun `overall and per-group streaks including gaps`() {
        val daySets = listOf(
            DaySet(iso(today.minusDays(0)), "chest", "Bench", 60_000, 5),
            DaySet(iso(today.minusDays(1)), "chest", "Bench", 60_000, 5),
            DaySet(iso(today.minusDays(4)), "chest", "Bench", 60_000, 5),
            DaySet(iso(today.minusDays(5)), "chest", "Bench", 60_000, 5),
            DaySet(iso(today.minusDays(6)), "chest", "Bench", 60_000, 5),
            DaySet(iso(today.minusDays(10)), "quads", "Leg press", 100_000, 8),
        )
        val result = StatsMath.compute(daySets, today)
        val chest = result.groups.first { it.muscleGroupId == "chest" }
        assertEquals(2, chest.currentStreak) // gap at -2/-3
        assertEquals(3, chest.bestStreak) // -6..-4
        assertEquals(0, result.groups.first { it.muscleGroupId == "quads" }.currentStreak)
        assertEquals(2, result.overall.currentStreak)
        assertEquals(3, result.overall.bestStreak)
    }

    @Test
    fun `current streak tolerates a not-yet-trained today`() {
        val dates = setOf(iso(today.minusDays(1)), iso(today.minusDays(2)))
        assertEquals(2, StatsMath.computeStreaks(dates, today).current)
    }

    @Test
    fun `streaks ignore malformed and future dates`() {
        val s = StatsMath.computeStreaks(setOf("garbage", iso(today.plusDays(3)), iso(today)), today)
        assertEquals(1, s.current)
        assertEquals(1, s.best)
    }

    // ── Exercise filtering ──

    @Test
    fun `exercise filter narrows to days logged with that exercise`() {
        val daySets = listOf(
            DaySet(iso(today.minusDays(2)), "chest", "Bench", 60_000, 5),
            DaySet(iso(today.minusDays(2)), "quads", "Leg press", 100_000, 10), // same day, other exercise
            DaySet(iso(today.minusDays(1)), "chest", "Fly", 30_000, 12),
        )
        val filtered = StatsMath.filterByExercise(daySets, "bench") // case-insensitive
        assertEquals(2, filtered.size) // whole day of -2 kept, -1 dropped
        val result = StatsMath.compute(filtered, today)
        assertEquals(2, result.overall.totalSets)
        assertEquals(1, result.overall.trainingDays)
    }

    @Test
    fun `empty exercise filter keeps everything`() {
        val daySets = listOf(DaySet(todayIso, "chest", "Bench", 60_000, 5))
        assertEquals(daySets, StatsMath.filterByExercise(daySets, ""))
        assertEquals(daySets, StatsMath.filterByExercise(daySets, "  "))
    }

    @Test
    fun `exercise names dedupe case-insensitively and sort`() {
        val daySets = listOf(
            DaySet(todayIso, "chest", "bench", 60_000, 5),
            DaySet(todayIso, "chest", "Bench", 60_000, 5),
            DaySet(todayIso, "chest", "Fly", 30_000, 12),
            DaySet(todayIso, "chest", " ", 30_000, 12), // blank ignored
        )
        assertEquals(listOf("bench", "Fly"), StatsMath.exerciseNames(daySets))
    }

    // ── Gram → unit conversion ──

    @Test
    fun `volume label converts grams to kg and lb`() {
        assertEquals("100 kg", StatsMath.volumeLabel(100_000, "kg"))
        assertEquals("1 kg", StatsMath.volumeLabel(1000, "kg"))
        assertEquals("0.5 kg", StatsMath.volumeLabel(500, "kg"))
        // 45359.237 g = 100.0 lb exactly at tenths precision
        assertEquals("100 lb", StatsMath.volumeLabel(45_359, "lb"))
        assertEquals("—", StatsMath.volumeLabel(0, "kg"))
    }
}
