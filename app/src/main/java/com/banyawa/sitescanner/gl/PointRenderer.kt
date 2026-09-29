package com.banyawa.sitescanner.gl

import android.opengl.GLES20
import com.banyawa.sitescanner.core.pointcloud.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders a coloured point cloud from a VBO (16 bytes per point: xyz float + rgba byte). */
class PointRenderer {
    private var program = 0
    private var vbo = 0
    private var positionAttr = 0
    private var colorAttr = 0
    private var mvpUniform = 0
    private var sizeUniform = 0
    private var tintUniform = 0
    private var staging: ByteBuffer? = null

    var count = 0
        private set

    fun createOnGlThread() {
        program = ShaderUtil.program(VERTEX, FRAGMENT)
        positionAttr = GLES20.glGetAttribLocation(program, "a_Position")
        colorAttr = GLES20.glGetAttribLocation(program, "a_Color")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        sizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
        tintUniform = GLES20.glGetUniformLocation(program, "u_Tint")
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        count = 0
        ShaderUtil.checkError("PointRenderer.create")
    }

    /** Uploads [cloud]; [colors] overrides the cloud's own RGB (e.g. height colouring). */
    fun upload(cloud: PointCloud, colors: ByteArray = cloud.rgb) {
        val n = cloud.size
        val bytes = n * STRIDE
        var buf = staging
        if (buf == null || buf.capacity() < bytes) {
            buf = ByteBuffer.allocateDirect(maxOf(bytes, 1024)).order(ByteOrder.nativeOrder())
            staging = buf
        }
        buf!!.clear()
        for (i in 0 until n) {
            buf.putFloat(cloud.xyz[i * 3])
            buf.putFloat(cloud.xyz[i * 3 + 1])
            buf.putFloat(cloud.xyz[i * 3 + 2])
            buf.put(colors[i * 3])
            buf.put(colors[i * 3 + 1])
            buf.put(colors[i * 3 + 2])
            buf.put(0xFF.toByte())
        }
        buf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bytes, buf, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        count = n
        ShaderUtil.checkError("PointRenderer.upload")
    }

    /**
     * @param tint RGB + mix amount; blends every point toward a highlight colour so the
     * scanned surface stands out against the live camera image.
     */
    fun draw(viewProj: FloatArray, pointSizePx: Float, tint: FloatArray = NO_TINT) {
        if (count == 0) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProj, 0)
        GLES20.glUniform1f(sizeUniform, pointSizePx)
        GLES20.glUniform4fv(tintUniform, 1, tint, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionAttr, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glVertexAttribPointer(colorAttr, 4, GLES20.GL_UNSIGNED_BYTE, true, STRIDE, 12)
        GLES20.glEnableVertexAttribArray(positionAttr)
        GLES20.glEnableVertexAttribArray(colorAttr)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glDisableVertexAttribArray(colorAttr)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        ShaderUtil.checkError("PointRenderer.draw")
    }

    companion object {
        private const val STRIDE = 16
        val NO_TINT = floatArrayOf(0f, 0f, 0f, 0f)

        private const val VERTEX = """
            uniform mat4 u_MVP;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_MVP * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
                v_Color = a_Color;
            }
        """

        private const val FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Tint;
            varying vec4 v_Color;
            void main() {
                gl_FragColor = vec4(mix(v_Color.rgb, u_Tint.rgb, u_Tint.a), 1.0);
            }
        """
    }
}
