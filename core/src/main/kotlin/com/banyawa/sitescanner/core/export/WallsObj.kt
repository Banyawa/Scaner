package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningTags
import com.banyawa.sitescanner.core.floorplan.WallSegment
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.project.Measurement
import java.io.Writer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Wavefront OBJ of the extracted walls extruded floor-to-ceiling with door and window
 * openings cut out, plus AR measurements as polylines. Metres, Y up (the OBJ convention);
 * X/Z match the plan's X / -Y.
 */
object WallsObj {
    fun write(
        plan: FloorPlan,
        out: Writer,
        measurements: List<Measurement> = emptyList(),
        defaultHeightM: Float = 2.6f,
    ) {
        val height = plan.roomHeight ?: defaultHeightM
        val sb = StringBuilder()
        sb.append("# Site Scanner - walls extruded from the scanned floor plan\n")
        sb.append("# units: metres, Y up\n")
        var vertex = 0
        fun v(x: Float, y: Float, z: Float) {
            sb.append("v ")
            Numbers.appendFixed(sb, x.toDouble(), 4).append(' ')
            Numbers.appendFixed(sb, y.toDouble(), 4).append(' ')
            Numbers.appendFixed(sb, z.toDouble(), 4).append('\n')
            vertex++
        }

        /** Vertical quad on plan segment a→b between heights h0 and h1. Plan (x, y) → OBJ (x, h, -y). */
        fun quad(a: Vec2, b: Vec2, h0: Float, h1: Float) {
            if (h1 - h0 < 1e-3f || a.distanceTo(b) < 1e-3f) return
            v(a.x, h0, -a.y)
            v(b.x, h0, -b.y)
            v(b.x, h1, -b.y)
            v(a.x, h1, -a.y)
            sb.append("f ${vertex - 3} ${vertex - 2} ${vertex - 1} $vertex\n")
        }

        sb.append("o walls\n")
        for (w in plan.walls) {
            for ((a, b) in solidParts(w, plan.openings)) quad(a, b, 0f, height)
        }
        // Wall above doors / windows and below windows.
        for (o in plan.openings) {
            quad(o.start, o.end, 0f, o.bottom)
            quad(o.start, o.end, o.top, height)
        }

        if (plan.openings.isNotEmpty()) {
            val tags = OpeningTags.assign(plan.openings)
            for (o in plan.openings) {
                sb.append("o ${tags.getValue(o.id)}_${o.type.name.lowercase()}\n")
                v(o.start.x, o.bottom, -o.start.y)
                v(o.end.x, o.bottom, -o.end.y)
                v(o.end.x, o.top, -o.end.y)
                v(o.start.x, o.top, -o.start.y)
                sb.append("l ${vertex - 3} ${vertex - 2} ${vertex - 1} $vertex ${vertex - 3}\n")
            }
        }

        if (measurements.isNotEmpty()) {
            sb.append("o measurements\n")
            for (m in measurements) {
                val a = plan.alignment.toSite(m.start)
                val b = plan.alignment.toSite(m.end)
                v(a.x, a.z, -a.y)
                v(b.x, b.z, -b.y)
                sb.append("l ${vertex - 1} $vertex\n")
            }
        }
        out.append(sb)
        out.flush()
    }

    /** Pieces of [w] not covered by an opening lying on the same line. */
    private fun solidParts(w: WallSegment, openings: List<Opening>): List<Pair<Vec2, Vec2>> {
        val len = w.length
        if (len <= 0f) return emptyList()
        val dir = w.direction
        val normal = dir.perp()
        val cuts = openings
            .filter { abs(it.direction cross dir) < 0.05f && abs((it.midpoint - w.start) dot normal) < 0.1f }
            .map {
                val a = (it.start - w.start) dot dir
                val b = (it.end - w.start) dot dir
                min(a, b) to max(a, b)
            }
            .filter { it.second > 0f && it.first < len }
            .sortedBy { it.first }
        val parts = ArrayList<Pair<Vec2, Vec2>>()
        var t = 0f
        for ((a, b) in cuts) {
            if (a > t) parts += (w.start + dir * t) to (w.start + dir * a)
            t = max(t, b)
        }
        if (t < len) parts += (w.start + dir * t) to w.end
        return parts
    }
}
