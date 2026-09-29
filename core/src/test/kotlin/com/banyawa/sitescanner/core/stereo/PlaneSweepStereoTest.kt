package com.banyawa.sitescanner.core.stereo

import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Plane-sweep stereo on exactly rendered synthetic views. The renderer builds camera rays with
 * the app's conventions (OpenGL pose, image v down, depth along the optical axis), so a sign or
 * convention error in the stereo code shows up as wrong depth, not just as noise.
 */
class PlaneSweepStereoTest {
    /** Render camera: 320×240, about 60° horizontal field of view. Stereo runs at half that. */
    private val renderK = CameraIntrinsics(fx = 277f, fy = 277f, cx = 160f, cy = 120f, width = 320, height = 240)
    private val downscale = 2
    private val stereoK = GrayImage.scaleIntrinsics(renderK, downscale)

    /**
     * Smooth random texture: a sum of sinusoids with 8–40 cm wavelengths (about 4–18 stereo
     * pixels on the wall), so every 5×5 patch has gradients in more than one direction. Much
     * smoother texture leaves 5×5 patches nearly linear ramps that match at many depths (the
     * aperture problem), which the uniqueness test then rightly rejects.
     */
    private class Texture(seed: Long) {
        private val kx = FloatArray(WAVES)
        private val ky = FloatArray(WAVES)
        private val phase = FloatArray(WAVES)
        private val amp = FloatArray(WAVES)

        init {
            val rnd = Random(seed)
            for (i in 0 until WAVES) {
                val wavelength = 0.08 + 0.32 * rnd.nextDouble()
                val theta = 2 * PI * rnd.nextDouble()
                kx[i] = (2 * PI * cos(theta) / wavelength).toFloat()
                ky[i] = (2 * PI * sin(theta) / wavelength).toFloat()
                phase[i] = (2 * PI * rnd.nextDouble()).toFloat()
                amp[i] = (12 + 12 * rnd.nextDouble()).toFloat()
            }
        }

        fun at(x: Float, y: Float): Float {
            var v = 128f
            for (i in 0 until WAVES) v += amp[i] * sin(kx[i] * x + ky[i] * y + phase[i])
            return v
        }

        companion object {
            const val WAVES = 8
        }
    }

    /**
     * A textured wall in the plane z = [wallZ] (facing +Z), optionally a nearer textured box
     * face at z = [boxZ] covering x < [boxRightX], and optionally a blank rectangle
     * ([blank] = xMin, xMax, yMin, yMax) painted flat grey on the wall.
     */
    private class Scene(
        val boxRightX: Float? = null,
        val blank: FloatArray? = null,
        val wallZ: Float = -3f,
        val boxZ: Float = -2f,
    ) {
        private val wallTexture = Texture(7)
        private val boxTexture = Texture(11)

        /** Distance along the ray parameter, grey value and surface of the last [trace]. */
        var t = 0f
        var value = 0f
        var surface = NONE

        /** Traces the ray `o + t·d`; returns false when it hits nothing in front. */
        fun trace(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Boolean {
            surface = NONE
            if (dz == 0f) return false
            if (boxRightX != null) {
                val tb = (boxZ - oz) / dz
                if (tb > 0f && ox + tb * dx < boxRightX) {
                    t = tb
                    value = boxTexture.at(ox + tb * dx, oy + tb * dy)
                    surface = BOX
                    return true
                }
            }
            val tw = (wallZ - oz) / dz
            if (tw <= 0f) return false
            val x = ox + tw * dx
            val y = oy + tw * dy
            t = tw
            val b = blank
            if (b != null && x >= b[0] && x <= b[1] && y >= b[2] && y <= b[3]) {
                value = 128f
                surface = BLANK
            } else {
                value = wallTexture.at(x, y)
                surface = WALL
            }
            return true
        }

        /**
         * Traces the ray through pixel ([u], [v]) of a camera with intrinsics [k] and pose [m],
         * built with the app's conventions: normalised `nx = (u - cx) / fx`, `ny = (v - cy) / fy`,
         * camera-space point at depth d is `(d·nx, -d·ny, -d)`, world = pose · camera. The ray
         * direction is the camera-space point at depth 1, so [t] is the optical-axis depth.
         */
        fun tracePixel(u: Float, v: Float, k: CameraIntrinsics, m: FloatArray): Boolean {
            val xc = (u - k.cx) / k.fx
            val yc = -(v - k.cy) / k.fy
            val zc = -1f
            val dx = m[0] * xc + m[4] * yc + m[8] * zc
            val dy = m[1] * xc + m[5] * yc + m[9] * zc
            val dz = m[2] * xc + m[6] * yc + m[10] * zc
            return trace(m[12], m[13], m[14], dx, dy, dz)
        }

        companion object {
            const val NONE = 0
            const val WALL = 1
            const val BOX = 2
            const val BLANK = 3
        }
    }

    /** Camera-to-world pose (OpenGL convention) of a camera at [eye] looking at [target]. */
    private fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3.UP): FloatArray {
        val f = (target - eye).normalized()
        val s = (f cross up).normalized()
        val u = s cross f
        return floatArrayOf(
            s.x, s.y, s.z, 0f,
            u.x, u.y, u.z, 0f,
            -f.x, -f.y, -f.z, 0f,
            eye.x, eye.y, eye.z, 1f,
        )
    }

