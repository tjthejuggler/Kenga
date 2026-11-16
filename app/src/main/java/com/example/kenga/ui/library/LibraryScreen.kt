package com.example.kenga.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.kenga.R
import com.example.kenga.data.local.AnalysisState
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.ui.components.GlassCard
import com.example.kenga.ui.components.KengaHeader
import com.example.kenga.ui.components.TwoRowChips
import java.io.File

/**
 * Library screen (Phase 2) — Octo library UX port:
 *  - LazyColumn of compact video cards (Coil thumbnail, title, channel,
 *    duration, analysis-state chip, muscle-group + exercise chips)
 *  - muscle-group filter row (TwoRowChips of FilterChips, multi-select OR)
 *  - empty state, per-card overflow menu (refresh metadata / delete)
 *  - FAB "Paste URL" dialog — same ingest path as the share trampoline
 *  - whole card click opens the video detail screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenVideo: (String) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val items by viewModel.items.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val filtered by viewModel.filteredItems.collectAsState()
    val selectedMuscles by viewModel.muscles.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LibraryViewModel.VideoUiItem?>(null) }

    // contentWindowInsets=0: the root app scaffold already applies the insets.
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true; addError = false }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.library_add_url))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            KengaHeader(
                title = stringResource(R.string.library_title),
                subtitle = stringResource(R.string.library_subtitle),
            )

            if (items.isNotEmpty()) {
                MuscleFilterRow(
                    selected = selectedMuscles,
                    onToggle = viewModel::toggleMuscle,
                    onClear = viewModel::clearFilter,
                )
            }

            when {
                items.isEmpty() -> LibraryEmptyState(Modifier.weight(1f))
                filtered.isEmpty() -> NoMatchState(
                    onClear = viewModel::clearFilter,
                    modifier = Modifier.weight(1f),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.video.videoId }) { item ->
                        VideoCard(
                            item = item,
                            progressPercent = downloadProgress[item.video.videoId],
                            onOpen = { onOpenVideo(item.video.videoId) },
                            onRefresh = { viewModel.refreshMetadata(item.video.videoId) },
                            onDelete = { deleteTarget = item },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddUrlDialog(
            onDismiss = { showAddDialog = false; addError = false },
            onAdd = { url ->
                viewModel.addUrl(url) { ok ->
                    if (ok) {
                        showAddDialog = false
                        addError = false
                    } else {
                        addError = true
                    }
                }
            },
            showError = addError,
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.library_delete_body) +
                        "\n\n" + target.video.title,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.video.videoId)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.library_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
}

/** Muscle-group filter chips in the shared TwoRowChips flow layout. */
@Composable
private fun MuscleFilterRow(
    selected: Set<MuscleGroup>,
    onToggle: (MuscleGroup) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        TwoRowChips(items = MuscleGroup.entries.toList()) { group ->
            FilterChip(
                selected = group in selected,
                onClick = { onToggle(group) },
                label = {
                    Text(group.displayName, style = MaterialTheme.typography.labelSmall)
                },
            )
        }
    }
    if (selected.isNotEmpty()) {
        TextButton(onClick = onClear, modifier = Modifier.padding(start = 16.dp)) {
            Text(stringResource(R.string.library_filter_clear))
        }
    }
}

/** Compact card — whole surface opens the video detail screen (Octo FB26 pattern). */
@Composable
private fun VideoCard(
    item: LibraryViewModel.VideoUiItem,
    progressPercent: Int?,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val video = item.video
    var menuOpen by remember { mutableStateOf(false) }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                AsyncImage(
                    model = thumbnailModel(video.videoId, video.thumbnailPath),
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (video.durationMs > 0) {
                    Text(
                        text = formatDuration(video.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = listOfNotNull(
                        video.channel.takeIf { it.isNotBlank() },
                        formatDuration(video.durationMs).takeIf { video.durationMs > 0 },
                    ).joinToString(" · ")
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    StateLabel(video.analysisState, progressPercent)
                }

                if (item.muscleGroups.isNotEmpty() || item.exerciseNames.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TwoRowChips(items = item.muscleGroups) { group ->
                            Tag(group.displayName)
                        }
                        TwoRowChips(items = item.exerciseNames) { name ->
                            Tag(name)
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.library_menu),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_refresh)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onRefresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_delete)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Tiny tag chip shared by muscle-group and exercise rows. */
@Composable
private fun Tag(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun StateLabel(state: AnalysisState, progressPercent: Int? = null) {
    val (labelRes, color) = when (state) {
        AnalysisState.PENDING_DOWNLOAD -> R.string.library_state_pending_download to MaterialTheme.colorScheme.secondary
        AnalysisState.DOWNLOADING -> R.string.library_state_downloading to MaterialTheme.colorScheme.tertiary
        AnalysisState.DOWNLOADED -> R.string.library_state_downloaded to MaterialTheme.colorScheme.primary
        AnalysisState.PENDING -> R.string.library_state_analysis_pending to MaterialTheme.colorScheme.secondary
        AnalysisState.RUNNING -> R.string.library_state_running to MaterialTheme.colorScheme.tertiary
        AnalysisState.DONE -> R.string.library_state_done to MaterialTheme.colorScheme.primary
        AnalysisState.FAILED -> R.string.library_state_failed to MaterialTheme.colorScheme.error
        AnalysisState.NONE -> R.string.library_state_pending_download to MaterialTheme.colorScheme.secondary
    }
    val colorValue = color
    val text = if (state == AnalysisState.DOWNLOADING && progressPercent != null && progressPercent >= 0) {
        stringResource(R.string.library_state_downloading_percent, progressPercent)
    } else {
        stringResource(labelRes)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colorValue,
        modifier = Modifier
            .background(colorValue.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Cached file when downloaded, remote hqdefault URL otherwise (Coil caches it). */
private fun thumbnailModel(videoId: String, thumbnailPath: String?): Any =
    thumbnailPath?.let { File(it) }
        ?: com.example.kenga.youtube.YoutubeUrlParser.thumbnailUrl(videoId)

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun AddUrlDialog(
    showError: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_add_url)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.library_add_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onAdd(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.library_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun LibraryEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🦘", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoMatchState(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🏋️", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.library_no_match),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.library_filter_clear))
        }
    }
}
