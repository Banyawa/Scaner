package com.banyawa.sitescanner.cli

import com.banyawa.sitescanner.core.geometry.Bounds3
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.mesh.TexturedMesh
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import com.banyawa.sitescanner.core.viewer.OrbitCamera
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Software renders of a mesh or point cloud (z-buffered, vertex colours lit from the
 * viewer) or of a photo-textured mesh, to look at a reconstruction without a 3D viewer:
 * from the front, a corner and straight down.
 */
object MeshPreview {
    fun renderViews(mesh: TriangleMesh?, cloud: PointCloud?, outDir: File, size: Int = 900) {
        val bounds = mesh?.bounds() ?: cloud?.bounds() ?: return
        forEachView(bounds, size) { name, viewProj, toEye, image, zbuf ->
            if (mesh != null && !mesh.isEmpty()) rasterize(mesh, viewProj, toEye, image, zbuf)
            else if (cloud != null) splat(cloud, viewProj, image, zbuf)
            ImageIO.write(image, "png", File(outDir, "preview_$name.png"))
        }
    }

    /**
     * Renders of a photo-textured mesh as it shows in a viewer: the atlas sampled
     * bilinearly, unlit (the photos carry the room's light, as the GLB's unlit material).
     * Written as `<prefix>_front.png` and so on.
     */
    fun renderViews(textured: TexturedMesh, outDir: File, size: Int = 900, prefix: String = "preview_textured") {
        val bounds = textured.mesh.bounds() ?: return
        forEachView(bounds, size) { name, viewProj, _, image, zbuf ->
            rasterizeTextured(textured, viewProj, image, zbuf)
            ImageIO.write(image, "png", File(outDir, "${prefix}_$name.png"))
        }
    }

    /** The front, corner and top views of [bounds], each on a fresh background with an empty z-buffer. */
    private fun forEachView(bounds: Bounds3, size: Int, draw: (name: String, viewProj: FloatArray, toEye: Vec3, image: BufferedImage, zbuf: FloatArray) -> Unit) {
        val views = listOf("front" to (0f to 0.15f), "corner" to (0.7f to 0.5f), "top" to (0f to 1.45f))
        for ((name, angles) in views) {
            val camera = OrbitCamera().apply { fit(bounds) }
            camera.yaw = angles.first
            camera.rotate(0f, (angles.second - 0.6f) / (Math.PI.toFloat() / size), size)
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = java.awt.Color(28, 30, 36)
            g.fillRect(0, 0, size, size)
            g.dispose()
            val viewProj = camera.viewProjection(1f)
            val toEye = (camera.eye() - camera.target).normalized()
            val zbuf = FloatArray(size * size) { Float.POSITIVE_INFINITY }
            draw(name, viewProj, toEye, image, zbuf)
        }
    }

