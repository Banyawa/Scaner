package com.banyawa.sitescanner.core.capture

import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.stereo.GrayImage
import com.banyawa.sitescanner.core.stereo.PlaneSweepStereo
import com.banyawa.sitescanner.core.stereo.StereoParams
import com.banyawa.sitescanner.core.stereo.StereoView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Depth estimated from the recorded images themselves (plane-sweep stereo against
 * nearby frames), for phones without a depth sensor. Each frame is matched against a few
 * neighbours a hand's width to half a metre away that look roughly the same way; the
 * result serves both the point cloud and the surface model.
 */
class StereoDepthSource(
    private val decoder: ImageDecoder,
    private val downscale: Int = 4,
    private val params: StereoParams = StereoParams(),
    private val neighbours: Int = 4,
) : DepthSource {
    private val grays = HashMap<Int, GrayImage>()
    private var lastIndex = -1
    private var lastResult: DepthFrame? = null

    override fun pointDepth(capture: Capture, index: Int, color: ColorLookup): DepthFrame? = estimate(capture, index, color)

    override fun surfaceDepth(capture: Capture, index: Int, color: ColorLookup): DepthFrame? = estimate(capture, index, color)

    private fun estimate(capture: Capture, index: Int, color: ColorLookup): DepthFrame? {
        if (index == lastIndex) return lastResult
        lastIndex = index
        val frame = capture.frames[index]
        val reference = view(capture, frame) ?: return null.also { lastResult = null }
        val others = pickNeighbours(capture, index).mapNotNull { view(capture, capture.frames[it]) }
        if (others.size < 2) return null.also { lastResult = null }
        val result = PlaneSweepStereo.estimate(reference, others, params)
        val rgb = color.of(frame)
        lastResult = DepthFrame(
            width = result.width,
            height = result.height,
            depthMm = result.depthMm,
            confidence = result.confidence,
            intrinsics = result.intrinsics,
            cameraToWorld = frame.pose(),
            color = rgb,
            colorIntrinsics = if (rgb != null) frame.imageIntrinsics.toCore().scaledTo(rgb.width, rgb.height) else null,
            timestampNs = frame.timestampNs,
        )
        // Frames well behind the current one will not be neighbours again.
        grays.keys.filter { it < index - WINDOW }.forEach { grays.remove(it) }
        return lastResult
    }

    private fun view(capture: Capture, frame: CaptureFrame): StereoView? {
        val gray = grays.getOrPut(frame.index) {
            val image = runCatching { decoder.decode(capture.imageBytes(frame)) }.getOrNull() ?: return null
            GrayImage.of(image, downscale)
        }
        return StereoView(gray, frame.imageIntrinsics.toCore().scaledTo(gray.width, gray.height), frame.pose())
    }

    /** Frames around [index] with a usable baseline and a similar viewing direction, nearest first. */
    private fun pickNeighbours(capture: Capture, index: Int): List<Int> {
        val ref = capture.frames[index].pose()
        val picked = ArrayList<Int>()
        var step = 1
        while (picked.size < neighbours && step <= WINDOW) {
            for (j in intArrayOf(index - step, index + step)) {
                if (j !in capture.frames.indices || picked.size >= neighbours) continue
                val other = capture.frames[j].pose()
                val baseline = distance(ref, other)
                if (baseline < MIN_BASELINE_M || baseline > MAX_BASELINE_M) continue
                if (forwardDot(ref, other) < MIN_FORWARD_DOT) continue
                picked += j
            }
            step++
        }
        return picked
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[12] - b[12]
        val dy = a[13] - b[13]
        val dz = a[14] - b[14]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Cosine between the two cameras' viewing directions (their -Z axes). */
    private fun forwardDot(a: FloatArray, b: FloatArray): Float = abs(a[8] * b[8] + a[9] * b[9] + a[10] * b[10])

    private companion object {
        const val WINDOW = 30
        const val MIN_BASELINE_M = 0.04f
        const val MAX_BASELINE_M = 0.5f
        const val MIN_FORWARD_DOT = 0.9f
    }
}
