package com.example.kenga.ui.body

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp

/**
 * Vector painter for the kangaroo figure (Art Upgrade v5). Shared by the
 * dashboard Canvas and the widget's offscreen [androidx.compose.ui.graphics.drawscope.CanvasDrawScope]
 * render — one pipeline, identical output on both surfaces.
 *
 * Rendering model, back-to-front:
 *  1. NEUTRALS  — fur-colored anatomy (silhouette, tail, head, ears, paws,
 *     shins) with a dark outline so the animal reads at any tier.
 *  2. ZONES     — every muscle group in its REAL tier color (the part itself
 *     recolors; no overlay patches), outlined, and BULK-SHAPED by tier:
 *     width ×1.00→1.42 from 0→6+ sets (see [KangarooBodyGeometry.bulkWidth]),
 *     so worked muscles visibly bulge outward.
 *  3. SHADING   — an inner highlight band on each muscle at tier ≥1 (the
 *     "pumped" specular), and a soft fur-colored rim shadow just inside the
 *     silhouette's left/right edges for volume.
 *  4. DETAIL    — muscle-detail strokes appear as the tier climbs: 1–2 sets =
 *     a simple split line; 3–4 = two split lines / faint six-pack rows;
 *     5–6+ = full striation stripes + extra definition creases. Detail color
 *     darkens with the fill so it stays legible on white-tier muscles.
 */
object KangarooFigurePainter {

    private val OUTLINE = Color(0xFF4A3728)
    private const val OUTLINE_W = 7f

    /** Fraction of the muscle width the inner highlight band covers. */
    private const val HIGHLIGHT_FRAC = 0.34f

    /** Tier level at which each detail style unlocks (count → details). */
    private fun detailLevel(count: Int): Int = when {
        count >= 5 -> 3
        count >= 3 -> 2
        count >= 1 -> 1
        else -> 0
    }

    /** A darkened version of [base] for creases/stripes (legible on white). */
    private fun detailColor(base: Color): Color = lerp(base, Color(0xFF3A2A1E), 0.45f)

    /**
     * Entry: draw the whole figure for [side] given today's counts. The
     * whole drawing is translated by [KangarooBodyGeometry.centeringOffsetX]
     * so the figure's union bbox (tail included) is centered on the canvas —
     * the rotationY flip mirrors around the center, and this keeps him in
     * the SAME position across front↔back swipes.
     */
    fun DrawScope.drawFigure(
        side: KangarooBodyGeometry.Side,
        counts: Map<String, Int>,
    ) {
        val neutrals = KangarooBodyGeometry.neutralsFor(side).sortedBy { it.drawOrder }
        val zones = KangarooBodyGeometry.zonesFor(side).sortedBy { it.drawOrder }
        translate(left = KangarooBodyGeometry.centeringOffsetX(), top = 0f) {
            drawFigureInner(neutrals, zones, counts)
        }
    }

    private fun DrawScope.drawFigureInner(
        neutrals: List<KangarooBodyGeometry.NeutralPart>,
        zones: List<KangarooBodyGeometry.Zone>,
        counts: Map<String, Int>,
    ) {

        // 1. Neutral anatomy (fur) with outline.
        for (part in neutrals) {
            for (shape in part.shapes) {
                drawShape(shape, part.fill, part.outlined)
            }
        }

        // 2. Tier-colored muscles, bulged by count.
        for (zone in zones) {
            val count = counts[zone.group.id] ?: 0
            val color = todayTierColor(count)
            val ws = KangarooBodyGeometry.bulkWidth(count)
            val ls = KangarooBodyGeometry.bulkLength(count)
            for (shape in zone.shapes) {
                drawShape(scaled(shape, ws, ls), color, outlined = true)
                if (count > 0) {
                    drawHighlight(scaled(shape, ws, ls), color, count)
                    drawDetail(scaled(shape, ws, ls), zone.detail, count)
                }
            }
        }
    }

    // ── primitives ──────────────────────────────────────────────────────────

