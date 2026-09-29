package com.banyawa.sitescanner.core.capture

import com.banyawa.sitescanner.core.mesh.TriangleMesh
import com.banyawa.sitescanner.core.mesh.TsdfVolume
import com.banyawa.sitescanner.core.pointcloud.DepthFilter
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.pointcloud.DepthUnprojector
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.pointcloud.VoxelPointCloud
import kotlin.math.abs

/** Where a capture frame's depth comes from: the recording itself, or estimated from the images. */
interface DepthSource {
    /** Depth for the point cloud (raw depth with confidence where available), or null. */
    fun pointDepth(capture: Capture, index: Int, color: ColorLookup): DepthFrame?

    /** Dense depth for the surface model, or null. */
    fun surfaceDepth(capture: Capture, index: Int, color: ColorLookup): DepthFrame?
}

/** Decodes a frame's image once per frame however many callers want it. */
class ColorLookup(private val capture: Capture, private val decoder: ImageDecoder?) {
    private var index = -1
    private var image: com.banyawa.sitescanner.core.pointcloud.ColorImage? = null

    fun of(frame: CaptureFrame): com.banyawa.sitescanner.core.pointcloud.ColorImage? {
        val d = decoder ?: return null
        if (frame.index != index) {
            image = runCatching { d.decode(capture.imageBytes(frame)) }.getOrNull()
            index = frame.index
        }
        return image
    }
}

/** The depth ARCore recorded with each frame. */
object RecordedDepth : DepthSource {
    override fun pointDepth(capture: Capture, index: Int, color: ColorLookup): DepthFrame? {
        val frame = capture.frames[index]
        return capture.depthFrame(frame, null, smooth = false, color = color.of(frame))
    }

    override fun surfaceDepth(capture: Capture, index: Int, color: ColorLookup): DepthFrame? {
        val frame = capture.frames[index]
        return capture.depthFrame(frame, null, smooth = true, color = color.of(frame))
    }
}

data class ReconstructionOptions(
    /** Surface model voxel size. 2 cm keeps a room within a phone's memory. */
    val surfaceVoxelM: Float = TsdfVolume.DEFAULT_VOXEL_M,
    val surfaceBlocks: Int = 40_000,
    val pointVoxelM: Float = 0.01f,
    val maxPoints: Int = 3_000_000,
    val maxDepthM: Float = 4f,
    /** Raw depth confidence (0..255) below which a pixel is not used. */
    val minRawConfidence: Int = 100,
    /** Depth jumping by more than this share to a neighbouring pixel marks an edge's flying pixels. */
    val maxEdgeJump: Float = 0.05f,
    /** Voxels seen only once with low confidence are dropped from the point cloud. */
    val minPointWeight: Float = 0.5f,
    /** Fewer frames than this get every frame; more are thinned to about this many. */
    val maxFrames: Int = 600,
    /** Without recorded depth, estimate it from the images (slower; needs a decoder). */
    val estimateDepth: Boolean = true,
    /** Frames given estimated depth at most: stereo costs about half a second a frame on a phone. */
    val maxStereoFrames: Int = 150,
)

class ReconstructionResult(
    val cloud: PointCloud,
    val mesh: TriangleMesh,
    val framesUsed: Int,
    val depthFrames: Int,
    val floorY: Float?,
)

fun interface ProgressListener {
    /** [stage] is "fuse" while frames are fused, "mesh" while the surface is extracted. */
    fun onProgress(stage: String, done: Int, total: Int)
}

/**
 * Builds the point cloud and colour surface model from a recorded walk-through, with all
 * the time the live scan could not spare: every frame, both passes, and depth estimated
 * from the images where the phone recorded none.
 */
