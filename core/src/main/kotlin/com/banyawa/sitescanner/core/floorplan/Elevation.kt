package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.project.Measurement
import com.banyawa.sitescanner.core.util.FloatList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** An opening as drawn on an elevation: [left]..[right] metres from the wall's left corner. */
data class ElevationOpening(
    val opening: Opening,
    val left: Float,
    val right: Float,
    /** The door's swing described from this side of the wall. */
    val swing: DoorSwing?,
) {
    val bottom: Float get() = opening.bottom
    val top: Float get() = opening.top
    val width: Float get() = right - left
}

/** An AR measurement taken along a wall, in elevation coordinates. */
data class ElevationMeasurement(
    val label: String,
    val x0: Float,
    val z0: Float,
    val x1: Float,
    val z1: Float,
    /** The measured 3D length. */
    val lengthM: Float,
)

/**
 * A wall seen square-on from inside the room, as drawn on an elevation for shop drawings.
 * Elevation coordinates: x metres from the wall's left corner (seen from inside), z metres
 * above the floor.
 */
class Elevation(
    /** Drawing key, A, B, C… clockwise from north; the plan marks each wall with it. */
    val key: String,
    /** Left and right corners of the wall in plan, seen from inside. */
    val start: Vec2,
    val end: Vec2,
    /** Floor to ceiling, or to the top of the scanned wall. */
    val height: Float,
    /**
     * Scan points on the wall and just in front of it, as (x, z, depth) triples; depth is
     * metres from the wall surface into the room (sockets, pipes, cabinets stand out).
     */
    val points: FloatArray,
    val openings: List<ElevationOpening> = emptyList(),
    val measurements: List<ElevationMeasurement> = emptyList(),
) {
    val length: Float get() = start.distanceTo(end)
    val direction: Vec2 get() = (end - start).normalized()

    /** Unit normal pointing into the room, the way the wall is viewed. */
    val interiorNormal: Vec2 get() = -direction.perp()
    val pointCount: Int get() = points.size / 3
    val midpoint: Vec2 get() = Vec2.lerp(start, end, 0.5f)

    /** Distance along the wall from its left corner. */
    fun along(p: Vec2): Float = (p - start) dot direction

    /** Distance from the wall line into the room. */
    fun offset(p: Vec2): Float = (p - start) dot interiorNormal

    fun with(openings: List<ElevationOpening>, measurements: List<ElevationMeasurement>) =
        Elevation(key, start, end, height, points, openings, measurements)

    companion object {
        /** Points standing further than this off the wall are objects in front of it, not its surface. */
        const val SURFACE_DEPTH = 0.03f
    }
}

data class ElevationParams(
    /** Shorter walls get no elevation. */
    val minLength: Float = 0.5f,
    /** Finest raster of the scan underlay; coarser on long walls to stay under [maxPoints]. */
    val cellSize: Float = 0.02f,
    val maxPoints: Int = 40_000,
    val minHits: Int = 2,
    /** Points from this far behind the wall surface… */
    val behind: Float = 0.05f,
    /** …to this far into the room are drawn: the wall plus what is mounted on it. */
    val front: Float = 0.3f,
    /** Floor and ceiling points at the foot and head of the wall are left out. */
    val edgeClearance: Float = 0.03f,
    /** So are the first centimetres of the walls at the corners. */
    val cornerClearance: Float = 0.05f,
    /** AR measurements with both ends this close to a wall are shown on its elevation. */
    val measurementTolerance: Float = 0.2f,
    /** Openings this close to a wall line belong to it (thick walls: either face). */
    val openingTolerance: Float = 0.3f,
    val defaultHeight: Float = 2.6f,
    val lines: OpeningParams = OpeningParams(),
)

/**
 * Builds an [Elevation] for every straight wall of a [FloorPlan]: the wall seen from the
 * room side (found from where the floor was scanned), its doors and windows, the AR
 * measurements taken along it and the scan of its surface as an underlay.
 */
class ElevationBuilder(private val params: ElevationParams = ElevationParams()) {

