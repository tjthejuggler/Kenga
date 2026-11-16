package com.example.kenga.data.weights

import kotlin.math.roundToInt

/**
 * Gram-based weight conversion helpers (Tail `HabitModels.kt` port, §7.3).
 *
 * Weights are stored canonically in GRAMS — both kg and lb inputs round-trip
 * losslessly at 1 g precision — and converted to a ×10-scaled display unit
 * (tenths of kg or lb) for the UI. Logging in lb while the display shows kg
 * (or vice versa) therefore works with no extra configuration.
 */
object WeightUnits {
    const val UNIT_KG = "kg"
    const val UNIT_LB = "lb"

    /** 1 lb = 453.59237 g exactly (same constant as Tail). */
    const val GRAMS_PER_LB = 453.59237

    fun kgToGrams(kg: Double): Int = (kg * 1000.0).roundToInt()

    fun lbToGrams(lb: Double): Int = (lb * GRAMS_PER_LB).roundToInt()

    fun toGrams(value: Double, unit: String): Int =
        if (unit == UNIT_LB) lbToGrams(value) else kgToGrams(value)

    /**
     * Grams → tenths of the display unit (round-to-nearest): kg tenth = 100 g,
     * lb tenth = 45.359237 g.
     */
    fun gramsToDisplayTenths(grams: Int, unit: String): Int =
        if (unit == UNIT_LB) (grams / (GRAMS_PER_LB / 10.0)).roundToInt()
        else (grams / 100.0).roundToInt()

    /** Tenths → "62.5"-style one-decimal label (Tail `formatWeightTenths`). */
    fun formatWeightTenths(tenths: Int): String =
        if (tenths < 0) "-" + formatWeightTenths(-tenths)
        else "${tenths / 10}.${tenths % 10}"

    /** Grams → display label in [unit], whole values without the decimal. */
    fun formatGrams(grams: Int, unit: String): String {
        if (grams <= 0) return "—"
        val tenths = gramsToDisplayTenths(grams, unit)
        return (if (tenths % 10 == 0) "${tenths / 10}" else formatWeightTenths(tenths)) + " $unit"
    }

    /** Input value → one-decimal text for pre-filling fields ("" when 0). */
    fun gramsToInputText(grams: Int, unit: String): String {
        if (grams <= 0) return ""
        val tenths = gramsToDisplayTenths(grams, unit)
        return if (tenths % 10 == 0) (tenths / 10).toString() else formatWeightTenths(tenths)
    }
}

/**
 * Pure PR-evaluation decision (§7.3) — JVM-testable with no Android deps.
 *
 * A set beats the union PB (case-insensitive exercise key is resolved by the
 * caller) when it strictly exceeds the standing best weight OR best reps.
 * First-ever sets (no previous record on a dimension) do not count as a PR —
 * there is no record that fell and no old-record story to tell.
 */
data class PrCheck(
    val weightPr: Boolean,
    val repsPr: Boolean,
    val newWeightGrams: Int,
    val newReps: Int,
    val oldWeightGrams: Int,
    val oldReps: Int,
    val oldWeightDate: String?, // yyyy-MM-dd
    val oldRepsDate: String?, // yyyy-MM-dd
) {
    val isPr: Boolean get() = weightPr || repsPr
}

object PrLogic {
    fun evaluate(
        oldBestWeightGrams: Int,
        oldBestReps: Int,
        newWeightGrams: Int,
        newReps: Int,
        oldWeightDate: String? = null,
        oldRepsDate: String? = null,
    ): PrCheck = PrCheck(
        weightPr = oldBestWeightGrams > 0 && newWeightGrams > oldBestWeightGrams,
        repsPr = oldBestReps > 0 && newReps > oldBestReps,
        newWeightGrams = newWeightGrams,
        newReps = newReps,
        oldWeightGrams = oldBestWeightGrams,
        oldReps = oldBestReps,
        oldWeightDate = oldWeightDate,
        oldRepsDate = oldRepsDate,
    )
}
