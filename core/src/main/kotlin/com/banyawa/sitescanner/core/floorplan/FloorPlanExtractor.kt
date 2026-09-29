package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class FloorPlanParams(
    /** Occupancy grid resolution of the horizontal slice. */
    val cellSize: Float = 0.025f,
    /** Slice band, measured from the floor. Low enough to catch walls behind furniture tops. */
    val sliceBottom: Float = 0.3f,
    val sliceTop: Float = 1.8f,
    /** Cells with fewer points are treated as noise. */
    val minCellHits: Int = 2,
    /** Max distance of a cell from its wall line. */
    val wallTolerance: Float = 0.05f,
    val minWallLength: Float = 0.3f,
    /** Gaps longer than this split a wall (door openings, occlusions). */
    val maxGap: Float = 0.3f,
    /** Occupied cells per cell-length of wall; rejects scattered clutter. */
    val minDensity: Float = 0.5f,
    /** Wall ends within this distance of another wall's line are extended/trimmed to meet it. */
    val cornerSnap: Float = 0.35f,
    val detectObliqueWalls: Boolean = true,
    val detectOpenings: Boolean = true,
    val openings: OpeningParams = OpeningParams(),
)

/**
 * Turns a room scan into a 2D wall plan:
 * 1. estimate floor / ceiling heights,
 * 2. take a horizontal slice through the walls and rasterise it into an occupancy grid,
 * 3. find the dominant wall direction (most buildings are rectilinear) and rotate the plan
 *    so those walls are axis-aligned,
 * 4. extract axis-aligned wall runs, then oblique walls with RANSAC,
 * 5. snap wall ends into clean corners,
 * 6. find doors and windows in each wall ([OpeningDetector]).
 */
class FloorPlanExtractor(private val params: FloorPlanParams = FloorPlanParams()) {

    fun extract(cloud: PointCloud, floorYHint: Float? = null): FloorPlanResult {
        val levels = LevelEstimator.estimate(cloud)
        val floorY = floorYHint ?: levels?.floorY ?: 0f
        val ceilingY = levels?.ceilingY?.takeIf { it > floorY + 1.5f }
        val bottom = floorY + params.sliceBottom
        val top = floorY + min(params.sliceTop, ceilingY?.let { it - floorY - 0.15f } ?: params.sliceTop)

        val grid = rasterise(cloud, bottom, top)
        if (grid.size == 0) {
            return FloorPlanResult(FloorPlan(emptyList(), SiteAlignment(0f, floorY), ceilingY), FloatArray(0))
        }

        val yaw = dominantYaw(grid)
        val alignment = SiteAlignment(yaw, floorY)
        val c = cos(-yaw)
        val s = sin(-yaw)
        val aligned = FloatArray(grid.size * 2)
        for (i in 0 until grid.size) {
            val u = grid.xs[i]
            val v = grid.ys[i]
            aligned[i * 2] = u * c - v * s
            aligned[i * 2 + 1] = u * s + v * c
        }

        val walls = extractWalls(aligned)
        val snapped = snapCorners(walls)
        var plan = FloorPlan(snapped, alignment, ceilingY)
        if (params.detectOpenings) plan = plan.copy(openings = OpeningDetector(params.openings).detect(cloud, plan))
        return FloorPlanResult(plan, aligned)
    }

    private class Grid(val xs: FloatArray, val ys: FloatArray) {
        val size get() = xs.size
    }

    /** Occupied cell centres of the slice in un-rotated plan coordinates (x, -z). */
    private fun rasterise(cloud: PointCloud, bottom: Float, top: Float): Grid {
        val cell = params.cellSize
        val counts = HashMap<Long, Int>()
        for (i in 0 until cloud.size) {
            val y = cloud.xyz[i * 3 + 1]
            if (y < bottom || y > top) continue
            val u = cloud.xyz[i * 3]
            val v = -cloud.xyz[i * 3 + 2]
            val cu = floor(u / cell).toInt()
            val cv = floor(v / cell).toInt()
            val key = (cu.toLong() shl 32) or (cv.toLong() and 0xFFFFFFFFL)
            counts.merge(key, 1, Int::plus)
        }
        val keep = counts.filterValues { it >= params.minCellHits }.keys
        val xs = FloatArray(keep.size)
        val ys = FloatArray(keep.size)
        for ((i, key) in keep.withIndex()) {
            val cu = (key shr 32).toInt()
            val cv = key.toInt()
            xs[i] = (cu + 0.5f) * cell
            ys[i] = (cv + 0.5f) * cell
        }
        return Grid(xs, ys)
    }

