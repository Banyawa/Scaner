package com.banyawa.sitescanner.core.floorplan

import com.banyawa.sitescanner.core.pointcloud.PointCloud
import kotlin.math.max

/**
 * Finds the floor and ceiling heights from the vertical distribution of points:
 * horizontal surfaces show up as sharp peaks in a height histogram, walls as a flat
 * baseline.
 */
object LevelEstimator {
    data class Levels(val floorY: Float, val ceilingY: Float?)

    fun estimate(cloud: PointCloud, binSize: Float = 0.02f, minCeilingHeight: Float = 1.5f): Levels? {
        val n = cloud.size
        if (n == 0) return null
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val y = cloud.xyz[i * 3 + 1]
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val bins = ((maxY - minY) / binSize).toInt().coerceIn(0, MAX_BINS) + 1
        val counts = IntArray(bins)
        val sums = DoubleArray(bins)
        for (i in 0 until n) {
            val y = cloud.xyz[i * 3 + 1]
            val b = ((y - minY) / binSize).toInt().coerceIn(0, bins - 1)
            counts[b]++
            sums[b] += y.toDouble()
        }
        val smooth = IntArray(bins) { i ->
            counts[i] + (if (i > 0) counts[i - 1] else 0) + (if (i < bins - 1) counts[i + 1] else 0)
        }
        val median = smooth.copyOf().also { it.sort() }[bins / 2]
        val maxCount = smooth.max()
        val threshold = max(median * 3, (maxCount * 0.15f).toInt()).coerceAtLeast(10)

        val peaks = ArrayList<Int>()
        for (i in 0 until bins) {
            val v = smooth[i]
            if (v < threshold) continue
            val left = if (i > 0) smooth[i - 1] else -1
            val right = if (i < bins - 1) smooth[i + 1] else -1
            if (v >= left && v > right) peaks.add(i)
        }

        fun refined(bin: Int): Float {
            var c = 0
            var s = 0.0
            for (b in (bin - 1)..(bin + 1)) {
                if (b !in 0 until bins) continue
                c += counts[b]
                s += sums[b]
            }
            return (s / c).toFloat()
        }

        val floorY = if (peaks.isNotEmpty()) refined(peaks.first()) else percentile(cloud, 0.01f)
        val ceilingY = peaks.lastOrNull()
            ?.let { refined(it) }
            ?.takeIf { it > floorY + minCeilingHeight }
        return Levels(floorY, ceilingY)
    }

    private fun percentile(cloud: PointCloud, p: Float): Float {
        val ys = FloatArray(cloud.size) { cloud.xyz[it * 3 + 1] }
        ys.sort()
        return ys[(ys.size * p).toInt().coerceIn(0, ys.size - 1)]
    }

    private const val MAX_BINS = 10_000
}
