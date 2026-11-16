package com.example.kenga.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Custom outback-themed bottom navigation bar (port of Octo's bar).
 *
 * Design: a floating rounded dusk bar holding 5 AI-generated outback-themed
 * monochrome glyph icons (ic_nav_*.png — tintable via Material3 colors:
 * jumping roo, sun-backed library card, tail-curved dumbbell, boomerang
 * chart, gumnut compass). Selection is signalled three ways
 * for instant comprehension: warm ochre glow + dot row + slight scale-up,
 * plus the text label under each icon. Fully accessible via
 * semantics/role/contentDescription.
 */
@Composable
fun KengaBottomBar(
    destinations: List<KengaDestination>,
    currentRoute: String?,
    onNavigate: (KengaDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Intrinsic height instead of a fixed value — the bar sizes to
                // content (icon + dot row + label) so labels can never overflow
                // the rounded clip no matter the font scale.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    destinations.forEach { destination ->
                        KengaNavItem(
                            destination = destination,
                            selected = currentRoute == destination.route,
                            onClick = { onNavigate(destination) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One selectable icon + label cell inside [KengaBottomBar]. */
@Composable
private fun KengaNavItem(
    destination: KengaDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Animate every visual channel of the selection transition.
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "navTint",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (selected) 0.30f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "navGlow",
    )
    val iconScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.88f
            selected -> 1.12f
            else -> 1f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "navScale",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.65f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "navLabelAlpha",
    )

    val isSelected = selected
    val description = "${destination.label} tab" +
        if (isSelected) ", selected" else ""

    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = destination.label,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                this.selected = isSelected
                contentDescription = description
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Soft ochre halo + scaled tinted glyph icon.
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (glowAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    tint.copy(alpha = glowAlpha),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                )
            }
            Image(
                painter = painterResource(destination.icon),
                contentDescription = null, // cell semantics carry the meaning
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(24.dp)
                    .scale(iconScale)
                    .semanticsOverride(),
            )
        }

        Spacer(Modifier.height(2.dp))

        // Dot row marking the active tab.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                val active = selected && index == 1
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .alpha(if (selected) 1f else 0.35f)
                        .background(
                            color = if (active) tint else tint.copy(alpha = 0.35f),
                            shape = CircleShape,
                        ),
                )
            }
        }

        Spacer(Modifier.height(1.dp))

        // Default font line height + no wrap — descenders never clip.
        Text(
            text = destination.label,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            color = tint,
            modifier = Modifier.alpha(labelAlpha),
        )
    }
}

/** Marks the emoji glyph decorative for TalkBack (the cell's semantics carry meaning). */
private fun Modifier.semanticsOverride(): Modifier = this.then(
    Modifier.semantics { contentDescription = "" },
)
