package com.banyawa.sitescanner.core.capture

import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * A recorded walk through a 4 × 2.7 × 5 m box room (walls red, floor grey, ceiling
 * white), reconstructed offline: the model should be the room, with the right colours.
 */
class ReconstructorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val depthK = CameraIntrinsics(125f, 125f, 80f, 60f, 160, 120)
    private val imageK = CameraIntrinsics(250f, 250f, 160f, 120f, 320, 240)

    private val decoder = ImageDecoder { bytes ->
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        RgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }

    private fun lookAt(eye: Vec3, target: Vec3): FloatArray {
        val f = (target - eye).normalized()
        val r = (f cross Vec3.UP).normalized()
        val u = r cross f
        return floatArrayOf(r.x, r.y, r.z, 0f, u.x, u.y, u.z, 0f, -f.x, -f.y, -f.z, 0f, eye.x, eye.y, eye.z, 1f)
    }

    /** Ray hit on the room box [0,4]×[0,2.7]×[-5,0]: distance and the surface colour. */
    private fun hit(e: Vec3, d: Vec3): Pair<Float, Int> {
        var best = Float.MAX_VALUE
        var color = 0
        fun plane(o: Float, dd: Float, p: Float, rgb: Int) {
            if (dd == 0f) return
            val t = (p - o) / dd
            if (t > 1e-4f && t < best) { best = t; color = rgb }
        }
        plane(e.x, d.x, 0f, 0xC03030); plane(e.x, d.x, 4f, 0xC03030)
        plane(e.z, d.z, 0f, 0xC03030); plane(e.z, d.z, -5f, 0xC03030)
        plane(e.y, d.y, 0f, 0x808080); plane(e.y, d.y, 2.7f, 0xF0F0F0)
        return best to color
    }

    private fun render(pose: FloatArray, k: CameraIntrinsics, depthOut: ShortArray?, colorOut: IntArray?) {
        val eye = Vec3(pose[12], pose[13], pose[14])
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val nx = (u - k.cx) / k.fx
            val ny = (v - k.cy) / k.fy
            val dir = Vec3(pose[0] * nx - pose[4] * ny - pose[8], pose[1] * nx - pose[5] * ny - pose[9], pose[2] * nx - pose[6] * ny - pose[10])
            val len = dir.length()
            val (t, rgb) = hit(eye, dir * (1f / len))
            val i = v * k.width + u
            if (depthOut != null) {
                val mm = (t / len * 1000f).toInt()
                if (mm in 1..65000) depthOut[i] = mm.toShort()
            }
            colorOut?.set(i, rgb)
        }
    }

    private fun jpeg(pixels: IntArray, w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun record(frames: Int, withDepth: Boolean): Capture {
        val dir = tmp.newFolder("cap")
        val writer = CaptureWriter(dir)
        for (i in 0 until frames) {
            val a = i / frames.toDouble() * 2 * Math.PI * 1.5
            val eye = Vec3(2f + 0.3f * sin(a * 2).toFloat(), 1.4f, -2.5f + 0.3f * cos(a * 3).toFloat())
            val target = eye + Vec3(sin(a).toFloat(), -0.2f + 0.3f * sin(a * 5).toFloat(), cos(a).toFloat())
            val pose = lookAt(eye, target)
            val depth = if (withDepth) ShortArray(depthK.width * depthK.height).also { render(pose, depthK, it, null) } else null
            val color = IntArray(imageK.width * imageK.height).also { render(pose, imageK, null, it) }
            writer.add(i * 100_000_000L, pose, jpeg(color, imageK.width, imageK.height), imageK, depth, if (withDepth) depthK else null, rawDepth = false)
        }
        writer.close(Manifest(device = "synthetic", floorY = 0f, depthSupported = withDepth))
        return Capture.open(dir)
    }

    @Test
    fun recordedRoomBecomesAColouredModel() {
        val capture = record(120, withDepth = true)
        val stages = ArrayList<String>()
        val result = Reconstructor(decoder).reconstruct(capture, ProgressListener { stage, done, total -> stages += "$stage $done/$total" })
        assertEquals(120, result.framesUsed)
        assertEquals(120, result.depthFrames)
        assertTrue("points: ${result.cloud.size}", result.cloud.size > 100_000)
        assertTrue("triangles: ${result.mesh.triangleCount}", result.mesh.triangleCount > 50_000)
        assertTrue(stages.first().startsWith("fuse 0/120"))
        assertTrue(stages.last() == "mesh 1/1")

        // The model spans the room and its colours come from the images: red walls, grey floor.
        val b = result.mesh.bounds()!!
        assertTrue("size ${b.size}", b.size.x > 3.5f && b.size.y > 2.3f && b.size.z > 4.5f)
        var wallRed = 0
        var walls = 0
        var floorGrey = 0
        var floors = 0
        val m = result.mesh
        for (v in 0 until m.vertexCount) {
            val y = m.positions[v * 3 + 1]
            val r = m.colors[v * 3].toInt() and 0xFF
            val g = m.colors[v * 3 + 1].toInt() and 0xFF
            when {
                y in 0.5f..2.2f -> { walls++; if (r > 150 && g < 90) wallRed++ }
                y < 0.05f -> { floors++; if (r in 100..150 && g in 100..150) floorGrey++ }
            }
        }
        assertTrue("red walls: $wallRed of $walls", wallRed > walls * 0.9)
        assertTrue("grey floor: $floorGrey of $floors", floors > 0 && floorGrey > floors * 0.9)
        assertEquals(0f, result.floorY)
    }

    @Test
    fun framesWithoutDepthGiveNoModelButDoNotFail() {
        val capture = record(5, withDepth = false)
        val result = Reconstructor(decoder).reconstruct(capture)
        assertEquals(0, result.depthFrames)
        assertTrue(result.mesh.isEmpty())
        assertEquals(0, result.cloud.size)
    }

    @Test
    fun cancellingStopsEarly() {
        val capture = record(30, withDepth = true)
        var calls = 0
        val result = Reconstructor(decoder).reconstruct(capture, isCancelled = { ++calls > 5 })
        assertTrue(result.depthFrames < 30)
        assertTrue(result.mesh.isEmpty())
    }
}
