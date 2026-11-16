package com.example.kenga.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the extended keyword-fallback classifier (Octo
 * `LlmHeuristicsTest` port, Kenga scope: muscles AND common exercise names).
 */
class LlmHeuristicsTest {

    // ── Muscle-group classification (Octo port) ──

    @Test
    fun `lift transcript yields the trained muscle groups`() {
        val transcript = """
            Today is chest and triceps day. We start with bench press for four sets,
            then incline bench press, and finish with triceps pushdown. Your chest
            should be fried, and the triceps are working hard on every press.
        """.trimIndent()
        val result = LlmHeuristics.classify(transcript)
        assertTrue(result.muscleGroups.contains("chest"))
        assertTrue(result.muscleGroups.contains("biceps_triceps"))
    }

    @Test
    fun `thin title-only text still captures signal`() {
        val title = "5 Exercises For Bigger Arms: Curl & Skull Crusher Guide #biceps #triceps"
        val result = LlmHeuristics.classify(title)
        assertTrue(result.muscleGroups.contains("biceps_triceps"))
        assertTrue(result.notes.contains("thin text"))
    }

    @Test
    fun `cooking text stays precise - no over-tagging`() {
        val cooking = """
            Welcome to this ten minute cooking video. Find a comfortable seat.
            Take a deep breath. We are roasting a chicken today with rosemary
            and potatoes. Notice the aromas. When the timer rings, let it rest.
        """.trimIndent()
        val result = LlmHeuristics.classify(cooking)
        // "roasting a chicken" must not cue anything; only incidental hits count.
        assertFalse(result.muscleGroups.contains("shoulders"))
        assertFalse(result.muscleGroups.contains("chest"))
    }

    @Test
    fun `single mention in long transcript does not tag - threshold guard`() {
        val filler = ("This is a long podcast excerpt about training philosophy. ").repeat(20)
        val long = filler + " We touched on shoulder health briefly. " + filler
        val result = LlmHeuristics.classify(long)
        assertTrue(long.length >= LlmHeuristics.LONG_TEXT_CHARS)
        assertFalse(result.muscleGroups.contains("shoulders"))
    }

    @Test
    fun `leg day phrase implies the leg muscle groups`() {
        val result = LlmHeuristics.classify("10 minute leg day finisher — squats and lunges")
        assertTrue(result.muscleGroups.containsAll(listOf("quads", "hamstrings", "hips_glutes", "calves")))
    }

    @Test
    fun `thin text is flagged with lower confidence`() {
        val thin = LlmHeuristics.classify("quick bench press tutorial")
        val long = LlmHeuristics.classify(
            "bench press tutorial. ".repeat(200) + " press and lockout throughout.",
        )
        assertTrue(thin.confidence < long.confidence)
    }

    // ── Exercise-name detection (Kenga extension) ──

    @Test
    fun `common lifts map to canonical names`() {
        val text = "We start with bench press, move to squat, then romanian deadlifts, " +
            "overhead press, lat pulldown and finish with lateral raises."
        val result = LlmHeuristics.classify(text)
        val names = result.exercises.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Bench Press", "Squat", "Romanian Deadlift", "Overhead Press")))
        assertTrue(names.contains("Lat Pulldown"))
        assertTrue(names.contains("Lateral Raise"))
    }

    @Test
    fun `rdl abbreviation maps to romanian deadlift not deadlift`() {
        val result = LlmHeuristics.classify("Three sets of RDLs to warm up the hinge.")
        assertEquals(listOf("Romanian Deadlift"), result.exercises.map { it.name })
    }

    @Test
    fun `leg curl is not confused with biceps curl`() {
        val result = LlmHeuristics.classify("Seated leg curl for the hamstrings, then preacher curl.")
        val names = result.exercises.map { it.name }
        assertTrue(names.contains("Leg Curl"))
        assertTrue(names.contains("Preacher Curl"))
        assertFalse(names.contains("Biceps Curl")) // "curl" alone never fires alongside specific variants
    }

    @Test
    fun `specific squat variants win over generic squat`() {
        val result = LlmHeuristics.classify("Front squat 5x5, then goblet squat as a burnout.")
        val names = result.exercises.map { it.name }
        assertTrue(names.contains("Front Squat"))
        assertTrue(names.contains("Goblet Squat"))
        assertFalse(names.contains("Squat")) // generic never fires when a variant matched the same text
    }

    @Test
    fun `each exercise reported once - no duplicates`() {
        val result = LlmHeuristics.classify("Bench press, bench press, BENCH PRESS again!")
        assertEquals(listOf("Bench Press"), result.exercises.map { it.name })
    }

    @Test
    fun `detected exercise credits its primary muscle group`() {
        val result = LlmHeuristics.classify("Heavy deadlift singles today.")
        assertTrue(result.muscleGroups.contains("lower_back"))
        assertEquals("Deadlift", result.exercises.single().name)
        assertEquals("lower_back", result.exercises.single().muscleGroup)
    }

    @Test
    fun `bodyweight exercise carries all its muscle groups - primary first`() {
        val result = LlmHeuristics.classify("Push-ups and pull-ups supersets.")
        val pushUp = result.exercises.first { it.name == "Push-Up" }
        assertEquals(listOf("chest", "shoulders", "biceps_triceps"), pushUp.muscleGroups)
        assertEquals("chest", pushUp.muscleGroup)
        val pullUp = result.exercises.first { it.name == "Pull-Up" }
        assertEquals(listOf("lats", "upper_back", "biceps_triceps"), pullUp.muscleGroups)
        // Every credited group lands in the transcript-level muscle list too.
        assertTrue(result.muscleGroups.containsAll(pushUp.muscleGroups + pullUp.muscleGroups))
    }

    @Test
    fun `substring does not false-match - word boundaries`() {
        // "crow" contains "row", "burgundy" contains… nothing relevant — the
        // whole-word matcher must not fire the Row heuristic.
        val result = LlmHeuristics.classify("Learning to crow pose and hold a burgundy crayon.")
        assertFalse(result.exercises.any { it.name == "Row" })
    }
}