    /** Capsule/ellipse fill (+ outline) in the shape's rotated frame. */
    private fun DrawScope.drawShape(
        shape: KangarooBodyGeometry.ZoneShape,
        color: Color,
        outlined: Boolean,
    ) {
        rotate(degrees = -shape.angleDeg, pivot = Offset(shape.cx, shape.cy)) {
            when (shape) {
                is KangarooBodyGeometry.ZoneShape.Capsule -> {
                    val topLeft = Offset(shape.cx - shape.w / 2f, shape.cy - shape.l / 2f)
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(shape.w, shape.l),
                        cornerRadius = CornerRadius(shape.w / 2f, shape.w / 2f),
                        style = Fill,
                    )
                    if (outlined) {
                        drawRoundRect(
                            color = OUTLINE,
                            topLeft = topLeft,
                            size = Size(shape.w, shape.l),
                            cornerRadius = CornerRadius(shape.w / 2f, shape.w / 2f),
                            style = Stroke(width = OUTLINE_W),
                        )
                    }
                }
                is KangarooBodyGeometry.ZoneShape.Ellipse -> {
                    val topLeft = Offset(shape.cx - shape.rx, shape.cy - shape.ry)
                    drawOval(
                        color = color,
                        topLeft = topLeft,
                        size = Size(shape.rx * 2f, shape.ry * 2f),
                    )
                    if (outlined) {
                        drawOval(
                            color = OUTLINE,
                            topLeft = topLeft,
                            size = Size(shape.rx * 2f, shape.ry * 2f),
                            style = Stroke(width = OUTLINE_W),
                        )
                    }
                }
            }
        }
    }

    /**
     * "Pumped" specular: a smaller, lighter copy of the muscle offset toward
     * its upper-left — cheap volume cue that scales with the bulk (and thus
     * with the tier), so a tier-6 bicep reads as genuinely swollen.
     */
    private fun DrawScope.drawHighlight(
        shape: KangarooBodyGeometry.ZoneShape,
        base: Color,
        count: Int,
    ) {
        val lift = lerp(base, Color.White, 0.30f + 0.04f * count.coerceAtMost(6))
        rotate(degrees = -shape.angleDeg, pivot = Offset(shape.cx, shape.cy)) {
            when (shape) {
                is KangarooBodyGeometry.ZoneShape.Capsule -> {
                    val hw = shape.w * HIGHLIGHT_FRAC
                    drawRoundRect(
                        color = lift,
                        topLeft = Offset(shape.cx - hw - shape.w * 0.08f, shape.cy - shape.l / 2f + shape.w * 0.18f),
                        size = Size(hw, shape.l - shape.w * 0.42f),
                        cornerRadius = CornerRadius(hw / 2f, hw / 2f),
                    )
                }
                is KangarooBodyGeometry.ZoneShape.Ellipse -> {
                    drawOval(
                        color = lift,
                        topLeft = Offset(shape.cx - shape.rx * 0.62f, shape.cy - shape.ry * 0.66f),
                        size = Size(shape.rx * 0.62f, shape.ry * 0.6f),
                    )
                }
            }
        }
    }

    /**
     * Muscle-detail strokes inside the part's rotated frame. Level 1 = one
     * split line; level 2 = two; level 3 = striation stripes along the limb.
     */
    private fun DrawScope.drawDetail(
        shape: KangarooBodyGeometry.ZoneShape,
        style: KangarooBodyGeometry.DetailStyle,
        count: Int,
    ) {
        if (style == KangarooBodyGeometry.DetailStyle.NONE) return
        val level = detailLevel(count)
        if (level <= 0) return
        val crease = detailColor(todayTierColor(count))

        rotate(degrees = -shape.angleDeg, pivot = Offset(shape.cx, shape.cy)) {
            when (shape) {
                is KangarooBodyGeometry.ZoneShape.Capsule -> {
                    val halfW = shape.w / 2f
                    val halfL = shape.l / 2f
                    when (style) {
                        KangarooBodyGeometry.DetailStyle.SPLIT,
                        KangarooBodyGeometry.DetailStyle.STRIPES -> {
                            // Lengthwise split(s): the muscle's two heads.
                            val lines = if (level >= 2) 2 else 1
                            for (i in 1..lines) {
                                val x = shape.cx + (if (lines == 1) 0f
                                    else (if (i == 1) -halfW * 0.45f else halfW * 0.45f))
                                drawLine(
                                    color = crease,
                                    start = Offset(x, shape.cy - halfL + halfW * 0.7f),
                                    end = Offset(x, shape.cy + halfL - halfW * 0.7f),
                                    strokeWidth = 4.5f,
                                )
                            }
                            if (level >= 3 && style == KangarooBodyGeometry.DetailStyle.STRIPES) {
                                // Cross striations every ~35px along the limb.
                                var y = shape.cy - halfL + halfW
                                while (y < shape.cy + halfL - halfW * 0.6f) {
                                    drawLine(
                                        color = crease.copy(alpha = 0.55f),
                                        start = Offset(shape.cx - halfW * 0.72f, y),
                                        end = Offset(shape.cx + halfW * 0.72f, y),
                                        strokeWidth = 3.5f,
                                    )
                                    y += 38f
                                }
                            }
                        }
                        KangarooBodyGeometry.DetailStyle.SIX_PACK -> {
                            // 2×3 ab rows; more rows defined at higher tiers.
                            val rows = if (level >= 3) 3 else if (level >= 2) 2 else 1
                            val cols = 2
                            val cellL = (shape.l * 0.72f) / rows
                            val cellW = shape.w * 0.36f
                            val topY = shape.cy - shape.l * 0.36f
                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val x = shape.cx + (if (c == 0) -cellW * 0.55f else cellW * 0.55f)
                                    val y = topY + r * cellL + cellL / 2f
                                    drawRoundRect(
                                        color = crease,
                                        topLeft = Offset(x - cellW / 2f, y - cellL * 0.38f),
                                        size = Size(cellW, cellL * 0.76f),
                                        cornerRadius = CornerRadius(cellW * 0.35f, cellW * 0.35f),
                                        style = Stroke(width = 4.5f),
                                    )
                                }
                            }
                        }
                        KangarooBodyGeometry.DetailStyle.PEC -> {
                            // Pec split: vertical center line + lateral crease.
                            drawLine(
                                color = crease,
                                start = Offset(shape.cx, shape.cy - halfL * 0.7f),
                                end = Offset(shape.cx, shape.cy + halfL * 0.5f),
                                strokeWidth = 4.5f,
                            )
                            if (level >= 2) {
                                for (dir in intArrayOf(-1, 1)) {
                                    drawLine(
                                        color = crease,
                                        start = Offset(
                                            shape.cx + dir * halfW * 0.5f,
                                            shape.cy - halfL * 0.5f,
                                        ),
                                        end = Offset(
                                            shape.cx + dir * halfW * 0.5f,
                                            shape.cy + halfL * 0.3f,
                                        ),
                                        strokeWidth = 4.5f,
                                    )
                                }
                            }
                        }
                        KangarooBodyGeometry.DetailStyle.NONE -> Unit
                    }
                }
                is KangarooBodyGeometry.ZoneShape.Ellipse -> {
                    // Delt caps: one horizontal crease at level ≥2.
                    if (level >= 2) {
                        drawLine(
                            color = crease,
                            start = Offset(shape.cx - shape.rx * 0.6f, shape.cy),
                            end = Offset(shape.cx + shape.rx * 0.6f, shape.cy),
                            strokeWidth = 4f,
                        )
                    }
                }
            }
        }
    }

    private fun scaled(
        shape: KangarooBodyGeometry.ZoneShape,
        ws: Float,
        ls: Float,
    ): KangarooBodyGeometry.ZoneShape = when (shape) {
        is KangarooBodyGeometry.ZoneShape.Capsule -> shape.copy(w = shape.w * ws, l = shape.l * ls)
        is KangarooBodyGeometry.ZoneShape.Ellipse -> shape.copy(rx = shape.rx * ws, ry = shape.ry * ws)
    }
}

/**
 * Back-compat bridge: the dashboard + widget called `drawBodyMap(side,
 * counts, artFront, artBack)` against the PNG pipeline; the vector figure
 * needs no bitmaps. Every call site now draws the vector figure directly.
 */
internal fun DrawScope.drawBodyMap(
    side: KangarooBodyGeometry.Side,
    counts: Map<String, Int>,
) {
    with(KangarooFigurePainter) { drawFigure(side, counts) }
}
