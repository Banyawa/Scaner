package com.banyawa.sitescanner.gl

import android.opengl.GLES20
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a triangle mesh with soft light from the viewer, so its shape reads while the
 * scanned colours stay close to what the camera saw: its photo texture when it has one,
 * else its vertex colours. OpenGL ES 2 only indexes 16-bit, so the mesh is split into
 * chunks of at most 65 535 vertices.
 */
class MeshRenderer {
    private class Chunk(val vbo: Int, val ibo: Int, val indexCount: Int)

    /** A linked program and where its inputs are; [surface] is a_Color or a_Uv. */
    private class Shading(vertex: String, fragment: String, surfaceAttr: String) {
        val program = ShaderUtil.program(vertex, fragment)
        val position = GLES20.glGetAttribLocation(program, "a_Position")
        val normal = GLES20.glGetAttribLocation(program, "a_Normal")
        val surface = GLES20.glGetAttribLocation(program, surfaceAttr)
        val mvp = GLES20.glGetUniformLocation(program, "u_MVP")
        val light = GLES20.glGetUniformLocation(program, "u_Light")
        val texture = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    private lateinit var colored: Shading
    private lateinit var photo: Shading
    private val chunks = ArrayList<Chunk>()

    /** The uploaded mesh's photo texture; 0 when it is drawn in its vertex colours. */
    private var texture = 0
    private var stride = STRIDE

    val isEmpty: Boolean get() = chunks.isEmpty()

    fun createOnGlThread() {
        colored = Shading(VERTEX, FRAGMENT, "a_Color")
        photo = Shading(TEXTURED_VERTEX, TEXTURED_FRAGMENT, "a_Uv")
        // Buffers and textures of an old GL context are gone with it.
        chunks.clear()
        texture = 0
        ShaderUtil.checkError("MeshRenderer.create")
    }

    /**
     * [uv] (two per vertex, origin top left) and [atlas] give the mesh its photo texture;
     * without either it is drawn in its vertex colours.
     */
    fun upload(mesh: TriangleMesh, uv: FloatArray? = null, atlas: RgbImage? = null) {
        release()
        if (mesh.isEmpty()) return
        val texCoords = uv?.takeIf { atlas != null && it.size >= mesh.vertexCount * 2 }
        // Textured vertices carry their texture coordinates after the colour.
        val vertexStride = if (texCoords != null) TEXTURED_STRIDE else STRIDE
        val normals = mesh.normals()
        val local = IntArray(mesh.vertexCount) { -1 }
        val used = IntArray(MAX_CHUNK_VERTICES)
        var usedCount = 0
        val chunkIndices = ShortArray(minOf(mesh.indices.size, MAX_CHUNK_VERTICES * 6))
        var indexCount = 0

        fun flush() {
            if (indexCount == 0) return
            val vertices = ByteBuffer.allocateDirect(usedCount * vertexStride).order(ByteOrder.nativeOrder())
            for (k in 0 until usedCount) {
                val v = used[k]
                vertices.putFloat(mesh.positions[v * 3]).putFloat(mesh.positions[v * 3 + 1]).putFloat(mesh.positions[v * 3 + 2])
                vertices.put(toSnorm(normals[v * 3])).put(toSnorm(normals[v * 3 + 1])).put(toSnorm(normals[v * 3 + 2])).put(0.toByte())
                vertices.put(mesh.colors[v * 3]).put(mesh.colors[v * 3 + 1]).put(mesh.colors[v * 3 + 2]).put(0xFF.toByte())
                if (texCoords != null) vertices.putFloat(texCoords[v * 2]).putFloat(texCoords[v * 2 + 1])
                local[v] = -1
            }
            vertices.position(0)
            val indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            indices.put(chunkIndices, 0, indexCount).position(0)
            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, usedCount * vertexStride, vertices, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexCount * 2, indices, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            chunks += Chunk(ids[0], ids[1], indexCount)
            usedCount = 0
            indexCount = 0
        }

        for (t in 0 until mesh.triangleCount) {
            if (usedCount + 3 > MAX_CHUNK_VERTICES || indexCount + 3 > chunkIndices.size) flush()
            for (j in 0 until 3) {
                val v = mesh.indices[t * 3 + j]
                if (local[v] < 0) {
                    local[v] = usedCount
                    used[usedCount++] = v
                }
                chunkIndices[indexCount++] = local[v].toShort()
            }
        }
        flush()
        stride = vertexStride
        if (texCoords != null && atlas != null) texture = uploadTexture(atlas)
        ShaderUtil.checkError("MeshRenderer.upload")
    }