    /**
     * Angle in (-π/4, π/4] that makes the occupied cells line up best with the axes, measured
     * by how "peaky" the projections onto both axes are (sum of squared histogram bins).
     */
    private fun dominantYaw(grid: Grid): Float {
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until grid.size) {
            cx += grid.xs[i]
            cy += grid.ys[i]
        }
        cx /= grid.size
        cy /= grid.size
        val px = FloatArray(grid.size) { (grid.xs[it] - cx).toFloat() }
        val py = FloatArray(grid.size) { (grid.ys[it] - cy).toFloat() }
        var radius = 0f
        for (i in px.indices) radius = max(radius, sqrt(px[i] * px[i] + py[i] * py[i]))
        val bin = 0.05f
        val bins = (2 * radius / bin).toInt() + 2
        val histA = IntArray(bins)
        val histB = IntArray(bins)

        fun score(theta: Double): Long {
            histA.fill(0)
            histB.fill(0)
            val c = cos(theta).toFloat()
            val s = sin(theta).toFloat()
            for (i in px.indices) {
                val a = px[i] * c + py[i] * s
                val b = -px[i] * s + py[i] * c
                histA[((a + radius) / bin).toInt().coerceIn(0, bins - 1)]++
                histB[((b + radius) / bin).toInt().coerceIn(0, bins - 1)]++
            }
            var sum = 0L
            for (k in 0 until bins) sum += histA[k].toLong() * histA[k] + histB[k].toLong() * histB[k]
            return sum
        }

