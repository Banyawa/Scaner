package com.banyawa.sitescanner.core.pointcloud

/** A colour image points and surfaces take their colours from. */
interface ColorImage {
    val width: Int
    val height: Int

    /** The pixel colour as 0xRRGGBB; [DEFAULT_RGB] outside the image. */
    fun rgbAt(px: Int, py: Int): Int

    companion object {
        const val DEFAULT_RGB = 0xB0B0B0
    }
}

/** Decoded RGB image, one 0xRRGGBB per pixel, row-major. */
class RgbImage(override val width: Int, override val height: Int, val pixels: IntArray) : ColorImage {
    init {
        require(pixels.size >= width * height) { "pixel buffer too small" }
    }

    override fun rgbAt(px: Int, py: Int): Int =
        if (px !in 0 until width || py !in 0 until height) ColorImage.DEFAULT_RGB else pixels[py * width + px] and 0xFFFFFF
}

/**
 * A copy of a YUV_420_888 camera image (the format of ARCore's CPU image).
 * Plane layouts follow android.media.Image: explicit row and pixel strides, chroma
 * subsampled 2x2.
 */
class YuvFrame(
    override val width: Int,
    override val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int,
) : ColorImage {
    /** Returns the pixel colour as 0xRRGGBB (BT.601 full range, as used by camera JPEG/JFIF). */
    override fun rgbAt(px: Int, py: Int): Int {
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
        const val DEFAULT_RGB = ColorImage.DEFAULT_RGB
    }
}
