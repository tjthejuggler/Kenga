package com.example.kenga.weights

import com.example.kenga.data.weights.PrLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-evaluation decision tests (§7.3): which records fall when a new set is
 * compared against the standing union PB for the exercise.
 */
class PrLogicTest {

    @Test
    fun beatsOldWeightOnly() {
        val check = PrLogic.evaluate(oldBestWeightGrams = 60000, oldBestReps = 10, newWeightGrams = 62500, newReps = 8)
        assertTrue(check.weightPr)
        assertFalse(check.repsPr)
        assertTrue(check.isPr)
        assertEquals(60000, check.oldWeightGrams)
        assertEquals(10, check.oldReps)
    }

    @Test
    fun beatsOldRepsOnly() {
        val check = PrLogic.evaluate(60000, 10, 55000, 12)
        assertFalse(check.weightPr)
        assertTrue(check.repsPr)
        assertTrue(check.isPr)
    }

    @Test
    fun beatsBothRecords() {
        val check = PrLogic.evaluate(60000, 10, 62500, 12)
        assertTrue(check.weightPr)
        assertTrue(check.repsPr)
    }

    @Test
    fun tiesDoNotCount() {
        val check = PrLogic.evaluate(60000, 10, 60000, 10)
        assertFalse(check.isPr)
    }

    @Test
    fun firstEverSetIsNotAPr() {
        // No previous record on a dimension → nothing fell, no story to tell.
        val check = PrLogic.evaluate(0, 0, 62500, 12)
        assertFalse(check.weightPr)
        assertFalse(check.repsPr)
        assertFalse(check.isPr)
    }

    @Test
    fun firstRepsWithExistingWeightRecord() {
        val check = PrLogic.evaluate(60000, 0, 60000, 12)
        assertFalse(check.weightPr) // tie, not a beat
        assertFalse(check.repsPr) // no previous reps record
        assertFalse(check.isPr)
    }

    @Test
    fun carriesOldRecordDatesForTheFlashStory() {
        val check = PrLogic.evaluate(
            oldBestWeightGrams = 60000,
            oldBestReps = 10,
            newWeightGrams = 62500,
            newReps = 12,
            oldWeightDate = "2026-01-15",
            oldRepsDate = "2026-02-20",
        )
        assertTrue(check.isPr)
        assertTrue(check.weightPr)
        assertTrue(check.repsPr)
        assertEquals("2026-01-15", check.oldWeightDate)
        assertEquals("2026-02-20", check.oldRepsDate)
    }
}
