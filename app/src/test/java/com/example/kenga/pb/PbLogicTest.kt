package com.example.kenga.pb

import com.example.kenga.data.local.PbEntity
import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.local.TailHabitEntryEntity
import com.example.kenga.data.local.TailSnapshotEntity
import com.example.kenga.data.local.TailSyncState
import com.example.kenga.data.repository.PbLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure PB-union / today-count dedup logic (§6.2).
 */
class PbLogicTest {

    private fun log(
        ts: Long,
        date: String,
        exercise: String,
        weight: Int,
        reps: Int,
        state: TailSyncState = TailSyncState.PENDING,
        group: String = "chest",
    ) = SetLogEntity(ts = ts, muscleGroupId = group, exerciseName = exercise,
        weightGrams = weight, reps = reps, machine = true, date = date,
        tailSyncState = state)

    // ── unionPbs ──────────────────────────────────────────────────────────

    @Test
    fun `local pending log beats tail pb weight`() {
        val tail = PbEntity("bench press", "Gym", bestWeightGrams = 80_000, bestReps = 5,
            bestWeightDate = "2026-10-01", bestRepsDate = "2026-10-01")
        val local = listOf(log(1, "2026-10-24", "Bench Press", 82_500, 3))

        val out = PbLogic.unionPbs(listOf(tail), local, emptyList())

        val pb = out.getValue("bench press")
        assertEquals(82_500, pb.bestWeightGrams)
        assertEquals("2026-10-24", pb.bestWeightDate)
        // Best reps still Tail's (5 > 3).
        assertEquals(5, pb.bestReps)
        assertEquals("2026-10-01", pb.bestRepsDate)
        // Last set is the local log.
        assertEquals(82_500, pb.lastWeightGrams)
        assertEquals(3, pb.lastReps)
        assertEquals("2026-10-24", pb.lastDate)
    }

    @Test
    fun `day max weight semantics for local logs`() {
        // Two sets same day: day-max = 90kg, not 170kg.
        val local = listOf(
            log(1, "2026-10-24", "Squat", 80_000, 5),
            log(2, "2026-10-24", "Squat", 90_000, 3),
        )
        val out = PbLogic.unionPbs(emptyList(), local, emptyList())
        assertEquals(90_000, out.getValue("squat").bestWeightGrams)
    }

    @Test
    fun `tail-only exercise keeps tail pb and gains snapshot last set`() {
        val tail = PbEntity("row", "Gym", bestWeightGrams = 60_000, bestReps = 10,
            bestWeightDate = "2026-10-01", bestRepsDate = "2026-10-01")
        val snap = TailSnapshotEntity("Gym", "2026-10-20",
            machineWeightG = 55_000, machineReps = 8, machineExercise = "Row")

        val out = PbLogic.unionPbs(listOf(tail), emptyList(), listOf(snap))

        val pb = out.getValue("row")
        assertEquals(60_000, pb.bestWeightGrams) // PB untouched by later smaller set
        assertEquals(55_000, pb.lastWeightGrams)
        assertEquals(8, pb.lastReps)
        assertEquals("2026-10-20", pb.lastDate)
    }

    @Test
    fun `union is case-insensitive`() {
        val local = listOf(log(1, "2026-10-24", "Bench Press", 50_000, 5))
        val tail = PbEntity("BENCH PRESS", "Gym", bestWeightGrams = 40_000, bestReps = 5,
            bestWeightDate = "2026-10-01", bestRepsDate = "2026-10-01")
        val out = PbLogic.unionPbs(listOf(tail), local, emptyList())
        assertEquals(1, out.size)
        assertEquals(50_000, out.getValue("bench press").bestWeightGrams)
    }

    @Test
    fun `empty inputs give empty map`() {
        assertTrueOrEmpty(PbLogic.unionPbs(emptyList(), emptyList(), emptyList()))
    }

    private fun assertTrueOrEmpty(map: Map<String, *>) = assertEquals(0, map.size)

    // ── tailSetsToday + todaySetCounts (per-set attribution) ──────────────

    private val catalog = mapOf(
        "lat pulldown" to "lats",
        "seated cable row" to "upper_back",
        "bench" to "chest",
        "pull-up" to "lats",
    )

    private fun wset(
        habit: String,
        time: String,
        exercise: String,
        grams: Int,
        reps: Int,
        date: String = "2026-10-24",
    ) = com.example.kenga.data.local.TailWeightSetEntity(
        habitName = habit, date = date, time = time,
        exerciseName = exercise, machine = true, weightGrams = grams, reps = reps,
    )

    @Test
    fun `local rows always count`() {
        val counts = PbLogic.todaySetCounts(
            localLogsToday = listOf(
                log(1, "2026-10-24", "Bench", 50_000, 5, state = TailSyncState.SENT),
                log(2, "2026-10-24", "Fly", 30_000, 10, state = TailSyncState.PENDING),
            ),
            tailSetsToday = emptyList(),
        )
        assertEquals(2, counts["chest"])
    }

    @Test
    fun `every per-set sidecar row is one set with its own catalog group`() {
        // The reported scenario: one back habit, 2× Lat Pulldown + 1× Seated
        // Cable Row = 3 sets — lats 2, upper_back 1.
        val sets = PbLogic.tailSetsToday(
            today = "2026-10-24",
            localLogsToday = emptyList(),
            snapshotsToday = emptyList(),
            weightSetsToday = listOf(
                wset("Back", "10:00:00", "Lat Pulldown", 40_000, 8),
                wset("Back", "10:05:00", "Lat Pulldown", 42_500, 8),
                wset("Back", "10:10:00", "Seated Cable Row", 35_000, 10),
            ),
            catalog = catalog,
        )
        assertEquals(3, sets.size)

        val counts = PbLogic.todaySetCounts(emptyList(), sets)
        assertEquals(2, counts["lats"])
        assertEquals(1, counts["upper_back"])
    }

