package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.util.FloatList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class OpeningParams(
    /** Resolution of the wall occupancy grid (along the wall × height). */
    val cellSize: Float = 0.04f,
    /** Points this close to the wall line count as wall surface. */
    val bandTolerance: Float = 0.06f,
    val minHits: Int = 2,
    /** Floor points are ignored for wall occupancy. */
    val floorIgnore: Float = 0.05f,
    val minWidth: Float = 0.35f,
    val maxWidth: Float = 4.0f,
    val minWindowHeight: Float = 0.3f,
    val minDoorHeight: Float = 1.7f,
    /** Openings starting at most this high are doors (thresholds, small steps). */
    val maxDoorSill: Float = 0.15f,
    /** Openings ending this close to the ceiling have no head. */
    val ceilingMargin: Float = 0.15f,
    /** Collinear wall pieces further apart than this are separate walls. */
    val maxLineGap: Float = 3.0f,
    /** A wall that stops short of a corner is analysed up to the corner (door next to a corner). */
    val maxEndExtension: Float = 2.5f,
    /** Walls with less of their surface scanned are skipped. */
    val minCoverage: Float = 0.4f,
    /** Fraction of the opening height that must be solid beside each jamb. */
    val minJamb: Float = 0.5f,
    /** Fraction of the width that must be solid above (head) / below (sill). */
    val minHeaderOrSill: Float = 0.5f,
    /** Window panes separated by mullions up to this wide are one window. */
    val mullionGap: Float = 0.12f,
    /** Room-side band used to spot furniture hiding the wall. */
    val frontMin: Float = 0.1f,
    val frontMax: Float = 1.2f,
    /**
     * Far-side band: surfaces seen through the opening. Starts beyond furniture depth so a
     * wall right behind a cabinet front is not mistaken for a view through an opening.
     */
    val behindMin: Float = 1.0f,
    val behindMax: Float = 6f,
    val defaultMaxHeight: Float = 3.5f,
)

/**
 * Finds doors, windows and openings in the walls of a [FloorPlan].
 *
 * Each wall line gets an occupancy grid over (position along the wall, height). A hole
 * in the wall surface is an opening candidate; it is accepted only with evidence that it
 * is real rather than an unscanned patch:
 * - solid jambs on both sides,
 * - a head above (doors, windows) and a sill below (windows), or surfaces seen through it,
 * - no furniture-like surface parallel to the wall right in front of it.
 */
class OpeningDetector(private val params: OpeningParams = OpeningParams()) {