    /**
     * [atlas] as an RGBA texture, sent in strips of rows so a 4096² atlas never needs a
     * 64 MB buffer at once. The rows go top row first and the texture coordinates count v
     * from the top, so the two agree without flipping either. Halved (nearest pixel) until
     * it fits the GPU's largest texture.
     */
    private fun uploadTexture(atlas: RgbImage): Int {
        val limit = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
        val maxSize = limit[0].coerceAtLeast(MIN_TEXTURE_SIZE)
        var step = 1
        while (atlas.width / step > maxSize || atlas.height / step > maxSize) step *= 2
        val w = (atlas.width / step).coerceAtLeast(1)
        val h = (atlas.height / step).coerceAtLeast(1)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val rows = (STRIP_BYTES / (w * 4)).coerceIn(1, h)
        val strip = ByteBuffer.allocateDirect(rows * w * 4)
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            strip.clear()
            for (row in y until y + n) {
                val src = row * step * atlas.width
                for (x in 0 until w) {
                    val p = atlas.pixels[src + x * step]
                    strip.put((p shr 16).toByte()).put((p shr 8).toByte()).put(p.toByte()).put(0xFF.toByte())
                }
            }
            strip.position(0)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, y, w, n, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, strip)
            y += n
        }
        // Mipmaps keep a zoomed-out room from shimmering; ES 2 has them for power-of-two sizes only.
        val mipmaps = isPowerOfTwo(w) && isPowerOfTwo(h)
        if (mipmaps) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            if (mipmaps) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** [light]: unit direction towards the viewer, world space. */
    fun draw(viewProj: FloatArray, light: FloatArray) {
        if (chunks.isEmpty()) return
        val textured = texture != 0
        val s = if (textured) photo else colored
        GLES20.glUseProgram(s.program)
        GLES20.glUniformMatrix4fv(s.mvp, 1, false, viewProj, 0)
        GLES20.glUniform3fv(s.light, 1, light, 0)
        if (textured) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(s.texture, 0)
        }
        GLES20.glEnableVertexAttribArray(s.position)
        GLES20.glEnableVertexAttribArray(s.normal)
        GLES20.glEnableVertexAttribArray(s.surface)
        for (c in chunks) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, c.vbo)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, c.ibo)
            GLES20.glVertexAttribPointer(s.position, 3, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glVertexAttribPointer(s.normal, 3, GLES20.GL_BYTE, true, stride, 12)
            if (textured) {
                GLES20.glVertexAttribPointer(s.surface, 2, GLES20.GL_FLOAT, false, stride, 20)
            } else {
                GLES20.glVertexAttribPointer(s.surface, 4, GLES20.GL_UNSIGNED_BYTE, true, stride, 16)
            }
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, c.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        }
        GLES20.glDisableVertexAttribArray(s.position)
        GLES20.glDisableVertexAttribArray(s.normal)
        GLES20.glDisableVertexAttribArray(s.surface)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        if (textured) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        ShaderUtil.checkError("MeshRenderer.draw")
    }

    private fun release() {
        for (c in chunks) GLES20.glDeleteBuffers(2, intArrayOf(c.vbo, c.ibo), 0)
        chunks.clear()
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
    }

    private fun toSnorm(v: Float): Byte = (v.coerceIn(-1f, 1f) * 127f).toInt().toByte()

    private fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    private companion object {
        const val STRIDE = 20
        const val TEXTURED_STRIDE = 28
        const val MAX_CHUNK_VERTICES = 65_535

        /** Texture upload buffer: 4 MB, 256 rows of a 4096-wide atlas. */
        const val STRIP_BYTES = 4 * 1024 * 1024

        /** Every ES 2 GPU takes textures this big, whatever it reports. */
        const val MIN_TEXTURE_SIZE = 64

        const val VERTEX = """
            uniform mat4 u_MVP;
            uniform vec3 u_Light;
            attribute vec3 a_Position;
            attribute vec3 a_Normal;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_MVP * vec4(a_Position, 1.0);
                // Two-sided: walls seen from behind are lit the same.
                float lambert = abs(dot(normalize(a_Normal), u_Light));
                v_Color = vec4(a_Color.rgb * (0.6 + 0.4 * lambert), 1.0);
            }
        """

        const val FRAGMENT = """
            precision mediump float;
            varying vec4 v_Color;
            void main() {
                gl_FragColor = v_Color;
            }
        """

        const val TEXTURED_VERTEX = """
            uniform mat4 u_MVP;
            uniform vec3 u_Light;
            attribute vec3 a_Position;
            attribute vec3 a_Normal;
            attribute vec2 a_Uv;
            varying vec2 v_Uv;
            varying float v_Light;
            void main() {
                gl_Position = u_MVP * vec4(a_Position, 1.0);
                // Two-sided, and the same soft headlight as the vertex-coloured model.
                float lambert = abs(dot(normalize(a_Normal), u_Light));
                v_Light = 0.6 + 0.4 * lambert;
                v_Uv = a_Uv;
            }
        """

        // Medium precision cannot address single texels of a 4096² atlas; use high where there is one.
        const val TEXTURED_FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D u_Texture;
            varying vec2 v_Uv;
            varying float v_Light;
            void main() {
                gl_FragColor = vec4(texture2D(u_Texture, v_Uv).rgb * v_Light, 1.0);
            }
        """
    }
}
