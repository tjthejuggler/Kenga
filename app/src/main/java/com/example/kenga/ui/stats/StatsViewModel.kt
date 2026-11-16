package com.example.kenga.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.kenga.KengaApplication
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.local.SetLogDao
import com.example.kenga.data.local.TailSnapshotDao
import com.example.kenga.data.settings.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One usage-bar row: [GroupStat] resolved with its display name + volume label. */
data class GroupRow(
    val muscleGroupId: String,
    val displayName: String,
    val sets: Int,
    val volumeLabel: String,
    val trainingDays: Int,
    val usageFraction: Float,
)

data class StatsUiState(
    val range: StatsRange = StatsRange.D30,
    val loading: Boolean = true,
    val unit: String = SettingsRepository.DEFAULT_WEIGHT_UNIT,
    /** Rows for every group with data in the window, busiest first. */
    val groups: List<GroupRow> = emptyList(),
    val totalSets: Int = 0,
    val volumeLabel: String = "—",
    val trainingDays: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    /** Every exercise name recorded in the window (chips). */
    val exercises: List<String> = emptyList(),
    /** "" = no filter. */
    val exerciseFilter: String = "",
) {
    val isEmpty: Boolean get() = totalSets == 0 && groups.isEmpty()
}

/**
 * Stats tab (§7.4, Phase 9): aggregates the combined local-log + Tail-snapshot
 * day-series per selected range and exercise filter (Octo `StatsViewModel`
 * pattern; the aggregation itself lives in the pure [StatsMath]).
 */
class StatsViewModel(
    setLogDao: SetLogDao,
    tailSnapshotDao: TailSnapshotDao,
    tailWeightSetDao: com.example.kenga.data.local.TailWeightSetDao,
    exerciseDao: com.example.kenga.data.local.ExerciseDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _range = MutableStateFlow(StatsRange.D30)
    val range: StateFlow<StatsRange> = _range.asStateFlow()

    private val _exerciseFilter = MutableStateFlow("")
    val exerciseFilter: StateFlow<String> = _exerciseFilter.asStateFlow()

    /** kg/lb display toggle — display conversion only, storage stays grams. */
    private val _unit = MutableStateFlow(SettingsRepository.DEFAULT_WEIGHT_UNIT)
    val unit: StateFlow<String> = _unit.asStateFlow()

    private data class Sources(
        val logs: List<com.example.kenga.data.local.SetLogEntity> = emptyList(),
        val snapshots: List<com.example.kenga.data.local.TailSnapshotEntity> = emptyList(),
        val weightSets: List<com.example.kenga.data.local.TailWeightSetEntity> = emptyList(),
        val catalog: Map<String, String> = emptyMap(),
    )

    private val sources = combine(
        setLogDao.observeAll(),
        tailSnapshotDao.observeAll(),
        tailWeightSetDao.observeAll(),
        exerciseDao.observeAll(),
    ) { logs, snapshots, weightSets, catalogRows ->
        Sources(
            logs = logs,
            snapshots = snapshots,
            weightSets = weightSets,
            catalog = catalogRows
                .filter { it.muscleGroupId.isNotBlank() }
                .associate { it.name.lowercase() to it.muscleGroupId },
        )
    }

    val state: StateFlow<StatsUiState> = combine(
        sources,
        _range,
        _exerciseFilter,
        _unit,
    ) { src, range, filter, unit ->
        val today = LocalDate.now()
        val from = range.fromDate(today)
        val allSets = StatsMath.combinedDaySets(
            src.logs, src.weightSets, src.snapshots, src.catalog, from, today.toString(),
        )
        val filtered = StatsMath.filterByExercise(allSets, filter)
        val result = StatsMath.compute(filtered, today)
        StatsUiState(
            range = range,
            loading = false,
            unit = unit,
            groups = result.groups.map { it.asRow(unit) },
            totalSets = result.overall.totalSets,
            volumeLabel = StatsMath.volumeLabel(result.overall.volumeGrams, unit),
            trainingDays = result.overall.trainingDays,
            currentStreak = result.overall.currentStreak,
            bestStreak = result.overall.bestStreak,
            exercises = StatsMath.exerciseNames(allSets),
            exerciseFilter = filter,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun GroupStat.asRow(unit: String) = GroupRow(
        muscleGroupId = muscleGroupId,
        displayName = MuscleGroup.fromId(muscleGroupId)?.displayName ?: muscleGroupId,
        sets = sets,
        volumeLabel = StatsMath.volumeLabel(volumeGrams, unit),
        trainingDays = trainingDays,
        usageFraction = usageFraction,
    )

    fun selectRange(range: StatsRange) {
        _range.value = range
    }

    /** "" clears the filter; clears itself when the exercise leaves the window. */
    fun selectExercise(name: String) {
        _exerciseFilter.value = if (_exerciseFilter.value == name) "" else name
    }

    fun selectUnit(unit: String) {
        _unit.value = if (unit == com.example.kenga.data.weights.WeightUnits.UNIT_LB)
            com.example.kenga.data.weights.WeightUnits.UNIT_LB else
            com.example.kenga.data.weights.WeightUnits.UNIT_KG
    }

    companion object {
        /** Factory pattern consistent with the other screens (manual DI). */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KengaApplication
                StatsViewModel(
                    setLogDao = app.appContainer.database.setLogDao(),
                    tailSnapshotDao = app.appContainer.database.tailSnapshotDao(),
                    tailWeightSetDao = app.appContainer.database.tailWeightSetDao(),
                    exerciseDao = app.appContainer.database.exerciseDao(),
                    settingsRepository = app.appContainer.settingsRepository,
                )
            }
        }
    }
}
