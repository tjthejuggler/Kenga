package com.example.kenga.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Semi-transparent "pane of outback glass": a sand-tinted wash over the dusk
 * background, a subtle top-edge highlight like sun raking across the desert,
 * and a faint border. Content sits on the horizon itself instead of an
 * opaque gray slab — keeping the app's warm, transparent outback feel.
 *
 * Dark theme: translucent ochre-tinted pane (light passes through).
 * Light theme: frosted white pane.
 */
/** Pane flavor: [Light] frosted sand, [Deep] dark hazy outback glass (readable w/ light text). */
enum class GlassTone { Light, Deep }

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    /** Opacity 0..1: 1f = standard outback glass, higher alphas = hazier/more opaque. */
    opacity: Float = 1f,
    tone: GlassTone = GlassTone.Light,
    content: @Composable () -> Unit,
) {
    val darkTheme = MaterialTheme.colorScheme.surface == DuskSurfaceRef
    val useDeep = tone == GlassTone.Deep || darkTheme
    // Deep glass keeps a DARK base so light text stays readable when hazy;
    // Light glass is the airy sand wash used on the standard section cards.
    val paneBase = when {
        tone == GlassTone.Deep -> Color(0xFF2A1B16) // DuskSurface: dark ochre glass
        useDeep -> SandBright
        else -> Color.White
    }
    val baseAlpha = when {
        tone == GlassTone.Deep -> 0.93f
        darkTheme -> 0.07f
        else -> 0.55f
    }
    val container = paneBase.copy(alpha = (baseAlpha * opacity).coerceAtMost(0.97f))
    val highlight = paneBase.copy(alpha = ((if (tone == GlassTone.Deep) 0.10f else if (darkTheme) 0.16f else 0.8f)) * opacity)
    val border = paneBase.copy(alpha = ((if (tone == GlassTone.Deep) 0.22f else if (darkTheme) 0.14f else 0.6f)) * opacity)

    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .drawBehind {
                    // Top-edge sun glint, fading to clear.
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(highlight, Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.35f,
                        ),
                    )
                },
        ) {
            content()
        }
    }
}

// Local theme anchors (kept out of the global namespace deliberately).
private val DuskSurfaceRef = Color(0xFF2A1B16)
private val SandBright = Color(0xFFEBD3B4)
