package com.banyawa.sitescanner.core.stereo

import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayImageTest {
    @Test
    fun downscaleAveragesLuminanceOverBlocks() {
        // 5×5 image, factor 2: 2×2 blocks, the fifth column and row are dropped.
        val red = 0xFF0000
        val green = 0x00FF00
        val white = 0xFFFFFF
        val blue = 0x0000FF
        val pixels = intArrayOf(
            red, green, white, white, red,
            green, red, 0, 0, red,
            blue, blue, 0x404040, 0x404040, red,
            blue, blue, 0x404040, 0x404040, red,
            red, red, red, red, red,
        )
        val g = GrayImage.of(RgbImage(5, 5, pixels), 2)
        assertEquals(2, g.width)
        assertEquals(2, g.height)
        assertEquals((2 * 0.299f + 2 * 0.587f) * 255f / 4f, g[0, 0], 1e-3f)
        assertEquals(255f / 2f, g[1, 0], 1e-3f)
        assertEquals(0.114f * 255f, g[0, 1], 1e-3f)
        assertEquals(64f, g[1, 1], 1e-3f)
    }

    @Test
    fun bilinearSampleInsideAndNaNOutside() {
        val g = GrayImage(3, 2, floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f))
        assertEquals(0f, g.sample(0f, 0f), 1e-6f)
        assertEquals(5f, g.sample(0.5f, 0f), 1e-6f)
        assertEquals(20f, g.sample(0.5f, 0.5f), 1e-6f)
        // The last row and column are inside.
        assertEquals(50f, g.sample(2f, 1f), 1e-6f)
        assertEquals(45f, g.sample(1.5f, 1f), 1e-6f)
        assertTrue(g.sample(-0.01f, 0.5f).isNaN())
        assertTrue(g.sample(2.01f, 0.5f).isNaN())
        assertTrue(g.sample(1f, 1.01f).isNaN())
        assertTrue(g.sample(Float.NaN, 0f).isNaN())
    }

    @Test
    fun intrinsicsScaleExactlyByTheFactor() {
        val k = CameraIntrinsics(fx = 500f, fy = 510f, cx = 321f, cy = 239f, width = 641, height = 481)
        val s = GrayImage.scaleIntrinsics(k, 4)
        assertEquals(CameraIntrinsics(125f, 127.5f, 80.25f, 59.75f, 160, 120), s)
    }
}
