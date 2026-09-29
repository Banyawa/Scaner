package com.banyawa.sitescanner.core.geometry

import kotlin.math.tan

/**
 * 4x4 matrix helpers using the OpenGL / ARCore layout: column-major float arrays,
 * element (row r, column c) stored at index `c * 4 + r`.
 */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** Returns `a * b`. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    fun transformPoint(m: FloatArray, p: Vec3): Vec3 {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        return if (w != 0f && w != 1f) Vec3(x / w, y / w, z / w) else Vec3(x, y, z)
    }

    fun translation(m: FloatArray) = Vec3(m[12], m[13], m[14])

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        val m = FloatArray(16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    fun lookAt(eye: Vec3, target: Vec3, up: Vec3): FloatArray {
        val f = (target - eye).normalized()
        val s = (f cross up).normalized()
        val u = s cross f
        val m = FloatArray(16)
        m[0] = s.x; m[4] = s.y; m[8] = s.z; m[12] = -(s dot eye)
        m[1] = u.x; m[5] = u.y; m[9] = u.z; m[13] = -(u dot eye)
        m[2] = -f.x; m[6] = -f.y; m[10] = -f.z; m[14] = f dot eye
        m[15] = 1f
        return m
    }

    /**
     * Projects a world point to view pixels (origin top-left, y down).
     * Returns null when the point is behind the camera.
     */
    fun projectToScreen(viewProj: FloatArray, p: Vec3, width: Int, height: Int): Vec2? {
        val cx = viewProj[0] * p.x + viewProj[4] * p.y + viewProj[8] * p.z + viewProj[12]
        val cy = viewProj[1] * p.x + viewProj[5] * p.y + viewProj[9] * p.z + viewProj[13]
        val cw = viewProj[3] * p.x + viewProj[7] * p.y + viewProj[11] * p.z + viewProj[15]
        if (cw <= 1e-6f) return null
        val ndcX = cx / cw
        val ndcY = cy / cw
        return Vec2((ndcX + 1f) * 0.5f * width, (1f - ndcY) * 0.5f * height)
    }
}
