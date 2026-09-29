package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class DoorSwingParams(
    /** Plan raster used to look for leaves. */
    val cellSize: Float = 0.02f,
    val minHits: Int = 2,
    /** Only the band where a leaf is plain (clear of floor, handle hardware and the head). */
    val bandBottom: Float = 0.3f,
    val bandTop: Float = 1.8f,
    val heightBin: Float = 0.1f,
    /** Leaf surface points may sit this far off the ray from the hinge (leaf thickness, frame). */
    val rayTolerance: Float = 0.045f,
    val radialBin: Float = 0.05f,
    /** The hinge may sit up to this far behind the room-side wall face (thick wall, outward door). */
    val maxHingeOffset: Float = 0.32f,
    val hingeOffsetStep: Float = 0.04f,
    /** Leaf angles tried, from closed (0°); a leaf folded flat against the wall is not found. */
    val minAngleDeg: Float = 6f,
    val maxAngleDeg: Float = 174f,
    val angleStepDeg: Float = 2f,
    /** Fraction of the leaf length that must show points along the ray. */
    val minCoverage: Float = 0.6f,
    /** Fraction of the height band that must show points on the leaf. */
    val minHeightCoverage: Float = 0.6f,
    /** A leaf stops at its width; a tall surface running on past that is a wall or furniture. */
    val maxBeyond: Float = 0.34f,
    /** Wider openings are not single or double swing doors. */
    val maxDoorWidth: Float = 2.4f,
)

/**
 * Finds which way doors open from the leaf standing open in the scan.
 *
 * An open leaf is a tall, flat surface about as wide as the opening that fans out from one
 * jamb (or, for a pair, two surfaces of half the width from both jambs). For each jamb, side
 * of the wall and hinge offset, rays at every opening angle are scored by how much of the
 * leaf's length and height they cover; a surface that runs on well past the leaf's width
 * (a wall, a wardrobe side) is rejected. A closed door is not an opening, and a leaf folded
 * flat against the wall cannot be told apart from it, so those stay unknown.
 */
class DoorSwingDetector(private val params: DoorSwingParams = DoorSwingParams()) {

    /**
     * A leaf as it stands in the scan (not necessarily at 90°), from where its line meets
     * the wall to its free edge.
     */
    class Leaf(val hinge: Vec2, val tip: Vec2)

    class Result(val swing: DoorSwing, val leaves: List<Leaf>, val score: Float) {
        /** True when [wall] is just one of these leaves picked up as a wall. */
        fun covers(wall: WallSegment): Boolean = leaves.any { leaf ->
            val len = leaf.hinge.distanceTo(leaf.tip)
            val dir = (leaf.tip - leaf.hinge).normalized()
            wall.length <= len + LEAF_SLACK &&
                listOf(wall.start, wall.end).all { p ->
                    val v = p - leaf.hinge
                    val t = v dot dir
                    abs(v cross dir) <= LEAF_WALL_TOLERANCE && t >= -LEAF_SLACK && t <= len + LEAF_SLACK
                }
        }
    }

