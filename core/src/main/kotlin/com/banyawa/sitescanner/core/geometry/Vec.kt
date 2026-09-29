package com.banyawa.sitescanner.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length() = sqrt(this dot this)
    fun distanceTo(o: Vec3) = (this - o).length()

    fun normalized(): Vec3 {
        val l = length()
        return if (l > 0f) this * (1f / l) else this
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)

        fun lerp(a: Vec3, b: Vec3, t: Float) = a + (b - a) * t
    }
}

@Serializable
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)

    infix fun dot(o: Vec2) = x * o.x + y * o.y

    /** Z component of the 3D cross product; positive when [o] is counter-clockwise from this. */
    infix fun cross(o: Vec2) = x * o.y - y * o.x

    fun length() = sqrt(this dot this)
    fun distanceTo(o: Vec2) = (this - o).length()

    fun normalized(): Vec2 {
        val l = length()
        return if (l > 0f) this * (1f / l) else this
    }

    /** Counter-clockwise perpendicular. */
    fun perp() = Vec2(-y, x)

    fun rotated(angleRad: Float): Vec2 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    companion object {
        val ZERO = Vec2(0f, 0f)

        fun lerp(a: Vec2, b: Vec2, t: Float) = a + (b - a) * t
    }
}

data class Bounds3(val min: Vec3, val max: Vec3) {
    val center get() = (min + max) * 0.5f
    val size get() = max - min
    val diagonal get() = size.length()
}

data class Bounds2(val min: Vec2, val max: Vec2) {
    val center get() = (min + max) * 0.5f
    val width get() = max.x - min.x
    val height get() = max.y - min.y

    fun union(o: Bounds2) = Bounds2(
        Vec2(minOf(min.x, o.min.x), minOf(min.y, o.min.y)),
        Vec2(maxOf(max.x, o.max.x), maxOf(max.y, o.max.y)),
    )

    companion object {
        fun of(points: Iterable<Vec2>): Bounds2? {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var any = false
            for (p in points) {
                any = true
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return if (any) Bounds2(Vec2(minX, minY), Vec2(maxX, maxY)) else null
        }
    }
}
