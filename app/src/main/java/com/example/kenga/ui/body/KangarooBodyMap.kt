package com.example.kenga.ui.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.kenga.data.local.MuscleGroup
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The kangaroo body map (Art Upgrade v5 — VECTOR FIGURE): the figure is drawn
 * entirely from vector geometry — no AI bitmap, no overlay patches. Every one
 * of the tappable muscle zones IS a real body part rendered in its tier color
 * ([todayTierColor], 0 sets = dull red … 6+ = white) and physically BULKS UP
 * with the tier (width grows ~7%/tier, plus highlights and muscle-detail
 * strokes as the part gets worked — see [KangarooFigurePainter]).
 *
 * Non-muscle anatomy (head, ears, muzzle, neck, paws, shins, tail) is drawn
 * in fixed neutral fur colors so the animal still reads as a kangaroo.
 *
 * Geometry lives in the fixed 1000×1500 canvas space; hit-testing maps the
 * tap into each shape's rotated frame and applies the SAME bulk scaling the
 * painter used, so a grown muscle is tappable where it visibly bulges.
 * The head/ears zone is GONE — NECK stays in the taxonomy (stats/log/settings)
 * but has no tappable region on the map.
 */
object KangarooBodyGeometry {

    const val CANVAS_WIDTH = 1000f
    const val CANVAS_HEIGHT = 1500f

    enum class Side { FRONT, BACK }

    /** High-tier muscle detailing painted inside the part (see painter). */
    enum class DetailStyle { NONE, SPLIT, STRIPES, SIX_PACK, PEC }

    /** One tappable zone: a muscle group drawn as one or more rotated shapes. */
    data class Zone(
        val group: MuscleGroup,
        val shapes: List<ZoneShape>,
        val drawOrder: Int, // back-to-front
        val detail: DetailStyle = DetailStyle.NONE,
    )

    /** Fixed-color anatomy part (head, ears, tail, paws, …). */
    data class NeutralPart(
        val shapes: List<ZoneShape>,
        val fill: Color,
        val drawOrder: Int,
        val outlined: Boolean = true,
    )

    /**
     * Rotated shapes. [angleDeg] is the tilt of the shape's long axis from
     * vertical, in degrees, positive = the axis leans right going down
     * (screen coords, y down). 0 = perfectly upright. NOTE: the draw path
     * negates the angle (Compose `rotate(+θ)` is clockwise = leans LEFT
     * going down), so this convention holds for both hit-testing AND draw.
     */
    sealed interface ZoneShape {
        val cx: Float
        val cy: Float
        val angleDeg: Float

        /** Rotated capsule: a stadium of length [l] and width [w] centered at (cx, cy). */
        data class Capsule(
            override val cx: Float,
            override val cy: Float,
            val l: Float,
            val w: Float,
            override val angleDeg: Float = 0f,
        ) : ZoneShape

        /** Rotated ellipse with radii [rx]/[ry] centered at (cx, cy). */
        data class Ellipse(
            override val cx: Float,
            override val cy: Float,
            val rx: Float,
            val ry: Float,
            override val angleDeg: Float = 0f,
        ) : ZoneShape
    }

    private fun cap(cx: Float, cy: Float, l: Float, w: Float, a: Float = 0f) =
        ZoneShape.Capsule(cx, cy, l, w, a)
    private fun ell(cx: Float, cy: Float, rx: Float, ry: Float, a: Float = 0f) =
        ZoneShape.Ellipse(cx, cy, rx, ry, a)

    // ── Neutral anatomy palette (fixed fur colors) ─────────────────────────
    val FUR = Color(0xFFC79A66)
    val FUR_DARK = Color(0xFF5D4632)
    val CREAM = Color(0xFFF2E4C8)
    val INNER_EAR = Color(0xFFE8B29A)
    val FACE_DARK = Color(0xFF3A2A1E)

