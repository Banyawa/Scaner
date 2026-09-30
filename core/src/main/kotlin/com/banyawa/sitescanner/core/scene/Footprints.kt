package com.banyawa.sitescanner.core.scene

import com.banyawa.sitescanner.core.geometry.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToLong

/** Convex outlines of objects seen from above and the tightest rectangles around them. */
internal object Footprints {
    /** Plan coordinates are quantised to 1/4096 m (a quarter millimetre) so the hull runs on exact integers. */
    private const val QUANTUM = 4096f

    /**
     * Convex hull (Andrew's monotone chain) of the first [count] points of [xy] (x0, y0, x1, y1, …),
     * counter-clockwise, collinear points dropped. One or two points come back as they are.
     */
    fun hull(xy: FloatArray, count: Int): List<Vec2> {
        if (count <= 0) return emptyList()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        for (i in 0 until count) {
            if (xy[i * 2] < minX) minX = xy[i * 2]
            if (xy[i * 2 + 1] < minY) minY = xy[i * 2 + 1]
        }
        // Sorting one long per point (x in the high word, y in the low) is the lexicographic
        // sort the chain needs, without boxing a point object per vertex.
        val keys = LongArray(count)
        for (i in 0 until count) {
            val qx = ((xy[i * 2] - minX) * QUANTUM).roundToLong()
            val qy = ((xy[i * 2 + 1] - minY) * QUANTUM).roundToLong()
            keys[i] = (qx shl 32) or qy
        }
        keys.sort()
        var unique = 0
        for (i in 0 until count) {
            if (i == 0 || keys[i] != keys[i - 1]) keys[unique++] = keys[i]
        }
        if (unique == 1) return listOf(decode(keys[0], minX, minY))

        val hull = LongArray(unique * 2)
        var k = 0
        for (i in 0 until unique) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], keys[i]) <= 0L) k--
            hull[k++] = keys[i]
        }
        val lowerEnd = k + 1
        for (i in unique - 2 downTo 0) {
            while (k >= lowerEnd && cross(hull[k - 2], hull[k - 1], keys[i]) <= 0L) k--
            hull[k++] = keys[i]
        }
        k-- // The chain closes on its first point.
        return List(k) { decode(hull[it], minX, minY) }
    }

    /**
     * Smallest-area rectangle around a convex [hull]: one of its sides lies along a hull edge,
     * so every edge direction is tried. [OrientedBox.length] is the longer side.
     */
    fun box(hull: List<Vec2>): OrientedBox {
        if (hull.isEmpty()) return OrientedBox(Vec2.ZERO, 0f, 0f, 0f)
        if (hull.size == 1) return OrientedBox(hull[0], 0f, 0f, 0f)
        if (hull.size == 2) {
            val d = hull[1] - hull[0]
            return OrientedBox(Vec2.lerp(hull[0], hull[1], 0.5f), d.length(), 0f, normalizeYaw(atan2(d.y, d.x)))
        }
        var bestArea = Float.POSITIVE_INFINITY
        var bestU = Vec2(1f, 0f)
        var bestMinU = 0f
        var bestMaxU = 0f
        var bestMinV = 0f
        var bestMaxV = 0f
        for (i in hull.indices) {
            val edge = hull[(i + 1) % hull.size] - hull[i]
            val len = edge.length()
            if (len < 1e-6f) continue
            val u = edge * (1f / len)
            val v = u.perp()
            var minU = Float.POSITIVE_INFINITY
            var maxU = Float.NEGATIVE_INFINITY
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            for (p in hull) {
                val a = p dot u
                val b = p dot v
                if (a < minU) minU = a
                if (a > maxU) maxU = a
                if (b < minV) minV = b
                if (b > maxV) maxV = b
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                bestU = u
                bestMinU = minU
                bestMaxU = maxU
                bestMinV = minV
                bestMaxV = maxV
            }
        }
        val v = bestU.perp()
        val centre = bestU * ((bestMinU + bestMaxU) / 2f) + v * ((bestMinV + bestMaxV) / 2f)
        val along = bestMaxU - bestMinU
        val across = bestMaxV - bestMinV
        return if (along >= across) {
            OrientedBox(centre, along, across, normalizeYaw(atan2(bestU.y, bestU.x)))
        } else {
            OrientedBox(centre, across, along, normalizeYaw(atan2(v.y, v.x)))
        }
    }

    /** A rectangle is the same turned by 180°, so its yaw is reported in [-90°, 90°). */
    private fun normalizeYaw(yaw: Float): Float {
        var a = yaw
        val half = PI.toFloat() / 2f
        while (a >= half) a -= PI.toFloat()
        while (a < -half) a += PI.toFloat()
        return a
    }

    private fun cross(o: Long, a: Long, b: Long): Long {
        val ox = o shr 32
        val oy = o and 0xFFFFFFFFL
        val ax = (a shr 32) - ox
        val ay = (a and 0xFFFFFFFFL) - oy
        val bx = (b shr 32) - ox
        val by = (b and 0xFFFFFFFFL) - oy
        return ax * by - ay * bx
    }

    private fun decode(key: Long, minX: Float, minY: Float) =
        Vec2(minX + (key shr 32) / QUANTUM, minY + (key and 0xFFFFFFFFL) / QUANTUM)
}
