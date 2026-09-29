package com.banyawa.sitescanner.core.pointcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxelPointCloudTest {
    @Test
    fun samplesInOneVoxelAreFusedWithWeights() {
        val cloud = VoxelPointCloud(voxelSize = 0.1f)
        cloud.accept(0.01f, 0.01f, 0.01f, 0xFF0000, 1f)
        cloud.accept(0.05f, 0.05f, 0.05f, 0x0000FF, 3f)
        assertEquals(1, cloud.size)
        val pc = cloud.toPointCloud()
        assertEquals(1, pc.size)
        assertEquals((0.01f + 0.05f * 3) / 4, pc.xyz[0], 1e-6f)
        assertEquals(64, pc.rgb[0].toInt() and 0xFF) // 255 * 1/4
        assertEquals(191, pc.rgb[2].toInt() and 0xFF) // 255 * 3/4
    }

    @Test
    fun negativeCoordinatesUseSeparateVoxels() {
        val cloud = VoxelPointCloud(voxelSize = 0.1f)
        cloud.accept(-0.01f, 0f, 0f, 0, 1f)
        cloud.accept(0.01f, 0f, 0f, 0, 1f)
        assertEquals(2, cloud.size)
    }

    @Test
    fun growsPastInitialCapacityAndRespectsMaxVoxels() {
        val cloud = VoxelPointCloud(voxelSize = 0.01f, maxVoxels = 150_000)
        for (i in 0 until 200_000) cloud.accept(i * 0.01f + 0.005f, 0.005f, 0.005f, 0, 1f)
        assertEquals(150_000, cloud.size)
        assertTrue(cloud.isFull)
        // Existing voxels still accept samples when full.
        cloud.accept(0.005f, 0.005f, 0.005f, 0, 1f)
        assertEquals(2f, cloud.weightAt(0), 1e-6f)
        val pc = cloud.toPointCloud()
        assertEquals(150_000, pc.size)
        assertEquals(0.005f + 0.01f * 149_999, pc.xyz[(149_999) * 3], 1e-3f)
    }

    @Test
    fun minWeightAndMaxPointsFilterOutput() {
        val cloud = VoxelPointCloud(voxelSize = 0.1f)
        for (i in 0 until 100) cloud.accept(i * 0.1f + 0.05f, 0f, 0f, 0, if (i % 2 == 0) 1f else 0.2f)
        assertEquals(50, cloud.toPointCloud(minWeight = 0.5f).size)
        assertEquals(10, cloud.toPointCloud(maxPoints = 10).size)
    }

    @Test
    fun ignoresNonPositiveWeightsAndNaN() {
        val cloud = VoxelPointCloud()
        cloud.accept(0f, 0f, 0f, 0, 0f)
        cloud.accept(Float.NaN, 0f, 0f, 0, 1f)
        assertEquals(0, cloud.size)
    }
}