    // ── FRONT zones (tier-colored muscles) ─────────────────────────────────
    val frontZones: List<Zone> = listOf(
        Zone(MuscleGroup.QUADS, listOf(
            cap(418f, 1078f, 210f, 92f, -6f), cap(578f, 1078f, 210f, 92f, 5f),
        ), 5, DetailStyle.STRIPES),
        Zone(MuscleGroup.ANKLES_FEET, listOf(
            ell(360f, 1370f, 62f, 36f, -28f), ell(640f, 1370f, 62f, 36f, 28f),
        ), 30),
        Zone(MuscleGroup.HIPS_GLUTES, listOf(
            cap(500f, 990f, 240f, 90f, 0f),
        ), 40),
        Zone(MuscleGroup.OBLIQUES, listOf(
            cap(432f, 745f, 122f, 40f, -10f), cap(568f, 745f, 122f, 40f, 10f),
        ), 50, DetailStyle.SPLIT),
        Zone(MuscleGroup.CORE, listOf(
            cap(500f, 750f, 172f, 132f, 0f),
        ), 60, DetailStyle.SIX_PACK),
        Zone(MuscleGroup.GROIN_ADDUCTORS, listOf(
            ell(500f, 982f, 26f, 20f, 0f),
        ), 65),
        Zone(MuscleGroup.CHEST, listOf(
            cap(500f, 565f, 225f, 185f, 0f),
        ), 80, DetailStyle.PEC),
        Zone(MuscleGroup.SHOULDERS, listOf(
            ell(395f, 478f, 56f, 48f, -16f), ell(605f, 478f, 56f, 48f, 16f),
        ), 90, DetailStyle.SPLIT),
        Zone(MuscleGroup.BICEPS_TRICEPS, listOf(
            cap(342f, 562f, 180f, 58f, -21f), cap(658f, 562f, 180f, 58f, 21f),
        ), 100, DetailStyle.STRIPES),
        Zone(MuscleGroup.WRISTS_FOREARMS, listOf(
            cap(291f, 720f, 155f, 48f, -14f), cap(709f, 720f, 155f, 48f, 14f),
        ), 110, DetailStyle.SPLIT),
    )

    // ── BACK zones (tier-colored muscles) ──────────────────────────────────
    val backZones: List<Zone> = listOf(
        Zone(MuscleGroup.HAMSTRINGS, listOf(
            cap(422f, 1130f, 200f, 88f, -5f), cap(578f, 1130f, 200f, 88f, 5f),
        ), 5, DetailStyle.STRIPES),
        Zone(MuscleGroup.CALVES, listOf(
            cap(412f, 1285f, 140f, 58f, -3f), cap(588f, 1287f, 140f, 58f, 4f),
        ), 15, DetailStyle.STRIPES),
        Zone(MuscleGroup.ANKLES_FEET, listOf(
            ell(383f, 1380f, 55f, 36f, -20f), ell(617f, 1380f, 55f, 36f, 20f),
        ), 30),
        Zone(MuscleGroup.HIPS_GLUTES, listOf(
            ell(438f, 995f, 64f, 72f, 0f), ell(562f, 995f, 64f, 72f, 0f),
        ), 40),
        Zone(MuscleGroup.OBLIQUES, listOf(
            cap(432f, 760f, 120f, 40f, -10f), cap(568f, 760f, 120f, 40f, 10f),
        ), 50, DetailStyle.SPLIT),
        Zone(MuscleGroup.LOWER_BACK, listOf(
            cap(500f, 810f, 160f, 140f, 0f),
        ), 60, DetailStyle.SPLIT),
        Zone(MuscleGroup.LATS, listOf(
            cap(430f, 690f, 140f, 72f, -6f), cap(570f, 690f, 140f, 72f, 6f),
        ), 70, DetailStyle.SPLIT),
        Zone(MuscleGroup.UPPER_BACK, listOf(
            cap(500f, 560f, 210f, 190f, 0f),
        ), 80, DetailStyle.SPLIT),
        Zone(MuscleGroup.SHOULDERS, listOf(
            ell(392f, 478f, 55f, 48f, -16f), ell(608f, 478f, 55f, 48f, 16f),
        ), 90, DetailStyle.SPLIT),
        Zone(MuscleGroup.BICEPS_TRICEPS, listOf( // triceps, seen from behind
            cap(342f, 562f, 180f, 58f, -21f), cap(658f, 562f, 180f, 58f, 21f),
        ), 100, DetailStyle.STRIPES),
        Zone(MuscleGroup.WRISTS_FOREARMS, listOf(
            cap(291f, 720f, 155f, 48f, -14f), cap(709f, 720f, 155f, 48f, 14f),
        ), 110, DetailStyle.SPLIT),
    )

