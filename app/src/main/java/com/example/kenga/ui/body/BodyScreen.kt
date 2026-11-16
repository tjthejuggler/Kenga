package com.example.kenga.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.repository.ExercisePb
import com.example.kenga.data.weights.WeightUnits
import com.example.kenga.ui.components.GlassCard
import com.example.kenga.ui.components.KengaHeader
import com.example.kenga.ui.log.QuickLog
import com.example.kenga.ui.log.QuickLogRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Body dashboard (§7.1, Phase 8): "Kenga" header with the date, the kangaroo
 * body map centered (tier colors = sets logged today per muscle group, local +
 * Tail snapshot), a summary row (total sets + volume in the display unit), and
 * a friendly fresh-day empty state. Tapping a zone opens
 * the region dialog (Octo pattern): label, sets today, today's volume,
 * per-exercise PBs for the group, and a "Log set here" button that pre-opens
 * the Log tab's weights dialog via [QuickLog].
 */
@Composable
fun BodyScreen(
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BodyViewModel = viewModel(factory = BodyViewModel.Factory),
) {
    val dashboard by viewModel.dashboard.collectAsState()
    var region by remember { mutableStateOf<MuscleGroup?>(null) }

    // ── Front/back flip (Octo Phase 20 pattern, compact port) ──
    var angleState by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val degPerPx = remember(density) { DEGREES_PER_DP_DP / density }

    val normalized = ((angleState % 360f) + 360f) % 360f
    val side = if (normalized < 90f || normalized > 270f) {
        KangarooBodyGeometry.Side.FRONT
    } else {
        KangarooBodyGeometry.Side.BACK
    }

    fun animateAngleTo(targetAbs: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            var current = angleState
            var last = withFrameNanos { it }
            while (abs(targetAbs - current) > 0.05f) {
                withFrameNanos { now ->
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    last = now
                    current += (targetAbs - current) * (1f - exp(-dt / 0.09f))
                    angleState = current
                }
            }
            angleState = targetAbs
        }
    }

    val settleToNearest: (Float) -> Unit = { flickBiasDegPerSec ->
        val projected = angleState + flickBiasDegPerSec * FLICK_BIAS_SEC
        animateAngleTo(round(projected / 180f) * 180f)
    }

    val flipTo: (Int) -> Unit = { direction ->
        animateAngleTo((round(angleState / 180f) + direction) * 180f)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KengaHeader(title = "Kenga") {
            Text(
                text = dateLabel(dashboard.date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (dashboard.totalSets == 0) {
            EmptyHint()
        }

        // Figure centerpiece: fixed aspect (1000×1500 canvas space).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            KangarooBodyMap(
                todayCounts = dashboard.countsByGroup,
                onZoneTap = { region = it },
                side = side,
                flipAngle = angleState,
                onDragDelta = { deltaPx ->
                    settleJob?.cancel()
                    settleJob = null
                    angleState += deltaPx * degPerPx
                },
                onSettle = { flickBiasDegPerSec -> settleToNearest(flickBiasDegPerSec) },
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(KangarooBodyGeometry.CANVAS_WIDTH / KangarooBodyGeometry.CANVAS_HEIGHT),
            )
        }

        // Side indicator doubles as a tap-to-flip toggle (Octo pattern).
        SideIndicator(
            side = side,
            onToggle = { flipTo(if (side == KangarooBodyGeometry.Side.FRONT) 1 else -1) },
        )

        Spacer(Modifier.height(10.dp))

        SummaryRow(
            totalSets = dashboard.totalSets,
            volumeLabel = WeightUnits.formatGrams(dashboard.totalVolumeGrams, dashboard.unit),
        )

        Spacer(Modifier.height(12.dp))
    }

    region?.let { group ->
        RegionDialog(
            group = group,
            dashboard = dashboard,
            onLogHere = {
                QuickLog.open(QuickLogRequest(muscleGroupId = group.id))
                region = null
                onOpenLog()
            },
            onDismiss = { region = null },
        )
    }
}

// ── Region dialog (Octo's tap-a-zone pattern, Kenga data) ──

@Composable
private fun RegionDialog(
    group: MuscleGroup,
    dashboard: BodyDashboard,
    onLogHere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pbs = dashboard.pbsByGroup[group.id].orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(todayTierColor(dashboard.countsByGroup[group.id] ?: 0)),
                )
                Spacer(Modifier.width(8.dp))
                Text(group.displayName)
            }
        },
        text = {
            Column {
                Text(
                    text = "${dashboard.setsFor(group.id)} sets today · " +
                        WeightUnits.formatGrams(dashboard.volumeGramsFor(group.id), dashboard.unit) +
                        " volume",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))

                // Every individual set done today for this zone — local rows
                // and Tail-done sets (weights + body-weight habits) alike.
                val localRows = dashboard.todayLogs.filter { it.muscleGroupId == group.id }
                val tailRows = dashboard.tailSets.filter { group.id in it.groupIds }
                if (localRows.isEmpty() && tailRows.isEmpty()) {
                    Text(
                        text = "No sets logged for this group today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Today's sets",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    localRows.forEach { row ->
                        TodaySetRow(
                            label = row.exerciseName,
                            value = WeightUnits.formatGrams(row.weightGrams, dashboard.unit) + " × ${row.reps}",
                        )
                    }
                    tailRows.forEach { row ->
                        TodaySetRow(
                            label = row.exerciseName,
                            value = if (row.weightGrams > 0) {
                                WeightUnits.formatGrams(row.weightGrams, dashboard.unit) + " × ${row.reps}"
                            } else {
                                "${row.reps} reps"
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (pbs.isEmpty()) {
                    Text(
                        text = "No exercise PBs for this group yet — log a set to start the record book.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Personal bests",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pbs.take(6).forEach { pb -> PbRow(pb, dashboard.unit) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onLogHere) { Text("Log set here") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun TodaySetRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PbRow(pb: ExercisePb, unit: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pb.exerciseName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = WeightUnits.formatGrams(pb.bestWeightGrams, unit) + " × " + pb.bestReps,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Summary, empty state, side pill ──

/** Summary row: total sets today + total volume in the current unit. */
@Composable
private fun SummaryRow(totalSets: Int, volumeLabel: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryStat(label = "Sets today", value = "$totalSets")
            SummaryStat(label = "Volume", value = volumeLabel)
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fresh-day empty state (Octo's EmptyHint equivalent). */
@Composable
private fun EmptyHint() {
    Text(
        text = "The kangaroo is resting — log a set to start today's colors! 🦘",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

/** Tiny "Front ● / Back ●" pill: active side accented; tap toggles the flip. */
@Composable
private fun SideIndicator(
    side: KangarooBodyGeometry.Side,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IndicatorDot(active = side == KangarooBodyGeometry.Side.FRONT, label = "Front")
            IndicatorDot(active = side == KangarooBodyGeometry.Side.BACK, label = "Back")
        }
    }
}

@Composable
private fun IndicatorDot(active: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dateLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        .replaceFirstChar { it.uppercase(Locale.getDefault()) } +
        " · " + date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** Drag gain constants shared with the map (deg per dp of horizontal drag). */
private const val DEGREES_PER_DP_DP = 0.9f
private const val FLICK_BIAS_SEC = 0.12f
