package com.banyawa.sitescanner.core.stereo

import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.ColorImage

/**
 * Single-channel float image for stereo matching: luminance 0..255, row-major.
 *
 * Matching only needs intensity, and running it on a small grey copy of the camera image is
 * what keeps a plane sweep affordable on a phone: its cost grows with pixels × planes × views.
 */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width >= 2 && height >= 2) { "image must be at least 2x2" }
        require(pixels.size >= width * height) { "pixel buffer too small" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    /**
     * Bilinear sample at ([x], [y]) in pixel coordinates, where integer coordinates address
     * pixels themselves (the same way `DepthUnprojector` pairs pixel indices with intrinsics).
     *
     * Returns [Float.NaN] when the position lies outside `[0, width-1] × [0, height-1]` (or
     * is NaN itself). NaN rather than a clamped edge value because a warped patch that leaves
     * the image has not been seen by that camera: any sum that includes the sample becomes
     * NaN, so the whole patch drops out of matching without a separate validity mask.
     */
    fun sample(x: Float, y: Float): Float {
        // Negated test so NaN coordinates are rejected too.
        if (!(x >= 0f && y >= 0f && x <= width - 1 && y <= height - 1)) return Float.NaN
        var x0 = x.toInt() // truncation is floor for non-negative values
        var y0 = y.toInt()
        // On the last row/column step back one pixel so both taps exist; the weight becomes 1.
        if (x0 > width - 2) x0 = width - 2
        if (y0 > height - 2) y0 = height - 2
        val fx = x - x0
        val fy = y - y0
        val p = pixels
        val i = y0 * width + x0
        val top = p[i] + (p[i + 1] - p[i]) * fx
        val bottom = p[i + width] + (p[i + width + 1] - p[i + width]) * fx
        return top + (bottom - top) * fy
    }

    companion object {
        /**
         * Luminance (BT.601 weights: 0.299 R + 0.587 G + 0.114 B) of [image], box-filtered down
         * by the integer factor [downscale]. Averaging the whole block, not picking one pixel
         * of it, removes the aliasing and sensor noise that would otherwise make patches of
         * the same surface disagree between views. Trailing rows/columns that do not fill a
         * whole block are dropped; pair the result with [scaleIntrinsics].
         */
        fun of(image: ColorImage, downscale: Int): GrayImage {
            require(downscale >= 1) { "downscale must be at least 1" }
            val w = image.width / downscale
            val h = image.height / downscale
            val out = FloatArray(w * h)
            val norm = 1f / (downscale * downscale)
            for (y in 0 until h) {
                val sy = y * downscale
                for (x in 0 until w) {
                    val sx = x * downscale
                    var sum = 0f
                    for (dy in 0 until downscale) {
                        for (dx in 0 until downscale) {
                            val rgb = image.rgbAt(sx + dx, sy + dy)
                            sum += 0.299f * ((rgb shr 16) and 0xFF) +
                                0.587f * ((rgb shr 8) and 0xFF) +
                                0.114f * (rgb and 0xFF)
                        }
                    }
                    out[y * w + x] = sum * norm
                }
            }
            return GrayImage(w, h, out)
        }

        /**
         * Intrinsics of an image produced by [of] with the same [downscale] from an image
         * with intrinsics [k]. Scales by exactly 1/[downscale] even when the source size is
         * not a multiple of it (unlike [CameraIntrinsics.scaledTo], which would stretch by the
         * cropped size). Like `scaledTo`, it ignores the sub-pixel shift of block centres; the
         * shift is the same for every view, so it acts as a tiny common rotation and does not
         * bias depth.
         */
        fun scaleIntrinsics(k: CameraIntrinsics, downscale: Int): CameraIntrinsics {
            require(downscale >= 1) { "downscale must be at least 1" }
            val s = 1f / downscale
            return CameraIntrinsics(k.fx * s, k.fy * s, k.cx * s, k.cy * s, k.width / downscale, k.height / downscale)
        }
    }
}