class Reconstructor(
    private val decoder: ImageDecoder?,
    private val options: ReconstructionOptions = ReconstructionOptions(),
    private val depthSource: DepthSource? = null,
) {
    /** The recorded depth when there is any; else depth estimated from the images. */
    fun depthSourceFor(capture: Capture): DepthSource =
        depthSource ?: when {
            capture.frames.any { it.hasDepth } -> RecordedDepth
            decoder != null && options.estimateDepth -> StereoDepthSource(decoder)
            else -> RecordedDepth
        }

    fun reconstruct(capture: Capture, progress: ProgressListener? = null, isCancelled: () -> Boolean = { false }): ReconstructionResult {
        val depthSource = depthSourceFor(capture)
        val points = VoxelPointCloud(options.pointVoxelM, options.maxPoints)
        val surface = TsdfVolume(voxelSize = options.surfaceVoxelM, maxBlocks = options.surfaceBlocks)
        val pointFilter = DepthFilter(minConfidence = options.minRawConfidence, maxDepthM = options.maxDepthM, maxEdgeJump = options.maxEdgeJump)
        val surfaceFilter = DepthFilter(minConfidence = 0, maxDepthM = options.maxDepthM, maxEdgeJump = options.maxEdgeJump)
        val color = ColorLookup(capture, decoder)

        val indices = thin(capture.frames.size, if (depthSource is StereoDepthSource) options.maxStereoFrames else options.maxFrames)
        var depthFrames = 0
        for ((n, i) in indices.withIndex()) {
            if (isCancelled()) break
            progress?.onProgress("fuse", n, indices.size)
            var any = false
            depthSource.pointDepth(capture, i, color)?.let {
                DepthUnprojector.unproject(it, pointFilter, points)
                any = true
            }
            depthSource.surfaceDepth(capture, i, color)?.let {
                surface.integrate(it, surfaceFilter)
                any = true
            }
            if (any) depthFrames++
        }
        progress?.onProgress("fuse", indices.size, indices.size)

        val filtered = points.toPointCloud(options.minPointWeight)
        // Low confidence everywhere (dim light, plain walls) would filter out nearly everything.
        val weighted = if (filtered.size < points.size * MIN_KEPT_FRACTION) points.toPointCloud() else filtered
        val cloud = consistentPoints(weighted, surface)

        progress?.onProgress("mesh", 0, 1)
        val mesh = if (isCancelled()) TriangleMesh.EMPTY else surface.extractMesh().withoutSmallParts()
        progress?.onProgress("mesh", 1, 1)
        return ReconstructionResult(cloud, mesh, indices.size, depthFrames, capture.manifest.floorY)
    }

    /**
     * Points the fused surface agrees with: a point sitting in space the other frames saw
     * through (a guessed depth on a plain wall, a tracking slip) is dropped, as is one
     * only a single frame ever saw. Points where nothing was fused at all are kept.
     */
    private fun consistentPoints(cloud: PointCloud, surface: TsdfVolume): PointCloud {
        if (cloud.size == 0 || surface.blockCount == 0) return cloud
        val keep = BooleanArray(cloud.size)
        val w = IntArray(1)
        var n = 0
        for (i in 0 until cloud.size) {
            val sdf = surface.sdfAt(cloud.xyz[i * 3], cloud.xyz[i * 3 + 1], cloud.xyz[i * 3 + 2], w)
            keep[i] = sdf.isNaN() || (w[0] >= MIN_CONSISTENT_WEIGHT && abs(sdf) <= MAX_SURFACE_DISTANCE)
            if (keep[i]) n++
        }
        if (n == cloud.size) return cloud
        val xyz = FloatArray(n * 3)
        val rgb = ByteArray(n * 3)
        var o = 0
        for (i in 0 until cloud.size) {
            if (!keep[i]) continue
            cloud.xyz.copyInto(xyz, o * 3, i * 3, i * 3 + 3)
            cloud.rgb.copyInto(rgb, o * 3, i * 3, i * 3 + 3)
            o++
        }
        return PointCloud(xyz, rgb)
    }

    /** Every frame up to [max], else evenly spaced ones. */
    private fun thin(count: Int, max: Int): IntArray =
        if (count <= max) IntArray(count) { it } else IntArray(max) { (it.toLong() * count / max).toInt() }

    private companion object {
        const val MIN_KEPT_FRACTION = 0.2f

        /** Fused weight a point's voxel needs (two close observations). */
        const val MIN_CONSISTENT_WEIGHT = 6

        /** Farthest a kept point may sit from the fused surface, as a share of the truncation. */
        const val MAX_SURFACE_DISTANCE = 0.6f
    }
}
