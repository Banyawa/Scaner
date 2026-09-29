package com.banyawa.sitescanner.core.pointcloud

/**
 * A copy of a YUV_420_888 camera image (the format of ARCore's CPU image).
 * Plane layouts follow android.media.Image: explicit row and pixel strides, chroma
 * subsampled 2x2.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int,
) {
    /** Returns the pixel colour as 0xRRGGBB (BT.601 full range, as used by camera JPEG/JFIF). */
    fun rgbAt(px: Int, py: Int): Int {
        if (px !in 0 until width || py !in 0 until height) return DEFAULT_RGB
        val yi = py * yRowStride + px * yPixelStride
        val ci = (py shr 1) * uvRowStride + (px shr 1) * uvPixelStride
        if (yi >= y.size || ci >= u.size || ci >= v.size) return DEFAULT_RGB
        val yy = (y[yi].toInt() and 0xFF).toFloat()
        val uu = (u[ci].toInt() and 0xFF) - 128f
        val vv = (v[ci].toInt() and 0xFF) - 128f
        val r = clamp(yy + 1.402f * vv)
        val g = clamp(yy - 0.344136f * uu - 0.714136f * vv)
        val b = clamp(yy + 1.772f * uu)
        return (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> (v + 0.5f).toInt()
    }

    companion object {
        const val DEFAULT_RGB = 0xB0B0B0
    }
}
