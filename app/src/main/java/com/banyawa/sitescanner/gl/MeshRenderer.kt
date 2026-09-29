package com.banyawa.sitescanner.gl

import android.opengl.GLES20
import com.banyawa.sitescanner.core.mesh.TriangleMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a vertex-coloured triangle mesh with soft light from the viewer, so its shape
 * reads while the scanned colours stay close to what the camera saw. OpenGL ES 2 only
 * indexes 16-bit, so the mesh is split into chunks of at most 65 535 vertices.
 */
class MeshRenderer {
    private class Chunk(val vbo: Int, val ibo: Int, val indexCount: Int)

    private var program = 0
    private var positionAttr = 0
    private var normalAttr = 0
    private var colorAttr = 0
    private var mvpUniform = 0
    private var lightUniform = 0
    private val chunks = ArrayList<Chunk>()

    val isEmpty: Boolean get() = chunks.isEmpty()

    fun createOnGlThread() {
        program = ShaderUtil.program(VERTEX, FRAGMENT)
        positionAttr = GLES20.glGetAttribLocation(program, "a_Position")
        normalAttr = GLES20.glGetAttribLocation(program, "a_Normal")
        colorAttr = GLES20.glGetAttribLocation(program, "a_Color")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        lightUniform = GLES20.glGetUniformLocation(program, "u_Light")
        // Buffers of an old GL context are gone with it.
        chunks.clear()
        ShaderUtil.checkError("MeshRenderer.create")
    }

    fun upload(mesh: TriangleMesh) {
        release()
        if (mesh.isEmpty()) return
        val normals = mesh.normals()
        val local = IntArray(mesh.vertexCount) { -1 }
        val used = IntArray(MAX_CHUNK_VERTICES)
        var usedCount = 0
        val chunkIndices = ShortArray(minOf(mesh.indices.size, MAX_CHUNK_VERTICES * 6))
        var indexCount = 0

        fun flush() {
            if (indexCount == 0) return
            val vertices = ByteBuffer.allocateDirect(usedCount * STRIDE).order(ByteOrder.nativeOrder())
            for (k in 0 until usedCount) {
                val v = used[k]
                vertices.putFloat(mesh.positions[v * 3]).putFloat(mesh.positions[v * 3 + 1]).putFloat(mesh.positions[v * 3 + 2])
                vertices.put(toSnorm(normals[v * 3])).put(toSnorm(normals[v * 3 + 1])).put(toSnorm(normals[v * 3 + 2])).put(0.toByte())
                vertices.put(mesh.colors[v * 3]).put(mesh.colors[v * 3 + 1]).put(mesh.colors[v * 3 + 2]).put(0xFF.toByte())
                local[v] = -1
            }
            vertices.position(0)
            val indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            indices.put(chunkIndices, 0, indexCount).position(0)
            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, usedCount * STRIDE, vertices, GLES20.GL_STATIC_DRAW)
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
        ShaderUtil.checkError("MeshRenderer.upload")
    }

    /** [light]: unit direction towards the viewer, world space. */
    fun draw(viewProj: FloatArray, light: FloatArray) {
        if (chunks.isEmpty()) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProj, 0)
        GLES20.glUniform3fv(lightUniform, 1, light, 0)
        GLES20.glEnableVertexAttribArray(positionAttr)
        GLES20.glEnableVertexAttribArray(normalAttr)
        GLES20.glEnableVertexAttribArray(colorAttr)
        for (c in chunks) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, c.vbo)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, c.ibo)
            GLES20.glVertexAttribPointer(positionAttr, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
            GLES20.glVertexAttribPointer(normalAttr, 3, GLES20.GL_BYTE, true, STRIDE, 12)
            GLES20.glVertexAttribPointer(colorAttr, 4, GLES20.GL_UNSIGNED_BYTE, true, STRIDE, 16)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, c.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        }
        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(normalAttr)
        GLES20.glDisableVertexAttribArray(colorAttr)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        ShaderUtil.checkError("MeshRenderer.draw")
    }

    private fun release() {
        for (c in chunks) GLES20.glDeleteBuffers(2, intArrayOf(c.vbo, c.ibo), 0)
        chunks.clear()
    }

    private fun toSnorm(v: Float): Byte = (v.coerceIn(-1f, 1f) * 127f).toInt().toByte()

    private companion object {
        const val STRIDE = 20
        const val MAX_CHUNK_VERTICES = 65_535

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
    }
}