    private fun rasterizeTextured(textured: TexturedMesh, viewProj: FloatArray, image: BufferedImage, zbuf: FloatArray) {
        val mesh = textured.mesh
        val size = image.width
        val screen = FloatArray(mesh.vertexCount * 3)
        for (v in 0 until mesh.vertexCount) {
            val p = project(viewProj, mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2], size)
            screen[v * 3] = p[0]; screen[v * 3 + 1] = p[1]; screen[v * 3 + 2] = p[2]
        }
        val atlas = textured.atlas
        val uv = textured.uv
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
            if (screen[a * 3 + 2].isNaN() || screen[b * 3 + 2].isNaN() || screen[c * 3 + 2].isNaN()) continue
            val s = screen
            val x0 = s[a * 3]; val y0 = s[a * 3 + 1]; val x1 = s[b * 3]; val y1 = s[b * 3 + 1]; val x2 = s[c * 3]; val y2 = s[c * 3 + 1]
            val minX = max(0, min(x0, min(x1, x2)).toInt()); val maxX = min(size - 1, max(x0, max(x1, x2)).toInt() + 1)
            val minY = max(0, min(y0, min(y1, y2)).toInt()); val maxY = min(size - 1, max(y0, max(y1, y2)).toInt() + 1)
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (abs(area) < 1e-6f) continue
            for (y in minY..maxY) for (x in minX..maxX) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
                val w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val z = w0 * s[a * 3 + 2] + w1 * s[b * 3 + 2] + w2 * s[c * 3 + 2]
                val i = y * size + x
                if (z >= zbuf[i]) continue
                zbuf[i] = z
                val u = w0 * uv[a * 2] + w1 * uv[b * 2] + w2 * uv[c * 2]
                val v = w0 * uv[a * 2 + 1] + w1 * uv[b * 2 + 1] + w2 * uv[c * 2 + 1]
                image.setRGB(x, y, sampleBilinear(atlas, u, v))
            }
        }
    }

    /** The atlas at texture coordinate ([u], [v]) (origin top left, pixel centres at +0.5), clamped at the edges. */
    private fun sampleBilinear(atlas: RgbImage, u: Float, v: Float): Int {
        val fx = u * atlas.width - 0.5f
        val fy = v * atlas.height - 0.5f
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        val xa = x0.coerceIn(0, atlas.width - 1); val xb = (x0 + 1).coerceIn(0, atlas.width - 1)
        val ya = y0.coerceIn(0, atlas.height - 1); val yb = (y0 + 1).coerceIn(0, atlas.height - 1)
        val p = atlas.pixels
        val w = atlas.width
        val p00 = p[ya * w + xa]; val p10 = p[ya * w + xb]; val p01 = p[yb * w + xa]; val p11 = p[yb * w + xb]
        var rgb = 0
        var shift = 16
        while (shift >= 0) {
            val top = ((p00 shr shift) and 0xFF) * (1f - tx) + ((p10 shr shift) and 0xFF) * tx
            val bottom = ((p01 shr shift) and 0xFF) * (1f - tx) + ((p11 shr shift) and 0xFF) * tx
            rgb = (rgb shl 8) or (top * (1f - ty) + bottom * ty + 0.5f).toInt().coerceIn(0, 255)
            shift -= 8
        }
        return rgb
    }

    private fun rasterize(mesh: TriangleMesh, viewProj: FloatArray, light: Vec3, image: BufferedImage, zbuf: FloatArray) {
        val size = image.width
        val normals = mesh.normals()
        val screen = FloatArray(mesh.vertexCount * 3)
        for (v in 0 until mesh.vertexCount) {
            val p = project(viewProj, mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2], size)
            screen[v * 3] = p[0]; screen[v * 3 + 1] = p[1]; screen[v * 3 + 2] = p[2]
        }
        val shade = FloatArray(mesh.vertexCount) { v ->
            val d = abs(normals[v * 3] * light.x + normals[v * 3 + 1] * light.y + normals[v * 3 + 2] * light.z)
            0.55f + 0.45f * d
        }
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
            if (screen[a * 3 + 2].isNaN() || screen[b * 3 + 2].isNaN() || screen[c * 3 + 2].isNaN()) continue
            triangle(image, zbuf, screen, mesh.colors, shade, a, b, c)
        }
    }

    private fun triangle(image: BufferedImage, zbuf: FloatArray, s: FloatArray, colors: ByteArray, shade: FloatArray, a: Int, b: Int, c: Int) {
        val size = image.width
        val x0 = s[a * 3]; val y0 = s[a * 3 + 1]; val x1 = s[b * 3]; val y1 = s[b * 3 + 1]; val x2 = s[c * 3]; val y2 = s[c * 3 + 1]
        val minX = max(0, min(x0, min(x1, x2)).toInt()); val maxX = min(size - 1, max(x0, max(x1, x2)).toInt() + 1)
        val minY = max(0, min(y0, min(y1, y2)).toInt()); val maxY = min(size - 1, max(y0, max(y1, y2)).toInt() + 1)
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (abs(area) < 1e-6f) return
        for (y in minY..maxY) for (x in minX..maxX) {
            val px = x + 0.5f; val py = y + 0.5f
            var w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
            var w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
            var w2 = 1f - w0 - w1
            if (w0 < 0f || w1 < 0f || w2 < 0f) continue
            val z = w0 * s[a * 3 + 2] + w1 * s[b * 3 + 2] + w2 * s[c * 3 + 2]
            val i = y * size + x
            if (z >= zbuf[i]) continue
            zbuf[i] = z
            val sh = w0 * shade[a] + w1 * shade[b] + w2 * shade[c]
            var rgb = 0
            for (ch in 0 until 3) {
                val v = w0 * (colors[a * 3 + ch].toInt() and 0xFF) + w1 * (colors[b * 3 + ch].toInt() and 0xFF) + w2 * (colors[c * 3 + ch].toInt() and 0xFF)
                rgb = (rgb shl 8) or (v * sh).toInt().coerceIn(0, 255)
            }
            image.setRGB(x, y, rgb)
        }
    }

    private fun splat(cloud: PointCloud, viewProj: FloatArray, image: BufferedImage, zbuf: FloatArray) {
        val size = image.width
        for (i in 0 until cloud.size) {
            val p = project(viewProj, cloud.xyz[i * 3], cloud.xyz[i * 3 + 1], cloud.xyz[i * 3 + 2], size)
            if (p[2].isNaN()) continue
            val x = p[0].toInt(); val y = p[1].toInt()
            if (x !in 0 until size || y !in 0 until size) continue
            val idx = y * size + x
            if (p[2] >= zbuf[idx]) continue
            zbuf[idx] = p[2]
            image.setRGB(x, y, ((cloud.rgb[i * 3].toInt() and 0xFF) shl 16) or ((cloud.rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (cloud.rgb[i * 3 + 2].toInt() and 0xFF))
        }
    }

    /** Screen x, y (pixels) and depth; depth NaN behind the camera. */
    private fun project(m: FloatArray, x: Float, y: Float, z: Float, size: Int): FloatArray {
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 1e-6f) return floatArrayOf(0f, 0f, Float.NaN)
        return floatArrayOf((cx / cw * 0.5f + 0.5f) * size, (0.5f - cy / cw * 0.5f) * size, cz / cw)
    }
}