    fun detect(cloud: PointCloud, plan: FloorPlan): List<Opening> {
        if (plan.walls.isEmpty() || cloud.size == 0) return emptyList()
        val lines = WallLines.build(plan.walls, params.maxLineGap, params.maxEndExtension)
        if (lines.isEmpty()) return emptyList()

        val maxH = plan.roomHeight?.let { it + 0.1f } ?: params.defaultMaxHeight
        val grids = lines.map { LineGrid(it, params, maxH) }
        val site = FloatArray(3)
        val xyz = cloud.xyz
        for (i in 0 until cloud.size) {
            plan.alignment.toSite(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], site, 0)
            for (g in grids) g.add(site[0], site[1], site[2])
        }
        val found = grids.filterNot { isFurnitureFront(it, grids) }.flatMap { it.openings(plan.roomHeight) }
        return finalizeOrder(dedupe(found), plan)
    }

    /**
     * Wardrobes and cabinets show up as short "walls" standing in front of a longer wall.
     * Their gaps are not openings, so such lines are skipped.
     */
    private fun isFurnitureFront(g: LineGrid, all: List<LineGrid>): Boolean {
        val l = g.line
        val outward = if (g.interiorSide == 0) -1f else 1f
        val len = l.segmentLength
        return all.any { other ->
            val m = other.line
            if (other === g || abs(l.dir cross m.dir) > WallLines.PARALLEL_SIN || m.segmentLength < len * 1.5f) return@any false
            val mid = m.point((m.segmentStart + m.segmentEnd) / 2f)
            val behind = ((l.normal dot mid) - l.offset) * outward
            if (behind < params.frontMin || behind > params.frontMax) return@any false
            val a = l.dir dot m.point(m.segmentStart)
            val b = l.dir dot m.point(m.segmentEnd)
            val overlap = min(max(a, b), l.segmentEnd) - max(min(a, b), l.segmentStart)
            overlap >= 0.8f * (l.segmentEnd - l.segmentStart)
        }
    }

    // --- per-wall analysis --------------------------------------------------------------

    private class Candidate(val i0: Int) {
        var i1 = i0
        val bottoms = ArrayList<Int>()
        val tops = ArrayList<Int>()
        var sumB = 0
        var sumE = 0

        fun add(i: Int, b: Int, e: Int) {
            i1 = i
            bottoms += b
            tops += e
            sumB += b
            sumE += e
        }

        val meanB get() = sumB.toFloat() / bottoms.size
        val meanE get() = sumE.toFloat() / tops.size
    }

    private class Raw(
        var i0: Int,
        var i1: Int,
        var b: Int,
        var e: Int,
        val type: OpeningType,
        var confidence: Float,
        val atCeiling: Boolean,
    )

    private class LineGrid(val line: WallLine, private val p: OpeningParams, maxHeight: Float) {
        /** 0 when the room (more floor) is on the +normal side, 1 otherwise. */
        val interiorSide: Int get() = if (floorSide[0] >= floorSide[1]) 0 else 1

        private val cell = p.cellSize
        private val t0 = line.tStart - PAD * cell
        private val nt = ceil((line.tEnd - line.tStart) / cell).toInt() + 2 * PAD
        private val nh = max(1, ceil(maxHeight / cell).toInt())
        private val maxH = nh * cell
        private val tMax = t0 + nt * cell

        private val wall = IntArray(nt * nh)
        private val nearCount = Array(2) { IntArray(nt * nh) }
        private val nearDist = Array(2) { FloatArray(nt * nh) }
        private val far = Array(2) { IntArray(nt * nh) }
        private val floorSide = IntArray(2)
        private val bandT = FloatList(4096)
        private val bandH = FloatList(4096)
        private val bandD = FloatList(4096)

        /** Where the wall surface actually is relative to the fitted line. */
        private val surfaceOffset: Float by lazy { percentile(bandD, 0.5f) }

        fun add(x: Float, y: Float, h: Float) {
            val t = line.dir.x * x + line.dir.y * y
            if (t < t0 || t >= tMax) return
            val d = line.normal.x * x + line.normal.y * y - line.offset
            val side = if (d >= 0f) 0 else 1
            val ad = abs(d)
            if (h < p.floorIgnore) {
                // Floor points tell which side of the wall is the scanned room.
                if (h > -0.1f && ad in 0.2f..1.5f && t >= line.tStart && t <= line.tEnd) floorSide[side]++
                return
            }
            if (h >= maxH) return
            val ti = min(nt - 1, ((t - t0) / cell).toInt())
            val hi = min(nh - 1, (h / cell).toInt())
            val idx = ti * nh + hi
            if (ad <= p.bandTolerance) {
                wall[idx]++
                bandT.add(t)
                bandH.add(h)
                bandD.add(d)
                return
            }
            if (ad >= p.frontMin && ad <= p.frontMax) {
                nearCount[side][idx]++
                nearDist[side][idx] += ad
            }
            if (ad >= p.behindMin && ad <= p.behindMax) far[side][idx]++
        }

        fun openings(roomHeight: Float?): List<Opening> {
            if (bandT.size == 0) return emptyList()
            val lineTop = (roomHeight ?: percentile(bandH, 0.98f)).coerceIn(1.8f, maxH)
            // Skip the wall/ceiling junction, where ceiling points fall inside the band.
            val rowTop = ((lineTop - CEILING_SKIP) / cell).toInt().coerceIn(1, nh)
            val occ = occupancy()
            if (coverage(occ, lineTop) < p.minCoverage) return emptyList()
            val interior = interiorSide

            val raws = candidates(occ, rowTop).mapNotNull { evaluate(it, occ, rowTop, lineTop, interior) }
            return mergeMullions(raws).map { toOpening(it, lineTop, interior) }
        }

        /** Occupied cells, without isolated specks (noise, dust, reflections). */
        private fun occupancy(): BooleanArray {
            val raw = BooleanArray(nt * nh) { wall[it] >= p.minHits }
            val clean = BooleanArray(nt * nh)
            for (i in 0 until nt) {
                for (j in 0 until nh) {
                    val idx = i * nh + j
                    if (!raw[idx]) continue
                    clean[idx] = (j > 0 && raw[idx - 1]) || (j < nh - 1 && raw[idx + 1]) ||
                        (i > 0 && raw[idx - nh]) || (i < nt - 1 && raw[idx + nh])
                }
            }
            return clean
        }

        private fun col(t: Float) = ((t - t0) / cell).toInt().coerceIn(0, nt - 1)

        private fun coverage(occ: BooleanArray, lineTop: Float): Float {
            val r0 = (0.3f / cell).toInt()
            val r1 = (min(lineTop, 2.0f) / cell).toInt().coerceAtMost(nh)
            var total = 0
            var hit = 0
            for (seg in line.segments) {
                for (i in col(seg[0])..col(seg[1])) {
                    for (j in r0 until r1) {
                        total++
                        if (occ[i * nh + j]) hit++
                    }
                }
            }
            return if (total == 0) 0f else hit.toFloat() / total
        }

        /** Longest empty vertical run per column, grouped over neighbouring columns. */
        private fun candidates(occ: BooleanArray, rowTop: Int): List<Candidate> {
            val minRun = ceil(p.minWindowHeight / cell).toInt()
            val result = ArrayList<Candidate>()
            var current: Candidate? = null
            for (i in 0 until nt) {
                var bestB = -1
                var bestE = -1
                var b = -1
                for (j in 0..rowTop) {
                    val empty = j < rowTop && !occ[i * nh + j]
                    if (empty) {
                        if (b < 0) b = j
                    } else if (b >= 0) {
                        if (j - b > bestE - bestB) {
                            bestB = b
                            bestE = j
                        }
                        b = -1
                    }
                }
                if (bestE - bestB < minRun) {
                    current?.let(result::add)
                    current = null
                    continue
                }
                val c = current
                if (c != null) {
                    val overlap = min(c.meanE, bestE.toFloat()) - max(c.meanB, bestB.toFloat())
                    if (overlap >= 0.6f * min(c.meanE - c.meanB, (bestE - bestB).toFloat())) {
                        c.add(i, bestB, bestE)
                        continue
                    }
                    result += c
                }
                current = Candidate(i).also { it.add(i, bestB, bestE) }
            }
            current?.let(result::add)
            return result
        }

        private fun evaluate(c: Candidate, occ: BooleanArray, rowTop: Int, lineTop: Float, interior: Int): Raw? {
            val b = median(c.bottoms)
            val e = median(c.tops)
            val cols = c.i1 - c.i0 + 1
            val width = cols * cell
            if (width < p.minWidth || width > p.maxWidth || e <= b) return null
            val bottom = b * cell
            val top = e * cell
            val height = top - bottom
            val atFloor = bottom <= p.maxDoorSill
            val atCeiling = top >= lineTop - p.ceilingMargin

            // Jambs: solid wall beside the hole over the middle of its height.
            val margin = ((e - b) * 0.15f).toInt()
            val left = sideFraction(occ, c.i0 - 2, c.i0 - 1, b + margin, e - margin)
            val right = sideFraction(occ, c.i1 + 1, c.i1 + 2, b + margin, e - margin)
            if (left < p.minJamb || right < p.minJamb) return null

            val inset = (cols * 0.2f).toInt()
            val header = if (atCeiling) 0f else bandFraction(occ, c.i0 + inset, c.i1 - inset, e, min(e + 3, rowTop))
            val sill = if (atFloor) 1f else bandFraction(occ, c.i0 + inset, c.i1 - inset, max(0, b - 3), b)

            var cells = 0
            var through = 0
            var covered = 0
            val exterior = 1 - interior
            for (i in c.i0..c.i1) {
                for (j in b until e) {
                    val idx = i * nh + j
                    cells++
                    if (far[exterior][idx] > 0) through++
                    if (nearCount[interior][idx] >= 2) covered++
                }
            }
            val seeThrough = through >= cells * 0.1f
            if (covered >= cells * 0.5f && parallelOccluder(c.i0, c.i1, b, e, interior)) return null

            val type = when {
                atFloor && height >= p.minDoorHeight && atCeiling ->
                    if (seeThrough) OpeningType.OPENING else return null
                atFloor && height >= p.minDoorHeight ->
                    if (header >= p.minHeaderOrSill) OpeningType.DOOR else return null
                !atFloor && height >= p.minWindowHeight && sill >= p.minHeaderOrSill ->
                    if ((!atCeiling && header >= p.minHeaderOrSill) || seeThrough) OpeningType.WINDOW else return null
                else -> return null
            }
            val support = when (type) {
                OpeningType.DOOR -> header
                OpeningType.WINDOW -> if (atCeiling) sill * 0.5f else min(sill, header)
                OpeningType.OPENING -> 0.5f
            }
            val confidence = ((left + right) * 0.2f + support * 0.4f + (if (seeThrough) 0.2f else 0f)).coerceIn(0f, 1f)
            return Raw(c.i0, c.i1, b, e, type, confidence, atCeiling)
        }

        /** Fraction of rows [r0, r1) with a solid cell in columns [c0, c1]. */
        private fun sideFraction(occ: BooleanArray, c0: Int, c1: Int, r0: Int, r1: Int): Float {
            if (r1 <= r0) return 0f
            var hit = 0
            for (j in r0 until r1) {
                for (i in c0..c1) {
                    if (i in 0 until nt && occ[i * nh + j]) {
                        hit++
                        break
                    }
                }
            }
            return hit.toFloat() / (r1 - r0)
        }

        /** Fraction of columns [c0, c1] with a solid cell in rows [r0, r1). */
        private fun bandFraction(occ: BooleanArray, c0: Int, c1: Int, r0: Int, r1: Int): Float {
            if (c1 < c0 || r1 <= r0) return 0f
            var hit = 0
            for (i in c0..c1) {
                for (j in max(0, r0) until min(nh, r1)) {
                    if (occ[i * nh + j]) {
                        hit++
                        break
                    }
                }
            }
            return hit.toFloat() / (c1 - c0 + 1)
        }

        /**
         * A cabinet or wardrobe in front of the wall hides it behind a surface parallel to the
         * wall. An open door leaf also sits in front of a doorway, but at an angle.
         */
        private fun parallelOccluder(i0: Int, i1: Int, b: Int, e: Int, side: Int): Boolean {
            var minD = Float.MAX_VALUE
            var maxD = -Float.MAX_VALUE
            for (i in i0..i1) {
                var sum = 0f
                var n = 0
                for (j in b until e) {
                    val idx = i * nh + j
                    sum += nearDist[side][idx]
                    n += nearCount[side][idx]
                }
                if (n == 0) continue
                val d = sum / n
                minD = min(minD, d)
                maxD = max(maxD, d)
            }
            return maxD - minD < 0.2f
        }

        private fun mergeMullions(raws: List<Raw>): List<Raw> {
            val gap = ceil(p.mullionGap / cell).toInt()
            val out = ArrayList<Raw>()
            for (r in raws.sortedBy { it.i0 }) {
                val last = out.lastOrNull()
                if (last != null && last.type == r.type && r.i0 - last.i1 - 1 <= gap &&
                    abs(r.b - last.b) <= 2 && abs(r.e - last.e) <= 2
                ) {
                    last.i1 = r.i1
                    last.b = min(last.b, r.b)
                    last.e = max(last.e, r.e)
                    last.confidence = max(last.confidence, r.confidence)
                } else {
                    out += r
                }
            }
            return out
        }

        /** Converts grid cells to metres, snapping edges to the actual jamb / sill / head points. */
        private fun toOpening(r: Raw, lineTop: Float, interior: Int): Opening {
            var tL = t0 + r.i0 * cell
            var tR = t0 + (r.i1 + 1) * cell
            var bottom = r.b * cell
            var top = r.e * cell
            val h0 = bottom + (top - bottom) * 0.2f
            val h1 = top - (top - bottom) * 0.2f

            denseEdge(tL - 3 * cell, tL + cell, h0, h1, alongT = true, fromTop = true)
                ?.takeIf { it in (tL - 2 * cell)..(tL + cell) }?.let { tL = it }
            denseEdge(tR - cell, tR + 3 * cell, h0, h1, alongT = true, fromTop = false)
                ?.takeIf { it in (tR - cell)..(tR + 2 * cell) }?.let { tR = it }

            val w0 = tL + (tR - tL) * 0.2f
            val w1 = tR - (tR - tL) * 0.2f
            if (r.type == OpeningType.WINDOW) {
                denseEdge(w0, w1, bottom - 3 * cell, bottom + cell, alongT = false, fromTop = true)
                    ?.takeIf { it in (bottom - 2 * cell)..(bottom + cell) }?.let { bottom = it }
            } else {
                bottom = 0f
            }
            if (r.atCeiling) {
                top = lineTop
            } else {
                denseEdge(w0, w1, top - cell, top + 3 * cell, alongT = false, fromTop = false)
                    ?.takeIf { it in (top - cell)..(top + 2 * cell) }?.let { top = it }
            }

            val interiorNormal = if (interior == 0) line.normal else -line.normal
            val forward = (interiorNormal.perp() dot line.dir) > 0f
            val a = line.point(tL)
            val b = line.point(tR)
            val ws = line.point(line.tStart)
            val we = line.point(line.tEnd)
            return Opening(
                id = "",
                type = r.type,
                start = if (forward) a else b,
                end = if (forward) b else a,
                bottom = bottom,
                top = top,
                wallStart = if (forward) ws else we,
                wallEnd = if (forward) we else ws,
                confidence = r.confidence,
            )
        }

        /**
         * Edge of a surface from the wall points inside t ∈ [ta, tb], h ∈ [ha, hb].
         * Returns the largest ([fromTop]) or smallest value (t if [alongT], else h) that has a
         * dense cluster of points just behind it, which ignores stray points in the hole.
         */
        private fun denseEdge(ta: Float, tb: Float, ha: Float, hb: Float, alongT: Boolean, fromTop: Boolean): Float? {
            val values = FloatList(256)
            val d0 = surfaceOffset
            for (k in 0 until bandT.size) {
                val t = bandT[k]
                val h = bandH[k]
                // Only the wall surface itself: excludes e.g. an open door leaf next to the jamb.
                if (t in ta..tb && h in ha..hb && abs(bandD[k] - d0) <= REFINE_BAND) values.add(if (alongT) t else h)
            }
            if (values.size < DENSE_K) return null
            val v = values.toArray().also { it.sort() }
            if (fromTop) {
                for (k in v.size - 1 downTo DENSE_K - 1) if (v[k] - v[k - DENSE_K + 1] <= DENSE_WINDOW) return v[k]
            } else {
                for (k in 0..v.size - DENSE_K) if (v[k + DENSE_K - 1] - v[k] <= DENSE_WINDOW) return v[k]
            }
            return null
        }

        private fun percentile(list: FloatList, q: Float): Float {
            val v = list.toArray().also { it.sort() }
            return v[(v.size * q).toInt().coerceIn(0, v.size - 1)]
        }

        private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]
    }

    // --- post-processing ---------------------------------------------------------------

    /** The same opening can be found on both faces of a thick wall; keep the surer one. */
    private fun dedupe(openings: List<Opening>): List<Opening> {
        val out = ArrayList<Opening>()
        for (o in openings.sortedByDescending { it.confidence }) {
            val dup = out.any {
                abs(it.direction cross o.direction) < 0.1f &&
                    it.midpoint.distanceTo(o.midpoint) < 0.4f &&
                    abs(it.bottom - o.bottom) < 0.3f
            }
            if (!dup) out += o
        }
        return out
    }

    /** Clockwise from north around the room centre, with stable ids. */
    private fun finalizeOrder(openings: List<Opening>, plan: FloorPlan): List<Opening> {
        val mids = plan.walls.map { it.midpoint }
        val cx = mids.map { it.x }.average().toFloat()
        val cy = mids.map { it.y }.average().toFloat()
        return openings
            .sortedBy {
                val a = atan2(it.midpoint.x - cx, it.midpoint.y - cy)
                if (a < 0) a + 2 * PI.toFloat() else a
            }
            .mapIndexed { i, o -> o.copy(id = "op${i + 1}") }
    }

    private companion object {
        const val PAD = 2
        const val CEILING_SKIP = 0.05f
        const val DENSE_K = 5
        const val DENSE_WINDOW = 0.012f
        const val REFINE_BAND = 0.025f
    }
}
