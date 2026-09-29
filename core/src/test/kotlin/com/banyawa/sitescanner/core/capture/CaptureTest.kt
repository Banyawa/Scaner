package com.banyawa.sitescanner.core.capture

import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class CaptureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val image = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val depth = CameraIntrinsics(125f, 125f, 80f, 60f, 160, 120)

    @Test
    fun framesSurviveWritingReadingAndZipping() {
        val dir = tmp.newFolder("cap")
        val writer = CaptureWriter(dir)
        val pose = FloatArray(16) { if (it % 5 == 0) 1f else 0f }.also { it[12] = 1.5f }
        val depthMm = ShortArray(160 * 120) { (1000 + it % 7).toShort() }
        val smooth = ShortArray(160 * 120) { 1200 }
        val conf = ByteArray(160 * 120) { 200.toByte() }
        writer.add(10L, pose, byteArrayOf(1, 2, 3), image, depthMm, depth, conf, rawDepth = true, smoothDepthMm = smooth)
        writer.add(20L, pose, byteArrayOf(4), image)
        writer.close(Manifest(device = "test phone", durationSec = 3, floorY = -1.2f, depthSupported = true))

        val cap = Capture.open(dir)
        assertEquals(2, cap.frames.size)
        assertEquals("test phone", cap.manifest.device)
        assertEquals(-1.2f, cap.manifest.floorY)
        val first = cap.frames[0]
        assertTrue(first.hasDepth && first.rawDepth)
        assertArrayEquals(pose, first.pose(), 0f)
        assertArrayEquals(depthMm, cap.depthMm(first))
        assertArrayEquals(smooth, cap.smoothDepthMm(first))
        assertArrayEquals(conf, cap.confidence(first))
        assertArrayEquals(byteArrayOf(1, 2, 3), cap.imageBytes(first))
        val points = cap.depthFrame(first, null)!!
        assertEquals(1000, points.depthMm[0].toInt())
        assertEquals(depth, points.intrinsics)
        val surface = cap.depthFrame(first, null, smooth = true)!!
        assertEquals(1200, surface.depthMm[0].toInt())
        assertNull(surface.confidence)
        assertFalse(cap.frames[1].hasDepth)
        assertNull(cap.depthFrame(cap.frames[1], null))

        val zip = ByteArrayOutputStream()
        CaptureZip.write(dir, zip, extra = mapOf("README.txt" to "hi".toByteArray()))
        val back = tmp.newFolder("back")
        CaptureZip.read(zip.toByteArray().inputStream(), back)
        assertEquals("hi", File(back, "README.txt").readText())
        val reopened = Capture.open(back)
        assertEquals(2, reopened.frames.size)
        assertArrayEquals(depthMm, reopened.depthMm(reopened.frames[0]))
    }

    @Test
    fun correctedPosesReplaceTheRecordedOnes() {
        val dir = tmp.newFolder("corr")
        val writer = CaptureWriter(dir)
        val pose = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        writer.add(1L, pose, byteArrayOf(1), image)
        writer.add(2L, pose, byteArrayOf(2), image)
        val moved = pose.copyOf().also { it[12] = 0.3f }
        writer.close(Manifest(), mapOf(2L to moved, 99L to moved))
        assertEquals(1, writer.correctedFrames)
        val cap = Capture.open(dir)
        assertArrayEquals(pose, cap.frames[0].pose(), 0f)
        assertNull(cap.frames[0].recordedCameraToWorld)
        assertArrayEquals(moved, cap.frames[1].pose(), 0f)
        assertArrayEquals(pose, FloatArray(16) { cap.frames[1].recordedCameraToWorld!![it] }, 0f)
    }

    @Test
    fun aRecordingCutShortKeepsItsFrames() {
        val dir = tmp.newFolder("cut")
        val writer = CaptureWriter(dir)
        writer.add(1L, FloatArray(16), byteArrayOf(9), image)
        // No close(): the app died. The frame line is already on disk.
        val cap = Capture.open(dir)
        assertEquals(1, cap.frames.size)
        assertTrue(Capture.isCapture(dir))
    }
}
