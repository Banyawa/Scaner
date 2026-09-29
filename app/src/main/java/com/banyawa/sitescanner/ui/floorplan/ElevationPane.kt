package com.banyawa.sitescanner.ui.floorplan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.core.floorplan.Elevation
import com.banyawa.sitescanner.core.floorplan.Hinge
import com.banyawa.sitescanner.core.floorplan.OpeningType
import com.banyawa.sitescanner.core.units.LengthFormat
import com.banyawa.sitescanner.ui.theme.PlanColors
import kotlin.math.max
import kotlin.math.min

/** The wall elevations: pick a wall by its key, then pan and zoom its drawing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElevationPane(
    state: PlanState.Loaded,
    selected: String?,
    onSelect: (String) -> Unit,
    resetToken: Int,
    modifier: Modifier = Modifier,
) {
    val elevations = state.elevations
    when {
        elevations == null -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.elevations_preparing))
            }
        }
        elevations.isEmpty() -> Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.elevations_empty), textAlign = TextAlign.Center)
        }
        else -> {
            val e = elevations.firstOrNull { it.key == selected } ?: elevations.first()
            Column(modifier) {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (el in elevations) {
                                FilterChip(selected = el.key == e.key, onClick = { onSelect(el.key) }, label = { Text(el.key) })
                            }
                        }
                        Text(
                            stringResource(R.string.elevation_summary, e.key, LengthFormat.format(e.length), LengthFormat.format(e.height)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.elevation_legend),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ElevationCanvas(e, state.tags, resetToken, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * One wall seen from inside: x metres from its left corner to the right, height up. Drawn
 * like the exported elevation: scan underlay, openings with their swing marks, running
 * dimensions along the floor, the height and AR measurements.
 */