    /** Swing per door id, for the doors whose leaf was found. */
    fun detect(cloud: PointCloud, alignment: SiteAlignment, openings: List<Opening>): Map<String, Result> {
        val rasters = openings
            .filter { it.type == OpeningType.DOOR && it.width in MIN_DOOR_WIDTH..params.maxDoorWidth }
            .mapNotNull { door -> DoorRaster.create(door, params) }
        if (rasters.isEmpty() || cloud.size == 0) return emptyMap()
        val site = FloatArray(3)
        val xyz = cloud.xyz
        for (i in 0 until cloud.size) {
            alignment.toSite(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], site, 0)
            for (r in rasters) r.add(site[0], site[1], site[2])
        }
        val out = LinkedHashMap<String, Result>()
        for (r in rasters) r.best()?.let { out[r.door.id] = it }
        return out
    }

    /** A leaf in the door frame: from ([ha], [hd]) along unit ([ea], [ed]) for [length]. */
    private class Candidate(val score: Float, val ha: Float, val hd: Float, val ea: Float, val ed: Float, val length: Float)

    /** Line fitted through a leaf's points, and how much of the space past its edge is taken. */
    private class Fit(val ha: Float, val hd: Float, val ea: Float, val ed: Float, val beyond: Float)

    /**
     * Occupancy of the door's surroundings in the door frame: a = along the opening from its
     * left jamb (0) to its right jamb (width), d = towards the room.
     */
    private class DoorRaster private constructor(val door: Opening, private val p: DoorSwingParams, private val hTop: Float) {
        private val w = door.width
        private val origin = door.start
        private val u = door.direction
        private val n = door.interiorNormal
        private val cell = p.cellSize
        /** Rays are followed far enough to check the space past a full-width leaf. */
        private val rayLength = BEYOND_TO * w + BEYOND_GAP + p.radialBin
        private val reach = rayLength + p.maxHingeOffset + p.rayTolerance
        private val a0 = -reach
        private val d0 = -reach
        private val na = ceil((w + 2 * reach) / cell).toInt()
        private val nd = ceil(2 * reach / cell).toInt()
        private val heightBins = max(1, floor((hTop - p.bandBottom) / p.heightBin).toInt())
        private val count = IntArray(na * nd)
        private val mask = IntArray(na * nd)

        fun add(x: Float, y: Float, h: Float) {
            if (h < p.bandBottom || h >= hTop) return
            val px = x - origin.x
            val py = y - origin.y
            val a = px * u.x + py * u.y
            val d = px * n.x + py * n.y
            val i = floor((a - a0) / cell).toInt()
            val j = floor((d - d0) / cell).toInt()
            if (i < 0 || i >= na || j < 0 || j >= nd) return
            // The wall face either side of the opening is not a leaf.
            if (abs(d) < WALL_BAND && (a < -JAMB_SLACK || a > w + JAMB_SLACK)) return
            val idx = i * nd + j
            count[idx]++
            mask[idx] = mask[idx] or (1 shl min(heightBins - 1, ((h - p.bandBottom) / p.heightBin).toInt()))
        }

        fun best(): Result? {
            var cells = 0
            for (c in count) if (c >= p.minHits) cells++
            if (cells == 0) return null
            val ca = FloatArray(cells)
            val cd = FloatArray(cells)
            val cm = IntArray(cells)
            var k = 0
            for (i in 0 until na) {
                for (j in 0 until nd) {
                    val idx = i * nd + j
                    if (count[idx] < p.minHits) continue
                    ca[k] = a0 + (i + 0.5f) * cell
                    cd[k] = d0 + (j + 0.5f) * cell
                    cm[k] = mask[idx]
                    k++
                }
            }

            val results = ArrayList<Result>()
            for (side in intArrayOf(1, -1)) {
                val left = search(ca, cd, cm, side, hingeA = 0f, toward = 1f)
                val right = search(ca, cd, cm, side, hingeA = w, toward = -1f)
                val inward = side > 0
                left[0]?.let { results += result(DoorSwing(Hinge.LEFT, inward), listOf(it), it.score) }
                right[0]?.let { results += result(DoorSwing(Hinge.RIGHT, inward), listOf(it), it.score) }
                val l = left[1]
                val r = right[1]
                if (l != null && r != null) results += result(DoorSwing(Hinge.BOTH, inward), listOf(l, r), min(l.score, r.score))
            }
            return results.maxByOrNull { it.score }
        }

        private fun result(swing: DoorSwing, leaves: List<Candidate>, score: Float) = Result(
            swing,
            leaves.map { c ->
                // Near a shallow leaf the jamb found by the opening detector can sit a little
                // off the real hinge; run the leaf's line back to the wall face instead.
                val back = if (abs(c.ed) > 0.05f) -c.hd / c.ed else 0f
                val t0 = if (back in -MAX_HINGE_SHIFT..0f) back else 0f
                Leaf(toPlan(c.ha + c.ea * t0, c.hd + c.ed * t0), toPlan(c.ha + c.ea * c.length, c.hd + c.ed * c.length))
            },
            score,
        )

        private fun toPlan(a: Float, d: Float) = origin + u * a + n * d

        /**
         * Best leaf hinged at a = [hingeA] opening to [side] (+1 room, -1 far side), for a full
         * width leaf (index 0) and a half width one of a pair (index 1).
         */
        private fun search(ca: FloatArray, cd: FloatArray, cm: IntArray, side: Int, hingeA: Float, toward: Float): Array<Candidate?> {
            val lengths = floatArrayOf(w, w / 2f)
            val best = arrayOfNulls<Candidate>(lengths.size)
            val bin = p.radialBin
            val tMax = rayLength
            val hits = IntArray(ceil(tMax / bin).toInt() + 1)
            val masks = IntArray(hits.size)
            val tol = p.rayTolerance

            // Cells that can lie on a ray from any of the hinge positions.
            val reachSq = (tMax + p.maxHingeOffset + tol).let { it * it }
            val near = ca.indices.filter { i ->
                val da = ca[i] - hingeA
                val dd = cd[i]
                dd * side > -tol && da * da + dd * dd <= reachSq
            }.toIntArray()
            if (near.isEmpty()) return best

            val offsets = (0..(p.maxHingeOffset / p.hingeOffsetStep).toInt()).map { it * p.hingeOffsetStep }
            var deg = p.minAngleDeg
            while (deg <= p.maxAngleDeg + 1e-3f) {
                val rad = Math.toRadians(deg.toDouble())
                val ua = toward * cos(rad).toFloat()
                val ud = side * sin(rad).toFloat()
                for (offset in offsets) {
                    val pd = side * offset
                    hits.fill(0)
                    masks.fill(0)
                    for (i in near) {
                        val va = ca[i] - hingeA
                        val vd = cd[i] - pd
                        val t = va * ua + vd * ud
                        if (t < 0f || t >= tMax || abs(va * ud - vd * ua) > tol) continue
                        val b = (t / bin).toInt()
                        hits[b]++
                        masks[b] = masks[b] or cm[i]
                    }
                    for ((li, length) in lengths.withIndex()) {
                        val cover = leafCover(hits, masks, length)
                        val current = best[li]
                        // The final score is at most the cover, so only a better cover is worth checking further.
                        if (cover <= 0f || (current != null && cover <= current.score)) continue
                        val fit = fit(ca, cd, cm, near, hingeA, pd, ua, ud, length) ?: continue
                        if (fit.beyond > p.maxBeyond) continue
                        val score = cover * (1f - fit.beyond)
                        if (current == null || score > current.score) {
                            best[li] = Candidate(score, fit.ha, fit.hd, fit.ea, fit.ed, length)
                        }
                    }
                }
                deg += p.angleStepDeg
            }
            return best
        }

        /** How well the ray's bins look like a leaf of [length] (0 when they do not). */
        private fun leafCover(hits: IntArray, masks: IntArray, length: Float): Float {
            val bin = p.radialBin
            val b0 = floor(LEAF_FROM * length / bin).toInt()
            val b1 = ceil(LEAF_TO * length / bin).toInt()
            if (b1 - b0 < 3) return 0f
            var occupied = 0
            var heights = 0
            for (b in b0 until b1) {
                if (hits[b] > 0) occupied++
                heights = heights or masks[b]
            }
            val cover = occupied.toFloat() / (b1 - b0)
            val heightCover = Integer.bitCount(heights).toFloat() / heightBins
            if (cover < p.minCoverage || heightCover < p.minHeightCoverage) return 0f
            // A leaf runs out from the hinge without gaps: every third of it shows up. A surface
            // crossing the ray at an angle (a wall beside the jamb) fills only part of it.
            var gap = 0
            for (b in b0 until b1) {
                gap = if (hits[b] > 0) 0 else gap + 1
                if (gap > MAX_GAP_BINS) return 0f
            }
            for (part in 0 until 3) {
                val s0 = b0 + (b1 - b0) * part / 3
                val s1 = b0 + (b1 - b0) * (part + 1) / 3
                var partHits = 0
                for (b in s0 until s1) if (hits[b] > 0) partHits++
                if (s1 > s0 && partHits < (s1 - s0) * MIN_PART) return 0f
            }
            return cover * heightCover
        }

        /**
         * Fits a line through the points along the ray from ([pa], [pd]) and measures the share
         * of the space just past the leaf's free edge taken by a tall surface running on in line
         * with it (a wall or wardrobe side, not a leaf). Following the fitted line rather than
         * the ray finds such a surface even when the ray is a few degrees off, while a wall
         * standing parallel a few centimetres beside the leaf does not count.
         */
        private fun fit(
            ca: FloatArray, cd: FloatArray, cm: IntArray, near: IntArray,
            pa: Float, pd: Float, ua: Float, ud: Float, length: Float,
        ): Fit? {
            val tol = p.rayTolerance
            val t0 = LEAF_FROM * length
            val t1 = LEAF_TO * length
            // Least-squares line through the leaf points.
            var n = 0
            var sa = 0.0
            var sd = 0.0
            for (i in near) {
                val va = ca[i] - pa
                val vd = cd[i] - pd
                val t = va * ua + vd * ud
                if (t < t0 || t > t1 || abs(va * ud - vd * ua) > tol) continue
                n++
                sa += ca[i]
                sd += cd[i]
            }
            if (n < 2) return null
            val ma = (sa / n).toFloat()
            val md = (sd / n).toFloat()
            var saa = 0.0
            var sdd = 0.0
            var sad = 0.0
            for (i in near) {
                val va = ca[i] - pa
                val vd = cd[i] - pd
                val t = va * ua + vd * ud
                if (t < t0 || t > t1 || abs(va * ud - vd * ua) > tol) continue
                val da = ca[i] - ma
                val dd = cd[i] - md
                saa += da * da
                sdd += dd * dd
                sad += da * dd
            }
            val angle = 0.5 * atan2(2 * sad, saa - sdd)
            var ea = cos(angle).toFloat()
            var ed = sin(angle).toFloat()
            if (ea * ua + ed * ud < 0f) {
                ea = -ea
                ed = -ed
            }
            // Distances along the fitted line, measured from the hinge's foot on it.
            val foot = (pa - ma) * ea + (pd - md) * ed
            val bin = p.radialBin
            val e0 = length + max(BEYOND_GAP, 0.12f * length)
            val e1 = BEYOND_TO * length + BEYOND_GAP
            val bins = max(1, ceil((e1 - e0) / bin).toInt())
            val tall = IntArray(bins)
            for (i in near) {
                val da = ca[i] - ma
                val dd = cd[i] - md
                val t = da * ea + dd * ed - foot
                if (t < e0 || t >= e1 || abs(da * ed - dd * ea) > tol) continue
                val b = ((t - e0) / bin).toInt().coerceIn(0, bins - 1)
                tall[b] = tall[b] or cm[i]
            }
            val beyond = tall.count { Integer.bitCount(it) >= heightBins * TALL }.toFloat() / bins
            return Fit(ma + ea * foot, md + ed * foot, ea, ed, beyond)
        }

        companion object {
            fun create(door: Opening, p: DoorSwingParams): DoorRaster? {
                val top = min(p.bandTop, door.top - HEAD_CLEARANCE)
                if (top - p.bandBottom < MIN_BAND) return null
                return DoorRaster(door, p, top)
            }
        }
    }

    private companion object {
        const val MIN_DOOR_WIDTH = 0.4f

        /** Part of the leaf that must show points (clear of the hinge knuckle and the free edge). */
        const val LEAF_FROM = 0.05f
        const val LEAF_TO = 0.92f
        const val MIN_PART = 0.4f
        const val MAX_GAP_BINS = 2
        const val BEYOND_GAP = 0.1f
        const val BEYOND_TO = 1.5f

        /** Share of the height band that makes a surface "tall" when checking past the leaf. */
        const val TALL = 0.4f
        const val HEAD_CLEARANCE = 0.15f
        const val MIN_BAND = 0.6f
        const val WALL_BAND = 0.05f
        const val JAMB_SLACK = 0.03f
        const val LEAF_WALL_TOLERANCE = 0.1f
        const val MAX_HINGE_SHIFT = 0.3f
        const val LEAF_SLACK = 0.15f
    }
}
