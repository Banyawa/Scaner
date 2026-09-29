package com.banyawa.sitescanner.gl

import android.opengl.GLES20
import java.nio.FloatBuffer

/** Draws small, frequently changing sets of lines / points with a single colour. */
class LineRenderer {
    private var program = 0
    private var positionAttr = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var sizeUniform = 0
    private var buffer: FloatBuffer = ShaderUtil.floatBuffer(256)

    fun createOnGlThread() {
        program = ShaderUtil.program(VERTEX, FRAGMENT)
        positionAttr = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        sizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
        ShaderUtil.checkError("LineRenderer.create")
    }

    /**
     * @param xyz vertex positions; [mode] is GL_LINES (pairs), GL_LINE_STRIP or GL_POINTS.
     * Drawn on top of everything (depth test disabled) so measurements are never hidden.
     */
    fun draw(
        viewProj: FloatArray,
        xyz: FloatArray,
        color: FloatArray,
        mode: Int = GLES20.GL_LINES,
        lineWidth: Float = 6f,
        pointSize: Float = 18f,
    ) {
        val vertexCount = xyz.size / 3
        if (vertexCount == 0) return
        if (buffer.capacity() < xyz.size) buffer = ShaderUtil.floatBuffer(xyz.size * 2)
        buffer.clear()
        buffer.put(xyz)
        buffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProj, 0)
        GLES20.glUniform4fv(colorUniform, 1, color, 0)
        GLES20.glUniform1f(sizeUniform, pointSize)
        GLES20.glLineWidth(lineWidth)
        GLES20.glVertexAttribPointer(positionAttr, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(positionAttr)
        GLES20.glDrawArrays(mode, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionAttr)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        ShaderUtil.checkError("LineRenderer.draw")
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 u_MVP;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVP * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
            }
        """

        const val FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
