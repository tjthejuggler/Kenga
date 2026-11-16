package com.example.kenga.ui.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kenga.R
import com.example.kenga.data.weights.WeightUnits
import com.example.kenga.ui.body.usageTierColor
import com.example.kenga.ui.components.GlassCard
import com.example.kenga.ui.components.KengaHeader

/**
 * Stats tab (§7.4, Phase 9): range selector (7D/30D/90D/ALL), overall summary
 * cards (sets · volume · training days · streaks), per-muscle-group usage bars
 * colored by relative usage ([usageTierColor] on the same continuous scale as
 * the body map), exercise filter chips narrowing every bar/card to days logged
 * with that exercise (Tail graph pattern), and a kg/lb display toggle. Octo
 * `StatsScreen` structure with Kenga's outback-glass visual language.
 */
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()

    // Clear a filter chip whose exercise left the window (Tail pattern).
    LaunchedEffect(state.exercises) {
        if (state.exerciseFilter.isNotEmpty() &&
            state.exercises.none { it.equals(state.exerciseFilter, ignoreCase = true) }
        ) {
            viewModel.selectExercise(state.exerciseFilter) // toggles the stale chip off
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        KengaHeader(title = "Stats", subtitle = "Ranges · streaks · volume")
        RangeChips(
            selected = state.range,
            onSelect = viewModel::selectRange,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        UnitToggle(selected = state.unit, onSelect = viewModel::selectUnit)

        when {
            state.loading && state.isEmpty -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.isEmpty -> EmptyStatsCard(Modifier.padding(vertical = 24.dp))

            else -> {
                Spacer(Modifier.height(8.dp))
                ExerciseFilterRow(state = state, onSelect = viewModel::selectExercise)
                Spacer(Modifier.height(8.dp))
                OverviewCards(state = state)
                Spacer(Modifier.height(12.dp))
                UsageBarsCard(state = state)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 7D / 30D / 90D / ALL window selector (defaults 30D). */
@Composable
private fun RangeChips(
    selected: StatsRange,
    onSelect: (StatsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsRange.entries.forEach { r ->
            FilterChip(
                selected = r == selected,
                onClick = { onSelect(r) },
                label = { Text(r.label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** kg / lb display toggle — display conversion only, storage stays grams. */
@Composable
private fun UnitToggle(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(WeightUnits.UNIT_KG, WeightUnits.UNIT_LB).forEach { unit ->
            FilterChip(
                selected = unit == selected,
                onClick = { onSelect(unit) },
                label = { Text(unit) },
            )
        }
    }
}

/**
 * Exercise filter chips (Tail GraphsScreen pattern): "All" + every exercise
 * name recorded in the range; the active chip narrows all bars/cards to the
 * days logged with that exercise.
 */
@Composable
private fun ExerciseFilterRow(state: StatsUiState, onSelect: (String) -> Unit) {
    if (state.exercises.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.exerciseFilter.isEmpty(),
            onClick = { onSelect("") },
            label = { Text("All") },
        )
        state.exercises.forEach { name ->
            val isActive = name.equals(state.exerciseFilter, ignoreCase = true)
            FilterChip(
                selected = isActive,
                onClick = { onSelect(if (isActive) "" else name) },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun OverviewCards(state: StatsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${state.totalSets}", "sets", Modifier.weight(1f))
            StatCard(state.volumeLabel, "volume", Modifier.weight(1f))
            StatCard("${state.trainingDays}", "training days", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                value = "🔥 ${state.currentStreak}",
                label = "current streak",
                modifier = Modifier.weight(1f),
                dimmed = state.currentStreak == 0,
            )
            StatCard(
                value = "🏆 ${state.bestStreak}",
                label = "best streak",
                modifier = Modifier.weight(1f),
                dimmed = state.bestStreak == 0,
            )
        }
    }
}

@Composable
private fun StatCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Per-muscle-group usage bars: bar LENGTH and COLOR both follow the group's
 * set count RELATIVE to the busiest group in the window ([usageFraction]) —
 * the busiest group fills the track white on the same continuous tier scale
 * as the body map, idle groups sit red.
 */
@Composable
private fun UsageBarsCard(state: StatsUiState) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Muscle-group usage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "sets · relative to the busiest group in ${state.range.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            state.groups.forEach { g -> UsageBarRow(g) }
        }
    }
}

@Composable
private fun UsageBarRow(row: GroupRow) {
    val idleColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (row.sets > 0) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(118.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(idleColor),
        ) {
            if (row.usageFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(row.usageFraction)
                        .fillMaxHeight()
                        .background(usageTierColor(row.usageFraction)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${row.sets} · ${row.volumeLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(90.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** Kangaroo-themed empty state for a fresh app. */
@Composable
private fun EmptyStatsCard(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "No stats yet — log a set and your progress will hop in here 🦘",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
