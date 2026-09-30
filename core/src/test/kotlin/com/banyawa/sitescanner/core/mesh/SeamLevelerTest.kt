package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.pointcloud.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A 1 × 1 m quad, gridded, cut into vertical strips of charts laid side by side in a small
 * atlas, every chart filled with its own grey: the seams between the strips are pure
 * exposure differences, which the leveler must remove without touching what is not a chart.
 */
class SeamLevelerTest {
    private class Strips(val textured: TexturedMesh, val chartX: IntArray)

    /**
     * [levels] charts left to right, each [CHART] pixels wide in a [size]² atlas with a
     * gutter of [GUTTER] around it, [rows] quads tall; the seam vertices are duplicated per
     * chart. A solid patch triangle at the bottom (all three uvs on one texel of colour
     * [patch]) sits off the charts.
     */
    private fun strips(levels: IntArray, rows: Int = 6, cols: Int = 4, size: Int = 128, patch: Int = 200, detailed: Int = -1): Strips {
        val charts = levels.size
        require(charts * (CHART + 2 * GUTTER) <= size)
        val positions = ArrayList<Float>()
        val uv = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val stripW = 1f / charts
        val chartX = IntArray(charts) { GUTTER + it * (CHART + 2 * GUTTER) }
        fun vertex(x: Float, y: Float, chart: Int): Int {
            positions += x; positions += y; positions += 0f
            val u = chartX[chart] + (x - chart * stripW) / stripW * CHART
            val v = GUTTER + y * CHART
            uv += u / size; uv += v / size
            return positions.size / 3 - 1
        }
        for (c in 0 until charts) {
            // The detailed chart is gridded 16 times finer across (the seam's vertices still meet).
            val nr = rows
            val nc = if (c == detailed) cols * 16 else cols
            val base = positions.size / 3
            for (j in 0..nr) for (i in 0..nc) vertex(c * stripW + stripW * i / nc, j.toFloat() / nr, c)
            for (j in 0 until nr) for (i in 0 until nc) {
                val p00 = base + j * (nc + 1) + i
                indices += listOf(p00, p00 + 1, p00 + nc + 2, p00, p00 + nc + 2, p00 + nc + 1)
            }
        }
        // The patch: a triangle elsewhere on the surface, sharing a corner position with chart 0.
        val cell = ((size - 8) + 0.5f) / size
        for ((x, y) in listOf(0f to 0f, 0f to -0.3f, 0.3f to -0.3f)) {
            positions += x; positions += y; positions += 0f
            uv += cell; uv += cell
        }
        val n = positions.size / 3
        indices += listOf(n - 3, n - 2, n - 1)
        val pixels = IntArray(size * size) { 0x404040 }
        for (c in 0 until charts) {
            val grey = levels[c]
            for (y in 0 until CHART + 2 * GUTTER) for (x in 0 until CHART + 2 * GUTTER) pixels[y * size + chartX[c] - GUTTER + x] = grey * 0x010101
        }
        pixels[(size - 8) * size + size - 8] = patch * 0x010101
        val mesh = TriangleMesh(positions.toFloatArray(), ByteArray(n * 3), indices.toIntArray())
        return Strips(TexturedMesh(mesh, uv.toFloatArray(), RgbImage(size, size, pixels)), chartX)
    }

    private fun grey(atlas: RgbImage, x: Int, y: Int): Int = atlas.pixels[y * atlas.width + x] and 0xFF

