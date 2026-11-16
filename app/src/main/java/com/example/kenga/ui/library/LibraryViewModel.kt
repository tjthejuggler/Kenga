package com.example.kenga.ui.library

import android.content.Context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.kenga.KengaApplication
import com.example.kenga.data.local.AnalysisState
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.local.VideoEntity
import com.example.kenga.data.repository.VideoRepository
import com.example.kenga.work.DownloadProgress
import com.example.kenga.work.DownloadVideoWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Library presentation state (Octo `LibraryViewModel` pattern, Phase 2 scope):
 * DB rows + client-side muscle-group filter. Download progress merge,
 * search and sorts arrive with later phases.
 */
class LibraryViewModel(
    private val appContext: Context,
    private val videoRepository: VideoRepository,
) : ViewModel() {

    /** Card-facing view of a row: parsed muscle groups + exercise names. */
    data class VideoUiItem(
        val video: VideoEntity,
        val muscleGroups: List<MuscleGroup>,
        val exerciseNames: List<String>,
    ) {
        val stateLabel: AnalysisState get() = video.analysisState
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Bump after refreshMetadata so cards re-render promptly (DB flow also fires). */
    private val refreshTick = MutableStateFlow(0)

    val items: StateFlow<List<VideoUiItem>> = videoRepository.library
        .combine(refreshTick) { rows, _ -> rows.map(::toUiItem) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Phase 3: live download percentages from WorkManager (videoId → percent, -1 = indeterminate). */
    val downloadProgress: StateFlow<Map<String, Int>> =
        DownloadProgress.observeAll(appContext)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Selected muscle-group filter — multi-select, OR semantics. */
    private val selectedMuscles = MutableStateFlow<Set<MuscleGroup>>(emptySet())
    val muscles: StateFlow<Set<MuscleGroup>> = selectedMuscles.asStateFlow()

    val filteredItems: StateFlow<List<VideoUiItem>> =
        combine(items, selectedMuscles) { rows, filter ->
            if (filter.isEmpty()) rows else rows.filter { item -> item.muscleGroups.any { it in filter } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleMuscle(group: MuscleGroup) {
        selectedMuscles.value = selectedMuscles.value.toMutableSet().apply {
            if (!add(group)) remove(group)
        }
    }

    fun clearFilter() {
        selectedMuscles.value = emptySet()
    }

    /** Paste-URL manual add — same ingest path as the share trampoline (then kick the download). */
    fun addUrl(text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val row = videoRepository.addSharedUrl(text)
            if (row != null) DownloadVideoWorker.enqueue(appContext, row.videoId)
            onResult(row != null)
        }
    }

    /** Re-enqueue a FAILED/PENDING download (unique-KEEP keeps running work untouched). */
    fun retryDownload(videoId: String) {
        DownloadVideoWorker.enqueue(appContext, videoId)
    }

    fun refreshMetadata(videoId: String) {
        viewModelScope.launch {
            videoRepository.refreshMetadata(videoId)
            refreshTick.value++
        }
    }

    fun delete(videoId: String) {
        viewModelScope.launch { videoRepository.deleteVideo(videoId) }
    }

    private fun toUiItem(row: VideoEntity): VideoUiItem = VideoUiItem(
        video = row,
        muscleGroups = row.muscleGroups
            .split(',')
            .filter { it.isNotBlank() }
            .mapNotNull(MuscleGroup::fromId),
        exerciseNames = parseExerciseNames(row.exercises),
    )

    private fun parseExerciseNames(raw: String): List<String> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            element.jsonObject["name"]?.jsonPrimitive?.content
        }
    }.getOrDefault(emptyList())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KengaApplication
                LibraryViewModel(
                    appContext = app.applicationContext,
                    videoRepository = app.appContainer.videoRepository,
                )
            }
        }
    }
}
