package com.banyawa.sitescanner.cli

import com.banyawa.sitescanner.core.geometry.Mat4
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import com.banyawa.sitescanner.core.viewer.OrbitCamera
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Software renders of a mesh or point cloud (z-buffered, vertex colours lit from the
 * viewer), to look at a reconstruction without a 3D viewer: from the front, a corner and
 * straight down.
 */
object MeshPreview {
    fun renderViews(mesh: TriangleMesh?, cloud: PointCloud?, outDir: File, size: Int = 900) {
        val bounds = mesh?.bounds() ?: cloud?.bounds() ?: return
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
            if (mesh != null && !mesh.isEmpty()) rasterize(mesh, viewProj, toEye, image, zbuf)
            else if (cloud != null) splat(cloud, viewProj, image, zbuf)
            ImageIO.write(image, "png", File(outDir, "preview_$name.png"))
        }
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