    @Test
    fun levelsTwoChartsToTheirMean() {
        val s = strips(intArrayOf(100, 140))
        val atlas = s.textured.atlas
        val stats = SeamLeveler().level(s.textured)
        assertEquals(2, stats.charts)
        assertEquals(7, stats.seamPairs)
        assertEquals(14, stats.seamVertices)
        assertEquals(40f, stats.seamDifferenceBefore, 0.01f)
        assertTrue("seam after: ${stats.seamDifferenceAfter}", stats.seamDifferenceAfter < 1.5f)
        // Same-sized charts meet in the middle, in the interior and at the seam.
        for (c in 0..1) {
            val centre = grey(atlas, s.chartX[c] + CHART / 2, GUTTER + CHART / 2)
            assertTrue("chart $c centre $centre", abs(centre - 120) <= 2)
            val edge = grey(atlas, if (c == 0) s.chartX[0] + CHART - 1 else s.chartX[1], GUTTER + CHART / 2)
            assertTrue("chart $c seam edge $edge", abs(edge - 120) <= 2)
            // The gutter next to the chart follows it; beyond the gutter nothing changes.
            val gutter = grey(atlas, s.chartX[c] - 1, GUTTER + CHART / 2)
            assertTrue("chart $c gutter $gutter", abs(gutter - 120) <= 2)
        }
        assertEquals(0x40, grey(atlas, s.chartX[0] + CHART / 2, 3 * GUTTER + CHART))
        assertEquals(0x40, grey(atlas, s.chartX[1] + CHART + GUTTER + 1, GUTTER + CHART / 2))
        // The solid patch is not a chart: untouched.
        assertEquals(200, grey(atlas, atlas.width - 8, atlas.width - 8))
        assertTrue("corrections ${stats.maxCorrection}", stats.maxCorrection in 18..22)
    }

    @Test
    fun levelsAChainOfCharts() {
        val s = strips(intArrayOf(80, 120, 160, 100), cols = 3, size = 256)
        val stats = SeamLeveler().level(s.textured)
        assertEquals(4, stats.charts)
        assertEquals(3 * 7, stats.seamPairs)
        assertTrue("seam after: ${stats.seamDifferenceAfter}", stats.seamDifferenceAfter < 2f)
        val atlas = s.textured.atlas
        for (c in 0 until 4) {
            val centre = grey(atlas, s.chartX[c] + CHART / 2, GUTTER + CHART / 2)
            assertTrue("chart $c centre $centre", abs(centre - 115) <= 3)
        }
    }

    @Test
    fun aSmallChartAdaptsToALargeOne() {
        // Chart 0 has many more vertices: it keeps its exposure, chart 1 takes it on.
        val s = strips(intArrayOf(100, 140), detailed = 0)
        val stats = SeamLeveler().level(s.textured)
        assertTrue("seam after: ${stats.seamDifferenceAfter}", stats.seamDifferenceAfter < 2f)
        val atlas = s.textured.atlas
        val centre0 = grey(atlas, s.chartX[0] + CHART / 2, GUTTER + CHART / 2)
        val centre1 = grey(atlas, s.chartX[1] + CHART / 2, GUTTER + CHART / 2)
        assertTrue("big chart stays near 100: $centre0", abs(centre0 - 100) <= 6)
        assertTrue("small chart moves to it: $centre1", abs(centre1 - 100) <= 8)
    }

    @Test
    fun aMisalignedDetailDoesNotBendTheWholeSeam() {
        val s = strips(intArrayOf(100, 140), rows = 12, cols = 4)
        val atlas = s.textured.atlas
        // A dark spot on chart 1 at one seam vertex only.
        val spotY = GUTTER + CHART / 2
        for (y in spotY - 2..spotY + 2) for (x in s.chartX[1] until s.chartX[1] + 4) atlas.pixels[y * atlas.width + x] = 0x101010
        val stats = SeamLeveler().level(s.textured)
        // The rest of the seam still meets in the middle.
        val edge = grey(atlas, s.chartX[0] + CHART - 1, GUTTER + 2)
        assertTrue("seam edge away from the spot $edge", abs(edge - 120) <= 4)
        assertTrue("corrections stay small: ${stats.maxCorrection}", stats.maxCorrection < 45)
    }

    @Test
    fun nothingToLevelLeavesTheAtlasAlone() {
        val s = strips(intArrayOf(100, 100))
        val before = s.textured.atlas.pixels.copyOf()
        val stats = SeamLeveler().level(s.textured)
        assertEquals(7, stats.seamPairs)
        assertEquals(0f, stats.seamDifferenceBefore, 0.01f)
        assertTrue(before.contentEquals(s.textured.atlas.pixels))
        val one = strips(intArrayOf(100))
        assertEquals(0, SeamLeveler().level(one.textured).seamPairs)
    }

    private companion object {
        const val CHART = 40
        const val GUTTER = 4
    }
}
