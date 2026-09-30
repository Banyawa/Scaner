package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.DoorLeaf
import com.banyawa.sitescanner.core.floorplan.Elevation
import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningTags
import com.banyawa.sitescanner.core.floorplan.OpeningType
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.scene.CeilingMap
import com.banyawa.sitescanner.core.scene.SceneObject
import com.banyawa.sitescanner.core.units.LengthFormat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a 2D plan drawing in millimetres: walls, aligned dimension strings, door and
 * window symbols with tags and a schedule, AR measurements (as 3D lines), elevation keys,
 * the objects found standing in the room (footprints, tags and a schedule), the clear
 * height under the ceiling and, optionally, the raw scan slice as a tracing underlay.
 */
object FloorPlanDxf {
    const val LAYER_WALLS = "A-WALL"
    const val LAYER_DIMS = "A-DIMS"
    const val LAYER_MEASURE = "A-MEAS"
    const val LAYER_SLICE = "A-SCAN-SLICE"
    const val LAYER_NOTES = "A-NOTE"
    const val LAYER_DOORS = "A-DOOR"
    const val LAYER_WINDOWS = "A-GLAZ"
    const val LAYER_TAGS = "A-OPEN-TAG"
    const val LAYER_SCHEDULE = "A-SCHED"
    const val LAYER_ELEVATION_KEYS = "A-ELEV-KEY"
    const val LAYER_OBJECTS = "A-OBJECT"
    const val LAYER_OBJECT_TAGS = "A-OBJECT-TAG"
    const val LAYER_CEILING = "A-CEIL-HT"

    data class Options(
        val title: String? = null,
        val dimensionOffsetMm: Double = 400.0,
        val textHeightMm: Double = 100.0,
        val includeSlice: Boolean = true,
        val maxSlicePoints: Int = 60_000,
        /** Also write the ceiling map's heights over the plan, the lowest of each 1 m square as text. */
        val ceilingGrid: Boolean = false,
    )

