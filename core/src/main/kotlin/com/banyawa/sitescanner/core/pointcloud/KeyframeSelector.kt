package com.banyawa.sitescanner.core.pointcloud

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Decides when a new depth frame is worth integrating: after the camera has moved or
 * turned enough, but never faster than [minIntervalMs]. A frame is also taken every
 * [maxIntervalMs] while standing still, which refines already-seen surfaces.
 */
class KeyframeSelector(
    private val minTranslationM: Float = 0.03f,
    private val minRotationDeg: Float = 3f,
    private val minIntervalMs: Long = 100L,
    private val maxIntervalMs: Long = 1000L,
) {
    private var lastPose: FloatArray? = null
    private var lastTimeMs = 0L

    fun shouldCapture(cameraToWorld: FloatArray, timeMs: Long): Boolean {
        val prev = lastPose
        if (prev == null) {
            accept(cameraToWorld, timeMs)
            return true
        }
        val elapsed = timeMs - lastTimeMs
        if (elapsed < minIntervalMs) return false
        val moved = translationBetween(prev, cameraToWorld) >= minTranslationM ||
            rotationDegBetween(prev, cameraToWorld) >= minRotationDeg ||
            elapsed >= maxIntervalMs
        if (moved) accept(cameraToWorld, timeMs)
        return moved
    }

    fun reset() {
        lastPose = null
        lastTimeMs = 0L
    }

    private fun accept(pose: FloatArray, timeMs: Long) {
        lastPose = pose.copyOf()
        lastTimeMs = timeMs
    }

    companion object {
        fun translationBetween(a: FloatArray, b: FloatArray): Float {
            val dx = a[12] - b[12]
            val dy = a[13] - b[13]
            val dz = a[14] - b[14]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** Angle of the relative rotation, from trace(Aᵀ·B) = 1 + 2·cos(θ). */
        fun rotationDegBetween(a: FloatArray, b: FloatArray): Float {
            var trace = 0f
            for (c in 0 until 3) for (r in 0 until 3) trace += a[c * 4 + r] * b[c * 4 + r]
            val cos = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
    }
}