    // ── Neutral anatomy ────────────────────────────────────────────────────

    /**
     * The fur body silhouette drawn UNDER every muscle zone: torso, pelvis
     * and limb columns. Any gap between tier-colored zones reads as fur,
     * never as a hole — and at tier 0 a rim of fur frames the dull-red
     * muscles, exactly like an anatomy map on a real animal.
     */
    private val bodySilhouette = NeutralPart(listOf(
        cap(500f, 730f, 560f, 215f, 0f),   // torso column
        ell(500f, 990f, 130f, 120f),       // pelvis block
        cap(416f, 1150f, 365f, 118f, -4f), // left leg column
        cap(584f, 1150f, 365f, 118f, 4f),  // right leg column
        cap(322f, 650f, 340f, 92f, -17f),  // left arm column
        cap(678f, 650f, 340f, 92f, 17f),   // right arm column
    ), FUR, 0)

    /** Tapering tail: chained circles along a quadratic curve (union-outlined). */
    private fun tailShapes(): List<ZoneShape> {
        val p0 = Pair(592f, 1005f); val p1 = Pair(795f, 1150f); val p2 = Pair(872f, 1425f)
        return (0..14).map { i ->
            val t = i / 14f
            val u = 1f - t
            val x = u * u * p0.first + 2f * u * t * p1.first + t * t * p2.first
            val y = u * u * p0.second + 2f * u * t * p1.second + t * t * p2.second
            val r = 50f + (15f - 50f) * t
            ell(x, y, r, r)
        }
    }

    val frontNeutrals: List<NeutralPart> = listOf(
        NeutralPart(tailShapes(), FUR, -10),
        bodySilhouette,
        NeutralPart(listOf( // shins (no calf zone on the front view)
            cap(404f, 1252f, 150f, 54f, -3f), cap(596f, 1254f, 150f, 54f, 4f),
        ), FUR, 20),
        NeutralPart(listOf( // paws
            ell(268f, 826f, 27f, 25f), ell(732f, 826f, 27f, 25f),
        ), FUR, 115),
        NeutralPart(listOf( // outer ears
            cap(436f, 168f, 185f, 46f, -10f), cap(564f, 168f, 185f, 46f, 10f),
        ), FUR, 128),
        NeutralPart(listOf( // inner ears (front face only)
            cap(438f, 172f, 135f, 22f, -10f), cap(562f, 172f, 135f, 22f, 10f),
        ), INNER_EAR, 130, outlined = false),
        NeutralPart(listOf(cap(500f, 402f, 110f, 84f)), FUR, 135), // neck
        NeutralPart(listOf(ell(500f, 298f, 92f, 88f)), FUR, 140), // head
        NeutralPart(listOf(ell(500f, 350f, 58f, 50f)), CREAM, 145), // muzzle
        NeutralPart(listOf(ell(500f, 316f, 15f, 11f)), FACE_DARK, 152, outlined = false),
        NeutralPart(listOf( // eyes + glints
            ell(464f, 276f, 11f, 11f), ell(536f, 276f, 11f, 11f),
        ), FACE_DARK, 153, outlined = false),
        NeutralPart(listOf(
            ell(468f, 271f, 3.5f, 3.5f), ell(540f, 271f, 3.5f, 3.5f),
        ), Color.White, 154, outlined = false),
        NeutralPart(listOf(cap(500f, 357f, 30f, 7f)), FACE_DARK, 155, outlined = false), // mouth
    )

    val backNeutrals: List<NeutralPart> = listOf(
        NeutralPart(tailShapes(), FUR, -10),
        bodySilhouette,
        NeutralPart(listOf(
            ell(268f, 826f, 27f, 25f), ell(732f, 826f, 27f, 25f),
        ), FUR, 115), // paws
        NeutralPart(listOf( // ears seen from behind: solid fur
            cap(436f, 168f, 185f, 46f, -10f), cap(564f, 168f, 185f, 46f, 10f),
        ), FUR, 128),
        NeutralPart(listOf(cap(500f, 402f, 110f, 84f)), FUR, 135), // neck
        NeutralPart(listOf(ell(500f, 298f, 92f, 88f)), FUR, 140), // back of head
    )

