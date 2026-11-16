package com.example.kenga.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.kenga.KengaApplication
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.repository.ExercisePb
import com.example.kenga.data.repository.LogDraft
import com.example.kenga.data.repository.PbLogic
import com.example.kenga.data.repository.PbRepository
import com.example.kenga.data.repository.TailSynchronizer
import com.example.kenga.data.repository.SetLogRepository
import com.example.kenga.data.settings.SettingsRepository
import com.example.kenga.data.weights.PrCheck
import com.example.kenga.data.weights.PrLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Weights dialog pre-selection state (null = closed). */
data class LogDialogState(
    val muscleGroupId: String = "",
    val exerciseName: String = "",
)

/**
 * Log tab presentation state (§7.3): today's set list (grouped by muscle
 * group in the screen), the weights dialog, PR evaluation + flash, and the
 * delete/undo plumbing. Also consumes [QuickLog] requests from other screens.
 */
class LogViewModel(
    private val setLogRepository: SetLogRepository,
    pbRepository: PbRepository,
    settingsRepository: SettingsRepository,
    tailSynchronizer: TailSynchronizer,
) : ViewModel() {

    private val today: String = LocalDate.now().toString()

    /** Today's rows, oldest first. */
    val todayLogs: StateFlow<List<SetLogEntity>> =
        setLogRepository.observeByDate(today)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Most-recent-first exercise names for the quick-choice chips. */
    val recentExercises: StateFlow<List<String>> =
        pbRepository.observeRecentExerciseNames()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Union PB map (Tail mirror + local logs incl. PENDING) for live readouts + PR checks. */
    val exercisePbs: StateFlow<Map<String, ExercisePb>> =
        pbRepository.observeExercisePbs()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val unit: StateFlow<String> = settingsRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_WEIGHT_UNIT)

    private val _dialog = MutableStateFlow<LogDialogState?>(null)
    val dialog: StateFlow<LogDialogState?> = _dialog.asStateFlow()

    /** Today's Tail-done slot aggregates — "what Tail already has" (§7.3). */
    val tailTodaySets: StateFlow<List<PbLogic.TailTodaySet>> =
        pbRepository.observeTailTodaySets(today)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _prFlash = MutableStateFlow<PrFlashEvent?>(null)
    val prFlash: StateFlow<PrFlashEvent?> = _prFlash.asStateFlow()

    /** Row ids that broke a PB when logged — the day list marks these "PR!". */
    private val _prRowIds = MutableStateFlow<Set<Long>>(emptySet())
    val prRowIds: StateFlow<Set<Long>> = _prRowIds.asStateFlow()

    private var flashVersion = 0

    init {
        // Quick-log entry point: Body region dialog / video detail pre-select here.
        viewModelScope.launch {
            QuickLog.request.collect { request ->
                if (request != null) {
                    _dialog.value = LogDialogState(
                        muscleGroupId = request.muscleGroupId.orEmpty(),
                        exerciseName = request.exerciseName.orEmpty(),
                    )
                    QuickLog.consume()
                    }
                }
            }
    
            // Load-from-Tail (§6.2 READ path): pulling a fresh snapshot on open
            // keeps today's done-sets (weights + body-weight habits), PBs, the
            // recent-exercise chips and the LLM muscle-group associations in sync
            // with what was marked done in Tail since the last sync. Fail-safe:
            // every step degrades silently when Tail is unreachable.
            viewModelScope.launch { tailSynchronizer.fullRefresh() }
        }

    /** PB readout for the dialog's live "PB + last set" line (case-insensitive). */
    fun pbFor(exerciseName: String): ExercisePb? =
        exercisePbs.value[exerciseName.trim().lowercase()]

    fun openDialog(muscleGroupId: String = "", exerciseName: String = "") {
        _dialog.value = LogDialogState(muscleGroupId, exerciseName)
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun dismissPrFlash() {
        _prFlash.value = null
    }

    /**
     * Save the dialog's set: PR-evaluate against the union PB BEFORE insert
     * (the insert itself changes the union), then persist + broadcast and
     * fire the flash / mark the row if a record fell.
     */
    fun confirm(draft: LogDraft) {
        _dialog.value = null
        viewModelScope.launch {
            val key = draft.exerciseName.trim().lowercase()
            val pb = exercisePbs.value[key]
            val check: PrCheck = PrLogic.evaluate(
                oldBestWeightGrams = pb?.bestWeightGrams ?: 0,
                oldBestReps = pb?.bestReps ?: 0,
                newWeightGrams = draft.weightGrams,
                newReps = draft.reps,
                oldWeightDate = pb?.bestWeightDate,
                oldRepsDate = pb?.bestRepsDate,
            )
            val id = setLogRepository.insert(draft)
            if (check.isPr) {
                _prRowIds.value = _prRowIds.value + id
                _prFlash.value =
                    PrFlashEvent(version = ++flashVersion, exerciseName = draft.exerciseName, check = check)
            }
        }
    }

    /** Delete with compensating Tail correction; returns the row for undo (null if gone). */
    suspend fun delete(id: Long): SetLogEntity? = setLogRepository.delete(id)

    /** Undo: re-insert + re-broadcast like a fresh log. */
    fun restore(row: SetLogEntity) {
        viewModelScope.launch { setLogRepository.restore(row) }
    }

    /** Factory pattern consistent with SettingsViewModel. */
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KengaApplication
                LogViewModel(
                    setLogRepository = app.appContainer.setLogRepository,
                    pbRepository = app.appContainer.pbRepository,
                    settingsRepository = app.appContainer.settingsRepository,
                    tailSynchronizer = app.appContainer.tailSynchronizer,
                )
            }
        }
    }
}
