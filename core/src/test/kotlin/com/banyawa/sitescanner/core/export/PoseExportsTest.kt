package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.CaptureWriter
import com.banyawa.sitescanner.core.capture.Manifest
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PoseExportsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val k = CameraIntrinsics(fx = 493.5f, fy = 495.25f, cx = 321.75f, cy = 238.5f, width = 640, height = 480)

    private fun d(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    private fun cross(a: DoubleArray, b: DoubleArray) =
        d(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun normalize(a: DoubleArray): DoubleArray {
        val l = sqrt(a.sumOf { it * it })
        return DoubleArray(3) { a[it] / l }
    }

    /**
     * Camera-to-world (column-major, OpenGL: looks down -Z, +Y up) of a camera at [eye]
     * looking at [target], turned by [roll] radians about its viewing direction.
     */
    private fun lookAt(eye: DoubleArray, target: DoubleArray, roll: Double = 0.0): FloatArray {
        val forward = normalize(sub(target, eye))
        val right = normalize(cross(forward, d(0.0, 1.0, 0.0)))
        val up = cross(right, forward)
        val x = DoubleArray(3) { cos(roll) * right[it] + sin(roll) * up[it] }
        val y = DoubleArray(3) { -sin(roll) * right[it] + cos(roll) * up[it] }
        val m = FloatArray(16)
        for (i in 0 until 3) {
            m[i] = x[i].toFloat()
            m[4 + i] = y[i].toFloat()
            m[8 + i] = (-forward[i]).toFloat()
            m[12 + i] = eye[i].toFloat()
        }
        m[15] = 1f
        return m
    }

    /** Yaw, pitch and roll in all directions, plus a nearly straight-down view. */
    private val poses = listOf(
        lookAt(d(0.0, 1.5, 0.0), d(0.0, 1.5, -1.0)),
        lookAt(d(1.5, 1.6, 2.0), d(-0.5, 1.0, -1.0), roll = 0.2),
        lookAt(d(-2.0, 0.5, 0.3), d(1.0, 2.5, -0.7), roll = -0.6),
        lookAt(d(0.3, 1.2, -3.0), d(0.3, 1.0, 1.0), roll = 2.5),
        lookAt(d(4.0, 2.0, 2.0), d(3.9, -3.0, 2.1)),
    )

    /** World point that [pose] sees at pixel (u, v) and depth [depth], by our convention. */
    private fun unproject(pose: FloatArray, u: Double, v: Double, depth: Double): DoubleArray {
        val xc = depth * (u - k.cx) / k.fx
        val yc = -depth * (v - k.cy) / k.fy
        val zc = -depth
        return DoubleArray(3) { i -> pose[i] * xc + pose[4 + i] * yc + pose[8 + i] * zc + pose[12 + i] }
    }

    @Test
    fun colmapPoseSeesEachPointAtTheSamePixel() {
        for (pose in poses) {
            val (q, t) = PoseMath.worldToCameraColmap(pose)
            assertEquals(1.0, sqrt(q.sumOf { it * it }), 1e-12)
            assertTrue(q[0] >= 0)
            val r = PoseMath.rotationFromQuaternion(q)
            for (u in listOf(3.0, 160.0, 321.75, 500.0, 637.0)) {
                for (v in listOf(2.0, 120.0, 238.5, 400.0, 478.0)) {
                    for (depth in listOf(0.3, 1.7, 6.0)) {
                        val p = unproject(pose, u, v, depth)
                        val ours = PoseMath.projectOpenGl(pose, k, p)!!
                        val colmap = PoseMath.projectColmap(q, t, k, p)!!
                        // Not exact: a float rotation is orthonormal only to about 1e-7.
                        assertEquals(u, ours[0], 1e-4)
                        assertEquals(v, ours[1], 1e-4)
                        assertEquals(ours[0], colmap[0], 1e-3)
                        assertEquals(ours[1], colmap[1], 1e-3)
                        // COLMAP's Z is the depth along the optical axis, positive in front.
                        val z = r[6] * p[0] + r[7] * p[1] + r[8] * p[2] + t[2]
                        assertEquals(depth, z, 1e-5)
                    }
                }
            }
            // Behind the camera for one is behind the camera for the other.
            val behind = unproject(pose, 200.0, 300.0, -2.0)
            assertNull(PoseMath.projectOpenGl(pose, k, behind))
            assertNull(PoseMath.projectColmap(q, t, k, behind))
        }
    }

    @Test
    fun cameraLookingDownMinusZIsAHalfTurnAboutXForColmap() {
        // Our camera at the origin looks down world -Z with +Y up. COLMAP's camera looks down
        // its own +Z with +Y down, so world-to-camera flips Y and Z: diag(1, -1, -1), which is
        // a half turn about X, quaternion (0, 1, 0, 0).
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val (q, t) = PoseMath.worldToCameraColmap(identity)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 0.0, 0.0), q, 1e-12)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), t, 1e-12)

        // Same camera moved to C = (1, 2, 3): same rotation, t = -R·C = (-1, 2, 3).
        val moved = identity.copyOf().also { it[12] = 1f; it[13] = 2f; it[14] = 3f }
        val (q2, t2) = PoseMath.worldToCameraColmap(moved)
        assertArrayEquals(q, q2, 1e-12)
        assertArrayEquals(doubleArrayOf(-1.0, 2.0, 3.0), t2, 1e-12)

        // A point 2 m ahead lands on the principal point; one above the axis lands higher up
        // in the image (smaller v), one to the right further right.
        val ahead = PoseMath.projectColmap(q, t, k, d(0.0, 0.0, -2.0))!!
        assertEquals(k.cx.toDouble(), ahead[0], 1e-9)
        assertEquals(k.cy.toDouble(), ahead[1], 1e-9)
        assertTrue(PoseMath.projectColmap(q, t, k, d(0.0, 0.5, -2.0))!![1] < k.cy)
        assertTrue(PoseMath.projectColmap(q, t, k, d(0.5, 0.0, -2.0))!![0] > k.cx)
    }

    /** A few bytes standing in for a JPEG: start and end markers only, so no frame header. */
    private fun fakeJpeg(i: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(), i.toByte())

    /** The start of a JPEG whose frame header (SOF0, after an APP0 segment) says [width] x [height]. */
    private fun jpegHeader(width: Int, height: Int) = intArrayOf(
        0xFF, 0xD8,
        0xFF, 0xE0, 0x00, 0x04, 0x4A, 0x46,
        0xFF, 0xC0, 0x00, 0x11, 0x08, height shr 8, height and 0xFF, width shr 8, width and 0xFF,
        0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        0xFF, 0xD9,
    ).map { it.toByte() }.toByteArray()

    private fun record(name: String, jpegs: List<ByteArray>, intrinsics: List<CameraIntrinsics>): Capture {
        val dir = tmp.newFolder(name)
        val writer = CaptureWriter(dir)
        jpegs.forEachIndexed { i, jpeg ->
            writer.add(timestampNs = i * 100_000_000L, cameraToWorld = poses[i], jpeg = jpeg, imageIntrinsics = intrinsics[i])
        }
        writer.close(Manifest())
        return Capture.open(dir)
    }

    private fun dataLines(file: File) = file.readLines().filter { !it.startsWith("#") }

    /** Checks images.txt: IDs from 1, the pose of [poses] in order, [cameraIds], empty point lines. */
    private fun checkImagesTxt(file: File, names: List<String>, cameraIds: List<Int>) {
        val lines = dataLines(file)
        assertEquals(names.size * 2, lines.size)
        for (i in names.indices) {
            val f = lines[i * 2].split(' ')
            assertEquals(10, f.size)
            assertEquals(i + 1, f[0].toInt())
            val (q, t) = PoseMath.worldToCameraColmap(poses[i])
            for (j in 0 until 4) assertEquals(q[j], f[1 + j].toDouble(), 1e-8)
            for (j in 0 until 3) assertEquals(t[j], f[5 + j].toDouble(), 1e-8)
            assertEquals(cameraIds[i], f[8].toInt())
            assertEquals(names[i], f[9])
            assertEquals("", lines[i * 2 + 1])
        }
    }

    private fun checkMatrix(frame: JsonObject, pose: FloatArray) {
        val rows = frame["transform_matrix"]!!.jsonArray
        assertEquals(4, rows.size)
        for (r in 0 until 4) {
            val row = rows[r].jsonArray
            assertEquals(4, row.size)
            for (c in 0 until 4) assertEquals(pose[c * 4 + r], row[c].jsonPrimitive.float, 0f)
        }
        assertEquals(listOf(0f, 0f, 0f, 1f), rows[3].jsonArray.map { it.jsonPrimitive.float })
    }

    @Test
    fun writesColmapModelAndTransformsJson() {
        val jpegs = (0 until 3).map { fakeJpeg(it) }
        val capture = record("capture", jpegs, List(3) { k })
        val names = (0 until 3).map { it.toString().padStart(6, '0') + ".jpg" }

        val colmapDir = tmp.newFolder("colmap")
        assertEquals(3, Colmap.write(capture, colmapDir))
        val model = File(colmapDir, "sparse/0")
        val cameras = dataLines(File(model, "cameras.txt"))
        assertEquals(1, cameras.size)
        val cam = cameras[0].split(' ')
        assertEquals(listOf("1", "PINHOLE", "640", "480"), cam.take(4))
        val params = cam.drop(4).map { it.toDouble() }
        assertArrayEquals(doubleArrayOf(493.5, 495.25, 321.75, 238.5), params.toDoubleArray(), 1e-3)
        checkImagesTxt(File(model, "images.txt"), names, listOf(1, 1, 1))
        assertTrue(dataLines(File(model, "points3D.txt")).isEmpty())
        names.forEachIndexed { i, name -> assertArrayEquals(jpegs[i], File(colmapDir, "images/$name").readBytes()) }
        assertTrue(File(colmapDir, "README.txt").readText().contains("--image_path images "))

        val nsDir = tmp.newFolder("nerfstudio")
        assertEquals(3, Nerfstudio.write(capture, nsDir))
        val json = Json.parseToJsonElement(File(nsDir, "transforms.json").readText()).jsonObject
        assertEquals("OPENCV", json["camera_model"]!!.jsonPrimitive.content)
        assertEquals(k.fx, json["fl_x"]!!.jsonPrimitive.float, 0f)
        assertEquals(k.fy, json["fl_y"]!!.jsonPrimitive.float, 0f)
        assertEquals(k.cx, json["cx"]!!.jsonPrimitive.float, 0f)
        assertEquals(k.cy, json["cy"]!!.jsonPrimitive.float, 0f)
        assertEquals(640, json["w"]!!.jsonPrimitive.int)
        assertEquals(480, json["h"]!!.jsonPrimitive.int)
        for (key in listOf("k1", "k2", "p1", "p2")) assertEquals(0f, json[key]!!.jsonPrimitive.float, 0f)
        val frames = json["frames"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, frames.size)
        frames.forEachIndexed { i, frame ->
            val path = frame["file_path"]!!.jsonPrimitive.content
            assertEquals("images/${names[i]}", path)
            assertArrayEquals(jpegs[i], File(nsDir, path).readBytes())
            assertFalse(frame.containsKey("fl_x"))
            checkMatrix(frame, poses[i])
        }
        assertTrue(File(nsDir, "README.txt").isFile)
    }

    @Test
    fun exportIntoTheCaptureFolderPointsAtItsOwnImages() {
        val jpegs = (0 until 3).map { fakeJpeg(it) }
        val capture = record("capture", jpegs, List(3) { k })
        val names = (0 until 3).map { it.toString().padStart(6, '0') + ".jpg" }

        Colmap.write(capture, capture.dir, copyImages = false, imageDir = Capture.FRAME_DIR)
        Nerfstudio.write(capture, capture.dir, copyImages = false, imageDir = Capture.FRAME_DIR)

        assertFalse(File(capture.dir, "images").exists())
        // COLMAP finds images by name under --image_path, so the name is the file name alone.
        checkImagesTxt(File(capture.dir, "sparse/0/images.txt"), names, listOf(1, 1, 1))
        val frames = Json.parseToJsonElement(File(capture.dir, "transforms.json").readText())
            .jsonObject["frames"]!!.jsonArray.map { it.jsonObject }
        frames.forEachIndexed { i, frame ->
            val path = frame["file_path"]!!.jsonPrimitive.content
            assertEquals("frames/${names[i]}", path)
            assertArrayEquals(jpegs[i], File(capture.dir, path).readBytes())
            checkMatrix(frame, poses[i])
        }
        assertTrue(File(capture.dir, "README.txt").readText().contains("--image_path frames "))
        // The capture itself is untouched and still opens.
        assertEquals(3, Capture.open(capture.dir).frames.size)

        // Copying onto the images themselves leaves them in place.
        Colmap.write(capture, capture.dir, copyImages = true, imageDir = Capture.FRAME_DIR)
        names.forEachIndexed { i, name -> assertArrayEquals(jpegs[i], File(capture.dir, "frames/$name").readBytes()) }
    }

    @Test
    fun imagesStoredSmallerGetScaledIntrinsicsAndTheirOwnCamera() {
        // Frame 0's JPEG is half size; frame 1's has no readable header, so its intrinsics stand.
        val capture = record("capture", listOf(jpegHeader(320, 240), fakeJpeg(1)), listOf(k, k))

        val colmapDir = tmp.newFolder("colmap")
        Colmap.write(capture, colmapDir)
        val cameras = dataLines(File(colmapDir, "sparse/0/cameras.txt")).map { it.split(' ') }
        assertEquals(2, cameras.size)
        assertEquals(listOf("1", "PINHOLE", "320", "240"), cameras[0].take(4))
        assertArrayEquals(
            doubleArrayOf(493.5 / 2, 495.25 / 2, 321.75 / 2, 238.5 / 2),
            cameras[0].drop(4).map { it.toDouble() }.toDoubleArray(),
            1e-3,
        )
        assertEquals(listOf("2", "PINHOLE", "640", "480"), cameras[1].take(4))
        checkImagesTxt(File(colmapDir, "sparse/0/images.txt"), listOf("000000.jpg", "000001.jpg"), listOf(1, 2))

        // Intrinsics that differ per frame go on each frame in transforms.json.
        val nsDir = tmp.newFolder("nerfstudio")
        Nerfstudio.write(capture, nsDir)
        val json = Json.parseToJsonElement(File(nsDir, "transforms.json").readText()).jsonObject
        assertFalse(json.containsKey("fl_x"))
        val frames = json["frames"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(320, 640), frames.map { it["w"]!!.jsonPrimitive.int })
        assertEquals(listOf(240, 480), frames.map { it["h"]!!.jsonPrimitive.int })
        assertEquals(k.fx / 2, frames[0]["fl_x"]!!.jsonPrimitive.float, 1e-3f)
        assertEquals(k.fx, frames[1]["fl_x"]!!.jsonPrimitive.float, 0f)

        // A JPEG stored rotated cannot be fixed by scaling: the export refuses it.
        val rotated = record("rotated", listOf(jpegHeader(480, 640)), listOf(k))
        assertThrows(IllegalStateException::class.java) { Colmap.write(rotated, tmp.newFolder("colmap-rotated")) }
    }
}