    fun zonesFor(side: Side): List<Zone> = if (side == Side.BACK) backZones else frontZones
    fun neutralsFor(side: Side): List<NeutralPart> =
        if (side == Side.BACK) backNeutrals else frontNeutrals

    /**
     * How many DISTINCT tappable body-part areas have logged sets today
     * (count > 0) — Kenga's "confidence" metric, served to Tail so the scene
     * widget moves him in front of the lizard at ≥ [CONFIDENT_ZONE_COUNT]
     * colored areas (he's been active today, so he steps forward).
     */
    fun coloredZoneCount(counts: Map<String, Int>): Int {
        val zoned = (frontZones + backZones).map { it.group.id }.toSet()
        return zoned.count { (counts[it] ?: 0) > 0 }
    }

    /** Confidence threshold: areas colored today before he steps in front. */
    const val CONFIDENT_ZONE_COUNT = 4

    // ── Flip centering (the turntable) ─────────────────────────────────────
    //
    // The tail (right side) makes the figure's bounding box asymmetric, and
    // the rotationY flip mirrors everything around the canvas center — an
    // off-center bbox would make him visibly jump sideways on every
    // front↔back swipe. So ALL geometry (both views, zones at max bulk)
    // is translated by [centeringOffsetX] so the UNION horizontal bbox is
    // centered on the canvas — exactly what the old bitmap pipeline did by
    // compositing each cutout "centered on its bbox center". Mirrored back
    // view then occupies the same band → the figure stays put.

    /** Horizontal half-extent of a (possibly rotated) shape. */
    private fun ZoneShape.halfExtentX(): Float {
        val rad = Math.toRadians(angleDeg.toDouble())
        val s = abs(sin(rad).toFloat())
        val c = abs(cos(rad).toFloat())
        return when (this) {
            // Capsule = Minkowski sum of a segment (length l−w) and a disk:
            // half-extent = (l−w)/2·|sinθ| + w/2.
            is ZoneShape.Capsule -> (l - w) / 2f * s + w / 2f
            is ZoneShape.Ellipse -> sqrt((rx * c) * (rx * c) + (ry * s) * (ry * s))
        }
    }

    /** Union horizontal bounds of every shape in BOTH views (max bulk). */
    private val unionBoundsX: ClosedFloatingPointRange<Float> by lazy {
        val ws = bulkWidth(6)
        val ls = bulkLength(6)
        val all = (frontZones + backZones).flatMap { z -> z.shapes.map { it.scaled(ws, ls) } } +
            frontNeutrals.flatMap { it.shapes } + backNeutrals.flatMap { it.shapes }
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (sh in all) {
            val h = sh.halfExtentX()
            if (sh.cx - h < min) min = sh.cx - h
            if (sh.cx + h > max) max = sh.cx + h
        }
        min..max
    }

    /** X translation that centers the figure's union bbox on the canvas. */
    fun centeringOffsetX(): Float =
        CANVAS_WIDTH / 2f - unionBoundsX.let { (it.start + it.endInclusive) / 2f }

    // ── Tier → muscle bulk (identical math for paint AND hit-test) ────────

    fun tierOf(count: Int): Int = count.coerceIn(0, 6)

    /** Width multiplier: 1.00 at 0 sets → 1.42 at 6+ (muscle bulk growth). */
    fun bulkWidth(count: Int): Float = 1f + 0.07f * tierOf(count)

    /** Length multiplier: muscles also lengthen slightly as they grow. */
    fun bulkLength(count: Int): Float = 1f + 0.015f * tierOf(count)

    private fun ZoneShape.scaled(ws: Float, ls: Float): ZoneShape = when (this) {
        is ZoneShape.Capsule -> copy(w = w * ws, l = l * ls)
        is ZoneShape.Ellipse -> copy(rx = rx * ws, ry = ry * ws)
    }

    /** Front-most first — for tap hit-testing. */
    fun hitTestOrder(side: Side): List<Zone> = zonesFor(side).sortedByDescending { it.drawOrder }

