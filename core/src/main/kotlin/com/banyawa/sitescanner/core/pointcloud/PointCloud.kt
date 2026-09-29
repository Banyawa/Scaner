package com.banyawa.sitescanner.core.pointcloud

import com.banyawa.sitescanner.core.geometry.Bounds3
import com.banyawa.sitescanner.core.geometry.Vec3

/**
 * Immutable-by-convention colored point cloud.
 * [xyz] holds `size * 3` floats (metres), [rgb] holds `size * 3` unsigned bytes.
 */
class PointCloud(val xyz: FloatArray, val rgb: ByteArray) {
    init {
        require(xyz.size % 3 == 0) { "xyz length must be a multiple of 3" }
        require(rgb.size == xyz.size) { "rgb must have one byte per coordinate" }
    }

    val size: Int get() = xyz.size / 3

    fun point(i: Int) = Vec3(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])

    fun color(i: Int): Int =
        ((rgb[i * 3].toInt() and 0xFF) shl 16) or
            ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or
            (rgb[i * 3 + 2].toInt() and 0xFF)

    fun bounds(): Bounds3? {
        if (size == 0) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (i in 0 until size) {
            val x = xyz[i * 3]
            val y = xyz[i * 3 + 1]
            val z = xyz[i * 3 + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
        }
        return Bounds3(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
    }

    /** Keeps every n-th point so that at most [maxPoints] remain. */
    fun decimated(maxPoints: Int): PointCloud {
        if (size <= maxPoints || maxPoints <= 0) return this
        val step = (size + maxPoints - 1) / maxPoints
        val n = (size + step - 1) / step
        val outXyz = FloatArray(n * 3)
        val outRgb = ByteArray(n * 3)
        var o = 0
        var i = 0
        while (i < size && o < n) {
            System.arraycopy(xyz, i * 3, outXyz, o * 3, 3)
            System.arraycopy(rgb, i * 3, outRgb, o * 3, 3)
            o++
            i += step
        }
        return PointCloud(outXyz, outRgb)
    }

    fun withColors(newRgb: ByteArray) = PointCloud(xyz, newRgb)

    companion object {
        val EMPTY = PointCloud(FloatArray(0), ByteArray(0))
    }
}

/** Receives un-projected world points. */
fun interface PointSink {
    fun accept(x: Float, y: Float, z: Float, rgb: Int, weight: Float)
}
