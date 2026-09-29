package com.banyawa.sitescanner.core.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriangleMeshTest {
    /** An n×n grid of quads at height [y], shifted by [x0]. */
    private fun grid(n: Int, x0: Float = 0f, y: Float = 0f): TriangleMesh {
        val positions = ArrayList<Float>()
        for (j in 0..n) for (i in 0..n) {
            positions += x0 + i * 0.1f
            positions += y
            positions += -j * 0.1f
        }
        val indices = ArrayList<Int>()
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i
            indices += listOf(a, a + 1, a + n + 2, a, a + n + 2, a + n + 1)
        }
        return TriangleMesh(positions.toFloatArray(), ByteArray(positions.size), indices.toIntArray())
    }

    private fun merge(a: TriangleMesh, b: TriangleMesh) = TriangleMesh(
        a.positions + b.positions,
        a.colors + b.colors,
        a.indices + b.indices.map { it + a.vertexCount },
    )

    @Test
    fun floatingSpecksAreRemoved() {
        val floor = grid(20)
        val speck = grid(2, x0 = 5f, y = 1f)
        val cleaned = merge(floor, speck).withoutSmallParts(minTriangles = 50)
        assertEquals(floor.triangleCount, cleaned.triangleCount)
        assertEquals(floor.vertexCount, cleaned.vertexCount)
        assertEquals(0f, cleaned.positions.filterIndexed { i, _ -> i % 3 == 1 }.max(), 0f)
    }

    @Test
    fun aSmallScanIsNotThrownAway() {
        val small = grid(3)
        assertEquals(small.triangleCount, small.withoutSmallParts(minTriangles = 200).triangleCount)
    }

    @Test
    fun smoothingIronsOutNoiseWithoutShrinking() {
        val noisy = grid(30)
        val rnd = java.util.Random(7)
        for (v in 0 until noisy.vertexCount) noisy.positions[v * 3 + 1] = (rnd.nextFloat() - 0.5f) * 0.04f
        fun spread(m: TriangleMesh) = (0 until m.vertexCount).map { m.positions[it * 3 + 1] }.let { ys -> ys.map { it * it }.average() }
        val smooth = noisy.smoothed(6)
        assertTrue("noise: ${spread(noisy)} -> ${spread(smooth)}", spread(smooth) < spread(noisy) / 4)
        assertEquals(noisy.triangleCount, smooth.triangleCount)
        val before = noisy.bounds()!!
        val after = smooth.bounds()!!
        assertEquals(before.size.x, after.size.x, before.size.x * 0.03f)
        assertEquals(before.size.z, after.size.z, before.size.z * 0.03f)
        assertArrayEquals(noisy.colors, smooth.colors)
    }

    @Test
    fun upFacingGridHasUpNormals() {
        // Quads wound counter-clockwise seen from above.
        val n = grid(4).normals()
        for (v in 0 until n.size / 3) assertEquals(1f, n[v * 3 + 1], 1e-4f)
    }
}
