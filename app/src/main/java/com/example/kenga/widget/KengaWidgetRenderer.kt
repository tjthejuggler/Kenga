package com.example.kenga.widget

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.kenga.ui.body.KangarooBodyGeometry
import com.example.kenga.ui.body.drawBodyMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Home-screen widget renderer: the kangaroo-man EXACTLY as the dashboard draws
 * him — the shared VECTOR figure (tier-colored, tier-bulked muscles; Art
 * Upgrade v5) — reusing the dashboard's own [drawBodyMap] pipeline offscreen
 * via [CanvasDrawScope]. No bitmaps to decode, no composition; rendered to a
 * Bitmap for RemoteViews.
 */
object KengaWidgetRenderer {

    fun render(
        context: Context,
        side: KangarooBodyGeometry.Side,
        todayCounts: Map<String, Int>,
        widthPx: Int,
        heightPx: Int,
    ): android.graphics.Bitmap {
        val scale = min(
            widthPx / KangarooBodyGeometry.CANVAS_WIDTH,
            heightPx / KangarooBodyGeometry.CANVAS_HEIGHT,
        ).coerceAtLeast(0.05f)
        val w = (KangarooBodyGeometry.CANVAS_WIDTH * scale).roundToInt().coerceAtLeast(1)
        val h = (KangarooBodyGeometry.CANVAS_HEIGHT * scale).roundToInt().coerceAtLeast(1)

        val image = ImageBitmap(w, h)

        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(image),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            // Pivot MUST be top-left: DrawScope.scale defaults to the canvas
            // CENTER, which at widget scale shoves the whole drawing right/down
            // and clips his right side + feet.
            scale(scale, scale, pivot = Offset.Zero) {
                drawBodyMap(side, todayCounts)
            }
        }
        return image.asAndroidBitmap()
    }
}
