package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Doors and windows added by hand on the plan. */
object OpeningPlacement {
    /** Typical clear sizes used until the user types in the measured ones. */
    data class Size(val width: Float, val bottom: Float, val top: Float)

    fun defaultSize(type: OpeningType, roomHeight: Float?): Size = when (type) {
        OpeningType.DOOR -> Size(0.8f, 0f, 2.0f)
        OpeningType.WINDOW -> Size(1.0f, 0.9f, 2.0f)
        OpeningType.OPENING -> Size(0.9f, 0f, roomHeight ?: 2.4f)
    }

    /**
     * A new opening of [type] centred on the wall nearest to [at] (plan metres), or null
     * when no wall is within [maxDistance]. It follows the same conventions as detected
     * openings: it spans the whole straight wall (corner to corner) for "from left corner",
     * and left / right and in / out are as seen from inside the room.
     */
    fun place(
        plan: FloorPlan,
        at: Vec2,
        type: OpeningType,
        id: String,
        maxDistance: Float = 0.3f,
        params: OpeningParams = OpeningParams(),
    ): Opening? {
        val lines = WallLines.build(plan.walls, params.maxLineGap, params.maxEndExtension)
        val line = lines
            .filter { l ->
                val t = l.dir dot at
                t >= l.tStart - maxDistance && t <= l.tEnd + maxDistance && abs(l.distance(at)) <= maxDistance
            }
            .minByOrNull { abs(it.distance(at)) }
            ?: return null

        val inside = interiorNormal(plan, line)
        // Wall ends in "seen from inside" order: the room's normal is -direction.perp().
        val forward = (inside.perp() dot line.dir) > 0f
        val a = line.point(line.tStart)
        val b = line.point(line.tEnd)
        val wallStart = if (forward) a else b
        val wallEnd = if (forward) b else a
        val wallLength = a.distanceTo(b)

        val size = defaultSize(type, plan.roomHeight)
        val width = min(size.width, wallLength)
        val centre = (at - wallStart) dot (wallEnd - wallStart).normalized()
        val fromLeft = (centre - width / 2f).coerceIn(0f, wallLength - width)
        return Opening(
            id = id,
            type = type,
            start = wallStart,
            end = wallStart,
            bottom = size.bottom,
            top = size.top,
            wallStart = wallStart,
            wallEnd = wallEnd,
            manual = true,
        ).resized(fromLeft, width, size.bottom, size.top)
    }

    /** First id of the form "u1", "u2"… not used by [openings]. */
    fun newId(openings: List<Opening>): String {
        val used = openings.mapTo(HashSet()) { it.id }
        return generateSequence(1) { it + 1 }.map { "u$it" }.first { it !in used }
    }

    /**
     * Which side of [line] the room is on. An opening already on that wall knows; otherwise
     * the side from which the walls look enclosed, falling back to the side facing the
     * middle of the plan.
     */
    private fun interiorNormal(plan: FloorPlan, line: WallLine): Vec2 {
        plan.openings.firstOrNull { o ->
            abs(o.direction cross line.dir) < 0.05f && abs(line.distance(o.midpoint)) < 0.1f
        }?.let { return it.interiorNormal }

        // Several probes, so one standing in a doorway does not decide it.
        var plus = 0
        var minus = 0
        for (f in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val p = line.point(line.tStart + (line.tEnd - line.tStart) * f)
            plus += enclosure(plan.walls, p + line.normal * PROBE)
            minus += enclosure(plan.walls, p - line.normal * PROBE)
        }
        if (plus != minus) return if (plus > minus) line.normal else -line.normal

        val mid = line.point((line.tStart + line.tEnd) / 2f)

        val mids = plan.walls.map { it.midpoint }
        val centroid = mids.fold(Vec2.ZERO) { acc, m -> acc + m } * (1f / mids.size.coerceAtLeast(1))
        return if ((centroid - mid) dot line.normal >= 0f) line.normal else -line.normal
    }

    /**
     * How many of [RAYS] rays from [q] cross the walls an odd number of times, i.e. how
     * enclosed [q] looks. Rays escaping through doorways or unscanned gaps just lower the
     * count, so this still works for rooms that are not quite closed.
     */
    private fun enclosure(walls: List<WallSegment>, q: Vec2): Int {
        var votes = 0
        for (k in 0 until RAYS) {
            val angle = (k + 0.5) * 2 * PI / RAYS
            val d = Vec2(cos(angle).toFloat(), sin(angle).toFloat())
            var crossings = 0
            for (w in walls) {
                val s = w.end - w.start
                val denom = d cross s
                if (abs(denom) < 1e-9f) continue
                val qs = w.start - q
                val t = (qs cross s) / denom
                val u = (qs cross d) / denom
                if (t > 0f && u in 0f..1f) crossings++
            }
            if (crossings % 2 == 1) votes++
        }
        return votes
    }

    private const val RAYS = 16

    /** Distance from the wall at which each side is probed. */
    private const val PROBE = 0.25f
}