    /** Renders [scene] from [pose] at [renderK] (8-bit grey, like a camera), then prepares it for stereo. */
    private fun view(scene: Scene, pose: FloatArray): StereoView {
        val k = renderK
        val pixels = IntArray(k.width * k.height)
        for (v in 0 until k.height) {
            for (u in 0 until k.width) {
                val g = if (scene.tracePixel(u.toFloat(), v.toFloat(), k, pose)) scene.value.roundToInt().coerceIn(0, 255) else 0
                pixels[v * k.width + u] = (g shl 16) or (g shl 8) or g
            }
        }
        val gray = GrayImage.of(RgbImage(k.width, k.height, pixels), downscale)
        return StereoView(gray, stereoK, pose)
    }

    private class Truth(val depth: FloatArray, val surface: IntArray)

    /** Optical-axis depth and surface id at every stereo-resolution pixel of the camera at [pose]. */
    private fun truth(scene: Scene, pose: FloatArray): Truth {
        val k = stereoK
        val depth = FloatArray(k.width * k.height)
        val surface = IntArray(k.width * k.height)
        for (v in 0 until k.height) {
            for (u in 0 until k.width) {
                if (scene.tracePixel(u.toFloat(), v.toFloat(), k, pose)) {
                    depth[v * k.width + u] = scene.t
                    surface[v * k.width + u] = scene.surface
                }
            }
        }
        return Truth(depth, surface)
    }

    /** True when every pixel within [margin] of ([u], [v]) sees [surface]. */
    private fun Truth.interior(u: Int, v: Int, surface: Int, margin: Int): Boolean {
        val w = stereoK.width
        val h = stereoK.height
        for (y in v - margin..v + margin) {
            for (x in u - margin..u + margin) {
                if (x !in 0 until w || y !in 0 until h || this.surface[y * w + x] != surface) return false
            }
        }
        return true
    }

    private fun StereoResult.depthM(i: Int) = (depthMm[i].toInt() and 0xFFFF) * 0.001f
    private fun StereoResult.conf(i: Int) = confidence[i].toInt() and 0xFF

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

    private class Stats(val confidentShare: Float, val accurateShare: Float, val validShare: Float)

    private fun stats(result: StereoResult, truth: Truth, label: String): Stats {
        val n = result.width * result.height
        var confident = 0
        var accurate = 0
        var valid = 0
        for (i in 0 until n) {
            if (result.depthMm[i].toInt() != 0) valid++
            if (result.conf(i) < 128) continue
            confident++
            val t = truth.depth[i]
            if (t > 0f && abs(result.depthM(i) - t) / t < 0.03f) accurate++
        }
        val s = Stats(confident / n.toFloat(), accurate / confident.coerceAtLeast(1).toFloat(), valid / n.toFloat())
        println(
            "STEREO $label: depth on %.1f%% of pixels, confident (>=128) %.1f%%, of which within 3%%: %.1f%%"
                .format(100 * s.validShare, 100 * s.confidentShare, 100 * s.accurateShare),
        )
        return s
    }

