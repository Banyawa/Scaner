package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import com.banyawa.sitescanner.core.pointcloud.YuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TsdfVolumeTest {
    private val k = CameraIntrinsics(fx = 60f, fy = 60f, cx = 40f, cy = 30f, width = 80, height = 60)

    /** Camera at [eye] looking at [target], as a column-major OpenGL camera-to-world matrix. */
    private fun lookAt(eye: Vec3, target: Vec3): FloatArray {
        val f = (target - eye).normalized()
        val r = (f cross Vec3.UP).normalized()
        val u = r cross f
        return floatArrayOf(r.x, r.y, r.z, 0f, u.x, u.y, u.z, 0f, -f.x, -f.y, -f.z, 0f, eye.x, eye.y, eye.z, 1f)
    }

    /** Depth image (mm along the camera's -Z) of whatever [hit] returns, the ray distance or null. */
    private fun render(pose: FloatArray, color: YuvFrame? = null, hit: (Vec3, Vec3) -> Float?): DepthFrame {
        val eye = Vec3(pose[12], pose[13], pose[14])
        val depth = ShortArray(k.width * k.height)
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val nx = (u - k.cx) / k.fx
            val ny = (v - k.cy) / k.fy
            val dir = Vec3(
                pose[0] * nx - pose[4] * ny - pose[8],
                pose[1] * nx - pose[5] * ny - pose[9],
                pose[2] * nx - pose[6] * ny - pose[10],
            )
            val len = dir.length()
            val t = hit(eye, dir * (1f / len)) ?: continue
            depth[v * k.width + u] = (t / len * 1000f).toInt().toShort()
        }
        return DepthFrame(k.width, k.height, depth, null, k, pose, color, if (color != null) k else null)
    }

    private val wallZ = -2f
    private fun wall(eye: Vec3, dir: Vec3): Float? = if (dir.z < -1e-3f) (wallZ - eye.z) / dir.z else null

    private val centre = Vec3(0f, 0f, -2f)
    private val radius = 0.5f
    private fun sphere(eye: Vec3, dir: Vec3): Float? {
        val oc = eye - centre
        val b = oc dot dir
        val disc = b * b - ((oc dot oc) - radius * radius)
        if (disc < 0f) return null
        val t = -b - sqrt(disc)
        return if (t > 0f) t else null
    }

    private fun solidColour(r: Int, g: Int, b: Int): YuvFrame {
        // Inverse of YuvFrame's BT.601 full-range conversion.
        val y = (0.299 * r + 0.587 * g + 0.114 * b)
        val u = (b - y) / 1.772 + 128
        val v = (r - y) / 1.402 + 128
        val n = k.width * k.height
        return YuvFrame(
            k.width, k.height,
            ByteArray(n) { y.toInt().toByte() }, k.width, 1,
            ByteArray(n / 4) { u.toInt().toByte() }, ByteArray(n / 4) { v.toInt().toByte() }, k.width / 2, 1,
        )
    }

    @Test
    fun flatWallBecomesAFlatSheetFacingTheCamera() {
        val volume = TsdfVolume()
        val red = solidColour(200, 40, 30)
        // 1.5 m away: close enough for full weight, so three frames make a trusted surface.
        for (x in listOf(-0.1f, 0f, 0.1f)) {
            val pose = lookAt(Vec3(x, 0f, -0.5f), Vec3(x, 0f, -1f))
            volume.integrate(render(pose, red, ::wall))
        }
        val mesh = volume.extractMesh()
        assertTrue("triangles: ${mesh.triangleCount}", mesh.triangleCount > 5_000)
        for (v in 0 until mesh.vertexCount) {
            assertEquals(wallZ, mesh.positions[v * 3 + 2], 0.012f)
        }
        val b = mesh.bounds()!!
        assertTrue("covers the view: ${b.size}", b.size.x > 1.8f && b.size.y > 1.3f)

        val normals = mesh.normals()
        val facing = (0 until mesh.vertexCount).count { normals[it * 3 + 2] > 0.9f }
        assertTrue("faces the camera: $facing of ${mesh.vertexCount}", facing > mesh.vertexCount * 0.95)

        for (v in 0 until mesh.vertexCount step 97) {
            assertEquals(200f, (mesh.colors[v * 3].toInt() and 0xFF).toFloat(), 6f)
            assertEquals(40f, (mesh.colors[v * 3 + 1].toInt() and 0xFF).toFloat(), 6f)
            assertEquals(30f, (mesh.colors[v * 3 + 2].toInt() and 0xFF).toFloat(), 6f)
        }
    }

    @Test
    fun sphereSeenFromAllSidesIsClosedAndFacesOut() {
        val volume = TsdfVolume(voxelSize = 0.01f)
        // Walking around it twice, above and below its middle, plus straight over and under.
        val eyes = (0 until 360 step 45).flatMap { deg ->
            val a = Math.toRadians(deg.toDouble())
            listOf(0.4f, -0.4f).map { h -> centre + Vec3(sin(a).toFloat(), h, cos(a).toFloat()) * 1.2f }
        } + listOf(centre + Vec3(0.05f, 1.2f, 0.3f), centre + Vec3(0.05f, -1.2f, 0.3f))
        for (eye in eyes) volume.integrate(render(lookAt(eye, centre), hit = ::sphere))
        val mesh = volume.extractMesh(minWeight = 4)
        assertTrue(mesh.triangleCount > 5_000)
        val normals = mesh.normals()
        var outward = 0
        for (v in 0 until mesh.vertexCount) {
            val p = Vec3(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
            assertEquals("vertex on the sphere", radius, p.distanceTo(centre), 0.012f)
            val n = Vec3(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2])
            if (n dot (p - centre).normalized() > 0.8f) outward++
        }
        assertTrue("faces out: $outward of ${mesh.vertexCount}", outward > mesh.vertexCount * 0.95)

        // Closed: every edge is shared by exactly two triangles.
        val edges = HashMap<Long, Int>()
        for (t in 0 until mesh.triangleCount) for (j in 0 until 3) {
            val a = mesh.indices[t * 3 + j]
            val b = mesh.indices[t * 3 + (j + 1) % 3]
            val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            edges[key] = (edges[key] ?: 0) + 1
        }
        val open = edges.values.count { it != 2 }
        assertTrue("open edges: $open of ${edges.size}", open < edges.size / 100)
    }

    @Test
    fun surfaceSeenOnceIsNotMeshed() {
        val volume = TsdfVolume()
        // One far glance: below the default weight needed to trust a surface.
        volume.integrate(render(lookAt(Vec3(0f, 0f, 2f), Vec3(0f, 0f, 0f)), hit = ::wall))
        assertTrue(volume.extractMesh().isEmpty())
    }

    @Test
    fun fullVolumeStopsGrowing() {
        val volume = TsdfVolume(maxBlocks = 50)
        volume.integrate(render(lookAt(Vec3.ZERO, Vec3(0f, 0f, -1f)), hit = ::wall))
        assertEquals(50, volume.blockCount)
        assertTrue(volume.isFull)
    }
}
