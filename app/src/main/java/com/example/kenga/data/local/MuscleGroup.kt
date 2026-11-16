package com.example.kenga.data.local

/**
 * The 16 canonical muscle-group ids (plans/kenga_architecture.md §3 — identical
 * to Octo's body-part ids so the Phase 4 LLM prompt stays compatible).
 *
 * Used by the Library filter row + video chips now, and by the analysis
 * pipeline, body map and Tail habit mapping later.
 */
enum class MuscleGroup(val id: String, val displayName: String) {
    NECK("neck", "Neck"),
    SHOULDERS("shoulders", "Shoulders"),
    CHEST("chest", "Chest"),
    UPPER_BACK("upper_back", "Upper back"),
    LOWER_BACK("lower_back", "Lower back"),
    CORE("core", "Core"),
    OBLIQUES("obliques", "Obliques"),
    LATS("lats", "Lats"),
    HIPS_GLUTES("hips_glutes", "Hips & glutes"),
    GROIN_ADDUCTORS("groin_adductors", "Groin"),
    QUADS("quads", "Quads"),
    HAMSTRINGS("hamstrings", "Hamstrings"),
    CALVES("calves", "Calves"),
    ANKLES_FEET("ankles_feet", "Ankles & feet"),
    WRISTS_FOREARMS("wrists_forearms", "Wrists & forearms"),
    BICEPS_TRICEPS("biceps_triceps", "Biceps & triceps"),
    ;

    companion object {
        fun fromId(id: String): MuscleGroup? = entries.firstOrNull { it.id == id }
    }
}
