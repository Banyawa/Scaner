package com.banyawa.sitescanner.core.capture

import com.banyawa.sitescanner.core.mesh.TriangleMesh
import com.banyawa.sitescanner.core.mesh.TsdfVolume
import com.banyawa.sitescanner.core.pointcloud.DepthFilter
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.pointcloud.DepthUnprojector
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.pointcloud.VoxelPointCloud

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
    val minRawConfidence: Int = 60,
    /** Depth jumping by more than this share to a neighbouring pixel marks an edge's flying pixels. */
    val maxEdgeJump: Float = 0.05f,
    /** Voxels seen only once with low confidence are dropped from the point cloud. */
    val minPointWeight: Float = 0.5f,
    /** Fewer frames than this get every frame; more are thinned to about this many. */
    val maxFrames: Int = 600,
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
    private val depthSource: DepthSource = RecordedDepth,
) {
    fun reconstruct(capture: Capture, progress: ProgressListener? = null, isCancelled: () -> Boolean = { false }): ReconstructionResult {
        val points = VoxelPointCloud(options.pointVoxelM, options.maxPoints)
        val surface = TsdfVolume(voxelSize = options.surfaceVoxelM, maxBlocks = options.surfaceBlocks)
        val pointFilter = DepthFilter(minConfidence = options.minRawConfidence, maxDepthM = options.maxDepthM, maxEdgeJump = options.maxEdgeJump)
        val surfaceFilter = DepthFilter(minConfidence = 0, maxDepthM = options.maxDepthM, maxEdgeJump = options.maxEdgeJump)
        val color = ColorLookup(capture, decoder)

        val indices = thin(capture.frames.size, options.maxFrames)
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
        val cloud = if (filtered.size < points.size * MIN_KEPT_FRACTION) points.toPointCloud() else filtered

        progress?.onProgress("mesh", 0, 1)
        val mesh = if (isCancelled()) TriangleMesh.EMPTY else surface.extractMesh().withoutSmallParts()
        progress?.onProgress("mesh", 1, 1)
        return ReconstructionResult(cloud, mesh, indices.size, depthFrames, capture.manifest.floorY)
    }

    /** Every frame up to [max], else evenly spaced ones. */
    private fun thin(count: Int, max: Int): IntArray =
        if (count <= max) IntArray(count) { it } else IntArray(max) { (it.toLong() * count / max).toInt() }

    private companion object {
        const val MIN_KEPT_FRACTION = 0.2f
    }
}
