package com.banyawa.sitescanner.core.viewer

import com.banyawa.sitescanner.core.geometry.Bounds3
import com.banyawa.sitescanner.core.geometry.Mat4
import com.banyawa.sitescanner.core.geometry.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Turntable camera orbiting [target] in a Y-up world (the ARCore convention). */
class OrbitCamera {
    var target: Vec3 = Vec3.ZERO
    var distance = 5f
        private set

    /** Rotation around the vertical axis, radians. */
    var yaw = 0.6f
    /** Elevation above the horizon, radians. */
    var pitch = 0.5f
        private set
    var fovYDeg = 50f

    private var minDistance = 0.1f
    private var maxDistance = 500f

    fun eye(): Vec3 {
        val cp = cos(pitch)
        return target + Vec3(cp * sin(yaw), sin(pitch), cp * cos(yaw)) * distance
    }

    fun viewMatrix(): FloatArray = Mat4.lookAt(eye(), target, Vec3.UP)

    fun projectionMatrix(aspect: Float): FloatArray =
        Mat4.perspective(fovYDeg, aspect, max(0.01f, distance * 0.01f), distance * 20f + 100f)

    fun viewProjection(aspect: Float): FloatArray = Mat4.multiply(projectionMatrix(aspect), viewMatrix())

    /** Frames the whole bounding box. */
    fun fit(bounds: Bounds3) {
        target = bounds.center
        val radius = max(bounds.diagonal * 0.5f, 0.5f)
        distance = (radius / tan(Math.toRadians(fovYDeg / 2.0)).toFloat() * 1.1f)
        minDistance = radius * 0.02f
        maxDistance = radius * 20f
        yaw = 0.6f
        pitch = 0.6f
    }

    /** Drag in pixels; a full-width drag turns roughly 180°. */
    fun rotate(dxPx: Float, dyPx: Float, viewWidthPx: Int) {
        val k = (Math.PI / max(1, viewWidthPx)).toFloat()
        yaw -= dxPx * k
        pitch = (pitch + dyPx * k).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(minDistance, maxDistance)
    }

    /** Moves the target so the scene follows the fingers. */
    fun pan(dxPx: Float, dyPx: Float, viewHeightPx: Int) {
        val worldPerPx = 2f * distance * tan(Math.toRadians(fovYDeg / 2.0)).toFloat() / max(1, viewHeightPx)
        val forward = (target - eye()).normalized()
        val right = (forward cross Vec3.UP).normalized()
        val up = right cross forward
        target = target - right * (dxPx * worldPerPx) + up * (dyPx * worldPerPx)
    }

    companion object {
        private const val MAX_PITCH = 1.5f
    }
}