    private val referenceAtOrigin = lookAt(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -3f))

    /**
     * Neighbours 15–18 cm to the sides, above and below a reference at [eye] looking at
     * [target], each aimed a little off the reference's target and rolled slightly.
     */
    private fun neighbourPoses(
        eye: Vec3 = Vec3(0f, 0f, 0f),
        target: Vec3 = Vec3(0f, 0f, -3f),
        up: Vec3 = Vec3.UP,
    ): List<FloatArray> = listOf(
        lookAt(eye + Vec3(0.18f, 0.02f, 0f), target + Vec3(0.08f, 0f, 0f), up),
        lookAt(eye + Vec3(-0.16f, -0.03f, 0.03f), target + Vec3(-0.1f, 0.06f, 0f), up),
        lookAt(eye + Vec3(0.03f, 0.15f, -0.04f), target + Vec3(0f, 0.12f, 0f), up + Vec3(0.03f, 0f, 0f)),
        lookAt(eye + Vec3(-0.05f, -0.14f, 0.05f), target + Vec3(0.06f, -0.05f, 0f), up + Vec3(-0.02f, 0f, 0f)),
    )

    @Test
    fun recoversWallAndNearerBoxFace() {
        // Box face 2 m away over the left third of the reference image, wall 3 m behind.
        val boxEdgeU = stereoK.width / 3f
        val scene = Scene(boxRightX = 2f * (boxEdgeU - stereoK.cx) / stereoK.fx)
        val reference = view(scene, referenceAtOrigin)
        val neighbours = neighbourPoses().map { view(scene, it) }
        val result = PlaneSweepStereo.estimate(reference, neighbours)
        val truth = truth(scene, referenceAtOrigin)

        assertEquals(stereoK.width, result.width)
        assertEquals(stereoK.height, result.height)
        assertEquals(stereoK, result.intrinsics)
        val s = stats(result, truth, "wall+box, 4 neighbours")
        assertTrue("confident share ${s.confidentShare}", s.confidentShare >= 0.6f)
        assertTrue("accurate share ${s.accurateShare}", s.accurateShare >= 0.85f)

        val box = ArrayList<Float>()
        val wall = ArrayList<Float>()
        for (v in 0 until result.height) {
            for (u in 0 until result.width) {
                val i = v * result.width + u
                if (result.conf(i) < 128) continue
                if (truth.interior(u, v, Scene.BOX, 4)) box += result.depthM(i)
                if (truth.interior(u, v, Scene.WALL, 4)) wall += result.depthM(i)
            }
        }
        println("STEREO box median %.3f m (%d px), wall median %.3f m (%d px)".format(median(box), box.size, median(wall), wall.size))
        assertTrue("box pixels ${box.size}", box.size > 1000)
        assertTrue("wall pixels ${wall.size}", wall.size > 3000)
        assertEquals(2f, median(box), 2f * 0.03f)
        assertEquals(3f, median(wall), 3f * 0.03f)
        // Depth and confidence agree on which pixels have an estimate.
        for (i in 0 until result.width * result.height) {
            assertEquals(result.depthMm[i].toInt() != 0, result.conf(i) >= StereoParams().minConfidence)
        }
    }

    @Test
    fun handlesAnArbitraryReferencePose() {
        // Everything moved off the origin and the reference turned and rolled, so the wall is
        // seen at a slant: depth varies across the image and pose handling is fully exercised.
        val scene = Scene()
        val eye = Vec3(0.4f, 1.3f, 0.5f)
        val target = Vec3(0.1f, 1.0f, -3f)
        val up = Vec3(0.06f, 1f, 0f)
        val reference = lookAt(eye, target, up)
        val neighbours = neighbourPoses(eye, target, up).take(3).map { view(scene, it) }
        val result = PlaneSweepStereo.estimate(view(scene, reference), neighbours)
        val truth = truth(scene, reference)
        val depths = truth.depth.filter { it > 0f }
        println("STEREO slanted wall truth depth %.2f..%.2f m".format(depths.min(), depths.max()))
        val s = stats(result, truth, "slanted wall, moved reference, 3 neighbours")
        assertTrue("confident share ${s.confidentShare}", s.confidentShare >= 0.6f)
        assertTrue("accurate share ${s.accurateShare}", s.accurateShare >= 0.85f)
    }

    @Test
    fun blankSurfaceGetsNoDepth() {
        // A 70×70 cm flat grey patch on the wall, right of centre (about 32×32 stereo pixels).
        val scene = Scene(blank = floatArrayOf(0.2f, 0.9f, -0.5f, 0.2f))
        val reference = view(scene, referenceAtOrigin)
        val neighbours = neighbourPoses().take(3).map { view(scene, it) }
        val result = PlaneSweepStereo.estimate(reference, neighbours)
        val truth = truth(scene, referenceAtOrigin)

        var blank = 0
        var confidenceSum = 0
        for (v in 0 until result.height) {
            for (u in 0 until result.width) {
                // Patch radius 2 plus the median window: 3 pixels in, no patch sees texture.
                if (!truth.interior(u, v, Scene.BLANK, 3)) continue
                val i = v * result.width + u
                blank++
                confidenceSum += result.conf(i)
                assertEquals("depth at ($u, $v)", 0, result.depthMm[i].toInt())
                assertEquals("confidence at ($u, $v)", 0, result.conf(i))
            }
        }
        println("STEREO blank patch: $blank interior pixels, mean confidence ${confidenceSum / blank.coerceAtLeast(1)}")
        assertTrue("blank pixels $blank", blank > 400)
        // The textured rest of the wall still matches.
        val s = stats(result, truth, "wall with blank patch, 3 neighbours")
        assertTrue(s.confidentShare >= 0.5f)
        assertTrue(s.accurateShare >= 0.85f)
    }

    @Test
    fun timingAt160x120With64PlanesAnd3Neighbours() {
        val scene = Scene(boxRightX = -0.4f)
        val reference = view(scene, referenceAtOrigin)
        val neighbours = neighbourPoses().take(3).map { view(scene, it) }
        val params = StereoParams(planes = 64, patchRadius = 2)
        assertEquals(160, reference.gray.width)
        assertEquals(120, reference.gray.height)

        var t0 = System.nanoTime()
        PlaneSweepStereo.estimate(reference, neighbours, params)
        val coldMs = (System.nanoTime() - t0) / 1e6
        var bestMs = Double.MAX_VALUE
        repeat(5) {
            t0 = System.nanoTime()
            PlaneSweepStereo.estimate(reference, neighbours, params)
            bestMs = minOf(bestMs, (System.nanoTime() - t0) / 1e6)
        }
        println("PERF plane sweep 160x120, 64 planes, 3 neighbours, 5x5 ZNCC: first run %.0f ms, best warm run %.0f ms".format(coldMs, bestMs))
        assertTrue("warm run took $bestMs ms", bestMs < 1500.0)
    }

    @Test
    fun relativeProjectionMatchesTheConventions() {
        // A world point seen by two cameras: projecting it with the pose conventions directly
        // must agree with the reference-ray + inverse-depth mapping the sweep uses.
        val k = stereoK
        val refPose = lookAt(Vec3(0.3f, 1.2f, 0.4f), Vec3(0.1f, 1f, -2f), Vec3(0.1f, 1f, 0f))
        val nPose = lookAt(Vec3(0.5f, 1.1f, 0.3f), Vec3(0.2f, 1.2f, -2f))
        val u = 37f
        val v = 81f
        val d = 2.4f
        // Reference pixel at depth d -> world, with the documented conventions.
        val xc = d * (u - k.cx) / k.fx
        val yc = -d * (v - k.cy) / k.fy
        val zc = -d
        val wx = refPose[0] * xc + refPose[4] * yc + refPose[8] * zc + refPose[12]
        val wy = refPose[1] * xc + refPose[5] * yc + refPose[9] * zc + refPose[13]
        val wz = refPose[2] * xc + refPose[6] * yc + refPose[10] * zc + refPose[14]
        // World -> neighbour camera (inverse of a rigid pose: R^T (p - c)) -> pixel.
        val px = wx - nPose[12]
        val py = wy - nPose[13]
        val pz = wz - nPose[14]
        val cx = nPose[0] * px + nPose[1] * py + nPose[2] * pz
        val cy = nPose[4] * px + nPose[5] * py + nPose[6] * pz
        val cz = nPose[8] * px + nPose[9] * py + nPose[10] * pz
        val depthN = -cz
        val expectU = k.fx * cx / depthN + k.cx
        val expectV = k.fy * (-cy) / depthN + k.cy

        val m = FloatArray(12)
        PlaneSweepStereo.relativeProjection(refPose, nPose, k, m)
        val nx = (u - k.cx) / k.fx
        val ny = (v - k.cy) / k.fy
        val rho = 1f / d
        val qx = m[0] * nx + m[1] * ny + m[2] + rho * m[9]
        val qy = m[3] * nx + m[4] * ny + m[5] + rho * m[10]
        val qz = m[6] * nx + m[7] * ny + m[8] + rho * m[11]
        assertEquals(expectU, qx / qz, 1e-3f)
        assertEquals(expectV, qy / qz, 1e-3f)
        assertEquals(depthN / d, qz, 1e-4f)
    }
}