@Composable
private fun ElevationCanvas(e: Elevation, tags: Map<String, String>, resetToken: Int, modifier: Modifier = Modifier) {
    val (surface, front) = remember(e) {
        val onWall = ArrayList<Offset>(e.pointCount)
        val inFront = ArrayList<Offset>()
        for (i in 0 until e.pointCount) {
            val p = Offset(e.points[i * 3], e.points[i * 3 + 1])
            if (e.points[i * 3 + 2] > Elevation.SURFACE_DEPTH) inFront += p else onWall += p
        }
        onWall to inFront
    }
    val details = e.openings.map { o ->
        buildString {
            append("${LengthFormat.toMillimeters(o.width)} × ${LengthFormat.toMillimeters(o.opening.height)}")
            if (o.opening.type == OpeningType.WINDOW) {
                append(" · ")
                append(stringResource(R.string.opening_sill, LengthFormat.format(o.bottom)))
            }
        }
    }
    val measureLabels = e.measurements.map { m ->
        listOf(m.label, LengthFormat.format(m.lengthM)).filter { it.isNotEmpty() }.joinToString(" ")
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val pad = with(LocalDensity.current) { FIT_PADDING.toPx() }

    fun fit() {
        if (canvasSize.width == 0 || canvasSize.height == 0) return
        // Leave room around the wall for the dimensions.
        scale = min((canvasSize.width - 2 * pad) / e.length, (canvasSize.height - 2 * pad) / e.height).coerceAtLeast(1f)
        ox = canvasSize.width / 2f - e.length / 2f * scale
        oy = canvasSize.height / 2f + e.height / 2f * scale
    }
    // A new wall gets fitted; edits to its openings keep the current view.
    LaunchedEffect(canvasSize, resetToken, e.key) { fit() }

    val measurer = rememberTextMeasurer()
    val dimStyle = TextStyle(color = PlanColors.Dimension, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val doorStyle = TextStyle(color = PlanColors.Door, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val windowStyle = TextStyle(color = PlanColors.Window, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val detailStyle = TextStyle(color = PlanColors.Wall, fontSize = 11.sp)
    val measureStyle = TextStyle(color = PlanColors.Measurement, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

    Canvas(
        modifier
            .background(PlanColors.Background)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(5f, 5000f)
                    val k = newScale / scale
                    ox = centroid.x - (centroid.x - ox) * k + pan.x
                    oy = centroid.y - (centroid.y - oy) * k + pan.y
                    scale = newScale
                }
            },
    ) {
        if (scale <= 0f) return@Canvas
        val px = 1f / scale
        withTransform({
            translate(ox, oy)
            scale(scale, -scale, pivot = Offset.Zero)
        }) {
            val dot = max(UNDERLAY_CELL, 2.5f * px)
            if (surface.isNotEmpty()) drawPoints(surface, PointMode.Points, PlanColors.Slice, strokeWidth = dot)
            if (front.isNotEmpty()) drawPoints(front, PointMode.Points, PlanColors.ElevationFront, strokeWidth = dot)
            for (o in e.openings) {
                val window = o.opening.type == OpeningType.WINDOW
                val color = openingColor(o.opening.type)
                val topLeft = Offset(o.left, o.bottom)
                val box = Size(o.width, o.top - o.bottom)
                drawRect(if (window) PlanColors.WindowFill else PlanColors.Background.copy(alpha = 0.6f), topLeft = topLeft, size = box)
                drawRect(color, topLeft = topLeft, size = box, style = Stroke(width = 2.dp.toPx() * px))
                // Door swing: lines from the free edge's corners meet on the hinge side.
                fun leaf(hingeX: Float, freeX: Float) {
                    val apex = Offset(hingeX, (o.bottom + o.top) / 2f)
                    drawLine(color, Offset(freeX, o.bottom), apex, strokeWidth = 1.dp.toPx() * px)
                    drawLine(color, Offset(freeX, o.top), apex, strokeWidth = 1.dp.toPx() * px)
                }
                when (o.swing?.hinge) {
                    Hinge.LEFT -> leaf(o.left, o.right)
                    Hinge.RIGHT -> leaf(o.right, o.left)
                    Hinge.BOTH -> {
                        leaf(o.left, (o.left + o.right) / 2f)
                        leaf(o.right, (o.left + o.right) / 2f)
                    }
                    null -> Unit
                }
            }
            drawRect(PlanColors.Wall, topLeft = Offset.Zero, size = Size(e.length, e.height), style = Stroke(width = 2.5.dp.toPx() * px))
            for (m in e.measurements) {
                drawLine(
                    PlanColors.Measurement,
                    Offset(m.x0, m.z0),
                    Offset(m.x1, m.z1),
                    strokeWidth = 2.dp.toPx() * px,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx() * px, 5.dp.toPx() * px)),
                )
            }
        }

        fun screen(x: Float, z: Float) = Offset(ox + x * scale, oy - z * scale)
        // Running dimensions along the floor (corner, opening edges, corner), then overall.
        val stops = (listOf(0f, e.length) + e.openings.flatMap { listOf(it.left, it.right) })
            .map { it.coerceIn(0f, e.length) }
            .sorted()
            .fold(mutableListOf<Float>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > MIN_DIMENSION) acc += v; acc }
        val chainY = oy + DIM_GAP.toPx()
        val overallY = if (stops.size > 2) chainY + DIM_STEP.toPx() else chainY
        for (x in stops) {
            drawLine(PlanColors.Dimension.copy(alpha = 0.5f), Offset(ox + x * scale, oy + 3.dp.toPx()), Offset(ox + x * scale, overallY + 4.dp.toPx()), 1f)
        }
        if (stops.size > 2) {
            for (k in 0 until stops.size - 1) {
                dimension(measurer, screen(stops[k], 0f).copy(y = chainY), screen(stops[k + 1], 0f).copy(y = chainY), mm(stops[k + 1] - stops[k]), dimStyle)
            }
        }
        dimension(measurer, Offset(ox, overallY), Offset(ox + e.length * scale, overallY), mm(e.length), dimStyle)
        val heightX = ox - DIM_GAP.toPx()
        dimension(measurer, Offset(heightX, oy), Offset(heightX, oy - e.height * scale), mm(e.height), dimStyle)

        for ((i, o) in e.openings.withIndex()) {
            val c = screen((o.left + o.right) / 2f, (o.bottom + o.top) / 2f)
            val style = if (o.opening.type == OpeningType.WINDOW) windowStyle else doorStyle
            drawLabel(measurer, tags[o.opening.id].orEmpty(), c - Offset(0f, 9.dp.toPx()), style)
            drawLabel(measurer, details[i], c + Offset(0f, 9.dp.toPx()), detailStyle)
        }
        for ((i, m) in e.measurements.withIndex()) {
            drawLabel(measurer, measureLabels[i], screen((m.x0 + m.x1) / 2f, (m.z0 + m.z1) / 2f), measureStyle)
        }
    }
}

/** Dimension line in screen space with 45° ticks and its text on the line. */
private fun DrawScope.dimension(measurer: TextMeasurer, a: Offset, b: Offset, text: String, style: TextStyle) {
    drawLine(PlanColors.Dimension, a, b, 1.dp.toPx())
    val t = 4.dp.toPx()
    for (p in listOf(a, b)) drawLine(PlanColors.Dimension, p + Offset(-t, t), p + Offset(t, -t), 1.5.dp.toPx())
    drawLabel(measurer, text, Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f), style)
}

private fun mm(m: Float) = LengthFormat.toMillimeters(m).toString()

private val FIT_PADDING = 56.dp
private val DIM_GAP = 22.dp
private val DIM_STEP = 22.dp

/** Stops closer than this (metres) are not dimensioned separately. */
private const val MIN_DIMENSION = 0.02f

/** Underlay points are drawn at least as big as their raster cell, so the wall looks solid. */
private const val UNDERLAY_CELL = 0.02f
