package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Serializable
enum class OpeningType {
    DOOR,
    WINDOW,

    /** Floor-to-ceiling opening without a head (passage, archway). */
    OPENING,
}

/** Hinge jamb of a swing door, as seen from inside the room facing the wall. */
@Serializable
enum class Hinge {
    LEFT,
    RIGHT,

    /** A pair of leaves, one hinged on each jamb. */
    BOTH,
}

/** How a hinged door opens. */
@Serializable
data class DoorSwing(
    val hinge: Hinge,
    /** True when the leaf opens into the scanned room. */
    val inward: Boolean,
) {
    /** Schedule code: L-IN, R-OUT, PAIR-IN… */
    val code: String
        get() = when (hinge) {
            Hinge.LEFT -> "L"
            Hinge.RIGHT -> "R"
            Hinge.BOTH -> "PAIR"
        } + if (inward) "-IN" else "-OUT"

    /** The same door described from the other side of the wall. */
    fun mirrored() = DoorSwing(
        when (hinge) {
            Hinge.LEFT -> Hinge.RIGHT
            Hinge.RIGHT -> Hinge.LEFT
            Hinge.BOTH -> Hinge.BOTH
        },
        !inward,
    )
}

/**
 * A door leaf in plan, drawn open at 90° the way plans show it: it turns about [hinge]
 * from [closed] (on the opening line) to [open].
 */
data class DoorLeaf(val hinge: Vec2, val closed: Vec2, val open: Vec2) {
    val width: Float get() = hinge.distanceTo(closed)

    /** Point on the swing arc, [f] = 0 at [closed] to 1 at [open]. */
    fun arcPoint(f: Float): Vec2 {
        val a = f * (PI / 2).toFloat()
        return hinge + (closed - hinge) * cos(a) + (open - hinge) * sin(a)
    }
}

/**
 * A door / window / opening in a wall, in plan coordinates (metres) of a [SiteAlignment].
 *
 * [start] and [end] are the left and right jambs as seen from inside the room facing the
 * wall, which is also how elevations are drawn. Sizes are the clear opening measured from
 * the scan (inside of frames / reveals), not the rough opening.
 */
@Serializable
data class Opening(
    val id: String,
    val type: OpeningType,
    val start: Vec2,
    val end: Vec2,
    /** Sill height above the floor (0 for doors). */
    val bottom: Float,
    /** Head height above the floor. */
    val top: Float,
    /** Left and right ends (corners) of the host wall, seen from inside. */
    val wallStart: Vec2,
    val wallEnd: Vec2,
    /** 0..1; below [LOW_CONFIDENCE] the detection should be checked on site. */
    val confidence: Float = 1f,
    /** Hinged doors only; null when unknown, sliding or not a door. */
    val swing: DoorSwing? = null,
    /** Placed or sized by hand (e.g. from a tape measurement) rather than taken from the scan. */
    val manual: Boolean = false,
) {
    val width: Float get() = start.distanceTo(end)
    val height: Float get() = top - bottom
    val midpoint: Vec2 get() = Vec2.lerp(start, end, 0.5f)

    /** Unit vector from [start] to [end]. */
    val direction: Vec2 get() = (end - start).normalized()

    /** Unit normal pointing into the room. */
    val interiorNormal: Vec2 get() = -direction.perp()

    val distanceFromWallStart: Float get() = wallStart.distanceTo(start)
    val distanceToWallEnd: Float get() = end.distanceTo(wallEnd)
    val wallLength: Float get() = wallStart.distanceTo(wallEnd)

    /** A detection nobody has checked that should be verified on site. */
    val needsCheck: Boolean get() = !manual && confidence < LOW_CONFIDENCE

    /** Leaves drawn open at 90° to the swing side; empty without a [swing]. */
    val leaves: List<DoorLeaf>
        get() {
            val s = swing ?: return emptyList()
            val n = if (s.inward) interiorNormal else -interiorNormal
            fun leaf(hinge: Vec2, closed: Vec2) = DoorLeaf(hinge, closed, hinge + n * hinge.distanceTo(closed))
            return when (s.hinge) {
                Hinge.LEFT -> listOf(leaf(start, end))
                Hinge.RIGHT -> listOf(leaf(end, start))
                Hinge.BOTH -> listOf(leaf(start, midpoint), leaf(end, midpoint))
            }
        }

    /** Distance from [p] to the opening line in plan. */
    fun distanceTo(p: Vec2): Float {
        val len = width
        if (len < 1e-6f) return p.distanceTo(start)
        val t = ((p - start) dot direction).coerceIn(0f, len)
        return p.distanceTo(start + direction * t)
    }

    /** Changes the type; only windows keep a sill and only doors a swing. */
    fun withType(t: OpeningType): Opening = copy(
        type = t,
        bottom = if (t == OpeningType.WINDOW) bottom else 0f,
        swing = if (t == OpeningType.DOOR) swing else null,
    )

    /**
     * Moves the opening along its host wall: [fromLeft] and [width] are measured along the
     * wall from its left corner, [bottom] and [top] above the floor.
     */
    fun resized(fromLeft: Float, width: Float, bottom: Float, top: Float): Opening {
        val along = wallEnd - wallStart
        val dir = if (along.length() > 1e-4f) along.normalized() else direction
        val s = wallStart + dir * fromLeft
        return copy(start = s, end = s + dir * width, bottom = bottom, top = top)
    }

    /** The same opening with the room taken to be on the other side of the wall. */
    fun flipped(): Opening = copy(
        start = end,
        end = start,
        wallStart = wallEnd,
        wallEnd = wallStart,
        swing = swing?.mirrored(),
    )

    /** True when both lie on the same wall line. */
    fun sameWallAs(other: Opening): Boolean =
        abs(direction cross other.direction) < 0.05f && abs((other.midpoint - start) dot direction.perp()) < 0.1f

    /** Re-expresses this opening, stored in frame [from], in frame [to] (same physical place). */
    fun realign(from: SiteAlignment, to: SiteAlignment): Opening {
        if (from == to) return this
        fun move(p: Vec2): Vec2 {
            val w = from.toWorld(p, 0f)
            return to.toPlan(w.x, w.z)
        }
        val dy = from.floorY - to.floorY
        return copy(
            start = move(start),
            end = move(end),
            wallStart = move(wallStart),
            wallEnd = move(wallEnd),
            bottom = if (type == OpeningType.WINDOW) (bottom + dy).coerceAtLeast(0f) else 0f,
            top = top + dy,
        )
    }

    companion object {
        const val LOW_CONFIDENCE = 0.7f
    }
}

/**
 * Openings the user reviewed (retyped / resized / added / deleted). Stored with the
 * alignment they were expressed in so they stay put even if a later version computes a
 * slightly different site frame for the same scan.
 */
@Serializable
data class OpeningEdits(val alignment: SiteAlignment, val openings: List<Opening>) {
    fun applyTo(plan: FloorPlan): FloorPlan =
        plan.copy(openings = openings.map { it.realign(alignment, plan.alignment) })
}

/** Drawing tags: D1, D2… for doors, W1… for windows, O1… for openings, in list order. */
object OpeningTags {
    fun assign(openings: List<Opening>): Map<String, String> {
        var doors = 0
        var windows = 0
        var others = 0
        return openings.associate {
            it.id to when (it.type) {
                OpeningType.DOOR -> "D${++doors}"
                OpeningType.WINDOW -> "W${++windows}"
                OpeningType.OPENING -> "O${++others}"
            }
        }
    }
}
