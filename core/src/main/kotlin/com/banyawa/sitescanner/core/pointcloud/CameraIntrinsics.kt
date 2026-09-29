package com.banyawa.sitescanner.core.pointcloud

/** Pinhole intrinsics in pixels for an image of [width] x [height]. */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    /** Same camera expressed for an image resampled to [newWidth] x [newHeight]. */
    fun scaledTo(newWidth: Int, newHeight: Int): CameraIntrinsics {
        if (newWidth == width && newHeight == height) return this
        val sx = newWidth.toFloat() / width
        val sy = newHeight.toFloat() / height
        return CameraIntrinsics(fx * sx, fy * sy, cx * sx, cy * sy, newWidth, newHeight)
    }
}
