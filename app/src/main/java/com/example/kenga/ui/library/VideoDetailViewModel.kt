package com.example.kenga.ui.library

import android.content.Context
import com.example.kenga.data.local.AnalysisState
import com.example.kenga.player.PlayerManager
import com.example.kenga.work.DownloadProgress
import com.example.kenga.work.DownloadVideoWorker
import kotlinx.coroutines.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.kenga.KengaApplication
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.local.VideoEntity
import com.example.kenga.data.repository.VideoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Video detail presentation state (basic Phase 2 version): the live row plus
 * parsed muscle groups / exercise names. Phase 3 attaches the player and
 * Phase 4 the analysis results + editable exercise chips.
 */
class VideoDetailViewModel(
    private val appContext: Context,
    private val videoId: String,
    private val videoRepository: VideoRepository,
    private val exerciseRepository: com.example.kenga.data.repository.ExerciseRepository,
    val playerManager: PlayerManager,
) : ViewModel() {

    /**
     * A detected exercise with its confidence, parsed from the row's exercises
     * JSON. [muscleGroups] carries ALL associated groups, primary first.
     */
    data class ExerciseView(
        val name: String,
        val muscleGroups: List<String>,
        val confidence: Double,
    ) {
        /** Primary (first) muscle-group id, or "" when unclassified. */
        val muscleGroup: String get() = muscleGroups.firstOrNull().orEmpty()
    }

    data class Detail(
        val video: VideoEntity,
        val muscleGroups: List<MuscleGroup>,
        val exercises: List<ExerciseView>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val detail: StateFlow<Detail?> = videoRepository.observe(videoId)
        .map { row ->
            row ?: return@map null
            Detail(
                video = row,
                muscleGroups = row.muscleGroups
                    .split(',')
                    .filter { it.isNotBlank() }
                    .mapNotNull(MuscleGroup::fromId),
                exercises = runCatching {
                    json.parseToJsonElement(row.exercises).jsonArray.mapNotNull { el ->
                        val obj = el.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val groupList = mutableListOf<String>()
                        obj["muscle_groups"]?.jsonArray?.forEach { g ->
                            runCatching { groupList.add(g.jsonPrimitive.content.trim()) }
                        }
                        // Legacy single-id rows written before multi-group support.
                        if (groupList.isEmpty()) {
                            obj["muscle_group"]?.jsonPrimitive?.content?.let {
                                groupList.add(it.trim())
                            }
                        }
                        ExerciseView(
                            name = name,
                            muscleGroups = groupList.filter { it.isNotBlank() }.distinct(),
                            confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        )
                    }
                }.getOrDefault(emptyList()),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Live download percentage for this video (-1 = indeterminate, null = no live work). */
    val downloadProgress: StateFlow<Int?> =
        DownloadProgress.observe(appContext, videoId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-enqueue a FAILED/PENDING download (unique-KEEP keeps running work untouched). */
    fun retryDownload() {
        viewModelScope.launch {
            videoRepository.updateAnalysisState(videoId, AnalysisState.PENDING_DOWNLOAD)
            DownloadVideoWorker.enqueue(appContext, videoId)
        }
    }

    // ── Phase 10: editing (§7.2) ──

    /** Catalog names for the rename autocomplete, Tail-sourced names first. */
    val knownExerciseNames: StateFlow<List<String>> =
        exerciseRepository.all
            .map { rows ->
                val (tail, other) = rows.partition { it.source == com.example.kenga.data.local.ExerciseSource.TAIL }
                (tail.map { it.aliases.ifBlank { it.name } } + other.map { it.name }).distinct()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Rename an exercise on this video (optionally on every video mentioning
     * it) and upsert the corrected name into the local catalog (MANUAL source),
     * carrying ALL of its associated muscle groups over.
     */
    fun renameExercise(exercise: ExerciseView, newName: String, applyToAll: Boolean) {
        val display = newName.trim()
        if (display.isEmpty()) return
        viewModelScope.launch {
            videoRepository.renameExerciseInVideo(videoId, exercise.name, display, applyToAll)
            exerciseRepository.upsertManualGroups(display, exercise.muscleGroups)
        }
    }

    /** Replace the row's muscle-group CSV from the manual multi-select sheet. */
    fun setMuscleGroups(groups: List<MuscleGroup>) {
        viewModelScope.launch {
            videoRepository.updateMuscleGroups(videoId, groups.joinToString(",") { it.id })
        }
    }

    /** "Log this exercise" quick action: pre-open the Log tab dialog + navigate. */
    fun logExercise(exercise: ExerciseView, onOpenLog: () -> Unit) {
        val group = MuscleGroup.fromId(exercise.muscleGroup)
        com.example.kenga.ui.log.QuickLog.open(
            com.example.kenga.ui.log.QuickLogRequest(
                muscleGroupId = group?.id,
                exerciseName = exercise.name,
            ),
        )
        onOpenLog()
    }

    companion object {
        fun factory(videoId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KengaApplication
                VideoDetailViewModel(
                    appContext = app.applicationContext,
                    videoId = videoId,
                    videoRepository = app.appContainer.videoRepository,
                    exerciseRepository = app.appContainer.exerciseRepository,
                    playerManager = app.appContainer.playerManager,
                )
            }
        }
    }
}