    /**
     * [elevations] get their keys marked outside the walls they show; [objects] are drawn as
     * footprints with "M<id>" tags and scheduled; [ceiling] adds the clear-height note and
     * marks the lowest overhead point when a beam or duct hangs under the ceiling.
     */
    fun build(
        plan: FloorPlan,
        slice: FloatArray? = null,
        measurements: List<Measurement> = emptyList(),
        options: Options = Options(),
        elevations: List<Elevation> = emptyList(),
        objects: List<SceneObject> = emptyList(),
        ceiling: CeilingMap? = null,
    ): DxfDocument {
        val dxf = DxfDocument()
            .layer(LAYER_WALLS, 7)
            .layer(LAYER_DIMS, 2)
            .layer(LAYER_MEASURE, 1)
            .layer(LAYER_SLICE, 8)
            .layer(LAYER_NOTES, 3)
            .layer(LAYER_DOORS, 4)
            .layer(LAYER_WINDOWS, 5)
            .layer(LAYER_TAGS, 6)
            .layer(LAYER_SCHEDULE, 3)
        if (elevations.isNotEmpty()) dxf.layer(LAYER_ELEVATION_KEYS, 30)
        if (objects.isNotEmpty()) dxf.layer(LAYER_OBJECTS, 6).layer(LAYER_OBJECT_TAGS, 6)
        if (ceiling != null) dxf.layer(LAYER_CEILING, 4)
        val th = options.textHeightMm

        if (options.includeSlice && slice != null && slice.isNotEmpty()) {
            val n = slice.size / 2
            val step = maxOf(1, (n + options.maxSlicePoints - 1) / options.maxSlicePoints)
            var i = 0
            while (i < n) {
                dxf.point(LAYER_SLICE, slice[i * 2] * 1000.0, slice[i * 2 + 1] * 1000.0)
                i += step
            }
        }

        val centroid = plan.walls.map { it.midpoint }.let { mids ->
            if (mids.isEmpty()) Vec2.ZERO else mids.reduce { a, b -> a + b } * (1f / mids.size)
        }
        for (w in plan.walls) {
            dxf.line(LAYER_WALLS, w.start.x * 1000.0, w.start.y * 1000.0, w.end.x * 1000.0, w.end.y * 1000.0)
            dimension(dxf, w.start, w.end, centroid, options)
        }

        val tags = OpeningTags.assign(plan.openings)
        for (o in plan.openings) opening(dxf, o, tags.getValue(o.id), th)
        for (e in elevations) elevationKey(dxf, e, options)
        for (o in objects) objectFootprint(dxf, o, th)
        if (ceiling != null) ceilingHeights(dxf, ceiling, th, options.ceilingGrid)

        for (m in measurements) {
            val a = plan.alignment.toSite(m.start)
            val b = plan.alignment.toSite(m.end)
            dxf.line(
                LAYER_MEASURE,
                a.x * 1000.0, a.y * 1000.0, a.z * 1000.0,
                b.x * 1000.0, b.y * 1000.0, b.z * 1000.0,
            )
            val label = listOf(m.label.trim(), "${LengthFormat.toMillimeters(m.lengthM)}").filter { it.isNotEmpty() }.joinToString(" = ")
            dxf.text(
                LAYER_MEASURE,
                (a.x + b.x) * 500.0,
                (a.y + b.y) * 500.0 + th * 0.5,
                th,
                label,
                align = DxfDocument.HAlign.CENTER,
            )
        }

        val bounds = plan.bounds()
        // Clear of the dimensions and, when drawn, the elevation keys.
        val margin = options.dimensionOffsetMm + th * (if (elevations.isEmpty()) 6 else 13)
        val noteX = (bounds?.min?.x ?: 0f) * 1000.0
        var noteY = (bounds?.max?.y ?: 0f) * 1000.0 + margin
        // Layer and text of each note line, top to bottom.
        val notes = buildList {
            options.title?.takeIf { it.isNotBlank() }?.let { add(LAYER_NOTES to it) }
            plan.roomHeight?.let { add(LAYER_NOTES to "Floor to ceiling: ${LengthFormat.toMillimeters(it)} mm") }
            if (ceiling != null && !ceiling.typicalHeight.isNaN()) add(LAYER_CEILING to clearHeightNote(ceiling))
            add(LAYER_NOTES to "Walls: ${plan.walls.size}, total length ${LengthFormat.toMillimeters(plan.totalWallLength)} mm")
            if (plan.openings.isNotEmpty()) {
                val doors = plan.openings.count { it.type != OpeningType.WINDOW }
                val windows = plan.openings.count { it.type == OpeningType.WINDOW }
                add(LAYER_NOTES to "Doors / openings: $doors, windows: $windows (clear sizes; verify rough openings on site)")
            }
            if (objects.isNotEmpty()) add(LAYER_NOTES to "Objects: ${objects.size} (M1..M${objects.size}, footprints from scan)")
            add(LAYER_NOTES to "Units: millimetres. Generated by Site Scanner - verify critical dimensions on site.")
        }
        for ((layer, line) in notes.asReversed()) {
            dxf.text(layer, noteX, noteY, th * 1.2, line)
            noteY += th * 2
        }

        var scheduleTop = (bounds?.min?.y ?: 0f) * 1000.0 - margin
        if (plan.openings.isNotEmpty()) {
            scheduleTop = schedule(dxf, plan.openings, tags, noteX, scheduleTop, th) - th * 4
        }
        if (objects.isNotEmpty()) objectSchedule(dxf, objects, noteX, scheduleTop, th)
        return dxf
    }

