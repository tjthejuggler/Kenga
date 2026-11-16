package com.example.kenga.llm

/**
 * Keyword-based fallback classification used when no LLM API key is configured
 * (Octo `LlmHeuristics` port, EXTENDED for Kenga: muscle groups AND ~30 common
 * exercise names). Runs over title + transcript (+ tags) text.
 *
 * Recall-oriented (Octo NOTES §23): the matching threshold is adaptive — real
 * transcripts (≥ [LONG_TEXT_CHARS]) require [MIN_HITS_PER_GROUP] distinct cue
 * hits per muscle group, while thin title/description-only text for a short
 * video needs just one hit.
 *
 * Results are always marked heuristic in the notes so the UI can show
 * "(heuristic)" next to chips derived this way.
 */
object LlmHeuristics {

    /** Cue phrases per canonical muscle-group id. Matched as substrings, ci. */
    private val cuePhrases: Map<String, List<String>> = mapOf(
        "neck" to listOf(
            "neck", "cervical", "chin tuck", "head tilt", "trap stretch",
        ),
        "shoulders" to listOf(
            "shoulder", "deltoid", "rotator cuff", "shoulder blade", "traps",
            "shrug", "lateral raise", "overhead press", "ohp", "face pull",
            "front raise",
        ),
        "chest" to listOf(
            "chest", "pec ", "pecs", "pectoral", "bench press", "bench",
            "dumbbell press", "fly", "flies", "dip", "dips", "push-up",
            "pushup", "press up",
        ),
        "upper_back" to listOf(
            "upper back", "thoracic", "rhomboid", "mid back", "mid-back",
            "shoulder blade", "row", "cable row", "barbell row", "dumbbell row",
            "seal row",
        ),
        "lower_back" to listOf(
            "lower back", "lumbar", "spine", "erector", "deadlift",
            "good morning", "back extension",
        ),
        "core" to listOf(
            "core", "abs", "abdominal", "plank", "navel", "belly", "hollow",
            "lower belly", "stomach", "crunch", "sit-up", "situp", "leg raise",
            "ab wheel",
        ),
        "obliques" to listOf(
            "oblique", "side plank", "twist", "spinal twist", "woodchop",
            "side bend", "russian twist",
        ),
        "lats" to listOf(
            "lat ", "lats", "latissimus", "wide grip", "pull-up", "pullup",
            "pull up", "chin-up", "chinup", "chin up", "lat pulldown",
            "pulldown", "pull-down",
        ),
        "hips_glutes" to listOf(
            "hip", "hips", "glute", "butt", "piriformis", "pigeon",
            "hip flexor", "figure four", "hip thrust", "thrust", "pelvis",
            "abduction", "kickback",
        ),
        "groin_adductors" to listOf(
            "adductor", "groin", "inner thigh", "butterfly", "frog",
            "straddle", "wide-legged",
        ),
        "quads" to listOf(
            "quad", "quads", "quadriceps", "thigh", "front of the thigh",
            "squat", "leg press", "leg extension", "lunge", "split squat",
            "bulgarian", "hack squat", "step-up",
        ),
        "hamstrings" to listOf(
            "hamstring", "hamstrings", "back of the thigh", "back of the leg",
            "romanian deadlift", "rdl", "stiff-leg", "stiff leg", "leg curl",
            "nordic",
        ),
        "calves" to listOf(
            "calf", "calves", "gastroc", "soleus", "calf raise", "toe press",
        ),
        "ankles_feet" to listOf(
            "ankle", "ankles", "foot", "feet", "toe", "toes", "plantar",
        ),
        "wrists_forearms" to listOf(
            "wrist", "wrists", "forearm", "forearms", "palm", "palms",
            "grip", "farmer's walk",
        ),
        "biceps_triceps" to listOf(
            "bicep", "biceps", "triceps", "tricep", "curl", "pushdown",
            "skull crusher", "extension triceps", "arm", "arms",
        ),
    )

    /** Multi-word phrases that imply several muscle groups at once. */
    private val phraseImplications: List<Pair<String, List<String>>> = listOf(
        "full body" to listOf(
            "neck", "shoulders", "chest", "core", "hips_glutes",
            "hamstrings", "quads",
        ),
        "full-body" to listOf(
            "neck", "shoulders", "chest", "core", "hips_glutes",
            "hamstrings", "quads",
        ),
        "full workout" to listOf(
            "neck", "shoulders", "chest", "core", "hips_glutes",
            "hamstrings", "quads",
        ),
        "push day" to listOf("chest", "shoulders", "biceps_triceps"),
        "pull day" to listOf("lats", "upper_back", "biceps_triceps"),
        "leg day" to listOf("quads", "hamstrings", "hips_glutes", "calves"),
    )

