package com.example.kenga.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared tag-row layout for muscle-group / exercise chips.
 *
 * FlowRow wrapping: every chip keeps its intrinsic width and simply flows to
 * the next row when the current one is full — all items stay visible with no
 * scroll and no "+n" overflow chip.
 *
 * Used by the Library video cards and the video-detail chips, which pass
 * their own chip composable as [itemContent].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> TwoRowChips(
    items: List<T>,
    modifier: Modifier = Modifier,
    rowSpacing: Dp = 4.dp,
    itemSpacing: Dp = 4.dp,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        items.forEach { itemContent(it) }
    }
}