    /**
     * Door: jamb ticks plus the leaf drawn open at 90° with its swing arc, or a threshold
     * line when the swing is unknown. Window: glazing box straddling the wall. Both get a tag.
     */
    private fun opening(dxf: DxfDocument, o: Opening, tag: String, th: Double) {
        val s = o.start * 1000f
        val e = o.end * 1000f
        val n = o.interiorNormal
        fun seg(layer: String, a: Vec2, b: Vec2) = dxf.line(layer, a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble())

        val size = "${mm(o.width)}x${mm(o.height)}"
        val detail = if (o.type == OpeningType.WINDOW) {
            val jamb = n * 50f
            seg(LAYER_WINDOWS, s - jamb, e - jamb)
            seg(LAYER_WINDOWS, s + jamb, e + jamb)
            seg(LAYER_WINDOWS, s - jamb, s + jamb)
            seg(LAYER_WINDOWS, e - jamb, e + jamb)
            seg(LAYER_WINDOWS, s, e)
            "$size SILL ${mm(o.bottom)}"
        } else {
            val jamb = n * 75f
            seg(LAYER_DOORS, s - jamb, s + jamb)
            seg(LAYER_DOORS, e - jamb, e + jamb)
            val leaves = o.leaves
            if (leaves.isEmpty()) seg(LAYER_DOORS, s, e)
            for (leaf in leaves) swing(dxf, leaf)
            size
        }
        // Labels run along the wall, on the room side.
        val angle = DxfDrafting.readableAngle(o.direction)
        val mid = Vec2.lerp(s, e, 0.5f)
        val tagAt = mid + n * (th * 3.5).toFloat()
        dxf.text(LAYER_TAGS, tagAt.x.toDouble(), tagAt.y.toDouble(), th * 1.4, tag, angle, DxfDocument.HAlign.CENTER)
        val detailAt = mid + n * (th * 1.6).toFloat()
        dxf.text(LAYER_TAGS, detailAt.x.toDouble(), detailAt.y.toDouble(), th * 0.8, detail, angle, DxfDocument.HAlign.CENTER)
    }

    private fun swing(dxf: DxfDocument, leaf: DoorLeaf) {
        val h = leaf.hinge * 1000f
        val open = leaf.open * 1000f
        val closed = leaf.closed * 1000f
        dxf.line(LAYER_DOORS, h.x.toDouble(), h.y.toDouble(), open.x.toDouble(), open.y.toDouble())
        val toOpen = open - h
        val toClosed = closed - h
        val aOpen = Math.toDegrees(atan2(toOpen.y.toDouble(), toOpen.x.toDouble()))
        val aClosed = Math.toDegrees(atan2(toClosed.y.toDouble(), toClosed.x.toDouble()))
        // DXF arcs run counter-clockwise; the quarter turn goes whichever way the leaf swings.
        val (from, to) = if ((toClosed cross toOpen) > 0f) aClosed to aOpen else aOpen to aClosed
        dxf.arc(LAYER_DOORS, h.x.toDouble(), h.y.toDouble(), h.distanceTo(closed).toDouble(), normalizeDeg(from), normalizeDeg(to))
    }

