package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A straight wall: collinear [WallSegment]s merged into the infinite line n·p = offset
 * with direction [dir]; positions along it are t = dir·p.
 */
internal class WallLine(
    val dir: Vec2,
    val normal: Vec2,
    val offset: Float,
    var tStart: Float,
    var tEnd: Float,
    /** [t0, t1] of the wall pieces that make up this line. */
    val segments: List<FloatArray>,
) {
    /** Extent of the wall pieces themselves, before corner extension. */
    val segmentStart = segments.minOf { it[0] }
    val segmentEnd = segments.maxOf { it[1] }
    val segmentLength = segments.fold(0f) { acc, s -> acc + s[1] - s[0] }

    fun point(t: Float) = Vec2(normal.x * offset + dir.x * t, normal.y * offset + dir.y * t)

    /** Signed distance of [p] from the line, positive on the [normal] side. */
    fun distance(p: Vec2) = (normal dot p) - offset
}

internal object WallLines {
    /** sin(3°) */
    const val PARALLEL_SIN = 0.052f
    private const val COLLINEAR_TOLERANCE = 0.1f

    /** sin(30°): only reasonably perpendicular walls form corners. */
    private const val CORNER_SIN = 0.5f
    private const val CORNER_SLACK = 0.3f
    private const val MIN_EXTEND_LENGTH = 1.0f

    /**
     * Merges collinear wall pieces less than [maxLineGap] apart into lines, then extends
     * each line end by up to [maxEndExtension] to the nearest crossing wall, so gaps next
     * to corners (a door right beside a corner) belong to the wall.
     */
    fun build(walls: List<WallSegment>, maxLineGap: Float, maxEndExtension: Float): List<WallLine> {
        val sorted = walls.filter { it.length > 0f }.sortedByDescending { it.length }
        val used = BooleanArray(sorted.size)
        val lines = ArrayList<WallLine>()
        for (i in sorted.indices) {
            if (used[i]) continue
            val ref = sorted[i]
            val dir = ref.direction
            val normal = dir.perp()
            val offset = normal dot ref.start
            val members = ArrayList<FloatArray>()
            for (j in sorted.indices) {
                if (used[j]) continue
                val w = sorted[j]
                if (abs(w.direction cross dir) > PARALLEL_SIN) continue
                if (abs((normal dot w.midpoint) - offset) > COLLINEAR_TOLERANCE) continue
                used[j] = true
                val a = dir dot w.start
                val b = dir dot w.end
                members += floatArrayOf(min(a, b), max(a, b))
            }
            members.sortBy { it[0] }
            var group = ArrayList<FloatArray>()
            var groupEnd = Float.NEGATIVE_INFINITY
            for (m in members) {
                if (group.isNotEmpty() && m[0] - groupEnd > maxLineGap) {
                    lines += WallLine(dir, normal, offset, group.first()[0], groupEnd, group)
                    group = ArrayList()
                    groupEnd = Float.NEGATIVE_INFINITY
                }
                group += m
                groupEnd = max(groupEnd, m[1])
            }
            if (group.isNotEmpty()) lines += WallLine(dir, normal, offset, group.first()[0], groupEnd, group)
        }
        extendToCorners(lines, maxEndExtension)
        return lines
    }

    private fun extendToCorners(lines: List<WallLine>, maxEndExtension: Float) {
        val original = lines.map { floatArrayOf(it.tStart, it.tEnd) }
        for ((i, l) in lines.withIndex()) {
            // Short free-standing pieces are usually furniture, not walls with a doorway.
            if (l.segmentLength < MIN_EXTEND_LENGTH) continue
            val ls = original[i][0]
            val le = original[i][1]
            var newStart = ls
            var newEnd = le
            var bestStartExt = Float.MAX_VALUE
            var bestEndExt = Float.MAX_VALUE
            for ((j, m) in lines.withIndex()) {
                if (i == j || abs(l.dir cross m.dir) < CORNER_SIN) continue
                val x = intersect(l, m) ?: continue
                val tm = m.dir dot x
                if (tm < original[j][0] - CORNER_SLACK || tm > original[j][1] + CORNER_SLACK) continue
                val tl = l.dir dot x
                if (tl <= ls + CORNER_SLACK && ls - tl <= maxEndExtension) {
                    val ext = abs(ls - tl)
                    if (ext < bestStartExt) {
                        bestStartExt = ext
                        newStart = min(ls, tl)
                    }
                }
                if (tl >= le - CORNER_SLACK && tl - le <= maxEndExtension) {
                    val ext = abs(tl - le)
                    if (ext < bestEndExt) {
                        bestEndExt = ext
                        newEnd = max(le, tl)
                    }
                }
            }
            l.tStart = newStart
            l.tEnd = newEnd
        }
    }

    private fun intersect(a: WallLine, b: WallLine): Vec2? {
        val det = a.normal.x * b.normal.y - a.normal.y * b.normal.x
        if (abs(det) < 1e-6f) return null
        return Vec2(
            (a.offset * b.normal.y - b.offset * a.normal.y) / det,
            (a.normal.x * b.offset - b.normal.x * a.offset) / det,
        )
    }
}
