package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import kotlinx.serialization.Serializable

@Serializable
enum class OpeningType {
    DOOR,
    WINDOW,

    /** Floor-to-ceiling opening without a head (passage, archway). */
    OPENING,
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
    /** 0..1; below ~0.7 the detection should be checked on site. */
    val confidence: Float = 1f,
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
}

/**
 * Openings the user reviewed (retyped / deleted). Stored with the alignment they were
 * expressed in so they stay put even if a later version computes a slightly different
 * site frame for the same scan.
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
