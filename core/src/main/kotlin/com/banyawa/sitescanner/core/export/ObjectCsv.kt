package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.scene.SceneObject
import com.banyawa.sitescanner.core.units.LengthFormat
import java.io.Flushable

/**
 * Schedule of the objects found standing in a scan (machines, furniture, cabinets), one row
 * per object in millimetres of the plan frame: the tightest box around the footprint
 * (length ≥ width, turned by yaw_deg about its centre), the lowest and highest surface, and
 * the clearance to the nearest wall (empty when the plan has no walls). Plain ASCII, no BOM.
 */
object ObjectCsv {
    const val HEADER = "tag,length_mm,width_mm,height_mm,bottom_mm,top_mm,centre_x_mm,centre_y_mm,yaw_deg,footprint_area_m2,wall_clearance_mm"

    fun write(objects: List<SceneObject>, out: Appendable) {
        out.append(HEADER).append('\n')
        for (o in objects) {
            val mm = { v: Float -> LengthFormat.toMillimeters(v).toString() }
            val row = listOf(
                "M${o.id}",
                mm(o.box.length),
                mm(o.box.width),
                mm(o.height),
                mm(o.bottom),
                mm(o.top),
                mm(o.box.centre.x),
                mm(o.box.centre.y),
                Numbers.fixed(Math.toDegrees(o.box.yawRad.toDouble()), 1),
                Numbers.fixed(o.footprintArea.toDouble(), 3),
                o.wallClearance?.let(mm) ?: "",
            )
            out.append(row.joinToString(",")).append('\n')
        }
        if (out is Flushable) out.flush()
    }

    fun toString(objects: List<SceneObject>): String = StringBuilder().also { write(objects, it) }.toString()
}
