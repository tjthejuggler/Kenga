package com.example.kenga.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.kenga.KengaApplication
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.repository.ExercisePb
import com.example.kenga.data.repository.ExerciseRepository
import com.example.kenga.data.repository.PbRepository
import com.example.kenga.data.repository.SetLogRepository
import com.example.kenga.data.settings.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Body dashboard presentation state (§7.1, Phase 8): per-group set counts for
 * TODAY (tier coloring, local + Tail snapshot without double counting), the
 * day's raw rows (per-group volume), per-exercise PBs grouped by muscle id
 * (catalog mapping + today's logged exercises), and the display weight unit.
 */
data class BodyDashboard(
    val date: LocalDate = LocalDate.now(),
    val countsByGroup: Map<String, Int> = emptyMap(),
    val todayLogs: List<SetLogEntity> = emptyList(),
    /** Today's Tail-done sets (per-set rows + body-weight habits), catalog-attributed. */
    val tailSets: List<com.example.kenga.data.repository.PbLogic.TailTodaySet> = emptyList(),
    val pbsByGroup: Map<String, List<ExercisePb>> = emptyMap(),
    val unit: String = SettingsRepository.DEFAULT_WEIGHT_UNIT,
) {
    val totalSets: Int get() = countsByGroup.values.sum()
    val totalVolumeGrams: Int
        get() = todayLogs.sumOf { it.weightGrams * it.reps } +
            tailSets.sumOf { it.weightGrams * it.reps }

    /**
     * Sets done today for one group. Uses [countsByGroup] (local Kenga rows +
     * Tail-derived sets incl. distinct exercises per habit), NOT todayLogs
     * alone — Tail-only workouts must show here too (region dialog + tiers
     * must agree).
     */
    fun setsFor(groupId: String): Int = countsByGroup[groupId] ?: 0

    /** Today's volume for one group in grams (Σ weight × reps, local + Tail). */
    fun volumeGramsFor(groupId: String): Int =
        todayLogs.filter { it.muscleGroupId == groupId }.sumOf { it.weightGrams * it.reps } +
            tailSets.filter { groupId in it.groupIds }.sumOf { it.weightGrams * it.reps }
}

class BodyViewModel(
    private val setLogRepository: SetLogRepository,
    pbRepository: PbRepository,
    exerciseRepository: ExerciseRepository,
    settingsRepository: SettingsRepository,
    tailSynchronizer: com.example.kenga.data.repository.TailSynchronizer,
) : ViewModel() {

    private val today: String = LocalDate.now().toString()

    init {
        // Refresh the Tail mirror on open so per-exercise lastDate PB rows
        // (distinct exercises done today) are fresh even when the user goes
        // straight to the Body tab without visiting the Log tab.
        viewModelScope.launch { runCatching { tailSynchronizer.fullRefresh() } }
    }

    val dashboard: StateFlow<BodyDashboard> = combine(
        listOf(
            pbRepository.observeTodaySetCounts(today),
            setLogRepository.observeByDate(today),
            pbRepository.observeTailTodaySets(today),
            pbRepository.observeExercisePbs(),
            exerciseRepository.all,
            settingsRepository.weightUnit,
        ),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val counts = values[0] as Map<String, Int>
        val logs = values[1] as List<SetLogEntity>
        val tailSets = values[2] as List<com.example.kenga.data.repository.PbLogic.TailTodaySet>
        val pbs = values[3] as Map<String, ExercisePb>
        val catalog = values[4] as List<com.example.kenga.data.local.ExerciseEntity>
        val unit = values[5] as String
        // Exercise→groups mapping: today's logged rows win over the catalog
        // (the row records the group the set was actually logged under);
        // catalog rows credit EVERY associated group (primary first).
        val groupsByKey = buildMap {
            catalog.forEach { put(it.name.lowercase(), it.muscleGroupIds) }
            logs.forEach { put(it.exerciseName.trim().lowercase(), listOf(it.muscleGroupId)) }
        }
        // A PB appears in EVERY region its exercise is associated with.
        val pbsByGroup = mutableMapOf<String, MutableList<ExercisePb>>()
        pbs.values.forEach { pb ->
            val groups = groupsByKey[pb.exerciseName.lowercase()].orEmpty()
                .ifEmpty { listOf("") }
            groups.forEach { groupId ->
                pbsByGroup.getOrPut(groupId) { mutableListOf() }.add(pb)
            }
        }
        val pbsByGroupSorted = pbsByGroup
            .mapValues { (_, list) -> list.sortedByDescending { it.bestWeightGrams } }
        BodyDashboard(
            date = LocalDate.now(),
            countsByGroup = counts,
            todayLogs = logs,
            tailSets = tailSets,
            pbsByGroup = pbsByGroupSorted,
            unit = unit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyDashboard())

    companion object {
        /** Factory pattern consistent with the other screens (manual DI). */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KengaApplication
                BodyViewModel(
                    setLogRepository = app.appContainer.setLogRepository,
                    pbRepository = app.appContainer.pbRepository,
                    exerciseRepository = app.appContainer.exerciseRepository,
                    settingsRepository = app.appContainer.settingsRepository,
                    tailSynchronizer = app.appContainer.tailSynchronizer,
                )
            }
        }
    }
}