        var bestDeg = 0.0
        var bestScore = -1L
        var deg = 0.0
        while (deg < 90.0) {
            val sc = score(Math.toRadians(deg))
            if (sc > bestScore) {
                bestScore = sc
                bestDeg = deg
            }
            deg += 0.5
        }
        val coarse = bestDeg
        deg = coarse - 0.5
        while (deg <= coarse + 0.5) {
            val sc = score(Math.toRadians(deg))
            if (sc > bestScore) {
                bestScore = sc
                bestDeg = deg
            }
            deg += 0.05
        }
        // Walls repeat every 90°: pick the smallest rotation so the plan keeps the scan's heading.
        var result = bestDeg % 90.0
        if (result <= -45.0) result += 90.0
        if (result > 45.0) result -= 90.0
        return Math.toRadians(result).toFloat()
    }

    private fun extractWalls(pts: FloatArray): List<WallSegment> {
        val n = pts.size / 2
        val used = BooleanArray(n)
        val walls = ArrayList<WallSegment>()
        extractAxisWalls(pts, used, alongX = true, walls)
        extractAxisWalls(pts, used, alongX = false, walls)
        if (params.detectObliqueWalls) extractObliqueWalls(pts, used, walls)
        return walls
    }

    private val minSupport get() = max(4, (params.minWallLength / params.cellSize * params.minDensity).toInt())

    private fun extractAxisWalls(pts: FloatArray, used: BooleanArray, alongX: Boolean, out: MutableList<WallSegment>) {
        val n = used.size
        val cell = params.cellSize
        val tol = params.wallTolerance
        val acrossOff = if (alongX) 1 else 0
        val alongOff = if (alongX) 0 else 1

        val hist = HashMap<Int, Int>()
        for (i in 0 until n) {
            if (used[i]) continue
            val b = floor(pts[i * 2 + acrossOff] / cell).toInt()
            hist.merge(b, 1, Int::plus)
        }
        val candidates = hist.entries.filter { it.value >= minSupport }.sortedByDescending { it.value }

        for ((bin, _) in candidates) {
            var center = (bin + 0.5f) * cell
            var members = collectBand(pts, used, acrossOff, center, tol)
            if (members.size < minSupport) continue
            center = members.map { pts[it * 2 + acrossOff] }.average().toFloat()
            members = collectBand(pts, used, acrossOff, center, tol)
            if (members.size < minSupport) continue

            val sorted = members.sortedBy { pts[it * 2 + alongOff] }
            for (run in splitRuns(sorted) { pts[it * 2 + alongOff] }) {
                val a0 = pts[run.first() * 2 + alongOff] - cell / 2
                val a1 = pts[run.last() * 2 + alongOff] + cell / 2
                val len = a1 - a0
                if (len < params.minWallLength || run.size / (len / cell) < params.minDensity) continue
                val c = run.map { pts[it * 2 + acrossOff] }.average().toFloat()
                val wall = if (alongX) {
                    WallSegment(Vec2(a0, c), Vec2(a1, c), run.size)
                } else {
                    WallSegment(Vec2(c, a0), Vec2(c, a1), run.size)
                }
                out.add(wall)
                for (i in run) used[i] = true
            }
        }
    }

    private fun collectBand(pts: FloatArray, used: BooleanArray, acrossOff: Int, center: Float, tol: Float): List<Int> {
        val result = ArrayList<Int>()
        for (i in used.indices) {
            if (!used[i] && abs(pts[i * 2 + acrossOff] - center) <= tol) result.add(i)
        }
        return result
    }

    private inline fun splitRuns(sorted: List<Int>, along: (Int) -> Float): List<List<Int>> {
        val runs = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var last = Float.NaN
        for (i in sorted) {
            val a = along(i)
            if (current.isNotEmpty() && a - last > params.maxGap) {
                runs.add(current)
                current = ArrayList()
            }
            current.add(i)
            last = a
        }
        if (current.isNotEmpty()) runs.add(current)
        return runs
    }

    private fun extractObliqueWalls(pts: FloatArray, used: BooleanArray, out: MutableList<WallSegment>) {
        val rnd = Random(RANSAC_SEED)
        val tol = params.wallTolerance
        val cell = params.cellSize
        repeat(MAX_OBLIQUE_WALLS) {
            val remaining = used.indices.filter { !used[it] }
            if (remaining.size < minSupport * 2) return

            var bestCount = 0
            var bestNx = 0f
            var bestNy = 0f
            var bestC = 0f
            repeat(RANSAC_ITERATIONS) {
                val i = remaining[rnd.nextInt(remaining.size)]
                val j = remaining[rnd.nextInt(remaining.size)]
                val dx = pts[j * 2] - pts[i * 2]
                val dy = pts[j * 2 + 1] - pts[i * 2 + 1]
                val len = sqrt(dx * dx + dy * dy)
                if (len < 0.2f) return@repeat
                val nx = -dy / len
                val ny = dx / len
                val c = nx * pts[i * 2] + ny * pts[i * 2 + 1]
                var count = 0
                for (k in remaining) if (abs(nx * pts[k * 2] + ny * pts[k * 2 + 1] - c) <= tol) count++
                if (count > bestCount) {
                    bestCount = count
                    bestNx = nx
                    bestNy = ny
                    bestC = c
                }
            }
            if (bestCount < minSupport) return

            // Least-squares refinement of the line through its inliers.
            val inliers = remaining.filter { abs(bestNx * pts[it * 2] + bestNy * pts[it * 2 + 1] - bestC) <= tol }
            var mx = 0.0
            var my = 0.0
            for (k in inliers) {
                mx += pts[k * 2]
                my += pts[k * 2 + 1]
            }
            mx /= inliers.size
            my /= inliers.size
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (k in inliers) {
                val dx = pts[k * 2] - mx
                val dy = pts[k * 2 + 1] - my
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
            val angle = 0.5 * atan2(2 * sxy, sxx - syy)
            val dirX = cos(angle).toFloat()
            val dirY = sin(angle).toFloat()
            val origin = Vec2(mx.toFloat(), my.toFloat())
            val nx = -dirY
            val ny = dirX
            val c = nx * origin.x + ny * origin.y
            val members = remaining.filter { abs(nx * pts[it * 2] + ny * pts[it * 2 + 1] - c) <= tol }
            val proj = { k: Int -> (pts[k * 2] - origin.x) * dirX + (pts[k * 2 + 1] - origin.y) * dirY }
            val sorted = members.sortedBy(proj)
            for (run in splitRuns(sorted, proj)) {
                val t0 = proj(run.first()) - cell / 2
                val t1 = proj(run.last()) + cell / 2
                val len = t1 - t0
                if (len < params.minWallLength || run.size / (len / cell) < params.minDensity) continue
                out.add(
                    WallSegment(
                        Vec2(origin.x + dirX * t0, origin.y + dirY * t0),
                        Vec2(origin.x + dirX * t1, origin.y + dirY * t1),
                        run.size,
                    ),
                )
            }
            for (k in members) used[k] = true
            for (k in inliers) used[k] = true
        }
    }

    /** Moves wall ends onto the intersection with a nearby, non-parallel wall. */
    private fun snapCorners(walls: List<WallSegment>): List<WallSegment> {
        val snap = params.cornerSnap
        return walls.mapIndexed { i, w ->
            fun snapEnd(e: Vec2, other: Vec2): Vec2 {
                var best: Vec2? = null
                var bestD = snap
                for ((j, o) in walls.withIndex()) {
                    if (j == i) continue
                    val x = intersection(w, o) ?: continue
                    val d = x.distanceTo(e)
                    if (d > bestD) continue
                    val t = (x - o.start) dot o.direction
                    if (t < -snap || t > o.length + snap) continue
                    // Never collapse or flip the wall.
                    if (x.distanceTo(other) < params.minWallLength * 0.5f) continue
                    best = x
                    bestD = d
                }
                return best ?: e
            }
            val start = snapEnd(w.start, w.end)
            val end = snapEnd(w.end, w.start)
            WallSegment(start, end, w.support)
        }
    }

    private fun intersection(a: WallSegment, b: WallSegment): Vec2? {
        val r = a.end - a.start
        val s = b.end - b.start
        val denom = r cross s
        if (abs(denom) < MIN_CORNER_SIN * r.length() * s.length()) return null
        val t = ((b.start - a.start) cross s) / denom
        return a.start + r * t
    }

    companion object {
        private const val RANSAC_SEED = 1234L
        private const val RANSAC_ITERATIONS = 200
        private const val MAX_OBLIQUE_WALLS = 12

        /** sin(20°): walls meeting at a shallower angle are not treated as corners. */
        private const val MIN_CORNER_SIN = 0.342f
    }
}
