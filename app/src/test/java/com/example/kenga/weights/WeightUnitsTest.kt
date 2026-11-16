package com.example.kenga.weights

import com.example.kenga.data.weights.WeightUnits
import com.example.kenga.data.weights.WeightUnits.UNIT_KG
import com.example.kenga.data.weights.WeightUnits.UNIT_LB
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gram-conversion helper tests (Tail `WeightsConversionTest` port, §7.3):
 * weights are stored in grams and converted to a ×10 display unit.
 */
class WeightUnitsTest {

    @Test
    fun kgInputConvertsToGrams() {
        assertEquals(62500, WeightUnits.kgToGrams(62.5))
        assertEquals(1250, WeightUnits.kgToGrams(1.25))
        assertEquals(0, WeightUnits.kgToGrams(0.0))
    }

    @Test
    fun lbInputConvertsToGrams() {
        // 1 lb = 453.59237 g exactly
        assertEquals(454, WeightUnits.lbToGrams(1.0))
        assertEquals(45359, WeightUnits.lbToGrams(100.0))
        assertEquals(1134, WeightUnits.lbToGrams(2.5))
    }

    @Test
    fun gramsToKgTenthsRoundsToNearest() {
        assertEquals(625, WeightUnits.gramsToDisplayTenths(62500, UNIT_KG))
        assertEquals(13, WeightUnits.gramsToDisplayTenths(1250, UNIT_KG))
        assertEquals(12, WeightUnits.gramsToDisplayTenths(1249, UNIT_KG))
    }

    @Test
    fun gramsToLbTenths() {
        assertEquals(1000, WeightUnits.gramsToDisplayTenths(45359, UNIT_LB))
        assertEquals(1378, WeightUnits.gramsToDisplayTenths(62500, UNIT_LB))
    }

    @Test
    fun kgAndLbRoundTripWithinOneTenth() {
        for (lbTenths in listOf(250, 550, 1000, 2250, 4500)) {
            val grams = WeightUnits.lbToGrams(lbTenths / 10.0)
            assertEquals(lbTenths, WeightUnits.gramsToDisplayTenths(grams, UNIT_LB))
        }
        for (kgTenths in listOf(50, 125, 625, 1000, 1500)) {
            val grams = WeightUnits.kgToGrams(kgTenths / 10.0)
            assertEquals(kgTenths, WeightUnits.gramsToDisplayTenths(grams, UNIT_KG))
        }
    }

    @Test
    fun tenthsFormatWithOneDecimal() {
        assertEquals("62.5", WeightUnits.formatWeightTenths(625))
        assertEquals("137.8", WeightUnits.formatWeightTenths(1378))
        assertEquals("0.0", WeightUnits.formatWeightTenths(0))
        assertEquals("100.0", WeightUnits.formatWeightTenths(1000))
    }

    @Test
    fun gramsLabelWholeAndDecimal() {
        assertEquals("62.5 kg", WeightUnits.formatGrams(62500, UNIT_KG))
        assertEquals("60 kg", WeightUnits.formatGrams(60000, UNIT_KG))
        assertEquals("—", WeightUnits.formatGrams(0, UNIT_KG))
    }
}
