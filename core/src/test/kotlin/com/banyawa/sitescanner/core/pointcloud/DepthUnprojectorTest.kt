package com.banyawa.sitescanner.core.pointcloud

import com.banyawa.sitescanner.core.geometry.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthUnprojectorTest {
    private val w = 16
    private val h = 12
    private val k = CameraIntrinsics(fx = 10f, fy = 10f, cx = 8f, cy = 6f, width = w, height = h)

    private fun flatWall(mm: Int) = ShortArray(w * h) { mm.toShort() }

    @Test
    fun wallInFrontOfIdentityCameraLandsAtNegativeZ() {
        val frame = DepthFrame(w, h, flatWall(2000), null, k, Mat4.identity())
        val pts = ArrayList<FloatArray>()
        val n = DepthUnprojector.unproject(frame, DepthFilter(minConfidence = 0), PointSink { x, y, z, _, _ ->
            pts.add(floatArrayOf(x, y, z))
        })
        assertEquals(w * h, n)
        pts.forEach { assertEquals(-2f, it[2], 1e-6f) }
        // Principal-point pixel maps onto the optical axis.
        val centre = pts[6 * w + 8]
        assertEquals(0f, centre[0], 1e-6f)
        assertEquals(0f, centre[1], 1e-6f)
        // Pixel (0, 0) is top-left: negative x, positive y (OpenGL camera, +Y up).
        val topLeft = pts[0]
        assertEquals(2f * (0 - 8) / 10f, topLeft[0], 1e-6f)
        assertEquals(2f * (6 - 0) / 10f, topLeft[1], 1e-6f)
    }

    @Test
    fun poseTranslationAndFiltersApply() {
        val pose = Mat4.identity().also { it[12] = 1f; it[13] = 1.5f; it[14] = -3f }
        val depth = flatWall(1000).also { it[0] = 0; it[1] = 9000.toShort() }
        val conf = ByteArray(w * h) { 255.toByte() }.also { it[2] = 10 }
        val frame = DepthFrame(w, h, depth, conf, k, pose)
        var count = 0
        DepthUnprojector.unproject(frame, DepthFilter(minConfidence = 100, maxDepthM = 4f), PointSink { _, _, z, _, wgt ->
            assertEquals(-4f, z, 1e-6f)
            assertEquals(1f, wgt, 1e-6f)
            count++
        })
        assertEquals(w * h - 3, count) // zero depth, too far, low confidence
    }

    @Test
    fun flyingPixelsAtDepthEdgesAreDropped() {
        // A box 1 m away covering the left half, the wall 3 m away on the right: the smeared
        // pixel between them (2 m) and both columns touching it are edges.
        val depth = ShortArray(w * h) { i -> if (i % w < 7) 1000 else 3000 }
        for (row in 0 until h) depth[row * w + 7] = 2000
        // A wall seen at an angle changes smoothly and is kept.
        val frame = DepthFrame(w, h, depth, null, k, Mat4.identity())
        val kept = HashSet<Int>()
        var i = 0
        DepthUnprojector.unproject(frame, DepthFilter(minConfidence = 0, maxEdgeJump = 0.05f), PointSink { x, _, z, _, _ ->
            kept += Math.round(x / -z * 10f + 8f)
            i++
        })
        assertEquals((0 until w).toSet() - setOf(6, 7, 8), kept)
        assertEquals((w - 3) * h, i)

        val slope = ShortArray(w * h) { idx -> (2000 + (idx % w) * 30).toShort() }
        val all = DepthUnprojector.unproject(
            DepthFrame(w, h, slope, null, k, Mat4.identity()),
            DepthFilter(minConfidence = 0, maxEdgeJump = 0.05f),
            PointSink { _, _, _, _, _ -> },
        )
        assertEquals(w * h, all)
    }

    @Test
    fun colourIsSampledThroughColourIntrinsics() {
        // Colour image twice the depth resolution, left half red, right half blue (in YUV).
        val cw = w * 2
        val ch = h * 2
        val y = ByteArray(cw * ch) { 76 }
        val u = ByteArray(cw * ch / 2) { 85 }
        val v = ByteArray(cw * ch / 2) { 255.toByte() }
        for (row in 0 until ch / 2) for (col in cw / 4 until cw / 2) {
            u[row * cw + col * 2] = 255.toByte()
            v[row * cw + col * 2] = 107
        }
        val yuv = YuvFrame(cw, ch, y, cw, 1, u, v, cw, 2)
        val frame = DepthFrame(w, h, flatWall(1500), null, k, Mat4.identity(), yuv, k.scaledTo(cw, ch))
        val colours = IntArray(w * h)
        var i = 0
        DepthUnprojector.unproject(frame, DepthFilter(minConfidence = 0), PointSink { _, _, _, rgb, _ -> colours[i++] = rgb })
        val left = colours[5 * w + 2]
        val right = colours[5 * w + 13]
        assertTrue("left should be red: ${left.toString(16)}", (left shr 16 and 0xFF) > 200 && (left and 0xFF) < 60)
        assertTrue("right should be blue: ${right.toString(16)}", (right and 0xFF) > 200)
    }

    @Test
    fun packingHonoursStrides() {
        val width = 3
        val height = 2
        val rowStride = 10 // bytes, with padding
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        for (r in 0 until height) for (c in 0 until width) buf.putShort(r * rowStride + c * 2, (r * 100 + c).toShort())
        val packed = ImagePacking.packShortPlane(buf, width, height, rowStride, 2)
        assertEquals(listOf<Short>(0, 1, 2, 100, 101, 102), packed.toList())

        val bytes = ByteBuffer.wrap(byteArrayOf(1, 9, 2, 9, 3, 9, 9, 9, 4, 9, 5, 9, 6, 9, 9, 9))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), ImagePacking.packBytePlane(bytes, 3, 2, 8, 2).toList())
        val tight = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 0, 4, 5, 6, 0))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), ImagePacking.packBytePlane(tight, 3, 2, 4, 1).toList())
    }

    @Test
    fun yuvGreyAndBounds() {
        val f = YuvFrame(2, 2, ByteArray(4) { 128.toByte() }, 2, 1, byteArrayOf(128.toByte()), byteArrayOf(128.toByte()), 1, 1)
        assertEquals(0x808080, f.rgbAt(1, 1))
        assertEquals(YuvFrame.DEFAULT_RGB, f.rgbAt(2, 0))
    }
}
