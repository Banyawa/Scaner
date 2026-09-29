package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.FloorPlan
import com.banyawa.sitescanner.core.project.Measurement
import java.io.Writer

/**
 * Wavefront OBJ of the extracted walls extruded floor-to-ceiling, plus AR measurements
 * as polylines. Metres, Y up (the OBJ convention); X/Z match the plan's X / -Y.
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

        sb.append("o walls\n")
        for (w in plan.walls) {
            // Plan (x, y) -> OBJ (x, height, -y)
            v(w.start.x, 0f, -w.start.y)
            v(w.end.x, 0f, -w.end.y)
            v(w.end.x, height, -w.end.y)
            v(w.start.x, height, -w.start.y)
            sb.append("f ${vertex - 3} ${vertex - 2} ${vertex - 1} $vertex\n")
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
}
