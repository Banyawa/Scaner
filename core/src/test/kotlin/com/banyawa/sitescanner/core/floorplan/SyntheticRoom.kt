package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.geometry.Vec2
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** Generates ARCore-frame point clouds of simple rooms for tests. */
object SyntheticRoom {
    /** A hole in the wall from [from] to [to] metres along edge [edge], below [topM]. */
    data class Opening(val edge: Int, val from: Float, val to: Float, val topM: Float = 2.0f)

    /**
     * @param outline room corners in local plan metres, counter-clockwise.
     * @param yawDeg rotation of the room in the plan.
     */
    fun polygon(
        outline: List<Vec2>,
        height: Float = 2.6f,
        yawDeg: Float = 0f,
        floorY: Float = -1.4f,
        offset: Vec2 = Vec2(0.7f, -2.3f),
        openings: List<Opening> = emptyList(),
        spacing: Float = 0.01f,
        noise: Float = 0.003f,
        seed: Long = 7,
    ): PointCloud {
        val rnd = Random(seed)
        val xyz = ArrayList<Float>()
        val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
        val c = cos(yaw)
        val s = sin(yaw)

        fun emit(local: Vec2, h: Float) {
            val u = local.x * c - local.y * s + offset.x + rnd.nextGaussian().toFloat() * noise
            val v = local.x * s + local.y * c + offset.y + rnd.nextGaussian().toFloat() * noise
            xyz += u
            xyz += floorY + h + rnd.nextGaussian().toFloat() * noise
            xyz += -v
        }

        // Walls
        for (i in outline.indices) {
            val a = outline[i]
            val b = outline[(i + 1) % outline.size]
            val len = a.distanceTo(b)
            val steps = (len / spacing).toInt()
            for (k in 0..steps) {
                val t = k * spacing
                val p = Vec2.lerp(a, b, t / len)
                var h = 0f
                while (h <= height) {
                    val inOpening = openings.any { it.edge == i && t >= it.from && t <= it.to && h < it.topM }
                    if (!inOpening) emit(p, h)
                    h += spacing * 2
                }
            }
        }

        // Floor and ceiling over the outline's bounding box, clipped to the polygon.
        val minX = outline.minOf { it.x }
        val maxX = outline.maxOf { it.x }
        val minY = outline.minOf { it.y }
        val maxY = outline.maxOf { it.y }
        var x = minX
        while (x <= maxX) {
            var y = minY
            while (y <= maxY) {
                val p = Vec2(x, y)
                if (inside(p, outline)) {
                    emit(p, 0f)
                    emit(p, height)
                }
                y += spacing
            }
            x += spacing
        }
        val arr = xyz.toFloatArray()
        return PointCloud(arr, ByteArray(arr.size) { 0x80.toByte() })
    }

    fun rectangle(width: Float, depth: Float, yawDeg: Float = 0f, openings: List<Opening> = emptyList(), height: Float = 2.6f) =
        polygon(
            listOf(Vec2(0f, 0f), Vec2(width, 0f), Vec2(width, depth), Vec2(0f, depth)),
            height = height,
            yawDeg = yawDeg,
            openings = openings,
        )

    private fun inside(p: Vec2, poly: List<Vec2>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}
