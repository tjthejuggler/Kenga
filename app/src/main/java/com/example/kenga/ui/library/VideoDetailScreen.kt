package com.example.kenga.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.kenga.R
import com.example.kenga.data.local.AnalysisState
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.ui.components.GlassCard
import com.example.kenga.ui.components.KengaHeader
import com.example.kenga.youtube.YoutubeUrlParser

/**
 * Video detail: metadata + player + editable analysis results (Phase 10).
 * Exercise chips open a rename dialog (free text + catalog autocomplete,
 * optional apply-to-all-videos); muscle chips open a 16-group multi-select
 * sheet; each exercise has a one-tap "Log this exercise" quick action that
 * pre-opens the Log tab dialog via [com.example.kenga.ui.log.QuickLog].
 */
@Composable
fun VideoDetailScreen(
    videoId: String,
    onBack: () -> Unit,
    onOpenLog: () -> Unit = {},
    viewModel: VideoDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = VideoDetailViewModel.factory(videoId),
    ),
) {
    val detail by viewModel.detail.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        KengaHeader(
            title = stringResource(R.string.video_detail_title),
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.video_detail_back),
                )
            }
        }

        val video = detail?.video
        if (video == null) {
            Text(
                text = stringResource(R.string.video_detail_unknown),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        // --- Metadata block ---
        GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(
                    model = video.thumbnailPath ?: YoutubeUrlParser.thumbnailUrl(video.videoId),
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        video.channel.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.video_detail_channel_unknown),
                        video.url,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // --- Player (Phase 3): embedded Media3 pane + fullscreen, offline-only ---
        GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            VideoPlayerPane(
                video = video,
                progressPercent = downloadProgress,
                onRetryDownload = viewModel::retryDownload,
                playerManager = viewModel.playerManager,
            )
        }

        // --- Analysis results (Phase 4) + Phase 10 editing ---
        detail?.let { AnalysisPane(it, viewModel, onOpenLog) }

        Spacer(Modifier.height(24.dp))
    }
}

/** Editing UI state holders for the analysis pane dialogs. */
private data class RenameTarget(val exercise: VideoDetailViewModel.ExerciseView)

/**
 * Renders the analysis leg: state banner until DONE, then tappable muscle
 * chips (multi-select sheet rewrites the CSV) and tappable exercise chips
 * (rename dialog with catalog autocomplete + apply-to-all + quick log).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisPane(
    detail: VideoDetailViewModel.Detail,
    viewModel: VideoDetailViewModel,
    onOpenLog: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    var showMuscleSheet by remember { mutableStateOf(false) }

    GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (detail.video.analysisState) {
                AnalysisState.RUNNING -> {
                    Text(
                        stringResource(R.string.video_detail_analysis_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    return@Column
                }
                AnalysisState.FAILED -> {
                    Text(
                        stringResource(R.string.video_detail_analysis_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@Column
                }
                else -> Unit
            }

            if (detail.muscleGroups.isEmpty() && detail.exercises.isEmpty()) {
                Text(
                    text = stringResource(R.string.video_detail_analysis_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            if (detail.muscleGroups.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.video_detail_section_muscles),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.video_detail_edit_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    detail.muscleGroups.forEach { group ->
                        AssistChip(
                            onClick = { showMuscleSheet = true },
                            label = {
                                Text(
                                    group.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (detail.exercises.isNotEmpty()) {
                Text(
                    stringResource(R.string.video_detail_section_exercises),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    detail.exercises.forEach { exercise ->
                        AssistChip(
                            onClick = { renameTarget = RenameTarget(exercise) },
                            label = {
                                Text(
                                    text = if (exercise.confidence > 0.0) {
                                        "${exercise.name} · ${
                                            stringResource(
                                                R.string.video_detail_analysis_confidence,
                                                (exercise.confidence * 100).toInt(),
                                            )
                                        }"
                                    } else {
                                        exercise.name
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                        AssistChip(
                            onClick = { viewModel.logExercise(exercise, onOpenLog) },
                            label = {
                                Text(
                                    stringResource(R.string.video_detail_log_exercise),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameExerciseDialog(
            exercise = target.exercise,
            knownNames = viewModel.knownExerciseNames.collectAsState().value,
            onDismiss = { renameTarget = null },
            onSave = { newName, applyToAll ->
                viewModel.renameExercise(target.exercise, newName, applyToAll)
                renameTarget = null
            },
        )
    }

    if (showMuscleSheet) {
        MuscleGroupSelectDialog(
            current = detail.muscleGroups,
            onDismiss = { showMuscleSheet = false },
            onSave = { groups ->
                viewModel.setMuscleGroups(groups)
                showMuscleSheet = false
            },
        )
    }
}

/** Rename dialog: free text + autocomplete suggestions (Tail names first). */
@Composable
private fun RenameExerciseDialog(
    exercise: VideoDetailViewModel.ExerciseView,
    knownNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (newName: String, applyToAll: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(exercise.name) }
    var applyToAll by remember { mutableStateOf(false) }
    val query = text.trim().lowercase()
    val suggestions = remember(knownNames, query) {
        if (query.isBlank()) {
            knownNames.take(8)
        } else {
            knownNames.filter { it.lowercase().contains(query) && it.lowercase() != query }
                .take(8)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_detail_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.video_detail_rename_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.video_detail_rename_suggestions),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { name ->
                            AssistChip(
                                onClick = { text = name },
                                label = {
                                    Text(name, style = MaterialTheme.typography.labelSmall)
                                },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it },
                    )
                    Text(
                        text = stringResource(R.string.video_detail_rename_apply_all),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text, applyToAll) },
                enabled = text.trim().isNotBlank(),
            ) {
                Text(stringResource(R.string.video_detail_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        },
    )
}

/** Muscle-group multi-select over the canonical 16; Save rewrites the CSV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MuscleGroupSelectDialog(
    current: List<MuscleGroup>,
    onDismiss: () -> Unit,
    onSave: (List<MuscleGroup>) -> Unit,
) {
    var selected by remember { mutableStateOf(current.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_detail_muscles_title)) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MuscleGroup.entries.forEach { group ->
                    FilterChip(
                        selected = group in selected,
                        onClick = {
                            selected = if (group in selected) selected - group else selected + group
                        },
                        label = { Text(group.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected.toList()) }) {
                Text(stringResource(R.string.video_detail_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        },
    )
}
