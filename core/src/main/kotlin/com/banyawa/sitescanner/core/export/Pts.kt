package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.floorplan.SiteAlignment
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import java.io.Writer

/**
 * Leica PTS text point cloud ("x y z intensity r g b"), the easiest format to bring into
 * Autodesk ReCap and from there into AutoCAD / Revit. Coordinates in metres, Z up.
 */
object Pts {
    fun write(cloud: PointCloud, out: Writer, alignment: SiteAlignment) {
        val sb = StringBuilder(1 shl 16)
        sb.append(cloud.size).append('\n')
        val site = FloatArray(3)
        for (i in 0 until cloud.size) {
            alignment.toSite(cloud.xyz[i * 3], cloud.xyz[i * 3 + 1], cloud.xyz[i * 3 + 2], site, 0)
            val r = cloud.rgb[i * 3].toInt() and 0xFF
            val g = cloud.rgb[i * 3 + 1].toInt() and 0xFF
            val b = cloud.rgb[i * 3 + 2].toInt() and 0xFF
            // PTS intensity is conventionally in -2048..2047; derive it from luminance.
            val intensity = ((0.299 * r + 0.587 * g + 0.114 * b) / 255.0 * 4095.0 - 2048.0).toInt()
            Numbers.appendFixed(sb, site[0].toDouble(), 4).append(' ')
            Numbers.appendFixed(sb, site[1].toDouble(), 4).append(' ')
            Numbers.appendFixed(sb, site[2].toDouble(), 4).append(' ')
            sb.append(intensity).append(' ').append(r).append(' ').append(g).append(' ').append(b).append('\n')
            if (sb.length > (1 shl 16) - 128) {
                out.append(sb)
                sb.setLength(0)
            }
        }
        out.append(sb)
        out.flush()
    }
}