    /**
     * Zone at (x, y) given today's counts — hit-testing uses the same bulk
     * scaling the painter applied, so taps land where the muscle visibly is.
     */
    fun zoneAt(side: Side, x: Float, y: Float, counts: Map<String, Int>): MuscleGroup? {
        // Undo the painter's flip-centering translation first.
        val sx = x - centeringOffsetX()
        for (zone in hitTestOrder(side)) {
            val count = counts[zone.group.id] ?: 0
            val ws = bulkWidth(count)
            val ls = bulkLength(count)
            if (zone.shapes.any { it.scaled(ws, ls).contains(sx, y) }) return zone.group
        }
        return null
    }

    private fun ZoneShape.contains(x: Float, y: Float): Boolean {
        val rad = Math.toRadians(angleDeg.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        // Axis unit (down the limb): (sin, cos); perpendicular: (cos, -sin).
        val u = dx * s + dy * c
        val v = dx * c - dy * s
        return when (this) {
            is ZoneShape.Capsule -> {
                val halfW = w / 2f
                val straight = l / 2f - halfW
                if (abs(u) <= straight) abs(v) <= halfW
                else {
                    // End cap circles.
                    val ux = (abs(u) - straight).coerceAtLeast(0f)
                    ux * ux + v * v <= halfW * halfW
                }
            }
            is ZoneShape.Ellipse -> {
                val du = u / ry
                val dv = v / rx
                du * du + dv * dv <= 1f
            }
        }
    }
}

/** Drag→angle gain (Octo DEGREES_PER_DP): ~half-screen drag ≈ 180°. */
private const val DEGREES_PER_DP = 0.9f
private const val FLICK_BIAS_SECONDS = 0.12f

/**
 * The flippable kangaroo figure. [todayCounts] maps muscle-group id → sets
 * logged today; each zone renders [todayTierColor] of its count and bulks up
 * with the tier. Tap a zone → [onZoneTap] with the [MuscleGroup]. Horizontal
 * drag turns the figure; the zone set swaps at ±90° and settles to the
 * nearest side on release.
 */
@Composable
fun KangarooBodyMap(
    todayCounts: Map<String, Int>,
    onZoneTap: (MuscleGroup) -> Unit,
    side: KangarooBodyGeometry.Side,
    flipAngle: Float,
    onDragDelta: (Float) -> Unit,
    onSettle: (flickBiasDegPerSec: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val degPerPx = remember(density) { DEGREES_PER_DP / density.density }
    val recentDeltas = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    val currentSide = side

    Canvas(
        modifier = modifier
            .graphicsLayer {
                rotationY = flipAngle
                cameraDistance = 14f * density.density * 160f
            }
            .pointerInput(currentSide) {
                detectHorizontalDragGestures(
                    onDragStart = { recentDeltas.clear() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        recentDeltas.add(dragAmount)
                        if (recentDeltas.size > 5) recentDeltas.removeAt(0)
                        onDragDelta(dragAmount)
                    },
                    onDragEnd = {
                        val avgPx = if (recentDeltas.isEmpty()) 0f else recentDeltas.average().toFloat()
                        onSettle(avgPx * 60f * degPerPx)
                        recentDeltas.clear()
                    },
                    onDragCancel = { onSettle(0f) },
                )
            }
            .pointerInput(currentSide, todayCounts) {
                detectTapGestures { offset ->
                    val sx = size.width / KangarooBodyGeometry.CANVAS_WIDTH
                    val sy = size.height / KangarooBodyGeometry.CANVAS_HEIGHT
                    val x = offset.x / sx
                    val y = offset.y / sy
                    KangarooBodyGeometry.zoneAt(currentSide, x, y, todayCounts)?.let(onZoneTap)
                }
            }
            .semantics { contentDescription = "Kangaroo body map, ${if (currentSide == KangarooBodyGeometry.Side.FRONT) "front" else "back"} view" },
    ) {
        val sx = size.width / KangarooBodyGeometry.CANVAS_WIDTH
        val sy = size.height / KangarooBodyGeometry.CANVAS_HEIGHT
        scale(sx, sy) {
            drawBodyMap(currentSide, todayCounts)
        }
    }
}
