package com.banyawa.sitescanner.core.pointcloud

/**
 * Thread-safe accumulation of depth frames into a [VoxelPointCloud].
 * Integration usually runs on a worker thread while the renderer takes snapshots.
 */
class ScanIntegrator(
    voxelSize: Float = 0.01f,
    maxVoxels: Int = 3_000_000,
    @Volatile var filter: DepthFilter = DepthFilter(),
) {
    private val lock = Any()
    private val cloud = VoxelPointCloud(voxelSize, maxVoxels)

    @Volatile
    var framesIntegrated = 0
        private set

    @Volatile
    var voxelCount = 0
        private set

    val isFull get() = synchronized(lock) { cloud.isFull }

    /** Fuses one depth frame; returns the number of accepted depth samples. */
    fun integrate(frame: DepthFrame): Int = synchronized(lock) {
        val n = DepthUnprojector.unproject(frame, filter, cloud)
        framesIntegrated++
        voxelCount = cloud.size
        n
    }

    /**
     * Adds sparse world-space points (e.g. ARCore feature points on devices without the
     * Depth API). [xyzc] holds x, y, z, confidence(0..1) quadruples.
     */
    fun integrateWorldPoints(xyzc: FloatArray, count: Int, rgb: Int = YuvFrame.DEFAULT_RGB, minConfidence: Float = 0.2f) =
        synchronized(lock) {
            for (i in 0 until count) {
                val c = xyzc[i * 4 + 3]
                if (c < minConfidence) continue
                cloud.accept(xyzc[i * 4], xyzc[i * 4 + 1], xyzc[i * 4 + 2], rgb, c)
            }
            framesIntegrated++
            voxelCount = cloud.size
        }

    fun snapshot(minWeight: Float = 0f, maxPoints: Int = Int.MAX_VALUE): PointCloud =
        synchronized(lock) { cloud.toPointCloud(minWeight, maxPoints) }

    fun clear() = synchronized(lock) {
        cloud.clear()
        framesIntegrated = 0
        voxelCount = 0
    }
}