    /**
     * Keyword → canonical exercise name + associated muscle groups (PRIMARY
     * FIRST). ORDER MATTERS: the first entry whose ALL keywords match wins, so
     * more specific lifts (front squat, romanian deadlift, cable row, hammer
     * curl…) precede their generic parents (squat, deadlift, row, curl).
     * Keywords are matched as whole words, case-insensitive.
     *
     * Compound lifts — especially body-weight drills — list every group they
     * meaningfully work, matching the LLM prompt's multi-group schema.
     */
    private val exerciseKeywords: List<ExerciseKeyword> = listOf(
        ExerciseKeyword(listOf("bench", "press"), "Bench Press", listOf("chest", "shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("incline", "bench"), "Incline Bench Press", listOf("chest", "shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("front", "squat"), "Front Squat", listOf("quads", "core")),
        ExerciseKeyword(listOf("goblet", "squat"), "Goblet Squat", listOf("quads", "core")),
        ExerciseKeyword(listOf("bulgarian", "split", "squat"), "Bulgarian Split Squat", listOf("quads", "hips_glutes")),
        ExerciseKeyword(listOf("split", "squat"), "Split Squat", listOf("quads", "hips_glutes")),
        ExerciseKeyword(listOf("romanian", "deadlift"), "Romanian Deadlift", listOf("hamstrings", "hips_glutes", "lower_back")),
        ExerciseKeyword(listOf("rdl"), "Romanian Deadlift", listOf("hamstrings", "hips_glutes", "lower_back")),
        ExerciseKeyword(listOf("stiff", "leg", "deadlift"), "Romanian Deadlift", listOf("hamstrings", "hips_glutes", "lower_back")),
        ExerciseKeyword(listOf("deadlift"), "Deadlift", listOf("lower_back", "hamstrings", "upper_back")),
        ExerciseKeyword(listOf("overhead", "press"), "Overhead Press", listOf("shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("ohp"), "Overhead Press", listOf("shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("military", "press"), "Overhead Press", listOf("shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("lat", "pulldown"), "Lat Pulldown", listOf("lats", "upper_back", "biceps_triceps")),
        ExerciseKeyword(listOf("pulldown"), "Lat Pulldown", listOf("lats", "upper_back", "biceps_triceps")),
        ExerciseKeyword(listOf("pull-up"), "Pull-Up", listOf("lats", "upper_back", "biceps_triceps")),
        ExerciseKeyword(listOf("pull", "up"), "Pull-Up", listOf("lats", "upper_back", "biceps_triceps")),
        ExerciseKeyword(listOf("pullup"), "Pull-Up", listOf("lats", "upper_back", "biceps_triceps")),
        ExerciseKeyword(listOf("chin-up"), "Chin-Up", listOf("biceps_triceps", "lats")),
        ExerciseKeyword(listOf("chin", "up"), "Chin-Up", listOf("biceps_triceps", "lats")),
        ExerciseKeyword(listOf("chinup"), "Chin-Up", listOf("biceps_triceps", "lats")),
        ExerciseKeyword(listOf("push-up"), "Push-Up", listOf("chest", "shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("pushup"), "Push-Up", listOf("chest", "shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("push", "up"), "Push-Up", listOf("chest", "shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("cable", "row"), "Cable Row", listOf("upper_back", "lats", "biceps_triceps")),
        ExerciseKeyword(listOf("barbell", "row"), "Barbell Row", listOf("upper_back", "lats", "biceps_triceps")),
        ExerciseKeyword(listOf("dumbbell", "row"), "Dumbbell Row", listOf("upper_back", "lats", "biceps_triceps")),
        ExerciseKeyword(listOf("seated", "row"), "Cable Row", listOf("upper_back", "lats", "biceps_triceps")),
        ExerciseKeyword(listOf("row"), "Row", listOf("upper_back", "lats", "biceps_triceps")),
        ExerciseKeyword(listOf("hammer", "curl"), "Hammer Curl", listOf("biceps_triceps")),
        ExerciseKeyword(listOf("preacher", "curl"), "Preacher Curl", listOf("biceps_triceps")),
        ExerciseKeyword(listOf("leg", "curl"), "Leg Curl", listOf("hamstrings")),
        ExerciseKeyword(listOf("curl"), "Biceps Curl", listOf("biceps_triceps")),
        ExerciseKeyword(listOf("triceps", "pushdown"), "Triceps Pushdown", listOf("biceps_triceps")),
        ExerciseKeyword(listOf("pushdown"), "Triceps Pushdown", listOf("biceps_triceps")),
        ExerciseKeyword(listOf("skull", "crusher"), "Skull Crusher", listOf("biceps_triceps")),
        ExerciseKeyword(listOf("lateral", "raise"), "Lateral Raise", listOf("shoulders")),
        ExerciseKeyword(listOf("side", "raise"), "Lateral Raise", listOf("shoulders")),
        ExerciseKeyword(listOf("leg", "press"), "Leg Press", listOf("quads", "hips_glutes")),
        ExerciseKeyword(listOf("leg", "extension"), "Leg Extension", listOf("quads")),
        ExerciseKeyword(listOf("calf", "raise"), "Calf Raise", listOf("calves")),
        ExerciseKeyword(listOf("lunge"), "Lunge", listOf("quads", "hips_glutes")),
        ExerciseKeyword(listOf("hip", "thrust"), "Hip Thrust", listOf("hips_glutes", "hamstrings")),
        ExerciseKeyword(listOf("glute", "bridge"), "Hip Thrust", listOf("hips_glutes", "hamstrings")),
        ExerciseKeyword(listOf("dip"), "Dip", listOf("chest", "shoulders", "biceps_triceps")),
        ExerciseKeyword(listOf("shrug"), "Shrug", listOf("shoulders", "upper_back")),
        ExerciseKeyword(listOf("face", "pull"), "Face Pull", listOf("shoulders", "upper_back")),
        ExerciseKeyword(listOf("chest", "fly"), "Chest Fly", listOf("chest")),
        ExerciseKeyword(listOf("fly"), "Chest Fly", listOf("chest")),
        ExerciseKeyword(listOf("squat"), "Squat", listOf("quads", "hips_glutes", "core")),
        ExerciseKeyword(listOf("good", "morning"), "Good Morning", listOf("hamstrings", "lower_back")),
    )

    private data class ExerciseKeyword(
        val keywords: List<String>,
        val name: String,
        val muscleGroups: List<String>, // primary mover first
    )

    /** Generic parent lifts, shadowed by any same-muscle variant that matched. */
    private val GENERIC_NAMES = setOf(
        "Squat", "Row", "Biceps Curl", "Deadlift", "Chest Fly", "Dip", "Shrug", "Lunge",
    )

    const val MIN_HITS_PER_GROUP = 2

    /** Text at or below this length is treated as thin (title/description-only). */
    const val LONG_TEXT_CHARS = 1_000

    /** Union result shape matching [LlmClient.ExerciseAnalysis]. */
    fun classify(text: String): LlmClient.ExerciseAnalysis {
        val lower = text.lowercase()
        val thinText = text.length < LONG_TEXT_CHARS
        val minHits = if (thinText) 1 else MIN_HITS_PER_GROUP
        val matched = LinkedHashSet<String>()

        for ((id, phrases) in cuePhrases) {
            var hits = 0
            for (phrase in phrases) {
                hits += countOccurrences(lower, phrase)
                if (hits >= minHits) break
            }
            if (hits >= minHits) matched += id
        }

        for ((phrase, implied) in phraseImplications) {
            if (lower.contains(phrase)) matched += implied
        }

        // Exercise-name detection: first-match-wins over the ORDERED list; each
        // canonical name reported at most once.
        val exercises = LinkedHashMap<String, LlmClient.DetectedExercise>()
        for (entry in exerciseKeywords) {
            if (entry.name in exercises) continue
            // A generic lift is suppressed when a variant with the same PRIMARY
            // muscle already matched ("front squat" + "goblet squat" → no plain
            // "Squat").
            if (entry.name in GENERIC_NAMES &&
                exercises.values.any { it.muscleGroup == entry.muscleGroups.first() }
            ) {
                continue
            }
            if (entry.keywords.all { containsWord(lower, it) }) {
                exercises[entry.name] = LlmClient.DetectedExercise(
                    name = entry.name,
                    muscleGroups = entry.muscleGroups,
                    confidence = 0.5,
                )
                // A detected exercise credits every muscle group it works.
                matched += entry.muscleGroups
            }
        }

        return LlmClient.ExerciseAnalysis(
            muscleGroups = matched.toList(),
            exercises = exercises.values.toList(),
            confidence = when {
                matched.isEmpty() && exercises.isEmpty() -> 0.0
                thinText -> 0.3 // title/description-only — weaker evidence
                else -> 0.4
            },
            notes = if (thinText) {
                "heuristic keyword match on thin text (title/description only; no LLM key configured)"
            } else {
                "heuristic keyword match (no LLM key configured)"
            },
        )
    }

    /** Whole-word, case-insensitive containment; an optional plural "s" is OK. */
    private fun containsWord(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        val pattern = Regex("\\b" + Regex.escape(needle) + "s?\\b")
        return pattern.containsMatchIn(haystack)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isBlank()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
