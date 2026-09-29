package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.Opening
import com.banyawa.sitescanner.core.floorplan.OpeningTags
import com.banyawa.sitescanner.core.floorplan.OpeningType
import com.banyawa.sitescanner.core.units.LengthFormat
import java.io.Writer

/**
 * Door / window schedule in millimetres; UTF-8 with BOM so Excel shows Thai text.
 * "from_left" and "hinge" are as seen from inside the room facing the wall; "swing" IN
 * opens into the room. "source" is MANUAL for openings placed or sized by hand.
 */
object OpeningCsv {
    fun write(openings: List<Opening>, out: Writer, scanName: String = "") {
        val tags = OpeningTags.assign(openings)
        out.append('﻿')
        out.append("scan,tag,type,width_mm,height_mm,sill_mm,head_mm,from_left_mm,to_right_mm,wall_length_mm,confidence,hinge,swing,source\n")
        for (o in openings) {
            val mm = { v: Float -> LengthFormat.toMillimeters(v).toString() }
            val row = listOf(
                csv(scanName),
                tags.getValue(o.id),
                o.type.name,
                mm(o.width),
                mm(o.height),
                if (o.type == OpeningType.WINDOW) mm(o.bottom) else "0",
                mm(o.top),
                mm(o.distanceFromWallStart),
                mm(o.distanceToWallEnd),
                mm(o.wallLength),
                Numbers.fixed(o.confidence.toDouble(), 2),
                o.swing?.hinge?.name ?: "",
                o.swing?.let { if (it.inward) "IN" else "OUT" } ?: "",
                if (o.manual) "MANUAL" else "SCAN",
            )
            out.append(row.joinToString(",")).append('\n')
        }
        out.flush()
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
