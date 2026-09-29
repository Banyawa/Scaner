package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Bounds2
import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.geometry.Vec3
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maps ARCore world coordinates (metres, +Y up, arbitrary heading) to the "site frame"
 * used by every export: +Z up, floor at Z = 0 and the dominant walls parallel to the
 * X / Y axes, which is what a drafter expects when the file is opened in CAD.
 *
 * Plan coordinates are (x, -z) of the ARCore world rotated by -[yawRad].
 */
@Serializable
data class SiteAlignment(val yawRad: Float = 0f, val floorY: Float = 0f) {
    @Transient private val c = cos(-yawRad)
    @Transient private val s = sin(-yawRad)

    fun toPlan(x: Float, z: Float): Vec2 {
        val u = x
        val v = -z
        return Vec2(u * c - v * s, u * s + v * c)
    }

    fun toSite(p: Vec3): Vec3 {
        val q = toPlan(p.x, p.z)
        return Vec3(q.x, q.y, p.y - floorY)
    }

    /** Allocation-free variant writing x, y, z to [out] at [offset]. */
    fun toSite(x: Float, y: Float, z: Float, out: FloatArray, offset: Int) {
        val u = x
        val v = -z
        out[offset] = u * c - v * s
        out[offset + 1] = u * s + v * c
        out[offset + 2] = y - floorY
    }

    companion object {
        val IDENTITY = SiteAlignment()
    }
}

@Serializable
data class WallSegment(val start: Vec2, val end: Vec2, val support: Int = 0) {
    val length: Float get() = start.distanceTo(end)
    val direction: Vec2 get() = (end - start).normalized()
    val midpoint: Vec2 get() = Vec2.lerp(start, end, 0.5f)
}

/** Walls are in plan coordinates (metres) of [alignment]. */
@Serializable
data class FloorPlan(
    val walls: List<WallSegment>,
    val alignment: SiteAlignment,
    val ceilingY: Float? = null,
) {
    val floorY: Float get() = alignment.floorY

    /** Floor-to-ceiling height in metres when a ceiling was observed. */
    val roomHeight: Float? get() = ceilingY?.let { it - alignment.floorY }

    val totalWallLength: Float get() = walls.fold(0f) { acc, w -> acc + w.length }

    fun bounds(): Bounds2? = Bounds2.of(walls.flatMap { listOf(it.start, it.end) })
}

/**
 * Result of floor-plan extraction. [slice] holds the occupied cells of the horizontal
 * slice as (x, y) pairs in plan coordinates, useful as a tracing underlay in CAD.
 */
class FloorPlanResult(val plan: FloorPlan, val slice: FloatArray) {
    val sliceSize: Int get() = slice.size / 2
}
