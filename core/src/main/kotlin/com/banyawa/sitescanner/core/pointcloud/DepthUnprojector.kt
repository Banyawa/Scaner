package com.banyawa.sitescanner.core.pointcloud

import kotlin.math.max

/**
 * One depth observation copied out of ARCore.
 *
 * @param depthMm row-major depth in millimetres (0 = no data), `width * height` entries.
 * @param confidence optional row-major confidence 0..255, same size as [depthMm].
 * @param intrinsics intrinsics of the depth image (the camera texture intrinsics scaled to it).
 * @param cameraToWorld column-major camera pose in the OpenGL convention (camera looks down -Z, +Y up).
 * @param color optional CPU camera image used to colour the points.
 * @param colorIntrinsics intrinsics of [color]; required when [color] is set.
 */
class DepthFrame(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
    val confidence: ByteArray?,
    val intrinsics: CameraIntrinsics,
    val cameraToWorld: FloatArray,
    val color: YuvFrame? = null,
    val colorIntrinsics: CameraIntrinsics? = null,
    val timestampNs: Long = 0L,
) {
    init {
        require(depthMm.size >= width * height) { "depth buffer too small" }
        require(confidence == null || confidence.size >= width * height) { "confidence buffer too small" }
        require(cameraToWorld.size == 16) { "pose must be a 4x4 matrix" }
    }
}

data class DepthFilter(
    /** Minimum ARCore raw-depth confidence, 0..255. */
    val minConfidence: Int = 100,
    val minDepthM: Float = 0.2f,
    /** Raw depth error grows roughly with the square of distance; far points hurt accuracy. */
    val maxDepthM: Float = 4.0f,
    /** Sample every n-th pixel in both directions. */
    val pixelStep: Int = 1,
)

object DepthUnprojector {
    /**
     * Back-projects every valid depth pixel into world space and feeds it to [sink].
     * Returns the number of points emitted.
     */
    fun unproject(frame: DepthFrame, filter: DepthFilter, sink: PointSink): Int {
        val k = frame.intrinsics
        val m = frame.cameraToWorld
        val color = frame.color
        val ck = frame.colorIntrinsics
        val colorize = color != null && ck != null
        val step = max(1, filter.pixelStep)
        val depth = frame.depthMm
        val conf = frame.confidence
        val invFx = 1f / k.fx
        val invFy = 1f / k.fy
        var emitted = 0

        var v = 0
        while (v < frame.height) {
            val row = v * frame.width
            // Normalised image-plane y (down positive) for this row.
            val ny = (v - k.cy) * invFy
            var u = 0
            while (u < frame.width) {
                val i = row + u
                val mm = depth[i].toInt() and 0xFFFF
                if (mm != 0) {
                    val d = mm * 0.001f
                    val c = if (conf != null) conf[i].toInt() and 0xFF else 255
                    if (d >= filter.minDepthM && d <= filter.maxDepthM && c >= filter.minConfidence) {
                        val nx = (u - k.cx) * invFx
                        // Camera space, OpenGL convention.
                        val xc = d * nx
                        val yc = -d * ny
                        val zc = -d
                        val wx = m[0] * xc + m[4] * yc + m[8] * zc + m[12]
                        val wy = m[1] * xc + m[5] * yc + m[9] * zc + m[13]
                        val wz = m[2] * xc + m[6] * yc + m[10] * zc + m[14]
                        val rgb = if (colorize) {
                            val px = (ck.fx * nx + ck.cx + 0.5f).toInt()
                            val py = (ck.fy * ny + ck.cy + 0.5f).toInt()
                            color.rgbAt(px, py)
                        } else {
                            YuvFrame.DEFAULT_RGB
                        }
                        sink.accept(wx, wy, wz, rgb, c / 255f)
                        emitted++
                    }
                }
                u += step
            }
            v += step
        }
        return emitted
    }
}
