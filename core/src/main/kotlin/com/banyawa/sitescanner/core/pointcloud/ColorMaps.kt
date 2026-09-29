package com.banyawa.sitescanner.core.pointcloud

object ColorMaps {
    /**
     * Colours points by height (the Y axis in ARCore world space) using a blue → green →
     * yellow → red ramp between the 2nd and 98th percentile, so a few outliers do not
     * flatten the gradient.
     */
    fun byHeight(cloud: PointCloud): ByteArray {
        val n = cloud.size
        val out = ByteArray(n * 3)
        if (n == 0) return out
        val ys = FloatArray(n) { cloud.xyz[it * 3 + 1] }
        val sorted = ys.copyOf().also { it.sort() }
        val lo = sorted[(n * 0.02f).toInt().coerceIn(0, n - 1)]
        val hi = sorted[(n * 0.98f).toInt().coerceIn(0, n - 1)]
        val range = (hi - lo).takeIf { it > 1e-4f } ?: 1f
        for (i in 0 until n) {
            val t = ((ys[i] - lo) / range).coerceIn(0f, 1f)
            val rgb = ramp(t)
            out[i * 3] = (rgb shr 16).toByte()
            out[i * 3 + 1] = (rgb shr 8).toByte()
            out[i * 3 + 2] = rgb.toByte()
        }
        return out
    }

    /** 0xRRGGBB for t in 0..1. */
    fun ramp(t: Float): Int {
        val stops = RAMP
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        val f = x - i
        val a = stops[i]
        val b = stops[i + 1]
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * f + 0.5f).toInt()
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val RAMP = intArrayOf(0x2B59C3, 0x1FA187, 0x9BC53D, 0xF2C14E, 0xE4572E)
}