    fun build(cloud: PointCloud, plan: FloorPlan, measurements: List<Measurement> = emptyList()): List<Elevation> {
        val all = WallLines.build(plan.walls, params.lines.maxLineGap, params.lines.maxEndExtension)
        val lines = all.filter { it.tEnd - it.tStart >= params.minLength && !isFurnitureFront(it, all) }
        if (lines.isEmpty()) return emptyList()

        val site = FloatArray(3)
        val xyz = cloud.xyz
        // Pass 1: which side the room is on, where the wall surface is and how high it goes.
        val surveys = lines.map { Survey(it) }
        for (i in 0 until cloud.size) {
            plan.alignment.toSite(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], site, 0)
            for (s in surveys) s.add(site[0], site[1], site[2])
        }
        // Pass 2: the wall surface and what stands on it, seen from the room.
        val frames = surveys.map { it.frame(plan) }
        for (i in 0 until cloud.size) {
            plan.alignment.toSite(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], site, 0)
            for (f in frames) f.add(site[0], site[1], site[2])
        }

        // Clockwise from north around the room. The sweep starts at north-west, so the wall
        // facing north comes first even when its middle sits a little west of the centre.
        val mids = frames.map { it.start + it.dir * (it.length / 2f) }
        val centre = mids.fold(Vec2.ZERO) { acc, m -> acc + m } * (1f / mids.size)
        val ordered = frames.indices.sortedBy { i ->
            val a = atan2(mids[i].x - centre.x, mids[i].y - centre.y) + (PI / 4).toFloat()
            if (a < 0) a + 2 * PI.toFloat() else a
        }.map { frames[it] }
        val elevations = ordered.mapIndexed { i, f -> f.toElevation(key(i)) }
        return attach(elevations, plan, measurements, params)
    }

    /**
     * Cabinets and wardrobes come out of the plan as short walls standing just in front of a
     * longer one. They show on that wall's elevation as objects in front of it instead.
     */
    private fun isFurnitureFront(line: WallLine, all: List<WallLine>): Boolean {
        // Scanned extents: corner extension would stretch a cabinet front to the corners.
        val length = line.segmentEnd - line.segmentStart
        return all.any { other ->
            if (other === line || abs(line.dir cross other.dir) > WallLines.PARALLEL_SIN) return@any false
            if (other.segmentLength < length * FURNITURE_RATIO) return@any false
            val gap = abs(line.distance(other.point((other.segmentStart + other.segmentEnd) / 2f)))
            if (gap < FURNITURE_MIN_GAP || gap > FURNITURE_MAX_GAP) return@any false
            val a = line.dir dot other.point(other.segmentStart)
            val b = line.dir dot other.point(other.segmentEnd)
            min(max(a, b), line.segmentEnd) - max(min(a, b), line.segmentStart) >= FURNITURE_OVERLAP * length
        }
    }

    /** First pass over one wall line. */
    private inner class Survey(val line: WallLine) {
        /** Floor points on the +normal and -normal side. */
        private val floor = IntArray(2)
        private val offsets = FloatList(1024)
        private val heights = FloatList(1024)
        private var seen = 0

        fun add(x: Float, y: Float, h: Float) {
            val t = line.dir.x * x + line.dir.y * y
            if (t < line.tStart || t > line.tEnd) return
            val d = line.normal.x * x + line.normal.y * y - line.offset
            if (h < FLOOR_TOP) {
                if (h > -0.1f && abs(d) in FLOOR_NEAR..FLOOR_FAR) floor[if (d >= 0f) 0 else 1]++
                return
            }
            if (abs(d) <= SURFACE_BAND && seen++ % SAMPLE_EVERY == 0) {
                offsets.add(d)
                heights.add(h)
            }
        }

        fun frame(plan: FloorPlan): Frame {
            val inside = roomSide() ?: OpeningPlacement.interiorNormal(plan, line)
            // Seen from inside, the wall runs left to right along inside.perp().
            val forward = (inside.perp() dot line.dir) > 0f
            val a = line.point(line.tStart)
            val b = line.point(line.tEnd)
            val start = if (forward) a else b
            val sign = if ((inside dot line.normal) > 0f) 1f else -1f
            val surface = if (offsets.size > 0) percentile(offsets, 0.5f) * sign else 0f
            val height = plan.roomHeight
                ?: if (heights.size > 0) max(MIN_HEIGHT, percentile(heights, 0.98f)) else params.defaultHeight
            return Frame(start, (if (forward) b else a) - start, inside, surface, height)
        }

        private fun roomSide(): Vec2? {
            val plus = floor[0]
            val minus = floor[1]
            if (max(plus, minus) < MIN_FLOOR_VOTES || max(plus, minus) < FLOOR_RATIO * min(plus, minus)) return null
            return if (plus > minus) line.normal else -line.normal
        }
    }

    /** Second pass: the wall's surface raster in elevation coordinates. */
    private inner class Frame(val start: Vec2, span: Vec2, val inside: Vec2, val surface: Float, val height: Float) {
        val length = span.length()
        val dir = span.normalized()
        private val cell = max(params.cellSize, sqrt(length * height / params.maxPoints))
        private val nx = max(1, ceil(length / cell).toInt())
        private val nz = max(1, ceil(height / cell).toInt())
        private val count = IntArray(nx * nz)
        private val depth = FloatArray(nx * nz) { -Float.MAX_VALUE }

        fun add(x: Float, y: Float, h: Float) {
            if (h < params.edgeClearance || h > height - params.edgeClearance) return
            val px = x - start.x
            val py = y - start.y
            val a = px * dir.x + py * dir.y
            if (a < params.cornerClearance || a > length - params.cornerClearance) return
            val d = px * inside.x + py * inside.y - surface
            if (d < -params.behind || d > params.front) return
            val idx = min(nx - 1, (a / cell).toInt()) * nz + min(nz - 1, (h / cell).toInt())
            count[idx]++
            // The surface nearest the viewer is what shows.
            if (d > depth[idx]) depth[idx] = d
        }

        fun toElevation(key: String): Elevation {
            val out = FloatList(4096)
            for (i in 0 until nx) {
                for (j in 0 until nz) {
                    val idx = i * nz + j
                    if (count[idx] < params.minHits) continue
                    out.add((i + 0.5f) * cell)
                    out.add((j + 0.5f) * cell)
                    out.add(depth[idx])
                }
            }
            return Elevation(key, start, start + dir * length, height, out.toArray())
        }
    }

    companion object {
        /**
         * Puts [plan]'s openings and the [measurements] taken along each wall onto the
         * [elevations] (replacing what they had), e.g. after the user edited the openings.
         */
        fun attach(
            elevations: List<Elevation>,
            plan: FloorPlan,
            measurements: List<Measurement>,
            params: ElevationParams = ElevationParams(),
        ): List<Elevation> {
            val openings = elevations.map { ArrayList<ElevationOpening>() }
            for (o in plan.openings) {
                val k = nearest(elevations) { e ->
                    if (abs(e.direction cross o.direction) > PARALLEL_SIN) return@nearest null
                    val a = e.along(o.midpoint)
                    if (a < -END_SLACK || a > e.length + END_SLACK) return@nearest null
                    abs(e.offset(o.midpoint)).takeIf { it <= params.openingTolerance }
                } ?: continue
                val e = elevations[k]
                val a0 = e.along(o.start)
                val a1 = e.along(o.end)
                val sameWay = (o.direction dot e.direction) > 0f
                openings[k] += ElevationOpening(o, min(a0, a1), max(a0, a1), if (sameWay) o.swing else o.swing?.mirrored())
            }

            val measured = elevations.map { ArrayList<ElevationMeasurement>() }
            for (m in measurements) {
                val a = plan.alignment.toSite(m.start)
                val b = plan.alignment.toSite(m.end)
                val pa = Vec2(a.x, a.y)
                val pb = Vec2(b.x, b.y)
                val k = nearest(elevations) { e ->
                    val xa = e.along(pa)
                    val xb = e.along(pb)
                    if (min(xa, xb) < -END_SLACK || max(xa, xb) > e.length + END_SLACK) return@nearest null
                    max(abs(e.offset(pa)), abs(e.offset(pb))).takeIf { it <= params.measurementTolerance }
                } ?: continue
                val e = elevations[k]
                measured[k] += ElevationMeasurement(m.label.trim(), e.along(pa), a.z, e.along(pb), b.z, m.lengthM)
            }
            return elevations.mapIndexed { k, e -> e.with(openings[k].sortedBy { it.left }, measured[k]) }
        }

        /** Index of the elevation with the smallest non-null [distance]. */
        private inline fun nearest(elevations: List<Elevation>, distance: (Elevation) -> Float?): Int? {
            var best: Int? = null
            var bestD = Float.MAX_VALUE
            for ((k, e) in elevations.withIndex()) {
                val d = distance(e) ?: continue
                if (d < bestD) {
                    best = k
                    bestD = d
                }
            }
            return best
        }

        /** A…Z, then A2…Z2 and so on. */
        fun key(index: Int): String = ('A' + index % 26).toString() + if (index >= 26) (index / 26 + 1).toString() else ""

        private fun percentile(list: FloatList, q: Float): Float {
            val v = list.toArray().also { it.sort() }
            return v[(v.size * q).toInt().coerceIn(0, v.size - 1)]
        }

        private const val FLOOR_TOP = 0.05f
        private const val FLOOR_NEAR = 0.2f
        private const val FLOOR_FAR = 1.5f
        private const val MIN_FLOOR_VOTES = 30
        private const val FLOOR_RATIO = 1.5f
        private const val SURFACE_BAND = 0.06f
        private const val SAMPLE_EVERY = 4
        private const val MIN_HEIGHT = 1.8f
        private const val PARALLEL_SIN = 0.1f
        private const val FURNITURE_RATIO = 1.5f
        private const val FURNITURE_MIN_GAP = 0.1f
        private const val FURNITURE_MAX_GAP = 0.8f
        private const val FURNITURE_OVERLAP = 0.8f
        private const val END_SLACK = 0.1f
    }
}