    @Test
    fun `tail set with several muscle groups credits each of them`() {
        // One push-up set (CSV: chest, shoulders, biceps_triceps) → +1 to all three.
        val sets = listOf(
            PbLogic.TailTodaySet(
                muscleGroupId = "chest,shoulders,biceps_triceps",
                habitName = "Push-ups",
                exerciseName = "Push-Up",
                weightGrams = 0,
                reps = 15,
                machine = false,
            ),
        )
        val counts = PbLogic.todaySetCounts(emptyList(), sets)
        assertEquals(1, counts["chest"])
        assertEquals(1, counts["shoulders"])
        assertEquals(1, counts["biceps_triceps"])
        assertNull(counts["lats"])
    }

    @Test
    fun `sidecar set not double-counted when local sent row mirrors the exercise`() {
        val sets = PbLogic.tailSetsToday(
            today = "2026-10-24",
            localLogsToday = listOf(log(1, "2026-10-24", "Lat Pulldown", 40_000, 8, state = TailSyncState.SENT)),
            snapshotsToday = emptyList(),
            weightSetsToday = listOf(wset("Back", "10:00:00", "Lat Pulldown", 40_000, 8)),
            catalog = catalog,
        )
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `snapshot fallback only for habits without sidecar rows`() {
        val sets = PbLogic.tailSetsToday(
            today = "2026-10-24",
            localLogsToday = emptyList(),
            snapshotsToday = listOf(
                TailSnapshotEntity("Home Gym", "2026-10-24",
                    machineExercise = "Lat Pulldown", machineWeightG = 40_000, machineReps = 8),
                // "Back" habit IS covered by the sidecar → snapshot ignored.
                TailSnapshotEntity("Back", "2026-10-24",
                    machineExercise = "Lat Pulldown", machineWeightG = 40_000, machineReps = 8),
            ),
            weightSetsToday = listOf(wset("Back", "10:00:00", "Lat Pulldown", 40_000, 8)),
            catalog = catalog,
        )
        // One set from the sidecar ("Back") + one from the uncovered habit's
        // snapshot slot ("Home Gym") — the "Back" snapshot row is skipped.
        assertEquals(2, sets.size)
        val fromSnapshot = sets.first { it.habitName == "Home Gym" }
        assertEquals("lats", fromSnapshot.muscleGroupId)
        assertEquals("Lat Pulldown", fromSnapshot.exerciseName)
    }

    @Test
    fun `other-day rows ignored`() {
        val sets = PbLogic.tailSetsToday(
            today = "2026-10-24",
            localLogsToday = emptyList(),
            snapshotsToday = listOf(TailSnapshotEntity("Gym", "2026-10-23", machineWeightG = 50_000, machineReps = 5)),
            weightSetsToday = listOf(wset("Gym", "10:00:00", "Bench", 50_000, 5, date = "2026-10-23")),
            catalog = catalog,
        )
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `inactive snapshot slots contribute nothing`() {
        val sets = PbLogic.tailSetsToday(
            today = "2026-10-24",
            localLogsToday = emptyList(),
            snapshotsToday = listOf(TailSnapshotEntity("Gym", "2026-10-24")),
            weightSetsToday = emptyList(),
            catalog = catalog,
        )
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `bodyweight entries become tail sets with subcategory variants`() {
        val entries = listOf(
            TailHabitEntryEntity("Pull-ups", "", "2026-10-24", 12, 0),
            TailHabitEntryEntity("Pull-ups", "wide grip", "2026-10-24", 5, 0),
            TailHabitEntryEntity("Push-ups", "", "2026-10-23", 20, 0), // other day
        )
        val bwMap = mapOf("Pull-ups" to "Pull-Up", "Push-ups" to "Push-Up")
        val bwCatalog = mapOf("pull-up" to "lats", "pull-up (wide grip)" to "lats")

        val rows = PbLogic.bodyweightTodaySets("2026-10-24", entries, bwMap, bwCatalog)
        assertEquals(listOf("Pull-Up", "Pull-Up (wide grip)"), rows.map { it.exerciseName })
        assertEquals(listOf(12, 5), rows.map { it.reps })
        assertTrue(rows.all { it.muscleGroupId == "lats" && it.weightGrams == 0 })

        val counts = PbLogic.todaySetCounts(
            localLogsToday = emptyList(),
            tailSetsToday = PbLogic.tailSetsToday(
                today = "2026-10-24",
                localLogsToday = emptyList(),
                snapshotsToday = emptyList(),
                weightSetsToday = emptyList(),
                catalog = bwCatalog,
                bodyweightToday = entries,
                bodyweightMap = bwMap,
            ),
        )
        assertEquals(2, counts["lats"])
    }

    @Test
    fun `bodyweight habit without mapping contributes nothing`() {
        val sets = PbLogic.tailSetsToday(
            today = "2026-10-24",
            localLogsToday = emptyList(),
            snapshotsToday = emptyList(),
            weightSetsToday = emptyList(),
            catalog = catalog,
            bodyweightToday = listOf(TailHabitEntryEntity("Reading", "", "2026-10-24", 1, 0)),
            bodyweightMap = emptyMap(),
        )
        assertTrue(sets.isEmpty())
        assertEquals(emptyMap<String, Int>(), PbLogic.todaySetCounts(emptyList(), sets))
    }
}
