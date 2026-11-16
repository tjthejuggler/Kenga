package com.example.kenga.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.sin
import kotlin.random.Random

/** Dusk→dawn gradient stops, themed. */
private data class OutbackColors(
    val deep: Color,
    val mid: Color,
    val shallow: Color,
    val ray: Color,
    val mote: Color,
)

private val DarkOutback = OutbackColors(
    deep = Color(0xFF140C0A), // night earth floor
    mid = Color(0xFF1C1210), // current background
    shallow = Color(0xFF2E1D14), // faint rust glow up top
    ray = Color(0xFFF2B36B), // brand ochre, low alpha shafts
    mote = Color(0xFFFFD9A8),
)

private val LightOutback = OutbackColors(
    deep = Color(0xFFE8D8C4),
    mid = Color(0xFFF5EDE0),
    shallow = Color(0xFFF7DBB0),
    ray = Color(0xFFD89A4E),
    mote = Color(0xFFD89A4E),
)

/**
 * Ambient outback backdrop for the whole app: a vertical dusk→dawn gradient,
 * two soft diagonal sun shafts, a couple of heat-ripple lines, and a handful
 * of drifting dust motes. Pure Canvas — resolution-independent, zero asset
 * weight, ~free to draw. Content goes in [content]; screens must use
 * transparent containers above it.
 */
@Composable
fun KengaBackground(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val outback = if (darkTheme) DarkOutback else LightOutback
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Deterministic dust field so it doesn't reshuffle every recomposition.
    val motes = remember(darkTheme) {
        List(10) {
            DustMotes(
                xFrac = Random(100 + it).nextFloat(),
                yFrac = Random(200 + it).nextFloat(),
                radiusDp = 2f + Random(300 + it).nextFloat() * 5f,
                alpha = 0.06f + Random(400 + it).nextFloat() * 0.10f,
                speed = 0.5f + Random(500 + it).nextFloat(),
            )
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(outback.shallow, outback.mid, outback.deep),
                    startY = 0f,
                    endY = h,
                ),
            )

            // Two wide diagonal sun shafts from the horizon.
            val shaftWidth = w * 0.28f
            listOf(-0.15f, 0.45f).forEachIndexed { i, xFrac ->
                rotate(degrees = if (i == 0) 14f else -10f, pivot = Offset(w * xFrac + shaftWidth / 2, 0f)) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(outback.ray.copy(alpha = if (darkTheme) 0.05f else 0.08f), Color.Transparent),
                            startY = 0f,
                            endY = h * 0.85f,
                        ),
                        topLeft = Offset(w * xFrac, 0f),
                        size = Size(shaftWidth, h),
                    )
                }
            }

            // A couple of very soft heat ripples near the top.
            repeat(3) { i ->
                val path = Path()
                val baseY = h * (0.06f + i * 0.05f)
                val amp = h * 0.012f
                path.moveTo(0f, baseY)
                var x = 0f
                while (x <= w) {
                    path.lineTo(x, baseY + sin(x / w * 6f + i * 2f) * amp)
                    x += w / 24f
                }
                drawPath(
                    path,
                    color = outback.ray.copy(alpha = if (darkTheme) 0.04f else 0.06f),
                    style = Stroke(width = with(density) { 2.dp.toPx() }),
                )
            }

            // Drifting dust motes (static field; the ambient motion lives in the app, not here).
            motes.forEach { m ->
                val layoutAwareX = if (layoutDirection == LayoutDirection.Rtl) 1f - m.xFrac else m.xFrac
                drawCircle(
                    color = outback.mote.copy(alpha = m.alpha),
                    radius = with(density) { m.radiusDp.dp.toPx() },
                    center = Offset(layoutAwareX * w, m.yFrac * h),
                )
            }
        }
    }
}

private data class DustMotes(val xFrac: Float, val yFrac: Float, val radiusDp: Float, val alpha: Float, val speed: Float)
