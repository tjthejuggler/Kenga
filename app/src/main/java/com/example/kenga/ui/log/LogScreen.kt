package com.example.kenga.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.local.TailSyncState
import com.example.kenga.data.repository.PbLogic
import com.example.kenga.data.weights.WeightUnits
import com.example.kenga.ui.components.GlassCard
import com.example.kenga.ui.components.KengaHeader
import kotlinx.coroutines.launch

/**
 * Log tab (§7.3): "Log set" primary action opening the Tail-style
 * [WeightsLogDialog], today's sets grouped by muscle group with per-row tail
 * sync state + PR chips, delete-with-undo Snackbar, and the [PrFlash]
 * bottom overlay.
 */
@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val vm: LogViewModel = viewModel(factory = LogViewModel.Factory)
    val logs by vm.todayLogs.collectAsState()
    val tailSets by vm.tailTodaySets.collectAsState()
    val recentExercises by vm.recentExercises.collectAsState()
    val unit by vm.unit.collectAsState()
    val dialog by vm.dialog.collectAsState()
    val prFlash by vm.prFlash.collectAsState()
    val prRowIds by vm.prRowIds.collectAsState()
    val exercisePbs by vm.exercisePbs.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Default muscle group: most recently logged today → first canonical.
    val defaultGroup = logs.lastOrNull()?.muscleGroupId
        ?: MuscleGroup.entries.first().id

    // The dialog's live PB readout needs the union PB for the typed name —
    // recomputed on each recomposition via the snapshot map.
    val dialogState = dialog

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            KengaHeader(
                title = "Log",
                subtitle = "Sets & PRs",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Button(
                onClick = { vm.openDialog(muscleGroupId = defaultGroup) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.padding(2.dp))
                Text("Log set")
            }

            if (logs.isEmpty() && tailSets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No sets yet today — log your first one!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val grouped = logs.groupBy { it.muscleGroupId }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    grouped.forEach { (groupId, rows) ->
                        item(key = "header_$groupId") { GroupHeader(groupId, rows.size) }
                        items(rows, key = { it.id }) { row ->
                            SetRow(
                                row = row,
                                unit = unit,
                                isPr = row.id in prRowIds,
                                onDelete = {
                                    scope.launch {
                                        val removed = vm.delete(row.id) ?: return@launch
                                        val result = snackbar.showSnackbar(
                                            message = "Deleted ${removed.exerciseName}",
                                            actionLabel = "Undo",
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            vm.restore(removed)
                                        }
                                    }
                                },
                            )
                        }
                    }

                    // "Done in Tail" section: activity marked done directly in
                    // Tail today that Kenga has no local mirror row for.
                    if (tailSets.isNotEmpty()) {
                        item(key = "header_tail") { TailHeader(tailSets.size) }
                        items(
                            tailSets,
                            key = { "tail_${it.muscleGroupId}_${it.exerciseName}_${it.machine}" },
                        ) { set ->
                            TailSetRow(set = set, unit = unit)
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        )

        // PR flash overlay — bottom of the screen, above everything else.
        PrFlash(
            event = prFlash,
            unit = unit,
            onDismiss = vm::dismissPrFlash,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    dialogState?.let { state ->
        WeightsLogDialog(
            initialMuscleGroupId = state.muscleGroupId.ifBlank { defaultGroup },
            initialExerciseName = state.exerciseName,
            defaultUnit = unit,
            recentExercises = recentExercises,
            pbs = exercisePbs,
            onConfirm = vm::confirm,
            onDismiss = vm::dismissDialog,
        )
    }
}

@Composable
private fun GroupHeader(muscleGroupId: String, count: Int) {
    val name = MuscleGroup.fromId(muscleGroupId)?.displayName ?: muscleGroupId
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(4.dp))
        Text(
            text = "· $count set${if (count == 1) "" else "s"}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TailHeader(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Done in Tail",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(4.dp))
        Text(
            text = "· $count slot${if (count == 1) "" else "s"}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TailSetRow(set: PbLogic.TailTodaySet, unit: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = set.exerciseName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (set.weightGrams > 0) {
                    "${WeightUnits.formatGrams(set.weightGrams, unit)} × ${set.reps} · " +
                        (if (set.machine) "Machine" else "Free") +
                        " · Tail habit: ${set.habitName}"
                } else {
                    "${set.reps} reps · body-weight · Tail habit: ${set.habitName}"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetRow(row: SetLogEntity, unit: String, isPr: Boolean, onDelete: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.exerciseName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isPr) {
                        Spacer(modifier = Modifier.padding(3.dp))
                        Text(
                            text = "PR!",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD700),
                        )
                    }
                }
                Text(
                    text = "${WeightUnits.formatGrams(row.weightGrams, unit)} × ${row.reps} · " +
                        (if (row.machine) "Machine" else "Free") + " · " + syncLabel(row.tailSyncState),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete set",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun syncLabel(state: TailSyncState): String = when (state) {
    TailSyncState.SENT -> "sent ✓"
    TailSyncState.PENDING -> "pending ⟳"
    TailSyncState.FAILED -> "pending ⟳"
    TailSyncState.OFFLINE -> "offline ⚠"
}
