package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.units.LengthFormat
import java.io.Writer

/** Measurement table in millimetres (site frame, Z up); UTF-8 with BOM so Excel shows Thai labels. */
object MeasurementCsv {
    fun write(measurements: List<Measurement>, alignment: SiteAlignment, out: Writer, scanName: String = "") {
        out.append('﻿')
        out.append("scan,no,label,length_mm,start_x_mm,start_y_mm,start_z_mm,end_x_mm,end_y_mm,end_z_mm,dx_mm,dy_mm,dz_mm\n")
        for ((i, m) in measurements.withIndex()) {
            val a = alignment.toSite(m.start)
            val b = alignment.toSite(m.end)
            val mm = { v: Float -> LengthFormat.toMillimeters(v).toString() }
            val row = listOf(
                escape(scanName),
                (i + 1).toString(),
                escape(m.label),
                mm(m.lengthM),
                mm(a.x), mm(a.y), mm(a.z),
                mm(b.x), mm(b.y), mm(b.z),
                mm(b.x - a.x), mm(b.y - a.y), mm(b.z - a.z),
            )
            out.append(row.joinToString(",")).append('\n')
        }
        out.flush()
    }

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