    private fun normalizeDeg(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

    /** Closed footprint outline, tagged "M<id>" with its box size along the box's long side. */
    private fun objectFootprint(dxf: DxfDocument, o: SceneObject, th: Double) {
        val outline = DoubleArray(o.footprint.size * 2)
        for ((i, p) in o.footprint.withIndex()) {
            outline[i * 2] = p.x * 1000.0
            outline[i * 2 + 1] = p.y * 1000.0
        }
        dxf.polyline(LAYER_OBJECTS, outline, closed = true)

        val c = o.box.centre * 1000f
        val angle = DxfDrafting.readableAngle(Vec2(cos(o.box.yawRad), sin(o.box.yawRad)))
        val rad = Math.toRadians(angle)
        val up = Vec2(-sin(rad).toFloat(), cos(rad).toFloat())
        val tagAt = c + up * (th * 0.5).toFloat()
        dxf.text(LAYER_OBJECT_TAGS, tagAt.x.toDouble(), tagAt.y.toDouble(), th * 1.4, "M${o.id}", angle, DxfDocument.HAlign.CENTER)
        val sizeAt = c - up * (th * 1.1).toFloat()
        val size = "${mm(o.box.length)} × ${mm(o.box.width)} × ${mm(o.height)} mm"
        dxf.text(LAYER_OBJECT_TAGS, sizeAt.x.toDouble(), sizeAt.y.toDouble(), th * 0.8, size, angle, DxfDocument.HAlign.CENTER)
    }

    private fun clearHeightNote(ceiling: CeilingMap): String {
        val typical = "Clear height: typical ${mm(ceiling.typicalHeight)} mm"
        return if (hasLowPoint(ceiling)) "$typical, lowest ${mm(ceiling.minHeight)} mm under obstruction (see $LAYER_CEILING)" else typical
    }

    /** A beam, duct or pipe hangs noticeably under the ceiling proper. */
    private fun hasLowPoint(ceiling: CeilingMap) =
        ceiling.minAt != null && !ceiling.minHeight.isNaN() && ceiling.minHeight < ceiling.typicalHeight - LOW_POINT_MARGIN

    /** Circle and "min <height> mm" at the lowest overhead point; optionally the heights on a 1 m grid. */
    private fun ceilingHeights(dxf: DxfDocument, ceiling: CeilingMap, th: Double, grid: Boolean) {
        val lowAt = ceiling.minAt
        if (lowAt != null && hasLowPoint(ceiling)) {
            val x = lowAt.x * 1000.0
            val y = lowAt.y * 1000.0
            val radius = th * 1.5
            dxf.circle(LAYER_CEILING, x, y, radius)
            dxf.text(LAYER_CEILING, x + radius + th * 0.4, y - th * 0.4, th * 0.8, "min ${mm(ceiling.minHeight)} mm")
        }
        if (!grid) return
        // The lowest cell of each 1 m square, written at the square's centre.
        val per = maxOf(1, Math.round(1f / ceiling.cell))
        var row = 0
        while (row < ceiling.rows) {
            var col = 0
            while (col < ceiling.cols) {
                var lowest = Float.NaN
                for (r in row until minOf(row + per, ceiling.rows)) for (c in col until minOf(col + per, ceiling.cols)) {
                    val h = ceiling.heightAt(c, r)
                    if (!h.isNaN() && (lowest.isNaN() || h < lowest)) lowest = h
                }
                if (!lowest.isNaN()) {
                    val x = (ceiling.originX + (col + per / 2f) * ceiling.cell) * 1000.0
                    val y = (ceiling.originY + (row + per / 2f) * ceiling.cell) * 1000.0
                    dxf.text(LAYER_CEILING, x, y, th * 0.6, mm(lowest), align = DxfDocument.HAlign.CENTER)
                }
                col += per
            }
            row += per
        }
    }

    /** Door / window schedule as a text table (one TEXT per cell so columns line up); returns the y of its last row. */
    private fun schedule(dxf: DxfDocument, openings: List<Opening>, tags: Map<String, String>, x: Double, top: Double, th: Double): Double {
        val columns = doubleArrayOf(0.0, 800.0, 2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7400.0, 8600.0)
        val header = listOf("TAG", "TYPE", "WIDTH", "HEIGHT", "SILL", "HEAD", "FROM LEFT", "SWING", "CHECK")
        dxf.text(LAYER_SCHEDULE, x, top, th * 1.4, "DOOR / WINDOW SCHEDULE (mm, clear opening from scan)")
        dxf.text(
            LAYER_SCHEDULE, x, top - th * 2, th * 0.8,
            "FROM LEFT / SWING: seen from inside the room facing the wall. L / R / PAIR = hinge side, IN = opens into the room.",
        )
        var y = top - th * 4.5
        header.forEachIndexed { i, h -> dxf.text(LAYER_SCHEDULE, x + columns[i], y, th, h) }
        y -= th * 0.6
        dxf.line(LAYER_SCHEDULE, x, y, x + columns.last() + 800.0, y)
        val ordered = openings.sortedWith(compareBy({ it.type.ordinal }, { tags[it.id]?.drop(1)?.toIntOrNull() ?: 0 }))
        for (o in ordered) {
            y -= th * 1.8
            val row = listOf(
                tags.getValue(o.id),
                o.type.name,
                mm(o.width),
                mm(o.height),
                if (o.type == OpeningType.WINDOW) mm(o.bottom) else "-",
                mm(o.top),
                mm(o.distanceFromWallStart),
                o.swing?.code ?: "",
                when {
                    o.manual -> "MANUAL"
                    o.needsCheck -> "VERIFY"
                    else -> ""
                },
            )
            row.forEachIndexed { i, v -> if (v.isNotEmpty()) dxf.text(LAYER_SCHEDULE, x + columns[i], y, th, v) }
        }
        return y
    }

    /** Object schedule below the opening schedule: tag, box size, height and wall clearance. */
    private fun objectSchedule(dxf: DxfDocument, objects: List<SceneObject>, x: Double, top: Double, th: Double) {
        val columns = doubleArrayOf(0.0, 800.0, 2000.0, 3200.0, 4400.0)
        val header = listOf("TAG", "LENGTH", "WIDTH", "HEIGHT", "WALL CLEARANCE")
        dxf.text(LAYER_SCHEDULE, x, top, th * 1.4, "OBJECT SCHEDULE (mm, tightest box around the scanned footprint)")
        dxf.text(LAYER_SCHEDULE, x, top - th * 2, th * 0.8, "HEIGHT: lowest to highest scanned surface. WALL CLEARANCE: from the footprint to the nearest wall of the plan.")
        var y = top - th * 4.5
        header.forEachIndexed { i, h -> dxf.text(LAYER_SCHEDULE, x + columns[i], y, th, h) }
        y -= th * 0.6
        dxf.line(LAYER_SCHEDULE, x, y, x + columns.last() + 2000.0, y)
        for (o in objects.sortedBy { it.id }) {
            y -= th * 1.8
            val row = listOf("M${o.id}", mm(o.box.length), mm(o.box.width), mm(o.height), o.wallClearance?.let { mm(it) } ?: "-")
            row.forEachIndexed { i, v -> dxf.text(LAYER_SCHEDULE, x + columns[i], y, th, v) }
        }
    }

    private fun mm(m: Float) = DxfDrafting.mm(m)

    private fun dimension(dxf: DxfDocument, startM: Vec2, endM: Vec2, centroid: Vec2, options: Options) {
        val lengthM = startM.distanceTo(endM)
        if (lengthM <= 0f) return
        var normal = (endM - startM).normalized().perp()
        if ((Vec2.lerp(startM, endM, 0.5f) - centroid) dot normal < 0f) normal = -normal
        DxfDrafting.dimension(
            dxf, LAYER_DIMS, startM * 1000f, endM * 1000f, normal,
            options.dimensionOffsetMm.toFloat(), options.textHeightMm.toFloat(), mm(lengthM),
        )
    }

    /** Circled elevation key outside each wall, matching the elevation drawings. */
    private fun elevationKey(dxf: DxfDocument, e: Elevation, options: Options) {
        val th = options.textHeightMm
        val radius = th * 2.2
        val at = e.midpoint * 1000f - e.interiorNormal * (options.dimensionOffsetMm + th * 8).toFloat()
        dxf.circle(LAYER_ELEVATION_KEYS, at.x.toDouble(), at.y.toDouble(), radius)
        // Centre the letters on the circle: TEXT is placed by its baseline.
        dxf.text(LAYER_ELEVATION_KEYS, at.x.toDouble(), at.y - th, th * 2, e.key, align = DxfDocument.HAlign.CENTER)
    }

    /** A beam has to hang this far under the ceiling proper before it is called out. */
    private const val LOW_POINT_MARGIN = 0.1f
}
